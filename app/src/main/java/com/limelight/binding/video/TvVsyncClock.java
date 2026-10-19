package com.limelight.binding.video;

import android.annotation.TargetApi;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.view.Choreographer;
import android.view.Display;

import com.limelight.LimeLog;

/**
 * The display's refresh grid, for TV smooth/TV game pacing and for working out which refresh each
 * frame is shown on (TvScreenModel, in every pacing mode of the TV build). Also measures the
 * exact refresh rate over a long time.
 *
 * A Choreographer on its own thread sees every vsync. On Android 13+ each vsync also reports when
 * a buffer queued for it will reach the screen and SurfaceFlinger's deadline for that buffer,
 * which pins SurfaceFlinger's timing down exactly. On older versions the vsync timestamps give
 * the grid and the pacer has to assume how deep SurfaceFlinger's pipeline is.
 *
 * The grid is published as volatile fields: refNs + k * periodNs (for any integer k) are the
 * grid instants. Readers call {@link #snapshot(long, long[])}.
 */
final class TvVsyncClock {
    // A grid that hasn't been refreshed for this long is not trusted (thread stalled or stopped)
    private static final long STALE_NS = 200_000_000L;
    private static final long DEFAULT_PERIOD_NS = 16_666_667L;

    // ---- Published grid (written by the active session's thread) ----
    private volatile long refNs;
    private volatile long periodNs = DEFAULT_PERIOD_NS;
    private volatile long pipelineNs;          // presentation minus queue deadline, 0 = unknown
    private volatile boolean presentationGrid; // true: refNs is an expected presentation time
    private volatile long updatedNs;

    private volatile Session activeSession;    // written under 'this'

    synchronized void start(Display display) {
        if (activeSession != null) {
            return;
        }
        updatedNs = 0L;
        final Session s = new Session(display);
        activeSession = s;
        s.begin();
    }

    synchronized void stop() {
        final Session s = activeSession;
        activeSession = null;
        updatedNs = 0L;
        if (s != null) {
            s.end();
        }
    }

    /**
     * Copies the grid into out = {refNs, periodNs, pipelineNs, presentationGrid ? 1 : 0}.
     *
     * @return false if there is no fresh grid (the caller should fall back to plain spacing)
     */
    boolean snapshot(long nowNs, long[] out) {
        final long updated = updatedNs;
        if (updated == 0L || nowNs - updated > STALE_NS) {
            return false;
        }
        final long ref = refNs;
        final long period = periodNs;
        if (ref == 0L || period <= 0L) {
            return false;
        }
        out[0] = ref;
        out[1] = period;
        out[2] = pipelineNs;
        out[3] = presentationGrid ? 1L : 0L;
        return true;
    }

    private void publish(Session from, long ref, long period, long pipeline, boolean presentation) {
        if (activeSession != from) {
            // A session that is shutting down must not overwrite the new one's grid
            return;
        }
        periodNs = period;
        pipelineNs = pipeline;
        presentationGrid = presentation;
        refNs = ref;
        updatedNs = System.nanoTime();
    }

    private static long periodFromDisplay(Display display, long fallbackNs) {
        if (display != null) {
            try {
                final float hz = display.getRefreshRate();
                if (hz > 20f && hz < 400f) {
                    return (long) (1_000_000_000.0 / hz);
                }
            } catch (Throwable ignored) { }
        }
        return fallbackNs;
    }

    /** One clock thread. A new one is made for every start() so stop/start can't mix them up. */
    private final class Session implements Choreographer.FrameCallback {
        private final Display display;
        private final HandlerThread thread;
        private volatile boolean alive = true;

        // Session thread only
        private Choreographer choreographer;
        private Object vsyncCallback;   // Choreographer.VsyncCallback on Android 13+
        private long period;              // the display's nominal refresh period
        private int framesUntilRateCheck;
        private final Pll pll = new Pll();
        private long lastLongPublish;

        Session(Display display) {
            this.display = display;
            this.period = periodFromDisplay(display, DEFAULT_PERIOD_NS);
            this.thread = new HandlerThread("Video - Vsync", Process.THREAD_PRIORITY_DISPLAY);
        }

