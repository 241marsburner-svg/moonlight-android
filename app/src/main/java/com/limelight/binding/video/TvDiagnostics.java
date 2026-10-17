package com.limelight.binding.video;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;

import com.limelight.preferences.PreferenceConfiguration;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for the Artemide TV build, shown as an extra block in the full performance overlay.
 *
 * The stock overlay can't tell *where* frames get lost between "received" and "rendered".
 * These counters split that up: what the display actually showed (from MediaCodec's
 * frame-rendered callback), gaps between shown frames, what the pacer did, how long the
 * decoder made us wait for input buffers, keyframe requests, and audio underruns.
 *
 * Writers are the video/audio threads; the reader is the UI thread once per overlay update.
 */
public final class TvDiagnostics {
    private TvDiagnostics() {}

    // --- Video presentation ---
    private static final AtomicLong framesReleased = new AtomicLong();
    private static final AtomicLong framesDisplayed = new AtomicLong();
    private static final AtomicLong displayGaps = new AtomicLong();
    private static volatile long lastDisplayNs;
    private static volatile long maxDisplayIntervalNs;
    private static volatile long expectedFramePeriodNs = 16_666_667L;

    // --- TV smooth pacer ---
    private static final AtomicLong pacerLate = new AtomicLong();
    private static final AtomicLong pacerTrims = new AtomicLong();
    private static final AtomicLong pacerCapped = new AtomicLong();
    private static final AtomicLong leadSumNs = new AtomicLong();
    private static final AtomicLong leadSamples = new AtomicLong();
    private static volatile long maxLeadNs;

    // --- Refresh grid and what the display did with the plan ---
    private static volatile long vsyncPeriodNs;          // 0 = no grid (plain spacing)
    private static volatile boolean vsyncPresentationGrid;
    private static volatile long assumedPipelineNs;
    private static final AtomicLong presentSamples = new AtomicLong();
    private static final AtomicLong presentOffsetSumNs = new AtomicLong();
    private static final AtomicLong presentSlips = new AtomicLong();
    private static volatile long presentBaselineNs;
    private static volatile boolean presentBaselineSet;
    private static final AtomicLong unplannedDrops = new AtomicLong();
    private static volatile long adaptiveMarginNs;

    // --- Decoder ---
    private static volatile String decoderLowLatencyFeature = "?";
    private static volatile boolean mtkFullSpeedHintsApplied;
    private static volatile int decoderConfigTry = -1;
    private static final AtomicLong inputWaits = new AtomicLong();
    private static volatile long maxInputWaitNs;
    private static final AtomicLong keyframeRequests = new AtomicLong();

    // --- Audio ---
    private static volatile String audioTrackMode = "?";
    private static volatile int audioBufferMs;
    private static volatile int audioUnderruns;
    private static final AtomicLong audioSkipped = new AtomicLong();
    private static final AtomicLong audioReprimes = new AtomicLong();

    // --- Overlay rate computation (UI thread only) ---
    private static long snapNs;
    private static long snapReleased, snapDisplayed, snapGaps, snapLate, snapTrims;
    private static long snapLeadSum, snapLeadSamples, snapInputWaits, snapKeyframes, snapSkipped;
    private static long snapPresentSamples, snapPresentOffsetSum, snapPresentSlips;
    private static int snapUnderruns;

    public static void resetVideo() {
        framesReleased.set(0);
        framesDisplayed.set(0);
        displayGaps.set(0);
        lastDisplayNs = 0;
        maxDisplayIntervalNs = 0;
        pacerLate.set(0);
        pacerTrims.set(0);
        pacerCapped.set(0);
        leadSumNs.set(0);
        leadSamples.set(0);
        maxLeadNs = 0;
        vsyncPeriodNs = 0;
        vsyncPresentationGrid = false;
        assumedPipelineNs = 0;
        presentSamples.set(0);
        presentOffsetSumNs.set(0);
        presentSlips.set(0);
        presentBaselineNs = 0;
        presentBaselineSet = false;
        unplannedDrops.set(0);
        adaptiveMarginNs = 0;
        snapPresentSamples = 0;
        snapPresentOffsetSum = 0;
        snapPresentSlips = 0;
        inputWaits.set(0);
        maxInputWaitNs = 0;
        keyframeRequests.set(0);
        snapNs = 0;
    }

    // ---------------- Writers ----------------

    static void setExpectedFramePeriodNs(long periodNs) {
        if (periodNs > 0) {
            expectedFramePeriodNs = periodNs;
        }
    }

    static void onFrameReleased() {
        framesReleased.incrementAndGet();
    }

    /** Called from MediaCodec.OnFrameRenderedListener with the real display time. */
    static void onFrameDisplayed(long renderNs) {
        framesDisplayed.incrementAndGet();
        final long last = lastDisplayNs;
        lastDisplayNs = renderNs;
        if (last != 0 && renderNs > last) {
            final long interval = renderNs - last;
            // A gap is a refresh where the previous frame had to stay on screen longer than
            // expected. Ignore long pauses (menus, static desktop) above 250 ms.
            if (interval > (expectedFramePeriodNs * 3) / 2 && interval < 250_000_000L) {
                displayGaps.incrementAndGet();
                if (interval > maxDisplayIntervalNs) {
                    maxDisplayIntervalNs = interval;
                }
            }
        }
    }

