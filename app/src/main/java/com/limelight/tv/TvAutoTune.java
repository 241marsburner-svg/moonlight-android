package com.limelight.tv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.preference.PreferenceManager;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.binding.video.TvDiagnostics;
import com.limelight.preferences.PreferenceConfiguration;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Artemide TV: auto-tune. Runs a series of short test streams with different settings against
 * whatever is running on the PC, measures each one and scores it.
 *
 * Every test: 10 s warm-up (decoder start, Apollo's display switch, Wi-Fi rate adaptation), then
 * 35 s measured. Three separate scores, 0-100, higher is better:
 *
 *  Smoothness  frames shown per second against what the screen can show, minus the time the
 *              picture stood still beyond its normal cadence (hitches), frame losses (each one
 *              freezes the picture until a new keyframe) and long network pauses
 *  Latency     what the TV adds (arrival to screen: decode, pacing buffer, compositor, measured per
 *              frame) + half the network round trip + half a frame interval (the PC waits for the
 *              next frame to capture, so more frames per second answer sooner)
 *  Picture     resolution (the panel is 4K, the TV scales the rest) and bits per pixel, counting
 *              AV1 as 30 % more efficient than HEVC
 *
 * Total = 40 % smoothness + 35 % latency + 25 % picture. A test that is not smooth (below 70)
 * is never picked as "best" while a smooth one exists.
 *
 * State lives in this class (one app process); the controller is TvAutoTuneActivity, the test
 * streams are ordinary Game activities that read the overrides in {@link #applyStepOverrides}.
 */
public final class TvAutoTune {
    private TvAutoTune() {}

    public static final long WARMUP_MS = 10_000;
    // The first test may have to launch the app on the PC (or create Apollo's display first)
    public static final long FIRST_WARMUP_MS = 20_000;
    public static final long MEASURE_MS = 35_000;
    public static final long CONNECT_TIMEOUT_MS = 40_000;
    private static final String PREF_LAST_LOG = "artemide_tv_autotune_last_log"; // old builds kept the log here
    private static final String PREF_LAST_SUMMARY = "artemide_tv_autotune_last_summary";
    private static final String LOG_FILE = "last-autotune.txt";

    public static final int CODEC_HEVC = 0;
    public static final int CODEC_AV1 = 1;
    public static final int CODEC_H264 = 2;

    // Score weights (also printed in the log)
    private static final double HITCH_POINTS_PER_STALL_MS = 0.24; // a one-refresh hitch at 60 Hz (16.7 ms) = 4 points
    private static final double LOSS_POINTS = 6;
    private static final double PAUSE_POINTS = 1;

    public static String codecName(int codec) {
        switch (codec) {
            case CODEC_AV1: return "AV1";
            case CODEC_H264: return "H.264";
            default: return "HEVC";
        }
    }

    public static final class Config {
        public final String label;
        public final int width, height, fps, bitrateKbps;
        public final boolean smoothPacing; // TV smooth instead of TV game
        public final int codec;

        Config(String label, int width, int height, int fps, int bitrateKbps, boolean smoothPacing, int codec) {
            this.label = label;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.bitrateKbps = bitrateKbps;
            this.smoothPacing = smoothPacing;
            this.codec = codec;
        }

        public String describe() {
            return String.format(Locale.US, "%s %d fps %d Mbps %s %s", resolutionName(width, height), fps,
                    bitrateKbps / 1000, codecName(codec), smoothPacing ? "TV smooth" : "TV game");
        }

        boolean sameAs(Config o) {
            return o != null && width == o.width && height == o.height && fps == o.fps &&
                    bitrateKbps == o.bitrateKbps && smoothPacing == o.smoothPacing && codec == o.codec;
        }
    }

    public static final class Result {
        public final Config config;
        public boolean ok;
        public String failure;
        // Measured over the window
        public double secs, shownFps, releasedFps, arrivedFps, expectedFps, hitchesPerMin, stallMsPerMin, netPausesPerMin;
        public double lossEventsPerMin, framesLostPerMin, decodeMs, screenMs, maxScreenMs, rttMs;
        public double leadMs, mbpsUsed, audioFecPct, onTimePct, screenHz;
        public long longestHitchMs, longestNetPauseMs, keyframes;
        public boolean staticPicture;
        public String codecUsed = "?", decoder = "?", display = "?";
        // Scores 0-100, and how each came about
        public double smooth, latency, picture, total;
        public String smoothWhy = "", latencyWhy = "", pictureWhy = "";
        public String report;
        public String timeline;

        Result(Config config) {
            this.config = config;
        }
    }

    private static final List<Config> plan = new ArrayList<>();
    private static final List<Result> results = new ArrayList<>();
    private static Intent gameIntent;
    private static int index = -1;           // test being run (or just finished)
    private static boolean running;
    private static boolean stepActive;        // a Game for plan[index] is up (or starting)
    private static boolean stepDone;          // ... and its result is in
    private static boolean measuring;
    private static TvDiagnostics.Sample windowStart;
    private static int[] audioStart;
    private static String startedAt = "";
    private static String abortReason;
    private static String deviceInfo = "";
    private static boolean av1Tested;

    // ---------------- Plan ----------------

    private static void buildPlan(PreferenceConfiguration current) {
        plan.clear();
        if (current != null && current.width > 0 && current.height > 0) {
            final int codec = current.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1 ? CODEC_AV1
                    : current.videoFormat == PreferenceConfiguration.FormatOption.FORCE_H264 ? CODEC_H264 : CODEC_HEVC;
            plan.add(new Config("your current settings", current.width, current.height,
                    Math.round(current.fps), current.bitrate,
                    current.framePacing == PreferenceConfiguration.FRAME_PACING_TV_SMOOTH, codec));
        }
        addIfNew(new Config("1440p baseline", 2560, 1440, 60, 60_000, false, CODEC_HEVC));
        addIfNew(new Config("1440p more bitrate", 2560, 1440, 60, 80_000, false, CODEC_HEVC));
        addIfNew(new Config("1440p high bitrate", 2560, 1440, 60, 100_000, false, CODEC_HEVC));
        addIfNew(new Config("1440p smooth pacing", 2560, 1440, 60, 80_000, true, CODEC_HEVC));
        // AV1 against HEVC at the same settings (only if the TV has an AV1 hardware decoder)
        av1Tested = TvDeviceInfo.hasHardwareDecoder("video/av01");
        if (av1Tested) {
            addIfNew(new Config("1440p AV1", 2560, 1440, 60, 80_000, false, CODEC_AV1));
        }
        addIfNew(new Config("1440p 90 fps", 2560, 1440, 90, 80_000, false, CODEC_HEVC));
        addIfNew(new Config("1080p", 1920, 1080, 60, 50_000, false, CODEC_HEVC));
        addIfNew(new Config("1080p 120 fps", 1920, 1080, 120, 80_000, false, CODEC_HEVC));
        addIfNew(new Config("4K", 3840, 2160, 60, 80_000, false, CODEC_HEVC));
        if (av1Tested) {
            addIfNew(new Config("4K AV1", 3840, 2160, 60, 80_000, false, CODEC_AV1));
        }
    }

    private static void addIfNew(Config c) {
        for (Config p : plan) {
            if (p.sameAs(c)) {
                return;
            }
        }
        plan.add(c);
    }

    // ---------------- Lifecycle (controller side) ----------------

    public static synchronized boolean isRunning() {
        return running;
    }

    /** True while a test stream for the auto-tune should be (or is) running. */
    public static synchronized boolean isStepActive() {
        return running && stepActive;
    }

    public static synchronized void begin(Context context, Intent fromGame) {
        gameIntent = new Intent(fromGame);
        results.clear();
        index = -1;
        running = true;
        stepActive = false;
        stepDone = false;
        measuring = false;
        abortReason = null;
        startedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
        buildPlan(PreferenceConfiguration.readPreferences(context));
        try {
            deviceInfo = TvDeviceInfo.describeAll(context.getApplicationContext());
        } catch (Throwable t) {
            deviceInfo = "(device info failed: " + t + ")\n";
        }
        LimeLog.info("Artemide TV auto-tune: " + plan.size() + " tests" + (av1Tested ? " (with AV1)" : " (no AV1 decoder)"));
    }

    /** Warm-up of the test that is running now. */
    public static synchronized long currentWarmupMs() {
        return index <= 0 ? FIRST_WARMUP_MS : WARMUP_MS;
    }

    public static synchronized int planSize() {
        return plan.size();
    }

    public static synchronized int completedCount() {
        return results.size();
    }

    /** @return the intent for the next test stream, or null when all tests are done */
    public static synchronized Intent nextStep() {
        if (!running) {
            return null;
        }
        if (index + 1 >= plan.size()) {
            return null;
        }
        index++;
        stepActive = true;
        stepDone = false;
        measuring = false;
        return new Intent(gameIntent);
    }

    /** Controller: the test stream for the current step has closed. */
    public static synchronized void onStepClosed() {
        if (!running || index < 0) {
            return;
        }
        if (stepActive && !stepDone) {
            // Closed before the measurement finished: the user left the stream (or it failed silently)
            abortReason = "test " + (index + 1) + " was closed before it finished";
            running = false;
        }
        stepActive = false;
    }

    public static synchronized void abort(String reason) {
        if (running) {
            abortReason = reason;
        }
        running = false;
        stepActive = false;
    }

    public static synchronized boolean finishedAll() {
        return results.size() >= plan.size() && !plan.isEmpty();
    }

    public static synchronized String currentLabel() {
        return (index >= 0 && index < plan.size()) ? plan.get(index).describe() : "";
    }

    public static synchronized String nextLabel() {
        return (index + 1 < plan.size()) ? plan.get(index + 1).describe() : "";
    }

    // ---------------- Test stream side (Game) ----------------

    /** Game.onCreate: the settings for this test replace the saved ones (nothing is written). */
    public static synchronized void applyStepOverrides(PreferenceConfiguration prefs) {
        if (!running || !stepActive || index < 0 || index >= plan.size() || prefs == null) {
            return;
        }
        final Config c = plan.get(index);
        prefs.width = c.width;
        prefs.height = c.height;
        prefs.fps = c.fps;
        prefs.bitrate = c.bitrateKbps;
        prefs.framePacing = c.smoothPacing ? PreferenceConfiguration.FRAME_PACING_TV_SMOOTH
                : PreferenceConfiguration.FRAME_PACING_TV_GAME;
        prefs.videoFormat = c.codec == CODEC_AV1 ? PreferenceConfiguration.FormatOption.FORCE_AV1
                : c.codec == CODEC_H264 ? PreferenceConfiguration.FormatOption.FORCE_H264
                : PreferenceConfiguration.FormatOption.FORCE_HEVC;
        // The figures come from the stream statistics, so they must be collected
        prefs.tvStreamReport = true;
        prefs.enablePerfOverlay = false;
        LimeLog.info("Artemide TV auto-tune: test " + (index + 1) + "/" + plan.size() + ": " + c.describe());
    }

    /** Game, WARMUP_MS after the stream started. */
    public static synchronized void onWindowStart(TvDiagnostics.Sample sample, int[] audioRtp) {
        if (!isStepActiveLocked()) {
            return;
        }
        windowStart = sample;
        audioStart = audioRtp;
        measuring = true;
        TvTimeline.mark("measuring starts");
    }

    /**
     * Game, at the end of the measured window.
     * @param codecUsed the codec the stream really ran with ("HEVC", "AV1", ...)
     */
    public static synchronized void onWindowEnd(Context context, TvDiagnostics.Sample end, int[] audioRtp,
                                                float screenHz, String report, String codecUsed) {
        if (!isStepActiveLocked() || stepDone) {
            return;
        }
        TvTimeline.mark("measuring ends");
        final Result r = new Result(plan.get(index));
        r.codecUsed = codecUsed != null ? codecUsed : "?";
        r.decoder = TvDiagnostics.decoderName();
        try {
            r.display = TvDiagnostics.describeDisplay(context);
        } catch (Throwable ignored) { }
        r.timeline = TvTimeline.text();
        if (!measuring || windowStart == null) {
            r.ok = false;
            r.failure = "no measurement";
        } else {
            measure(r, windowStart, end, audioStart, audioRtp, screenHz);
            r.ok = true;
        }
        if (r.ok && !codecName(r.config.codec).equals(r.codecUsed) && !"?".equals(r.codecUsed)) {
            r.ok = false;
            r.failure = "asked for " + codecName(r.config.codec) + " but the stream ran " + r.codecUsed;
        }
        r.report = report;
        score(r);
        results.add(r);
        stepDone = true;
        measuring = false;
        saveProgress(context);
        LimeLog.info(String.format(Locale.US, "Artemide TV auto-tune: test %d done, smooth %.0f latency %.0f picture %.0f total %.0f",
                index + 1, r.smooth, r.latency, r.picture, r.total));
    }

    /** Game: the test stream could not start or broke down. */
    public static synchronized void onStepFailed(Context context, String why) {
        if (!isStepActiveLocked() || stepDone) {
            return;
        }
        final Result r = new Result(plan.get(index));
        r.ok = false;
        r.failure = why;
        r.decoder = TvDiagnostics.decoderName();
        r.timeline = TvTimeline.text();
        score(r);
        results.add(r);
        stepDone = true;
        measuring = false;
        saveProgress(context);
        LimeLog.warning("Artemide TV auto-tune: test " + (index + 1) + " failed: " + why);
    }

    private static boolean isStepActiveLocked() {
        return running && stepActive && index >= 0 && index < plan.size();
    }

    // ---------------- Measuring and scoring ----------------

    private static void measure(Result r, TvDiagnostics.Sample a, TvDiagnostics.Sample b,
                                int[] audioA, int[] audioB, float screenHz) {
        final double secs = Math.max(1.0, (b.atNs - a.atNs) / 1e9);
        final double mins = secs / 60.0;
        r.secs = secs;
        r.shownFps = (b.displayed - a.displayed) / secs;
        r.releasedFps = (b.released - a.released) / secs;
        r.arrivedFps = (b.arrivals - a.arrivals) / secs;
        final double hz = screenHz > 20f ? screenHz : 60.0;
        r.screenHz = hz;
        r.expectedFps = Math.min(r.config.fps, hz);
        r.hitchesPerMin = (b.displayGaps - a.displayGaps) / mins;
        r.stallMsPerMin = (b.hitchExtraNs - a.hitchExtraNs) / 1e6 / mins;
        r.longestHitchMs = b.maxDisplayIntervalNs / 1_000_000L;
        r.netPausesPerMin = (b.netGaps - a.netGaps) / mins;
        r.longestNetPauseMs = b.maxNetGapMs;
        r.lossEventsPerMin = (b.lossEvents - a.lossEvents) / mins;
        r.framesLostPerMin = (b.framesLost - a.framesLost) / mins;
        r.keyframes = b.keyframeRequests - a.keyframeRequests;
        final long decN = b.decodeSamples - a.decodeSamples;
        r.decodeMs = decN > 0 ? (b.decodeSumUs - a.decodeSumUs) / (double) decN / 1000.0 : -1;
        final long scrN = b.screenSamples - a.screenSamples;
        r.screenMs = scrN > 0 ? (b.screenSumUs - a.screenSumUs) / (double) scrN / 1000.0 : -1;
        r.maxScreenMs = b.maxScreenUs / 1000.0;
        final long rttN = b.rttSamples - a.rttSamples;
        r.rttMs = rttN > 0 ? (b.rttSumMs - a.rttSumMs) / (double) rttN : -1;
        final long leadN = b.leadSamples - a.leadSamples;
        r.leadMs = leadN > 0 ? (b.leadSumNs - a.leadSumNs) / (double) leadN / 1e6 : 0;
        r.mbpsUsed = (b.bytes - a.bytes) * 8.0 / secs / 1e6;
        r.staticPicture = r.mbpsUsed < r.config.bitrateKbps / 1000.0 * 0.25;
        final long presN = b.presentSamples - a.presentSamples;
        r.onTimePct = presN > 0 ? 100.0 * (presN - (b.presentSlips - a.presentSlips)) / presN : -1;
        if (audioA != null && audioB != null && audioA.length >= 3 && audioB.length >= 3) {
            final long pk = audioB[0] - audioA[0];
            r.audioFecPct = pk > 0 ? 100.0 * (audioB[2] - audioA[2]) / pk : 0;
        }
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }

    private static void score(Result r) {
        if (!r.ok) {
            r.smooth = r.latency = r.picture = r.total = 0;
            r.smoothWhy = r.latencyWhy = r.pictureWhy = "not measured: " + r.failure;
            return;
        }
        // Smoothness: frames that reached the screen, minus the time the picture stood still
        // beyond its cadence (a one-refresh hitch costs 4 points at 60 Hz, 2 at 120 Hz), frame
        // losses (each freezes the picture until a keyframe) and long network pauses
        final double shown = r.shownFps > 0 ? r.shownFps : r.releasedFps;
        final double ratio = r.expectedFps > 0 ? Math.min(1.0, shown / r.expectedFps) : 0;
        final double frames = 100 * ratio;
        final double hitchP = HITCH_POINTS_PER_STALL_MS * r.stallMsPerMin;
        final double lossP = LOSS_POINTS * r.lossEventsPerMin;
        final double pauseP = PAUSE_POINTS * r.netPausesPerMin;
        r.smooth = clamp(frames - hitchP - lossP - pauseP);
        r.smoothWhy = String.format(Locale.US,
                "%.0f = frames %.0f (%.1f of %.0f fps shown; PC sent %.1f) - hitches %.0f (%.1f/min, picture stood still %.0f ms/min, longest frame %d ms)" +
                        " - losses %.0f (%.1f/min, %.1f frames/min) - network pauses %.0f (%.1f/min, longest %d ms)",
                r.smooth, frames, shown, r.expectedFps, r.arrivedFps, hitchP, r.hitchesPerMin, r.stallMsPerMin, r.longestHitchMs,
                lossP, r.lossEventsPerMin, r.framesLostPerMin, pauseP, r.netPausesPerMin, r.longestNetPauseMs);

        // Latency: what the TV adds + network + the PC waiting for the next frame
        double tvMs = r.screenMs;
        String tvHow = "measured arrival to screen";
        if (tvMs <= 0) {
            // No frame-rendered times on this TV: estimate from decode + buffer + one refresh
            tvMs = Math.max(0, r.decodeMs) + r.leadMs + 1000.0 / Math.max(1.0, r.expectedFps);
            tvHow = "estimated: decode + buffer + 1 refresh";
        }
        final double net = Math.max(0, r.rttMs) / 2.0;
        final double capture = 500.0 / Math.max(1, r.config.fps);
        final double total = tvMs + net + capture;
        r.latency = clamp(100 - (total - 20) * 1.5);
        r.latencyWhy = String.format(Locale.US,
                "%.0f = 100 - 1.5 x (TV %.1f ms [%s; decode %.1f, buffer %.1f, max %.0f] + network %.1f ms [round trip %.0f / 2] + capture wait %.1f ms - 20) = %.1f ms in all",
                r.latency, tvMs, tvHow, r.decodeMs, r.leadMs, r.maxScreenMs, net, r.rttMs, capture, total);

        // Picture: resolution and bits per pixel (0.15 bpp is plenty for HEVC game streaming;
        // AV1 needs about 30 % fewer bits for the same picture, H.264 about 60 % more)
        final double res = r.config.height >= 2160 ? 100 : r.config.height >= 1440 ? 85
                : r.config.height >= 1080 ? 70 : 50;
        final double efficiency = r.config.codec == CODEC_AV1 ? 1.3 : r.config.codec == CODEC_H264 ? 0.6 : 1.0;
        final double bpp = r.config.bitrateKbps * 1000.0 /
                ((double) r.config.width * r.config.height * Math.max(1, r.config.fps)) * efficiency;
        r.picture = clamp(res * (0.6 + 0.4 * Math.min(1.0, bpp / 0.15)));
        r.pictureWhy = String.format(Locale.US, "%.0f = %s %.0f x (0.6 + 0.4 x %.3f bits per pixel (%s) / 0.15)",
                r.picture, resolutionName(r.config.width, r.config.height), res, bpp, codecName(r.config.codec));

        r.total = 0.40 * r.smooth + 0.35 * r.latency + 0.25 * r.picture;
    }

    /** Ranked best first; a smooth result (70+) always ranks above a stuttering one. */
    public static synchronized List<Result> ranked() {
        final List<Result> list = new ArrayList<>(results);
        Collections.sort(list, (x, y) -> {
            final boolean sx = x.ok && x.smooth >= 70, sy = y.ok && y.smooth >= 70;
            if (sx != sy) {
                return sx ? -1 : 1;
            }
            return Double.compare(y.total, x.total);
        });
        return list;
    }

    public static synchronized Result best() {
        final List<Result> list = ranked();
        return (!list.isEmpty() && list.get(0).ok) ? list.get(0) : null;
    }

    /** Best in one category (0 picture, 1 smoothness, 2 latency), among the smooth tests if any. */
    public static synchronized Result bestBy(int which) {
        boolean anySmooth = false;
        for (Result r : results) {
            anySmooth |= r.ok && r.smooth >= 70;
        }
        Result best = null;
        for (Result r : results) {
            if (!r.ok || (anySmooth && which != 1 && r.smooth < 70)) {
                continue;
            }
            final double v = which == 0 ? r.picture : which == 1 ? r.smooth : r.latency;
            final double bv = best == null ? -1 : which == 0 ? best.picture : which == 1 ? best.smooth : best.latency;
            if (best == null || v > bv) {
                best = r;
            }
        }
        return best;
    }

    public static synchronized List<Result> results() {
        return new ArrayList<>(results);
    }

    /** Writes a result's settings as the saved settings. */
    public static void use(Context context, Config c) {
        final SharedPreferences.Editor e = PreferenceManager.getDefaultSharedPreferences(context).edit();
        e.putString("list_resolution", c.width + "x" + c.height);
        e.putString("list_fps", String.valueOf(c.fps));
        e.putInt("seekbar_bitrate_kbps", c.bitrateKbps);
        e.putString("frame_pacing", c.smoothPacing ? "tv" : "tvgame");
        e.putString("video_format", c.codec == CODEC_AV1 ? "forceav1" : c.codec == CODEC_H264 ? "neverh265" : "forceh265");
        if (c.fps > 60) {
            e.putBoolean("checkbox_unlock_fps", true);
        }
        e.apply();
        LimeLog.info("Artemide TV auto-tune: now using " + c.describe());
    }

    // ---------------- Text and log file ----------------

    public static synchronized String summaryText() {
        final StringBuilder b = new StringBuilder();
        final List<Result> list = ranked();
        b.append(String.format(Locale.US, "%-3s %-36s %5s %6s %7s %7s\n", "#", "Settings", "Total", "Smooth", "Latency", "Picture"));
        int rank = 1;
        for (Result r : list) {
            if (r.ok) {
                b.append(String.format(Locale.US, "%-3d %-36s %5.0f %6.0f %7.0f %7.0f%s\n", rank, r.config.describe(),
                        r.total, r.smooth, r.latency, r.picture, r.staticPicture ? "  (static picture)" : ""));
            } else {
                b.append(String.format(Locale.US, "%-3s %-36s failed: %s\n", "-", r.config.describe(), r.failure));
            }
            rank++;
        }
        final Result bp = bestBy(0), bs = bestBy(1), bl = bestBy(2);
        if (bp != null && bs != null && bl != null) {
            b.append("\n(Picture and latency winners are picked among the smooth tests)");
            b.append("\nBest picture:    ").append(bp.config.describe());
            b.append("\nBest smoothness: ").append(bs.config.describe());
            b.append("\nBest latency:    ").append(bl.config.describe());
            b.append('\n');
        }
        if (!av1Tested) {
            b.append("\nNo AV1 test: this TV has no AV1 hardware decoder.\n");
        }
        if (abortReason != null) {
            b.append("\nStopped early: ").append(abortReason).append('\n');
        }
        return b.toString();
    }

    /** Why each test got its scores (for the screen). */
    public static synchronized String detailsText() {
        final StringBuilder b = new StringBuilder(4096);
        int n = 1;
        for (Result r : results) {
            b.append(n++).append(". ").append(r.config.describe());
            if (!r.ok) {
                b.append(": failed (").append(r.failure).append(")\n\n");
                continue;
            }
            b.append(String.format(Locale.US, " · screen %.0f Hz · %s · %s\n", r.screenHz, r.codecUsed, r.decoder));
            b.append("  Smoothness ").append(r.smoothWhy).append('\n');
            b.append("  Latency ").append(r.latencyWhy).append('\n');
            b.append("  Picture ").append(r.pictureWhy).append('\n');
            b.append(String.format(Locale.US, "  Used %.1f Mbps · keyframe requests %d · audio rebuilt by FEC %.2f%%%s\n\n",
                    r.mbpsUsed, r.keyframes, r.audioFecPct, r.staticPicture ? " · STATIC PICTURE: the PC sent little, compare with care" : ""));
        }
        return b.toString();
    }

    public static synchronized String fullLog() {
        final StringBuilder b = new StringBuilder(64 * 1024);
        b.append("Artemide TV auto-tune · ").append(startedAt).append(" · ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE).append(" · app ")
                .append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        b.append(String.format(Locale.US, "Each test: %d s warm-up (the first one %d s), %d s measured.\n\n",
                WARMUP_MS / 1000, FIRST_WARMUP_MS / 1000, MEASURE_MS / 1000));
        b.append("RANKING\n").append(summaryText()).append('\n');
        b.append("SCORES (0-100, higher is better)\n");
        b.append(String.format(Locale.US,
                " Smoothness = 100 x shown/possible frames - %.2f per ms/min the picture stood still beyond its cadence" +
                        " - %.0f per frame loss/min - %.0f per network pause (40 ms+) per minute\n",
                HITCH_POINTS_PER_STALL_MS, LOSS_POINTS, PAUSE_POINTS));
        b.append(" Latency    = 100 - 1.5 x (ms the TV adds + round trip/2 + half a frame interval - 20)\n");
        b.append(" Picture    = resolution (4K 100, 1440p 85, 1080p 70) x (0.6 + 0.4 x bits-per-pixel/0.15, capped; AV1 x1.3)\n");
        b.append(" Total      = 40% smoothness + 35% latency + 25% picture; tests below 70 smoothness rank last\n\n");
        b.append("WHY EACH TEST GOT ITS SCORES\n").append(detailsText());
        b.append("DETAILS\n");
        b.append("test;settings;ok;total;smooth;latency;picture;shown fps;possible fps;PC sent fps;screen Hz;hitches/min;" +
                "stood still ms/min;longest hitch ms;net pauses/min;longest pause ms;loss events/min;frames lost/min;" +
                "keyframe requests;decode ms;TV adds ms (arrival to screen);max TV ms;round trip ms;buffer ms;Mbps used;" +
                "audio FEC %;on time %;codec used;decoder;note\n");
        int n = 1;
        for (Result r : results) {
            b.append(String.format(Locale.US,
                    "%d;%s;%s;%.0f;%.0f;%.0f;%.0f;%.1f;%.0f;%.1f;%.0f;%.1f;%.0f;%d;%.1f;%d;%.1f;%.1f;%d;%.1f;%.1f;%.0f;%.0f;%.1f;%.1f;%.2f;%.0f;%s;%s;%s\n",
                    n++, r.config.describe(), r.ok ? "yes" : "no", r.total, r.smooth, r.latency, r.picture,
                    r.shownFps, r.expectedFps, r.arrivedFps, r.screenHz, r.hitchesPerMin, r.stallMsPerMin, r.longestHitchMs,
                    r.netPausesPerMin, r.longestNetPauseMs, r.lossEventsPerMin, r.framesLostPerMin, r.keyframes,
                    r.decodeMs, r.screenMs, r.maxScreenMs, r.rttMs, r.leadMs, r.mbpsUsed, r.audioFecPct, r.onTimePct,
                    r.codecUsed, r.decoder,
                    !r.ok ? r.failure : (r.staticPicture ? "static picture: compare with care" : "")));
        }
        b.append("\nPER TEST: STREAM REPORT AND TIMELINE (one line per second)\n");
        n = 1;
        for (Result r : results) {
            b.append("--- Test ").append(n++).append(": ").append(r.config.describe()).append('\n');
            b.append("Screen at the end: ").append(r.display).append('\n');
            b.append(r.report != null ? r.report : (r.failure != null ? "failed: " + r.failure : "-")).append("\n");
            if (r.timeline != null) {
                b.append("Timeline:\n").append(r.timeline);
            }
            b.append('\n');
        }
        b.append("\n").append(deviceInfo);
        return b.toString();
    }

    private static void saveProgress(Context context) {
        try {
            TvLog.writePrivate(context, LOG_FILE, fullLog());
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .remove(PREF_LAST_LOG)
                    .putString(PREF_LAST_SUMMARY, summaryText())
                    .apply();
        } catch (Throwable t) {
            LimeLog.warning("Artemide TV auto-tune: could not save progress: " + t);
        }
    }

    public static String lastSummary(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_LAST_SUMMARY, null);
    }

    public static String lastLog(Context context) {
        final String f = TvLog.readPrivate(context, LOG_FILE);
        return f != null ? f : PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_LAST_LOG, null);
    }

    /**
     * Saves the log as a text file in Downloads/ArtemideTV (visible to file managers on the TV).
     * @return where it went, for the screen, or null if it could not be written
     */
    public static String writeLogFile(Context context) {
        final String text = fullLog();
        saveProgress(context);
        final String name = "autotune-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        return TvLog.saveToDownloads(context, name, text);
    }

    static String resolutionName(int w, int h) {
        if (w == 3840 && h == 2160) return "4K";
        if (w == 2560 && h == 1440) return "1440p";
        if (w == 1920 && h == 1080) return "1080p";
        if (w == 1280 && h == 720) return "720p";
        return w + "x" + h;
    }
}
