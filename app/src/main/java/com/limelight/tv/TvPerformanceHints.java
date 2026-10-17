package com.limelight.tv;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.PerformanceHintManager;

import com.limelight.LimeLog;
import com.limelight.binding.video.TvDiagnostics;

import java.util.ArrayList;
import java.util.List;

/**
 * Artemide TV: Android performance hints (ADPF, Android 12+) for the video threads.
 *
 * Tells the TV's power manager how long each frame may take (one frame period) and how long it
 * really took, so it keeps the CPU clocks up for these threads instead of reacting to the
 * average load. Idea and measurements from moonlight-tcl (pabragin/moonlight-tcl), which uses
 * the same on TCL TVs with this chip. Adapted for Android 12: Session.setThreads() only exists
 * from Android 14, so adding a thread there recreates the session.
 *
 * Everything is best effort; a TV whose power HAL declines hint sessions leaves this inert.
 */
@TargetApi(31)
public final class TvPerformanceHints {
    private final PerformanceHintManager manager;
    private final long preferredRateNs;
    private final long targetNs;
    private final List<Integer> tids = new ArrayList<>();
    private volatile PerformanceHintManager.Session session;
    private volatile long lastReportNs;
    private boolean declined;
    private boolean closed;

    private TvPerformanceHints(PerformanceHintManager manager, long preferredRateNs, long targetNs) {
        this.manager = manager;
        this.preferredRateNs = Math.max(0L, preferredRateNs);
        this.targetNs = targetNs;
    }

    /** @return null when the TV has no hint support (or Android is older than 12) */
    public static TvPerformanceHints create(Context context, float fps) {
        if (Build.VERSION.SDK_INT < 31 || context == null) {
            TvDiagnostics.setAdpfState("not available (Android " + Build.VERSION.RELEASE + ")");
            return null;
        }
        try {
            final PerformanceHintManager manager = context.getSystemService(PerformanceHintManager.class);
            if (manager == null) {
                TvDiagnostics.setAdpfState("not available on this TV");
                return null;
            }
            final long rate = manager.getPreferredUpdateRateNanos();
            if (rate < 0) {
                TvDiagnostics.setAdpfState("not supported by this TV");
                return null;
            }
            final long target = fps > 1f ? (long) (1_000_000_000.0 / fps) : 16_666_667L;
            TvDiagnostics.setAdpfState("waiting for threads");
            return new TvPerformanceHints(manager, rate, target);
        } catch (Throwable t) {
            TvDiagnostics.setAdpfState("unavailable (" + t.getClass().getSimpleName() + ")");
            return null;
        }
    }

    /** Adds a thread (by tid) to the hint session. */
    public synchronized void addThread(int tid) {
        if (closed || declined || tid <= 0 || tids.contains(tid)) {
            return;
        }
        tids.add(tid);
        final int[] all = new int[tids.size()];
        for (int i = 0; i < all.length; i++) {
            all[i] = tids.get(i);
        }
        try {
            final PerformanceHintManager.Session old = session;
            PerformanceHintManager.Session next = null;
            if (old != null && Build.VERSION.SDK_INT >= 34) {
                old.setThreads(all);
                next = old;
            } else {
                next = manager.createHintSession(all, targetNs);
                if (old != null) {
                    try { old.close(); } catch (Throwable ignored) { }
                }
            }
            if (next == null) {
                declined = true;
                session = null;
                TvDiagnostics.setAdpfState("declined by the TV");
                LimeLog.info("ADPF: createHintSession returned null; hints disabled");
                return;
            }
            session = next;
            TvDiagnostics.setAdpfState("on (" + all.length + " threads, target " + (targetNs / 1000) + " us)");
            LimeLog.info("ADPF: hint session threads=" + tids + " target=" + (targetNs / 1000) + " us");
        } catch (Throwable t) {
            // A thread that already ended makes createHintSession throw: forget it and carry on
            tids.remove(Integer.valueOf(tid));
            LimeLog.warning("ADPF: " + t);
        }
    }

    /** How long the frame that was just handed to the display took (arrival to release). */
    public void reportWorkNanos(long workNs) {
        final PerformanceHintManager.Session s = session;
        if (s == null) {
            return;
        }
        final long now = System.nanoTime();
        if (now - lastReportNs < preferredRateNs) {
            return;
        }
        lastReportNs = now;
        if (workNs < 1) {
            workNs = 1;
        } else if (workNs > 1_000_000_000L) {
            workNs = 1_000_000_000L;
        }
        try {
            s.reportActualWorkDuration(workNs);
        } catch (Throwable ignored) { }
    }

    public synchronized void close() {
        closed = true;
        final PerformanceHintManager.Session s = session;
        session = null;
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }
}