    static void onFrameScheduled(long leadNs, boolean late, boolean trimmed, boolean capped) {
        if (leadNs < 0) {
            leadNs = 0;
        }
        leadSumNs.addAndGet(leadNs);
        leadSamples.incrementAndGet();
        if (leadNs > maxLeadNs) {
            maxLeadNs = leadNs;
        }
        if (late) {
            pacerLate.incrementAndGet();
        }
        if (trimmed) {
            pacerTrims.incrementAndGet();
        }
        if (capped) {
            pacerCapped.incrementAndGet();
        }
    }

    static void setVsyncInfo(long periodNs, boolean presentationGrid, long pipelineNs) {
        vsyncPeriodNs = periodNs;
        vsyncPresentationGrid = presentationGrid;
        assumedPipelineNs = pipelineNs;
    }

    /**
     * How far from its planned refresh a frame was actually shown (frame-rendered thread only).
     * A constant offset is normal (it depends on how the device reports times); a frame shown
     * more than half a refresh later than usual is a "slip": it missed the refresh it was planned for.
     */
    static void onPresentOffset(long offsetNs, long periodNs) {
        presentSamples.incrementAndGet();
        presentOffsetSumNs.addAndGet(offsetNs);
        long baseline = presentBaselineNs;
        if (!presentBaselineSet) {
            baseline = offsetNs;
            presentBaselineSet = true;
        } else if (offsetNs < baseline) {
            // Follow improvements quickly, regressions slowly
            baseline += (offsetNs - baseline) / 4;
        } else {
            baseline += (offsetNs - baseline) / 64;
        }
        presentBaselineNs = baseline;
        if (periodNs > 0 && offsetNs > baseline + periodNs / 2) {
            presentSlips.incrementAndGet();
        }
    }

    /** Frames the display dropped although the pacer gave each its own refresh. */
    static void onUnplannedDrops(int count) {
        unplannedDrops.addAndGet(count);
    }

    static void setAdaptiveMarginNs(long marginNs) {
        adaptiveMarginNs = marginNs;
    }

    public static void setDecoderLowLatencyFeature(boolean supported) {
        decoderLowLatencyFeature = supported ? "yes" : "no";
    }

    public static void setMtkFullSpeedHintsApplied(boolean applied) {
        mtkFullSpeedHintsApplied = applied;
    }

    static void setDecoderConfigTry(int tryNumber) {
        decoderConfigTry = tryNumber;
    }

    static void onInputWait(long waitedNs) {
        inputWaits.incrementAndGet();
        if (waitedNs > maxInputWaitNs) {
            maxInputWaitNs = waitedNs;
        }
    }

    public static void onKeyframeRequested() {
        keyframeRequests.incrementAndGet();
    }

    public static void setAudioTrackInfo(String mode, int bufferMs) {
        audioTrackMode = mode;
        audioBufferMs = bufferMs;
        audioUnderruns = 0;
        audioSkipped.set(0);
        audioReprimes.set(0);
        snapUnderruns = 0;
        snapSkipped = 0;
    }

    /** The audio cushion had to be refilled with silence (audio was lost, not just late). */
    public static void onAudioReprimed() {
        audioReprimes.incrementAndGet();
    }

    public static void setAudioUnderruns(int underruns) {
        audioUnderruns = underruns;
    }

    public static void onAudioSkipped() {
        audioSkipped.incrementAndGet();
    }

    // ---------------- Reader (UI thread) ----------------

