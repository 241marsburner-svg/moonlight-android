package com.limelight.binding.video;

/**
 * Artemide TV: which refresh each frame reaches the screen on, worked out from SurfaceFlinger's
 * timing rules instead of the decoder's frame-rendered callback.
 *
 * On this TV (Android 12, Codec2 decoders) the frame-rendered callback fires when a frame is
 * handed over and reports the requested time, not when it was shown, so it can't see repeats,
 * skipped frames or the real delay. SurfaceFlinger's rules are known, though: a buffer reaches
 * the first refresh that is at least the display's presentation deadline after it was queued
 * and not before its requested time; frames keep their order; when several reach the same
 * refresh, only the newest is shown. With the refresh grid (TvVsyncClock) this gives the refresh
 * every frame is shown on. It is an estimate, as exact as the deadline the TV reports.
 *
 * Together with the PC's capture times (TvFrameTimes) it counts:
 *  - frozen refreshes: the picture stayed up longer than the content's own rhythm (a repeat).
 *    Those where the PC had no newer picture at all (a PC-side gap) are counted separately.
 *  - skipped frames: captured, arrived, but never shown, in a stream that is not faster than the
 *    screen (the picture jumps)
 *  - uneven steps, for streams faster than the screen (e.g. 120 fps on 60 Hz, two frames per
 *    refresh): a refresh whose picture moved half a refresh more or less than the time that passed
 *  - the delay from a frame's arrival to the refresh it is shown on
 */
final class TvScreenModel {
    // A step this long is a pause (menu, static picture), not a stutter
    private static final int MAX_STEP_REFRESHES = 60;
    // A frame-rendered time this close to the requested one is an echo of it
    private static final long ECHO_TOLERANCE_NS = 200_000L;

    private final TvVsyncClock clock;
    private final long[] grid = new long[4];
    private volatile long deadlineNs;         // presentation deadline, 0 = one refresh
    private volatile long presentShiftNs;     // app vsync offset: refreshes are this much before the grid

    // The refresh being filled and its newest frame
    private long curSlotNs;                   // 0 = none
    private long curPtsUs;
    private long curHostUs = -1;
    private long curFirstHostUs = -1;
    private int curFrames;
    // The previous shown picture
    private long lastShownSlotNs;
    private long lastShownHostUs = -1;

    // Requested times of the latest frames, for the echo check of the frame-rendered callback
    private static final int RING = 64;
    private final long[] ringPts = new long[RING];
    private final long[] ringDesired = new long[RING];
    private int ringPos;

    TvScreenModel(TvVsyncClock clock) {
        this.clock = clock;
    }

    /** From the Display: getPresentationDeadlineNanos() and getAppVsyncOffsetNanos(). */
    void setDisplayTiming(long presentationDeadlineNs, long appVsyncOffsetNs) {
        deadlineNs = presentationDeadlineNs;
        presentShiftNs = (appVsyncOffsetNs > 0 && appVsyncOffsetNs < 8_000_000L) ? appVsyncOffsetNs : 0L;
        TvDiagnostics.setDisplayTiming(presentationDeadlineNs, appVsyncOffsetNs);
    }

    synchronized void reset() {
        curSlotNs = 0L;
        curPtsUs = 0L;
        curHostUs = -1;
        curFirstHostUs = -1;
        curFrames = 0;
        lastShownSlotNs = 0L;
        lastShownHostUs = -1;
    }

    /** Floor division for a positive divisor (Math.floorDiv needs API 24). */
    private static long floorDiv(long a, long b) {
        long q = a / b;
        if (a % b < 0) {
            q--;
        }
        return q;
    }

    private static long gridCeil(long t, long ref, long period) {
        return ref + floorDiv(t - ref + period - 1, period) * period;
    }

