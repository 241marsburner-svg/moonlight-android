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
        private long lastVsyncNs;
        private long period;
        private int framesUntilRateCheck;
        private long longStartNs;
        private long longRefreshes;
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

            // Follow the real refresh period. Callbacks can skip vsyncs, so divide by the
            // number of refreshes in between, and only follow small differences here.
            final long last = lastVsyncNs;
            lastVsyncNs = vsyncNs;
            if (last != 0L && vsyncNs > last) {
                final long delta = vsyncNs - last;
                final long refreshes = Math.max(1L, Math.round((double) delta / period));
                if (refreshes <= 4L) {
                    final long perRefresh = delta / refreshes;
                    if (Math.abs(perRefresh - period) < period / 20) {
                        period += (perRefresh - period) / 16;
                    }
                }
                // The exact refresh rate: refreshes counted over a long time (a stream at
                // 60.000 fps on a 60.012 Hz screen slips a frame every 80 s)
                if (delta < 1_000_000_000L && longStartNs != 0L) {
                    longRefreshes += refreshes;
                } else {
                    longStartNs = vsyncNs;
                    longRefreshes = 0;
                }
                if (longRefreshes >= 600 && longRefreshes - lastLongPublish >= 60) {
                    lastLongPublish = longRefreshes;
                    final double avg = (vsyncNs - longStartNs) / (double) longRefreshes;
                    TvDiagnostics.setMeasuredRefresh(1e9 / avg, (int) ((vsyncNs - longStartNs) / 1_000_000_000L));
                }
            } else if (longStartNs == 0L) {
                longStartNs = vsyncNs;
                longRefreshes = 0;
                lastLongPublish = 0;
            }

            // A display mode switch (e.g. 60 -> 120 Hz) shows up here within half a second
            if (--framesUntilRateCheck <= 0) {
                framesUntilRateCheck = 30;
                final long fromDisplay = periodFromDisplay(display, 0L);
                if (fromDisplay > 0L && Math.abs(fromDisplay - period) > period / 50) {
                    period = fromDisplay;
                    // The long measurement starts over at the new rate
                    longStartNs = vsyncNs;
                    longRefreshes = 0;
                    lastLongPublish = 0;
                }
            }

            if (presentNs > 0L) {
                long pipeline = 0L;
                if (deadlineNs > 0L && presentNs > deadlineNs && presentNs - deadlineNs < 4 * period) {
                    pipeline = presentNs - deadlineNs;
                }
                publish(this, presentNs, period, pipeline, true);
            } else {
                publish(this, vsyncNs, period, 0L, false);
            }

            postNext();
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