        void begin() {
            thread.start();
            new Handler(thread.getLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        choreographer = Choreographer.getInstance();
                        if (Build.VERSION.SDK_INT >= 33) {
                            vsyncCallback = Api33.createCallback(Session.this);
                        }
                        postNext();
                    } catch (Throwable t) {
                        LimeLog.warning("TV vsync clock unavailable: " + t);
                    }
                }
            });
        }

        void end() {
            alive = false;
            try {
                new Handler(thread.getLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (choreographer != null) {
                                choreographer.removeFrameCallback(Session.this);
                                if (vsyncCallback != null && Build.VERSION.SDK_INT >= 33) {
                                    Api33.remove(choreographer, vsyncCallback);
                                }
                            }
                        } catch (Throwable ignored) { }
                        try {
                            thread.quitSafely();
                        } catch (Throwable ignored) { }
                    }
                });
            } catch (Throwable t) {
                try {
                    thread.quitSafely();
                } catch (Throwable ignored) { }
            }
        }

        private void postNext() {
            if (!alive || choreographer == null) {
                return;
            }
            if (vsyncCallback != null && Build.VERSION.SDK_INT >= 33) {
                Api33.post(choreographer, vsyncCallback);
            } else {
                choreographer.postFrameCallback(this);
            }
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            onTick(frameTimeNanos, 0L, 0L);
        }

        /** Never let a timing glitch take the app down: worst case the pacer falls back. */
        void onTick(long vsyncNs, long presentNs, long deadlineNs) {
            try {
                tick(vsyncNs, presentNs, deadlineNs);
            } catch (Throwable t) {
                LimeLog.warning("TV vsync clock error: " + t);
                try {
                    postNext();
                } catch (Throwable ignored) { }
            }
        }

        /**
         * @param vsyncNs     this vsync's timestamp
         * @param presentNs   when a buffer queued for it will be shown (0 = unknown)
         * @param deadlineNs  SurfaceFlinger's deadline for that buffer (0 = unknown)
         */
        private void tick(long vsyncNs, long presentNs, long deadlineNs) {
            if (!alive) {
                return;
            }

            // A display mode switch (e.g. 60 -> 120 Hz) shows up here within half a second
            if (--framesUntilRateCheck <= 0) {
                framesUntilRateCheck = 30;
                final long fromDisplay = periodFromDisplay(display, 0L);
                if (fromDisplay > 0L && Math.abs(fromDisplay - period) > period / 50) {
                    period = fromDisplay;
                    pll.reset(); // lock again at the new rate
                }
            }

            pll.onVsync(vsyncNs, period);
            if (pll.lockedRefreshes >= 1200 && pll.lockedRefreshes - lastLongPublish >= 60) {
                lastLongPublish = pll.lockedRefreshes;
                TvDiagnostics.setMeasuredRefresh(pll.measuredHz(), pll.lockedSeconds());
            } else if (pll.lockedRefreshes < lastLongPublish) {
                lastLongPublish = 0;
            }
            final long gridPeriod = Math.round(pll.period);
            final long gridRef = Math.round(pll.ref);

            if (presentNs > 0L) {
                long pipeline = 0L;
                if (deadlineNs > 0L && presentNs > deadlineNs && presentNs - deadlineNs < 4 * gridPeriod) {
                    pipeline = presentNs - deadlineNs;
                }
                publish(this, presentNs, gridPeriod, pipeline, true);
            } else {
                publish(this, gridRef, gridPeriod, 0L, false);
            }

            postNext();
        }
    }

    /**
     * Phase-locked refresh grid. This TV's vsync timestamps jitter (and Choreographer moves them
     * after skipped frames), so the grid follows them slowly instead of jumping to each one: a
     * frame planned for a refresh keeps that refresh from one frame to the next. Also measures
     * the exact refresh rate over the time the grid stayed locked.
     */
    static final class Pll {
        double ref;                 // a refresh instant of the grid, 0 = not locked
        double period;
        private int outliers;
        private double lockRef;     // ref when the grid locked
        long lockedRefreshes;       // refreshes since then

        void reset() {
            ref = 0;
            outliers = 0;
            lockedRefreshes = 0;
        }

        /** @param nominalNs the display's refresh period, for locking and as a sanity bound */
        void onVsync(long vsyncNs, long nominalNs) {
            if (ref == 0) {
                ref = vsyncNs;
                period = nominalNs;
                lockRef = ref;
                lockedRefreshes = 0;
                outliers = 0;
                return;
            }
            final double k = Math.rint((vsyncNs - ref) / period);
            if (k >= 1 && k <= 120) {
                final double predicted = ref + k * period;
                final double err = vsyncNs - predicted;
                if (Math.abs(err) <= period / 4) {
                    // Phase follows an eighth of the error, the period much less (critically
                    // damped: it settles within a few seconds and then barely moves)
                    ref = predicted + err / 8;
                    period += err / (k * 256);
                    if (period < nominalNs * 0.97 || period > nominalNs * 1.03) {
                        period = nominalNs;
                    }
                    lockedRefreshes += (long) k;
                    outliers = 0;
                } else if (++outliers >= 8) {
                    // Not a single odd timestamp: the grid really moved. Start over.
                    ref = vsyncNs;
                    lockRef = ref;
                    lockedRefreshes = 0;
                    outliers = 0;
                }
                // (a single odd timestamp is ignored: the grid stays)
            } else if (k > 120) {
                // No callbacks for two seconds: lock the phase again, keep the period
                ref = vsyncNs;
                lockRef = ref;
                lockedRefreshes = 0;
                outliers = 0;
            }
        }

        double measuredHz() {
            return lockedRefreshes > 0 ? 1e9 * lockedRefreshes / (ref - lockRef) : 0;
        }

        int lockedSeconds() {
            return (int) ((ref - lockRef) / 1e9);
        }
    }

    /** Android 13+ frame timelines. Kept in its own class so older versions never load it. */
    @TargetApi(33)
    private static final class Api33 {
        private Api33() {}

        static Object createCallback(final Session session) {
            return new Choreographer.VsyncCallback() {
                @Override
                public void onVsync(Choreographer.FrameData data) {
                    long present = 0L;
                    long deadline = 0L;
                    try {
                        final Choreographer.FrameTimeline timeline = data.getPreferredFrameTimeline();
                        present = timeline.getExpectedPresentationTimeNanos();
                        deadline = timeline.getDeadlineNanos();
                    } catch (Throwable ignored) { }
                    session.onTick(data.getFrameTimeNanos(), present, deadline);
                }
            };
        }

        static void post(Choreographer choreographer, Object callback) {
            choreographer.postVsyncCallback((Choreographer.VsyncCallback) callback);
        }

        static void remove(Choreographer choreographer, Object callback) {
            choreographer.removeVsyncCallback((Choreographer.VsyncCallback) callback);
        }
    }
}