    /**
     * A frame was handed to the display.
     * @param nowNs     when (System.nanoTime)
     * @param desiredNs the presentation time it was given, 0 = as soon as possible
     * @param ptsUs     its decoder timestamp (= its arrival time)
     * @param hostUs    when the PC captured it, -1 = unknown
     */
    synchronized void onRelease(long nowNs, long desiredNs, long ptsUs, long hostUs) {
        if (!TvDiagnostics.isCollecting()) {
            return;
        }
        if (desiredNs > 0L) {
            final int p = ringPos;
            ringPts[p] = ptsUs;
            ringDesired[p] = desiredNs;
            ringPos = (p + 1) % RING;
        }
        if (!clock.snapshot(nowNs, grid)) {
            return;
        }
        final long period = grid[1];
        final long ref = grid[0] - presentShiftNs;
        long deadline = deadlineNs;
        if (deadline <= 0L || deadline > 3 * period) {
            deadline = period;
        }
        long t = nowNs + deadline;
        if (desiredNs > 0L && desiredNs + 1 > t) {
            t = desiredNs + 1;
        }
        final long slot = gridCeil(t, ref, period);

        if (curSlotNs != 0L && slot <= curSlotNs) {
            if (curSlotNs - slot > 4 * period) {
                // The grid moved (display mode switch): start over
                reset();
            } else {
                // Same refresh as the frame before (frames keep their order): only the newer is shown
                curFrames++;
                curPtsUs = ptsUs;
                curHostUs = hostUs;
                TvDiagnostics.onModelReplaced();
                return;
            }
        }
        if (curSlotNs != 0L) {
            finish(period);
        }
        curSlotNs = slot;
        curPtsUs = ptsUs;
        curHostUs = hostUs;
        curFirstHostUs = hostUs;
        curFrames = 1;
    }

    /** The refresh curSlotNs is complete: its newest frame is the one shown. */
    private void finish(long period) {
        final long slot = curSlotNs;
        if (curPtsUs > 0L) {
            TvDiagnostics.onModelScreen(slot / 1000L - curPtsUs);
            com.limelight.tv.TvLatencyTest.onFrameShownModel(curPtsUs, slot);
        }
        if (lastShownSlotNs != 0L) {
            final long refreshes = Math.round((slot - lastShownSlotNs) / (double) period);
            if (refreshes >= 1 && refreshes <= MAX_STEP_REFRESHES) {
                final long pcPeriod = Math.max(1_000_000L, TvDiagnostics.pcFramePeriodNs());
                final boolean host = curHostUs >= 0 && curFirstHostUs >= 0 && lastShownHostUs >= 0 &&
                        curFirstHostUs > lastShownHostUs && curHostUs >= curFirstHostUs &&
                        curHostUs - lastShownHostUs < 2_000_000L;
                long frozen;
                long frozenPc = 0;
                long skippedNs = 0;
                long unevenNs = 0;
                if (period < pcPeriod * 5 / 4) {
                    // A frame per refresh (or fewer): each picture should stay up for its own rhythm
                    final long natural = Math.max(1L, Math.round(pcPeriod / (double) period));
                    frozen = Math.max(0L, refreshes - natural);
                    if (host && frozen > 0) {
                        // How late the next frame was in the PC's own time: that part was the PC
                        final long contentRefreshes = Math.round((curFirstHostUs - lastShownHostUs) * 1000.0 / period);
                        frozenPc = Math.max(0L, Math.min(frozen, contentRefreshes - natural));
                    }
                    if (curFrames > 1) {
                        // Frames that reached the same refresh as a newer one were never shown
                        skippedNs = (curFrames - 1) * pcPeriod;
                    }
                } else {
                    // Faster than the screen: every refresh should show a new picture, moved on
                    // by one refresh's worth
                    frozen = refreshes - 1;
                    if (host && frozen > 0) {
                        final long contentRefreshes = Math.round((curFirstHostUs - lastShownHostUs) * 1000.0 / period);
                        frozenPc = Math.max(0L, Math.min(frozen, contentRefreshes - 1));
                    }
                    if (host && refreshes == 1) {
                        final long lag = period - (curHostUs - lastShownHostUs) * 1000L;
                        if (Math.abs(lag) * 10 >= period * 4) {
                            unevenNs = Math.abs(lag);
                        }
                    }
                }
                TvDiagnostics.onModelStep(refreshes, frozen, frozenPc, skippedNs, unevenNs, period);
            }
        }
        lastShownSlotNs = slot;
        lastShownHostUs = curHostUs;
    }

    /**
     * The decoder's frame-rendered callback. Compares its time with the requested one: equal means
     * the callback only echoes the request (it says nothing about when the frame was shown).
     */
    synchronized void onRendered(long ptsUs, long renderNs) {
        int p = (ringPos - 1 + RING) % RING;
        for (int i = 0; i < RING; i++) {
            if (ringPts[p] == ptsUs && ringDesired[p] > 0L) {
                TvDiagnostics.onRenderEchoCheck(Math.abs(renderNs - ringDesired[p]) <= ECHO_TOLERANCE_NS);
                ringDesired[p] = 0L;
                return;
            }
            p = (p - 1 + RING) % RING;
        }
    }
}