    /**
     * Builds the TV block for the full overlay. Rates are per second since the last call.
     */
    public static String buildOverlayBlock(Context context, PreferenceConfiguration prefs) {
        final long now = System.nanoTime();
        final long released = framesReleased.get();
        final long displayed = framesDisplayed.get();
        final long gaps = displayGaps.get();
        final long late = pacerLate.get();
        final long trims = pacerTrims.get();
        final long leadSum = leadSumNs.get();
        final long leadN = leadSamples.get();
        final long waits = inputWaits.get();
        final long keyframes = keyframeRequests.get();
        final long skipped = audioSkipped.get();
        final int underruns = audioUnderruns;
        final long pSamples = presentSamples.get();
        final long pOffsetSum = presentOffsetSumNs.get();
        final long pSlips = presentSlips.get();

        final double secs = (snapNs == 0) ? 0 : (now - snapNs) / 1e9;
        final boolean haveRates = secs > 0.2;

        final double shownFps = haveRates ? (displayed - snapDisplayed) / secs : 0;
        final double releasedFps = haveRates ? (released - snapReleased) / secs : 0;
        final double gapsPerSec = haveRates ? (gaps - snapGaps) / secs : 0;
        final double latePerSec = haveRates ? (late - snapLate) / secs : 0;
        final long windowLeadN = leadN - snapLeadSamples;
        final double avgLeadMs = (windowLeadN > 0) ? ((leadSum - snapLeadSum) / (double) windowLeadN) / 1e6 : 0;
        final long windowPresentN = pSamples - snapPresentSamples;
        final double avgPresentOffsetMs = (windowPresentN > 0)
                ? ((pOffsetSum - snapPresentOffsetSum) / (double) windowPresentN) / 1e6 : 0;
        final double slipsPerSec = haveRates ? (pSlips - snapPresentSlips) / secs : 0;

        snapNs = now;
        snapReleased = released;
        snapDisplayed = displayed;
        snapGaps = gaps;
        snapLate = late;
        snapTrims = trims;
        snapLeadSum = leadSum;
        snapLeadSamples = leadN;
        snapInputWaits = waits;
        snapKeyframes = keyframes;
        snapSkipped = skipped;
        snapUnderruns = underruns;
        snapPresentSamples = pSamples;
        snapPresentOffsetSum = pOffsetSum;
        snapPresentSlips = pSlips;

        final StringBuilder sb = new StringBuilder(400);
        sb.append("\n— TV —\n");

        // Display mode as the system reports it right now
        sb.append("Display: ").append(describeDisplay(context));
        sb.append(" · pacing ").append(describePacing(prefs)).append('\n');

        // What actually reached the screen
        if (displayed > 0) {
            sb.append(String.format(Locale.US, "Shown %.1f fps (sent %.1f) · gaps %.1f/s, longest %d ms\n",
                    shownFps, releasedFps, gapsPerSec, maxDisplayIntervalNs / 1_000_000L));
        } else {
            sb.append(String.format(Locale.US, "Sent to display %.1f fps (no render callbacks)\n", releasedFps));
        }

        if (prefs != null && prefs.framePacing == PreferenceConfiguration.FRAME_PACING_TV_SMOOTH) {
            sb.append(String.format(Locale.US, "Jitter buffer +%.1f ms (max %d ms) · late %.1f/s · trims %d · capped %d\n",
                    avgLeadMs, maxLeadNs / 1_000_000L, latePerSec, trims, pacerCapped.get()));

            final long period = vsyncPeriodNs;
            if (period > 0) {
                sb.append(String.format(Locale.US, "Refresh grid %.2f Hz (%s) · SF lead %.1f ms",
                        1e9 / period, vsyncPresentationGrid ? "present times" : "vsync",
                        assumedPipelineNs / 1e6));
            } else {
                sb.append("Refresh grid: none (plain spacing)");
            }
            if (windowPresentN > 0) {
                sb.append(String.format(Locale.US, " · shown vs plan %+.1f ms · slips %.1f/s",
                        avgPresentOffsetMs, slipsPerSec));
            }
            sb.append(String.format(Locale.US, " · display drops %d (margin +%.1f ms)\n",
                    unplannedDrops.get(), adaptiveMarginNs / 1e6));
        }

        sb.append("Decoder: low-latency ").append(decoderLowLatencyFeature)
                .append(" · MTK full-speed ").append(mtkFullSpeedHintsApplied ? "on" : "off")
                .append(" · preset ").append(decoderConfigTry)
                .append(String.format(Locale.US, " · input waits %d (max %d ms) · keyframe req %d\n",
                        waits, maxInputWaitNs / 1_000_000L, keyframes));

        sb.append("Audio: ").append(audioTrackMode).append(' ').append(audioBufferMs).append(" ms")
                .append(" · underruns ").append(underruns)
                .append(" · refilled ").append(audioReprimes.get())
                .append(" · skipped ").append(skipped);

        return sb.toString();
    }

    private static String describePacing(PreferenceConfiguration prefs) {
        if (prefs == null) {
            return "?";
        }
        switch (prefs.framePacing) {
            case PreferenceConfiguration.FRAME_PACING_TV_SMOOTH: return "TV smooth";
            case PreferenceConfiguration.FRAME_PACING_BALANCED: return "Balanced";
            case PreferenceConfiguration.FRAME_PACING_MIN_LATENCY: return "Lowest latency";
            case PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS: return "Smoothest";
            case PreferenceConfiguration.FRAME_PACING_CAP_FPS: return "Balanced + FPS limit";
            case PreferenceConfiguration.FRAME_PACING_GPU_RAW: return "GPU raw";
            case PreferenceConfiguration.FRAME_PACING_WARP: return "Warp";
            case PreferenceConfiguration.FRAME_PACING_WARP2: return "Warp 2";
            default: return "?";
        }
    }

    @SuppressWarnings("deprecation")
    private static String describeDisplay(Context context) {
        try {
            Display d = null;
            if (context instanceof Activity) {
                d = ((Activity) context).getWindowManager().getDefaultDisplay();
            } else if (context != null) {
                final WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    d = wm.getDefaultDisplay();
                }
            }
            if (d == null) {
                return "?";
            }
            final float hz = d.getRefreshRate();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                final Display.Mode m = d.getMode();
                return String.format(Locale.US, "%dx%d @ %.2f Hz", m.getPhysicalWidth(), m.getPhysicalHeight(), hz);
            }
            return String.format(Locale.US, "%.2f Hz", hz);
        } catch (Throwable t) {
            return "?";
        }
    }
}
