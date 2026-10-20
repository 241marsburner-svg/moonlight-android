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
 * Artemide TV: auto-tune. Runs short test streams with different settings against whatever is
 * running on the PC, measures each one and scores it, in three stages:
 *
 *  1 Formats          resolution, frame rate, bitrate and codec (HEVC, AV1), all with TV game pacing
 *    Re-check         the best format again, and the runner-up when it was close: the format is
 *                     chosen on the averages of the two runs (same picture: the faster one,
 *                     unless it is clearly less smooth)
 *  2 Latency options  on the chosen format: TV smooth, the stock "lowest latency" pacing,
 *                     MediaTek's extra decoder keys, and synchronous decoding, each against the
 *                     chosen format's two runs
 *  3 Final check      the options that helped, together (only when more than one helped)
 *
 * Every test waits until its picture has been smooth for 3 seconds in a row (streams often
 * stutter for a few seconds after they start: the TV's display mode switch, Apollo and the game
 * adapting to the new resolution, Wi-Fi rates settling), at least 6 s and at most 25 s (the first
 * test 15 to 40 s, it may have to start the app on the PC), and then measures 35 s. How long it
 * had to wait is in the log. Three scores, 0-100, higher is better:
 *
 *  Smoothness  new pictures per second against what the screen can show, minus the time the
 *              picture stood still longer than the content's rhythm (frozen refreshes), frames that
 *              were skipped or moved the picture unevenly, frame losses (each one freezes the
 *              picture until a new keyframe) and long network pauses. Which refresh a frame is
 *              shown on comes from SurfaceFlinger's timing (TvScreenModel): this TV's
 *              frame-rendered callback only echoes the requested time.
 *  Latency     an estimate of the delay from the PC's screen to the TV's screen: PC (capture to
 *              encoded, as the PC reports it) + half the network round trip + what the TV adds
 *              (arrival to the refresh the frame is shown on: decode, pacing buffer, compositor) +
 *              half a frame interval (a new picture waits that long on average for the next capture)
 *  Picture     resolution (the panel is 4K, the TV scales the rest) and bits per pixel, counting
 *              AV1 as 30 % more efficient than HEVC
 *
 * Total = 40 % smoothness + 35 % latency + 25 % picture. A test that is not smooth (below 70)
 * is never picked as best while a smooth one exists, nor is one whose picture never settled after
 * the start (it is measured anyway, 20 s instead of 35). Stage 2 compares every option with the
 * baseline format measured twice (stage 1 and again in stage 2, averaged): an option counts when
 * it is faster by more than the measuring noise without losing smoothness, or clearly smoother
 * without being slower.
 *
 * State lives in this class (one app process); the controller is TvAutoTuneActivity, the test
 * streams are ordinary Game activities that read the overrides in {@link #applyStepOverrides}.
 */
public final class TvAutoTune {
    private TvAutoTune() {}

    // Before measuring: at least MIN, then until the picture was smooth for 3 s, at most MAX
    public static final long MIN_WARMUP_MS = 6_000;
    public static final long MAX_WARMUP_MS = 25_000;
    // The first test may have to launch the app on the PC (or create Apollo's display first)
    public static final long FIRST_MIN_WARMUP_MS = 15_000;
    public static final long FIRST_MAX_WARMUP_MS = 40_000;
    public static final long MEASURE_MS = 35_000;
    // A setting whose picture never settles is clearly not the one: measure it, but shorter
    public static final long MEASURE_UNSETTLED_MS = 20_000;
    public static final long CONNECT_TIMEOUT_MS = 40_000;
    private static final String PREF_LAST_LOG = "artemide_tv_autotune_last_log"; // old builds kept the log here
    private static final String PREF_LAST_SUMMARY = "artemide_tv_autotune_last_summary";
    private static final String LOG_FILE = "last-autotune.txt";

    public static final int CODEC_HEVC = 0;
    public static final int CODEC_AV1 = 1;
    public static final int CODEC_H264 = 2;

    // Score weights (also printed in the log)
    // Without the screen model (no refresh grid): hitches seen by the frame-rendered callback
    private static final double HITCH_POINTS_PER_STALL_MS = 0.24; // a one-refresh hitch at 60 Hz (16.7 ms) = 4 points
    // With it: a frozen refresh at 60 Hz (16.7 ms) costs 0.8 points per minute, a skipped frame or
    // a half-refresh uneven step about half that. One repeat and skip per second: -75.
    private static final double FROZEN_POINTS_PER_MS = 0.05;
    private static final double JUMP_POINTS_PER_MS = 0.025;
    private static final double LOSS_POINTS = 6;
    private static final double PAUSE_POINTS = 1;
    // A frame rate that doesn't fit the screen's refresh rate (90 fps on 60 or 120 Hz) is shown
    // with frames of unequal length: motion judders even when no frame is late
    private static final double UNEVEN_MOTION_POINTS = 15;
    private static final double LATENCY_POINTS_PER_MS = 1.5;
    private static final double LATENCY_FREE_MS = 25;     // delay below this costs no points
    // Stage 2, against the baseline measured twice (averaged): an option counts when it is faster
    // by at least MIN_FASTER_MS (or the latency noise) and loses at most MAX_SMOOTH_LOSS (or the
    // smoothness noise), or when it is smoother by MIN_SMOOTHER (or the noise) and at most
    // MAX_SLOWER_MS slower
    private static final double MIN_FASTER_MS = 3.0;
    private static final double MAX_SMOOTH_LOSS = 5.0;
    private static final double MIN_SMOOTHER = 8.0;
    private static final double MAX_SLOWER_MS = 2.0;

    public static final int STAGE_FORMAT = 1;
    public static final int STAGE_OPTIONS = 2;
    public static final int STAGE_FINAL = 3;
    // Runs between formats and options: the best format again, and the runner-up when it was close
    public static final int STAGE_RECHECK = 4;
    // A runner-up this close (total points) to the best format is measured again before choosing
    private static final double CLOSE_POINTS = 6.0;

    public static String codecName(int codec) {
        switch (codec) {
            case CODEC_AV1: return "AV1";
            case CODEC_H264: return "H.264";
            default: return "HEVC";
        }
    }

    public static final class Config {
        public final String label;          // what this test is for
        public final int stage;
        public final int width, height, fps, bitrateKbps, codec;
        public final int pacing;            // PreferenceConfiguration.FRAME_PACING_*
        public final boolean aggressive;    // TV pacing: aggressive timing
        public final boolean extraKeys;     // MediaTek vendor decoder keys + operating rate
        public final boolean ultraLowLatency;
        public final boolean syncDecode;    // MediaCodec without async callbacks

        Config(String label, int stage, int width, int height, int fps, int bitrateKbps, int codec,
               int pacing, boolean aggressive, boolean extraKeys, boolean ultraLowLatency, boolean syncDecode) {
            this.label = label;
            this.stage = stage;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.bitrateKbps = bitrateKbps;
            this.codec = codec;
            this.pacing = pacing;
            this.aggressive = aggressive;
            this.extraKeys = extraKeys;
            this.ultraLowLatency = ultraLowLatency;
            this.syncDecode = syncDecode;
        }

        /** A stage 1 test: one format with the standard options (TV game pacing). */
        static Config format(String label, int width, int height, int fps, int bitrateKbps, int codec) {
            return new Config(label, STAGE_FORMAT, width, height, fps, bitrateKbps, codec,
                    PreferenceConfiguration.FRAME_PACING_TV_GAME, false, false, false, false);
        }

        /** The same format with other options. */
        Config with(String label, int stage, int pacing, boolean aggressive, boolean extraKeys, boolean syncDecode) {
            return new Config(label, stage, width, height, fps, bitrateKbps, codec,
                    pacing, aggressive, extraKeys, extraKeys, syncDecode);
        }

        public String describeFormat() {
            return String.format(Locale.US, "%s %d fps %d Mbps %s", resolutionName(width, height), fps,
                    bitrateKbps / 1000, codecName(codec));
        }

        public String describeOptions() {
            final StringBuilder b = new StringBuilder(PreferenceConfiguration.describeFramePacing(pacing));
            if (aggressive) {
                b.append(" + aggressive timing");
            }
            if (extraKeys) {
                b.append(" + MediaTek extra keys");
            }
            if (ultraLowLatency && !extraKeys) {
                b.append(" + ultra low latency");
            }
            if (syncDecode) {
                b.append(" + sync decoding");
            }
            return b.toString();
        }

        public String describe() {
            return describeFormat() + " · " + describeOptions();
        }

        boolean sameFormat(Config o) {
            return o != null && width == o.width && height == o.height && fps == o.fps &&
                    bitrateKbps == o.bitrateKbps && codec == o.codec;
        }

        boolean sameOptions(Config o) {
            return o != null && pacing == o.pacing && aggressive == o.aggressive && extraKeys == o.extraKeys &&
                    ultraLowLatency == o.ultraLowLatency && syncDecode == o.syncDecode;
        }

        boolean standardOptions() {
            return pacing == PreferenceConfiguration.FRAME_PACING_TV_GAME && !aggressive && !extraKeys &&
                    !ultraLowLatency && !syncDecode;
        }
    }

    public static final class Result {
        public final Config config;
        public boolean ok;
        public String failure;
        // Measured over the window
        public double secs, shownFps, releasedFps, arrivedFps, expectedFps, hitchesPerMin, stallMsPerMin, netPausesPerMin;
        public double lossEventsPerMin, framesLostPerMin, decodeMs, screenMs, maxScreenMs, rttMs, hostMs, maxHostMs;
        public double leadMs, mbpsUsed, audioFecPct, onTimePct, screenHz, framesInDecoder;
        public long longestHitchMs, longestNetPauseMs, keyframes, maxFramesInDecoder;
        // The screen by SurfaceFlinger's timing (TvScreenModel); hasModel false = no refresh grid
        public boolean hasModel;
        public double modelFps, frozenPerMin, frozenMsPerMin, frozenPcPerMin, skippedPerMin, skippedMsPerMin;
        public double unevenPerMin, unevenMsPerMin, replacedPerSec, modelTvMs = -1, maxModelTvMs;
        public long longestFreezeMs;
        // The PC's rhythm (capture times); hasHost false = the PC sends none
        public boolean hasHost;
        public double pcFps, pcGapsPerMin, deliveryStallsPerMin, late8Pct;
        // A frame from its first to its last packet (the PC's sending spreads it out), ms
        public double spreadMs = -1, maxSpreadMs;
        public long longestPcGapMs, longestDeliveryStallMs;
        // Asked for more frames than the screen shows (120 fps), but the PC sent far fewer: the
        // game or the stream test page drew at a lower rate on the PC, so this tested nothing new
        public boolean pcLimited;
        public double realFps;              // the frame rate the PC really sent
        public int runs = 1;                // > 1: the average of that many runs of the same settings
        public boolean staticPicture;
        public double settleSecs = -1;      // wait before measuring
        public boolean settled;             // false: it never was smooth for 3 s, measured anyway
        public String codecUsed = "?", decoder = "?", display = "?";
        // Scores 0-100, how each came about, and the estimated delay
        public double smooth, latency, picture, total, latencyMs;
        public String smoothWhy = "", latencyWhy = "", pictureWhy = "", decoderWhy = "";
        public String report;
        public String timeline;
        public String settings; // what the stream really ran with (TvStreamSettings)
        public String verified = ""; // "" = ran as planned, else what was different

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
    private static boolean recheckPlanned;    // the re-check runs are in the plan
    private static boolean optionsPlanned;    // stage 2 is in the plan (or could not be made)
    private static boolean finalPlanned;      // stage 3 likewise
    private static TvDiagnostics.Sample windowStart;
    private static int[] audioStart;
    private static double stepSettleSecs = -1;
    private static boolean stepSettled;
    private static String startedAt = "";
    private static String abortReason;
    private static volatile String deviceInfo = "";
    private static boolean av1Tested;
    private static float screenHzSeen = 60f;
    // Outcome
    private static Result optionsBase;        // stage 2 baseline (same format, run again)
    private static Result formatWinner;       // best stage 1 result
    private static Result recommended;
    private static String recommendation = "";
    private static double noise = -1;         // |stage 1 winner - stage 2 baseline| total points

    // ---------------- Plan ----------------

    private static void buildPlan(PreferenceConfiguration current, float screenHz) {
        plan.clear();
        if (current != null && current.width > 0 && current.height > 0) {
            final int codec = current.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1 ? CODEC_AV1
                    : current.videoFormat == PreferenceConfiguration.FormatOption.FORCE_H264 ? CODEC_H264 : CODEC_HEVC;
            final Config mine = new Config("your current settings", STAGE_FORMAT, current.width, current.height,
                    Math.round(current.fps), current.bitrate, codec, current.framePacing, current.tvAggressiveTiming,
                    current.mtkExtraDecoderKeys, current.enableUltraLowLatency, !current.asyncDecodeEnabled);
            plan.add(mine);
        }
        addIfNew(Config.format("1440p baseline", 2560, 1440, 60, 60_000, CODEC_HEVC));
        addIfNew(Config.format("1440p more bitrate", 2560, 1440, 60, 80_000, CODEC_HEVC));
        addIfNew(Config.format("1440p high bitrate", 2560, 1440, 60, 100_000, CODEC_HEVC));
        // AV1 against HEVC at the same settings (only if the TV has an AV1 hardware decoder)
        av1Tested = TvDeviceInfo.hasHardwareDecoder("video/av01");
        if (av1Tested) {
            addIfNew(Config.format("1440p AV1", 2560, 1440, 60, 80_000, CODEC_AV1));
        }
        // More frames than 60: the PC captures more often and the TV shows the newest frame each
        // refresh, which lowers the delay. Only frame rates the screen can show evenly: 120 fps on
        // a 60 Hz screen is two frames per refresh; 90 fps on 60 Hz would be 1.5 (judder).
        final double hz = screenHz > 20f ? screenHz : 60.0;
        final boolean even120 = evenCadence(120, hz);
        if (even120) {
            addIfNew(Config.format("1440p 120 fps", 2560, 1440, 120, 80_000, CODEC_HEVC));
            if (av1Tested) {
                addIfNew(Config.format("1440p 120 fps AV1", 2560, 1440, 120, 80_000, CODEC_AV1));
            }
        } else if (evenCadence(90, hz)) {
            addIfNew(Config.format("1440p 90 fps", 2560, 1440, 90, 80_000, CODEC_HEVC));
        }
        addIfNew(Config.format("1080p", 1920, 1080, 60, 50_000, CODEC_HEVC));
        if (even120) {
            addIfNew(Config.format("1080p 120 fps", 1920, 1080, 120, 80_000, CODEC_HEVC));
        }
        addIfNew(Config.format("4K", 3840, 2160, 60, 80_000, CODEC_HEVC));
        if (av1Tested) {
            addIfNew(Config.format("4K AV1", 3840, 2160, 60, 80_000, CODEC_AV1));
        }
    }

    private static void addIfNew(Config c) {
        for (Config p : plan) {
            if (p.stage == c.stage && p.sameFormat(c) && p.sameOptions(c)) {
                return;
            }
        }
        plan.add(c);
    }

    /**
     * Re-check: the best format again (its two runs are the baseline for the options), and the
     * runner-up again when it was within CLOSE_POINTS: one run can be unlucky (the PC busy for a
     * moment, Wi-Fi), and the format is the biggest choice. Both run with the standard options.
     */
    private static void planRecheck() {
        recheckPlanned = true;
        final Result w = bestOfStage(STAGE_FORMAT);
        if (w == null) {
            return;
        }
        final int game = PreferenceConfiguration.FRAME_PACING_TV_GAME;
        plan.add(w.config.with(w.config.standardOptions() ? "the best format again (shows how repeatable the results are)"
                : "the best format again, with the standard options (the baseline for the options)", STAGE_RECHECK, game, false, false, false));
        Result runnerUp = null;
        for (Result r : results) {
            if (r.ok && r.config.stage == STAGE_FORMAT && r != w && !r.config.sameFormat(w.config) &&
                    smoothOk(r) == smoothOk(w) && r.pcLimited == w.pcLimited &&
                    (runnerUp == null || betterRank(r, runnerUp))) {
                runnerUp = r;
            }
        }
        if (runnerUp != null && runnerUp.total >= w.total - CLOSE_POINTS) {
            plan.add(runnerUp.config.with("the runner-up again: it was close to the best", STAGE_RECHECK, game, false, false, false));
        }
    }

    // How the format was chosen (for the results)
    private static String formatWhy = "";

    /** The stage 1 run of a format with the standard options, or null. */
    private static Result formatRun(Config c) {
        for (Result r : results) {
            if (r.ok && r.config.stage == STAGE_FORMAT && r.config.sameFormat(c) && r.config.standardOptions()) {
                return r;
            }
        }
        return null;
    }

    /** The re-check run of a format, or null. */
    private static Result recheckRun(Config c) {
        for (Result r : results) {
            if (r.ok && r.config.stage == STAGE_RECHECK && r.config.sameFormat(c)) {
                return r;
            }
        }
        return null;
    }

    /** A format's result: the average of its two runs when it was re-checked. */
    private static Result formatResult(Result stage1) {
        final Result again = recheckRun(stage1.config);
        if (again == null) {
            return stage1;
        }
        final Result first = formatRun(stage1.config);
        return first != null ? average(first, again) : again;
    }

    /**
     * Is format a better than b? A smooth one beats a stuttering one; between two of the same
     * picture class (e.g. 1440p HEVC against 1440p AV1) the faster one wins unless it is clearly
     * less smooth; otherwise the higher total.
     */
    private static boolean preferFormat(Result a, Result b, double latNoise, double smoothNoise) {
        final boolean sa = smoothOk(a), sb = smoothOk(b);
        if (sa != sb) {
            return sa;
        }
        if (a.pcLimited != b.pcLimited) {
            return !a.pcLimited;
        }
        if (Math.abs(a.picture - b.picture) < 3) {
            final double faster = Math.max(MIN_FASTER_MS, latNoise);
            final double loss = Math.max(MAX_SMOOTH_LOSS, smoothNoise);
            if (a.latencyMs <= b.latencyMs - faster && a.smooth >= b.smooth - loss) {
                return true;
            }
            if (b.latencyMs <= a.latencyMs - faster && b.smooth >= a.smooth - loss) {
                return false;
            }
        }
        return a.total > b.total;
    }

    /** The format to use: stage 1's best, or the runner-up when the re-check says so. */
    private static Result chooseFormat() {
        final Result w = bestOfStage(STAGE_FORMAT);
        formatWhy = "";
        if (w == null) {
            return null;
        }
        // Noise: how much the same format differed between its two runs
        double latNoise = 0, smoothNoise = 0;
        final List<Result> candidates = new ArrayList<>();
        candidates.add(w);
        for (Result r : results) {
            if (r.ok && r.config.stage == STAGE_RECHECK) {
                final Result first = formatRun(r.config);
                if (first != null) {
                    latNoise = Math.max(latNoise, Math.abs(first.latencyMs - r.latencyMs));
                    smoothNoise = Math.max(smoothNoise, Math.abs(first.smooth - r.smooth));
                }
                if (!r.config.sameFormat(w.config) && first != null) {
                    candidates.add(first);
                }
            }
        }
        Result best = formatResult(w);
        for (int i = 1; i < candidates.size(); i++) {
            final Result other = formatResult(candidates.get(i));
            final boolean switched = preferFormat(other, best, latNoise, smoothNoise);
            formatWhy = String.format(Locale.US,
                    "Re-check: %s %s (average of 2 runs: smoothness %.0f, delay %.1f ms, total %.0f) against %s (smoothness %.0f, delay %.1f ms, total %.0f); " +
                            "runs of the same format differed by up to %.1f ms and %.0f smoothness.",
                    switched ? other.config.describeFormat() : best.config.describeFormat(),
                    switched ? "beats" : "stays ahead of",
                    switched ? other.smooth : best.smooth, switched ? other.latencyMs : best.latencyMs, switched ? other.total : best.total,
                    switched ? best.config.describeFormat() : other.config.describeFormat(),
                    switched ? best.smooth : other.smooth, switched ? best.latencyMs : other.latencyMs, switched ? best.total : other.total,
                    latNoise, smoothNoise);
            if (switched) {
                best = other;
            }
        }
        return best;
    }

    /**
     * Stage 2: the latency options, on the chosen format. (Aggressive timing is not tested: by the
     * deadline this TV reports for SurfaceFlinger it can only make frames miss their refresh, and
     * whether the real deadline is shorter can't be measured without a camera.)
     */
    private static void planOptions() {
        optionsPlanned = true;
        formatWinner = chooseFormat();
        if (formatWinner == null) {
            return;
        }
        final Config f = formatWinner.config;
        final int game = PreferenceConfiguration.FRAME_PACING_TV_GAME;
        if (recheckRun(f) == null) {
            // (Only when the re-check could not run this format: the baseline for the options)
            plan.add(f.with("TV game with standard options: the baseline for the options", STAGE_OPTIONS, game, false, false, false));
        }
        final double hz = formatWinner.screenHz > 20 ? formatWinner.screenHz : screenHzSeen;
        final boolean framePerRefresh = f.fps <= hz * 1.1;
        plan.add(f.with(framePerRefresh ? "TV smooth: a bigger buffer against Wi-Fi bunching"
                        : "TV smooth: frames grouped by capture time, so every refresh moves the picture on evenly",
                STAGE_OPTIONS, PreferenceConfiguration.FRAME_PACING_TV_SMOOTH, false, false, false));
        // (With more frames than refreshes TV game already shows every frame at once)
        if (framePerRefresh) {
            plan.add(f.with("lowest latency: every frame shown at once, no pacing", STAGE_OPTIONS,
                    PreferenceConfiguration.FRAME_PACING_MIN_LATENCY, false, false, false));
        }
        if (formatWinner.decoder != null && formatWinner.decoder.toLowerCase(Locale.US).contains("mtk")) {
            plan.add(f.with("MediaTek extra decoder keys (vendor speed keys, operating rate, ultra low latency)",
                    STAGE_OPTIONS, game, false, true, false));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            plan.add(f.with("sync decoding: the decoder without async callbacks", STAGE_OPTIONS, game, false, false, true));
        }
        LimeLog.info("Artemide TV auto-tune: latency options on " + f.describeFormat());
    }

    /** Stage 3: the options that helped, together, when more than one helped. */
    private static void planFinal() {
        finalPlanned = true;
        decide();
        if (recommended == null || optionsBase == null) {
            return;
        }
        final Config pick = recommendedConfigBeforeFinal;
        if (pick != null && !isTested(pick)) {
            plan.add(pick.with("final check: the options that helped, together", STAGE_FINAL, pick.pacing,
                    pick.aggressive, pick.extraKeys, pick.syncDecode));
        }
    }

    private static Config recommendedConfigBeforeFinal;

    private static boolean isTested(Config c) {
        for (Result r : results) {
            if (r.ok && r.config.sameFormat(c) && r.config.sameOptions(c)) {
                return true;
            }
        }
        return false;
    }

    private static void planMoreIfNeeded() {
        if (index + 1 < plan.size() || !running) {
            return;
        }
        if (!recheckPlanned && results.size() >= plan.size()) {
            planRecheck();
        }
        if (index + 1 < plan.size()) {
            return;
        }
        if (recheckPlanned && !optionsPlanned && results.size() >= plan.size()) {
            planOptions();
        }
        if (index + 1 < plan.size()) {
            return;
        }
        if (optionsPlanned && !finalPlanned && results.size() >= plan.size()) {
            planFinal();
        }
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
        recheckPlanned = false;
        optionsPlanned = false;
        finalPlanned = false;
        formatWhy = "";
        optionsBase = null;
        formatWinner = null;
        recommended = null;
        recommendedConfigBeforeFinal = null;
        recommendation = "";
        noise = -1;
        abortReason = null;
        screenHzSeen = 60f;
        startedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
        buildPlan(PreferenceConfiguration.readPreferences(context), screenRefreshHz(context));
        // The decoder list and screen modes take a moment to read: not on the UI thread
        deviceInfo = "";
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            String info;
            try {
                info = TvDeviceInfo.describeAll(app);
            } catch (Throwable t) {
                info = "(device info failed: " + t + ")\n";
            }
            deviceInfo = info;
        }, "TvAutoTuneInfo").start();
        LimeLog.info("Artemide TV auto-tune: " + plan.size() + " format tests" + (av1Tested ? " (with AV1)" : " (no AV1 decoder)"));
    }

    @SuppressWarnings("deprecation")
    private static float screenRefreshHz(Context context) {
        try {
            final android.view.WindowManager wm = (android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            final float hz = wm != null ? wm.getDefaultDisplay().getRefreshRate() : 0f;
            if (hz > 20f) {
                screenHzSeen = hz;
            }
            return hz;
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** Shortest wait before measuring, for the test that is running now. */
    public static synchronized long currentMinWarmupMs() {
        return index <= 0 ? FIRST_MIN_WARMUP_MS : MIN_WARMUP_MS;
    }

    /** How long to measure, depending on whether the picture settled first. */
    public static long measureMs(boolean settled) {
        return settled ? MEASURE_MS : MEASURE_UNSETTLED_MS;
    }

    /** Longest wait before measuring (measured even if the picture never settled). */
    public static synchronized long currentMaxWarmupMs() {
        return index <= 0 ? FIRST_MAX_WARMUP_MS : MAX_WARMUP_MS;
    }

    /** The number of tests, as far as it is known (stage 2 and 3 are planned on the way). */
    public static synchronized int planSize() {
        int n = plan.size();
        if (!recheckPlanned) {
            n += 2;
        }
        if (!optionsPlanned) {
            n += 4;
        }
        if (!finalPlanned) {
            n += 1;
        }
        return n;
    }

    public static synchronized boolean planComplete() {
        return recheckPlanned && optionsPlanned && finalPlanned;
    }

    public static synchronized int completedCount() {
        return results.size();
    }

    /** @return the intent for the next test stream, or null when all tests are done */
    public static synchronized Intent nextStep() {
        if (!running) {
            return null;
        }
        planMoreIfNeeded();
        if (index + 1 >= plan.size()) {
            if (!finalPlanned) {
                // Nothing more could be planned (e.g. every format failed)
                recheckPlanned = true;
                optionsPlanned = true;
                finalPlanned = true;
                decide();
            }
            return null;
        }
        index++;
        stepActive = true;
        stepDone = false;
        measuring = false;
        stepSettleSecs = -1;
        stepSettled = false;
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
            decide();
        }
        stepActive = false;
    }

    public static synchronized void abort(String reason) {
        if (running) {
            abortReason = reason;
        }
        running = false;
        stepActive = false;
        decide();
    }

    public static synchronized boolean finishedAll() {
        if (results.size() < plan.size() || plan.isEmpty()) {
            return false;
        }
        planMoreIfNeeded();
        return results.size() >= plan.size() && recheckPlanned && optionsPlanned && finalPlanned;
    }

    public static synchronized String currentLabel() {
        return (index >= 0 && index < plan.size()) ? plan.get(index).describe() : "";
    }

    public static synchronized String nextLabel() {
        planMoreIfNeeded();
        if (index + 1 < plan.size()) {
            final Config c = plan.get(index + 1);
            return c.describe() + (c.stage == STAGE_FORMAT ? "" : " (" + c.label + ")");
        }
        return "";
    }

    /** "formats", "latency options" or "final check", for the screen */
    public static synchronized String nextStageName() {
        if (index + 1 < plan.size()) {
            final int st = plan.get(index + 1).stage;
            return st == STAGE_FORMAT ? "formats" : st == STAGE_RECHECK ? "re-check of the best formats"
                    : st == STAGE_OPTIONS ? "latency options" : "final check";
        }
        return "";
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
        prefs.videoFormat = c.codec == CODEC_AV1 ? PreferenceConfiguration.FormatOption.FORCE_AV1
                : c.codec == CODEC_H264 ? PreferenceConfiguration.FormatOption.FORCE_H264
                : PreferenceConfiguration.FormatOption.FORCE_HEVC;
        prefs.framePacing = c.pacing;
        prefs.tvAggressiveTiming = c.aggressive;
        prefs.mtkExtraDecoderKeys = c.extraKeys;
        prefs.enableUltraLowLatency = c.ultraLowLatency;
        prefs.asyncDecodeEnabled = !c.syncDecode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
        // The figures come from the stream statistics, so they must be collected
        prefs.tvStreamReport = true;
        prefs.enablePerfOverlay = false;
        LimeLog.info("Artemide TV auto-tune: test " + (index + 1) + ": " + c.describe() + " (" + c.label + ")");
    }

    /**
     * Game: the wait is over (the picture was smooth for 3 s, or the longest wait passed).
     * @param waitedMs since the stream started
     * @param settled  false if the picture never settled
     */
    public static synchronized void onWindowStart(TvDiagnostics.Sample sample, int[] audioRtp, long waitedMs, boolean settled) {
        if (!isStepActiveLocked()) {
            return;
        }
        windowStart = sample;
        audioStart = audioRtp;
        measuring = true;
        stepSettleSecs = waitedMs / 1000.0;
        stepSettled = settled;
        TvTimeline.mark(settled ? String.format(Locale.US, "measuring starts (smooth for 3 s after %.0f s)", stepSettleSecs)
                : String.format(Locale.US, "measuring starts (not smooth within %.0f s: measured anyway)", stepSettleSecs));
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
        r.settings = TvStreamSettings.text();
        r.settleSecs = stepSettleSecs;
        r.settled = stepSettled;
        if (!measuring || windowStart == null) {
            r.ok = false;
            r.failure = "no measurement";
        } else {
            measure(r, windowStart, end, audioStart, audioRtp, screenHz);
            r.ok = true;
            if (screenHz > 20f) {
                screenHzSeen = screenHz;
            }
        }
        if (r.ok && !codecName(r.config.codec).equals(r.codecUsed) && !"?".equals(r.codecUsed)) {
            r.ok = false;
            r.failure = "asked for " + codecName(r.config.codec) + " but the stream ran " + r.codecUsed;
        }
        r.report = report;
        r.verified = verify(r);
        score(r);
        results.add(r);
        stepDone = true;
        measuring = false;
        saveProgress(context);
        LimeLog.info(String.format(Locale.US, "Artemide TV auto-tune: test %d done, smooth %.0f latency %.0f (%.0f ms) picture %.0f total %.0f",
                index + 1, r.smooth, r.latency, r.latencyMs, r.picture, r.total));
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
        r.settings = TvStreamSettings.isEmpty() ? null : TvStreamSettings.text();
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

    /**
     * Did the test stream really run with the settings it was meant to test? Checks the
     * "settings in use" the stream recorded. "" when everything matches.
     */
    private static String verify(Result r) {
        final String s = r.settings;
        if (s == null || s.isEmpty()) {
            return "";
        }
        final Config c = r.config;
        final List<String> off = new ArrayList<>();
        final String pacing = lineValue(s, "Frame pacing");
        if (pacing != null) {
            if (!pacing.startsWith(PreferenceConfiguration.describeFramePacing(c.pacing))) {
                off.add("pacing was " + pacing);
            } else if (pacing.contains("aggressive timing") != c.aggressive) {
                off.add(c.aggressive ? "aggressive timing was off" : "aggressive timing was on");
            }
        }
        final String mode = lineValue(s, "Decoder mode");
        if (mode != null && mode.startsWith("sync") != c.syncDecode) {
            off.add(c.syncDecode ? "the decoder still ran with async callbacks" : "the decoder ran without async callbacks");
        }
        final String decoder = lineValue(s, "Decoder");
        if (c.extraKeys && decoder != null && decoder.toLowerCase(Locale.US).contains("mtk") && !decoder.contains("MTK extras")) {
            off.add("the decoder refused the extra keys (it ran with the standard set)");
        }
        final String asked = lineValue(s, "Asked of the PC");
        if (asked != null) {
            if (!asked.startsWith(c.width + "x" + c.height)) {
                off.add("the PC was asked for " + asked.substring(0, Math.min(asked.length(), 9)).trim());
            }
            if (!asked.contains("bitrate " + c.bitrateKbps / 1000 + " Mbps")) {
                off.add("another bitrate went to the PC (" + asked.replaceAll(".*bitrate ([0-9]+ Mbps).*", "$1") + ")");
            }
        }
        return String.join("; ", off);
    }

    /** The value of a "Name: value" line in a settings text, or null. */
    private static String lineValue(String text, String name) {
        for (String line : text.split("\n")) {
            if (line.startsWith(name + ": ")) {
                return line.substring(name.length() + 2);
            }
        }
        return null;
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
        final long hostN = b.hostSamples - a.hostSamples;
        r.hostMs = hostN > 0 ? (b.hostSumTenths - a.hostSumTenths) / 10.0 / hostN : -1;
        r.maxHostMs = b.maxHostTenths / 10.0;
        final long inN = b.inFlightSamples - a.inFlightSamples;
        r.framesInDecoder = inN > 0 ? (b.inFlightSum - a.inFlightSum) / (double) inN : -1;
        r.maxFramesInDecoder = b.maxInFlight;
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
        // The screen by SurfaceFlinger's timing
        final long pictures = b.modelPictures - a.modelPictures;
        r.hasModel = pictures > secs * 5;
        if (r.hasModel) {
            r.modelFps = pictures / secs;
            r.frozenPerMin = (b.modelFrozen - a.modelFrozen) / mins;
            r.frozenMsPerMin = (b.modelFrozenNs - a.modelFrozenNs) / 1e6 / mins;
            r.frozenPcPerMin = (b.modelFrozenPc - a.modelFrozenPc) / mins;
            r.skippedPerMin = (b.modelSkipped - a.modelSkipped) / mins;
            r.skippedMsPerMin = (b.modelSkippedNs - a.modelSkippedNs) / 1e6 / mins;
            r.unevenPerMin = (b.modelUneven - a.modelUneven) / mins;
            r.unevenMsPerMin = (b.modelUnevenNs - a.modelUnevenNs) / 1e6 / mins;
            r.replacedPerSec = (b.modelReplaced - a.modelReplaced) / secs;
            r.longestFreezeMs = b.modelLongestFreezeNs / 1_000_000L;
            final long mN = b.modelScreenN - a.modelScreenN;
            r.modelTvMs = mN > 0 ? (b.modelScreenSumUs - a.modelScreenSumUs) / (double) mN / 1000.0 : -1;
            r.maxModelTvMs = b.maxModelScreenUs / 1000.0;
        }
        final long spN = b.spreadN - a.spreadN;
        if (spN > 0) {
            r.spreadMs = (b.spreadSumUs - a.spreadSumUs) / 1000.0 / spN;
            r.maxSpreadMs = b.maxSpreadUs / 1000.0;
        }
        // The PC's rhythm
        final long hostFrames = b.hostFrames - a.hostFrames;
        r.hasHost = hostFrames > secs * 5 && TvDiagnostics.hostTimesAvailable();
        if (r.hasHost) {
            final long cadN = b.hostCadenceN - a.hostCadenceN;
            final long cadUs = b.hostCadenceSumUs - a.hostCadenceSumUs;
            r.pcFps = cadN > 0 && cadUs > 0 ? 1e6 * cadN / cadUs : 0;
            r.pcGapsPerMin = (b.pcGaps - a.pcGaps) / mins;
            r.longestPcGapMs = b.maxPcGapMs;
            r.deliveryStallsPerMin = (b.deliveryStalls - a.deliveryStalls) / mins;
            r.longestDeliveryStallMs = b.maxDeliveryStallMs;
            r.late8Pct = 100.0 * (b.late8 - a.late8) / hostFrames;
        }
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }

    /** True when every frame can stay on screen equally long (fps and refresh in a whole ratio). */
    static boolean evenCadence(int fps, double hz) {
        if (fps <= 0 || hz <= 20) {
            return true;
        }
        final double up = hz / fps, down = fps / hz;
        final double r = up >= 1 ? up : down;
        return Math.abs(r - Math.round(r)) < 0.03 * r;
    }

    /** "+3", "-2", or "±0" for a difference that rounds to nothing. */
    private static String signed(double v, String unit) {
        final long n = Math.round(v);
        return (n == 0 ? "±0" : String.format(Locale.US, "%+d", n)) + unit;
    }

    /** A total's difference with one decimal ("+1.8"), for the reasons. */
    private static String signed1(double v) {
        return Math.abs(v) < 0.05 ? "±0.0" : String.format(Locale.US, "%+.1f", v);
    }

    private static void score(Result r) {
        if (!r.ok) {
            r.smooth = r.latency = r.picture = r.total = 0;
            r.latencyMs = -1;
            r.smoothWhy = r.latencyWhy = r.pictureWhy = r.decoderWhy = "not measured: " + r.failure;
            return;
        }
        final double lossP = LOSS_POINTS * r.lossEventsPerMin;
        final double pauseP = PAUSE_POINTS * r.netPausesPerMin;
        final boolean even = evenCadence(r.config.fps, r.screenHz);
        final double unevenP = even ? 0 : UNEVEN_MOTION_POINTS;
        final String cadenceWhy = even ? "" : String.format(Locale.US,
                " - uneven motion %.0f (%d fps on a %.0f Hz screen: frames stay up for unequal times)",
                unevenP, r.config.fps, r.screenHz);
        final String pcWhy = !r.hasHost ? ""
                : String.format(Locale.US, " · PC: %.1f fps captured, %.1f gaps/min where it captured nothing (longest %d ms), " +
                                "%.1f late deliveries/min (longest %d ms), %.1f%% of frames 8 ms+ behind its rhythm",
                        r.pcFps, r.pcGapsPerMin, r.longestPcGapMs, r.deliveryStallsPerMin, r.longestDeliveryStallMs, r.late8Pct);
        if (r.hasModel) {
            // Smoothness by SurfaceFlinger's timing: new pictures per refresh it can show, minus
            // the time the picture stood still longer than the content's rhythm, and frames that
            // were skipped or moved the picture unevenly
            final double ratio = r.expectedFps > 0 ? Math.min(1.0, r.modelFps / r.expectedFps) : 0;
            final double frames = 100 * ratio;
            final double frozenP = FROZEN_POINTS_PER_MS * r.frozenMsPerMin;
            // Half-refresh uneven steps (more frames than refreshes) cost at most what a frame
            // rate that doesn't fit the screen costs: the motion judders a little, nothing stops
            final double jumpP = JUMP_POINTS_PER_MS * r.skippedMsPerMin
                    + Math.min(UNEVEN_MOTION_POINTS, JUMP_POINTS_PER_MS * r.unevenMsPerMin);
            r.smooth = clamp(frames - frozenP - jumpP - lossP - pauseP - unevenP);
            r.smoothWhy = String.format(Locale.US,
                    "%.0f = new pictures %.0f (%.1f of %.0f per s; PC sent %.1f fps) - frozen %.0f (%.1f refreshes/min, %.0f ms/min, longest still %d ms; " +
                            "the PC had nothing newer for %.1f/min) - skipped/uneven %.0f (%.1f skipped frames/min, %.1f uneven steps/min)" +
                            " - losses %.0f (%.1f/min, %.1f frames/min) - network pauses %.0f (%.1f/min, longest %d ms)%s%s",
                    r.smooth, frames, r.modelFps, r.expectedFps, r.arrivedFps, frozenP, r.frozenPerMin, r.frozenMsPerMin,
                    r.longestFreezeMs, r.frozenPcPerMin, jumpP, r.skippedPerMin, r.unevenPerMin,
                    lossP, r.lossEventsPerMin, r.framesLostPerMin, pauseP, r.netPausesPerMin, r.longestNetPauseMs,
                    cadenceWhy, pcWhy);
        } else {
            // No refresh grid: what the frame-rendered callback saw
            final double shown = r.shownFps > 0 ? r.shownFps : r.releasedFps;
            final double ratio = r.expectedFps > 0 ? Math.min(1.0, shown / r.expectedFps) : 0;
            final double frames = 100 * ratio;
            final double hitchP = HITCH_POINTS_PER_STALL_MS * r.stallMsPerMin;
            r.smooth = clamp(frames - hitchP - lossP - pauseP - unevenP);
            r.smoothWhy = String.format(Locale.US,
                    "%.0f = frames %.0f (%.1f of %.0f fps shown; PC sent %.1f) - hitches %.0f (%.1f/min, picture stood still %.0f ms/min, longest frame %d ms)" +
                            " - losses %.0f (%.1f/min, %.1f frames/min) - network pauses %.0f (%.1f/min, longest %d ms)%s%s",
                    r.smooth, frames, shown, r.expectedFps, r.arrivedFps, hitchP, r.hitchesPerMin, r.stallMsPerMin, r.longestHitchMs,
                    lossP, r.lossEventsPerMin, r.framesLostPerMin, pauseP, r.netPausesPerMin, r.longestNetPauseMs,
                    cadenceWhy, pcWhy);
        }

        // Latency: PC + network + TV + the wait for the next capture
        double tvMs = r.modelTvMs;
        String tvHow = "arrival to the refresh it is shown on, SurfaceFlinger's timing";
        double tvMax = r.maxModelTvMs;
        if (tvMs <= 0) {
            tvMs = r.screenMs;
            tvHow = "frame-rendered callback";
            tvMax = r.maxScreenMs;
        }
        if (tvMs <= 0) {
            // No frame-rendered times on this TV: estimate from decode + buffer + one refresh
            tvMs = Math.max(0, r.decodeMs) + r.leadMs + 1000.0 / Math.max(1.0, r.expectedFps);
            tvHow = "estimated: decode + buffer + 1 refresh";
        }
        final double host = Math.max(0, r.hostMs);
        // Network: half the round trip for the first packet, plus the time until the frame's
        // last packet is in (a PC that paces its sending spreads every frame out)
        final double spread = Math.max(0, r.spreadMs);
        final double net = Math.max(0, r.rttMs) / 2.0 + spread;
        // A new picture waits half a frame on average for the next capture, at the rate the PC
        // really sent (a 120 fps stream from a game drawing 60 fps waits like a 60 fps one)
        r.realFps = r.hasHost && r.pcFps > 1 ? Math.min(r.config.fps, r.pcFps)
                : r.arrivedFps > 1 ? Math.min(r.config.fps, r.arrivedFps) : r.config.fps;
        r.pcLimited = r.config.fps > r.screenHz * 1.1 && r.realFps < r.config.fps * 0.85;
        final double capture = 500.0 / Math.max(1, r.realFps);
        r.latencyMs = host + net + tvMs + capture;
        r.latency = clamp(100 - (r.latencyMs - LATENCY_FREE_MS) * LATENCY_POINTS_PER_MS);
        r.latencyWhy = String.format(Locale.US,
                "%.0f = 100 - %.1f x (%.1f ms - %.0f): PC %s + network %.1f ms [round trip %.0f / 2 + %.1f ms from a frame's first to its last packet] + TV %.1f ms [%s; decode %.1f, buffer %.1f, max %.0f] + capture wait %.1f ms [half a frame at the %.0f fps the PC sent]",
                r.latency, LATENCY_POINTS_PER_MS, r.latencyMs, LATENCY_FREE_MS,
                r.hostMs >= 0 ? String.format(Locale.US, "%.1f ms [capture to encoded, max %.1f]", r.hostMs, r.maxHostMs)
                        : "? [the PC sent no timing: counted as 0]",
                net, r.rttMs, spread, tvMs, tvHow, r.decodeMs, r.leadMs, tvMax, capture, r.realFps);

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

        // Did the decoder really work in low-latency mode?
        final double frameMs = 1000.0 / Math.max(1, r.config.fps);
        final boolean fast = r.decodeMs >= 0 && r.decodeMs < frameMs * 0.75;
        final boolean noBacklog = r.framesInDecoder < 0 || r.framesInDecoder < 0.5;
        r.decoderWhy = String.format(Locale.US, "decode %.1f ms (a frame every %.1f ms) · frames waiting inside %s → %s",
                r.decodeMs, frameMs,
                r.framesInDecoder >= 0 ? String.format(Locale.US, "%.2f (at most %d)", r.framesInDecoder, r.maxFramesInDecoder) : "?",
                r.decodeMs < 0 ? "not measured" : fast && noBacklog ? "low-latency: yes" : fast ? "mostly (keeps a frame back now and then)" : "low-latency: NO");

        r.total = 0.40 * r.smooth + 0.35 * r.latency + 0.25 * r.picture;
    }

    // ---------------- Deciding ----------------

    /** Smooth enough to recommend: 70+ smoothness, and the picture settled after the start. */
    private static boolean smoothOk(Result r) {
        return r != null && r.ok && r.smooth >= 70 && (r.settled || r.settleSecs < 0);
    }

    private static boolean betterRank(Result x, Result y) {
        // A smooth result always ranks above a stuttering one (or one that never settled)
        final boolean sx = smoothOk(x), sy = smoothOk(y);
        if (sx != sy) {
            return sx;
        }
        // ... and a high frame rate test the PC really delivered above one it didn't
        if (x.pcLimited != y.pcLimited) {
            return !x.pcLimited;
        }
        return x.total > y.total;
    }

    private static Result bestOfStage(int stage) {
        Result best = null;
        for (Result r : results) {
            if (r.ok && r.config.stage == stage && (best == null || betterRank(r, best))) {
                best = r;
            }
        }
        return best;
    }

    private static Result findOption(boolean extraKeys, boolean sync) {
        for (Result r : results) {
            if (r.config.stage == STAGE_OPTIONS && r.ok && r.config.extraKeys == extraKeys && r.config.syncDecode == sync &&
                    (extraKeys || sync) && r.config.pacing == PreferenceConfiguration.FRAME_PACING_TV_GAME && !r.config.aggressive) {
                return r;
            }
        }
        return null;
    }

    // The stage 2 baseline: the format's standard options, measured in stage 1 and again in stage 2
    private static Result baseline;          // averaged when both runs exist, else the one run
    private static double latencyNoise = -1, smoothNoise = -1;

    private static double requiredFaster() {
        return Math.max(MIN_FASTER_MS, latencyNoise >= 0 ? latencyNoise : 0);
    }

    private static double allowedSmoothLoss() {
        return Math.max(MAX_SMOOTH_LOSS, smoothNoise >= 0 ? smoothNoise : 0);
    }

    private static double requiredSmoother() {
        return Math.max(MIN_SMOOTHER, smoothNoise >= 0 ? smoothNoise : 0);
    }

    /**
     * Does this option beat the baseline clearly? Faster by more than the noise without losing
     * smoothness (and smooth enough to use), or clearly smoother without being slower.
     */
    private static boolean helps(Result r) {
        if (r == null || !r.ok || baseline == null) {
            return false;
        }
        final boolean faster = r.latencyMs <= baseline.latencyMs - requiredFaster();
        final boolean keepsSmooth = r.smooth >= baseline.smooth - allowedSmoothLoss() && (smoothOk(r) || !smoothOk(baseline));
        final boolean smoother = r.smooth >= baseline.smooth + requiredSmoother() && smoothOk(r);
        final boolean notSlower = r.latencyMs <= baseline.latencyMs + MAX_SLOWER_MS;
        return (faster && keepsSmooth) || (smoother && notSlower);
    }

    /** The average of two runs of the same settings (scores and the figures shown in the tables). */
    private static Result average(Result a, Result b) {
        final Result m = new Result(b.config);
        m.ok = a.ok && b.ok;
        m.runs = a.runs + b.runs;
        m.secs = a.secs + b.secs;
        m.smooth = (a.smooth + b.smooth) / 2;
        m.latency = (a.latency + b.latency) / 2;
        m.picture = (a.picture + b.picture) / 2;
        m.total = (a.total + b.total) / 2;
        m.latencyMs = (a.latencyMs + b.latencyMs) / 2;
        m.decodeMs = (a.decodeMs + b.decodeMs) / 2;
        m.settled = a.settled && b.settled;
        m.settleSecs = Math.max(a.settleSecs, b.settleSecs);
        m.screenHz = b.screenHz;
        m.codecUsed = b.codecUsed;
        m.decoder = b.decoder;
        m.verified = b.verified;
        m.staticPicture = a.staticPicture || b.staticPicture;
        m.smoothWhy = String.format(Locale.US, "average of %.0f and %.0f", a.smooth, b.smooth);
        m.latencyWhy = String.format(Locale.US, "average of %.1f and %.1f ms", a.latencyMs, b.latencyMs);
        return m;
    }

    /** Works out the recommendation from what has been measured so far. */
    private static void decide() {
        final Result chosen = chooseFormat();
        if (chosen != null || formatWinner == null) {
            formatWinner = chosen;
        }
        // The baseline's second run: the re-check of the chosen format (or, when that could not
        // run, the standard-options run planned with the options)
        optionsBase = formatWinner != null ? recheckRun(formatWinner.config) : null;
        if (optionsBase == null) {
            for (Result r : results) {
                if (r.ok && r.config.stage == STAGE_OPTIONS && r.config.standardOptions()) {
                    optionsBase = r;
                    break;
                }
            }
        }
        // Noise: the same settings measured in stage 1 and again in stage 2. The baseline is
        // their average: one run can be unlucky (the PC busy for a moment, Wi-Fi), and every
        // option is compared with it.
        noise = -1;
        latencyNoise = -1;
        smoothNoise = -1;
        baseline = optionsBase;
        Result firstRun = null;
        if (optionsBase != null) {
            firstRun = formatRun(optionsBase.config);
            if (firstRun != null) {
                noise = Math.abs(firstRun.total - optionsBase.total);
                latencyNoise = Math.abs(firstRun.latencyMs - optionsBase.latencyMs);
                smoothNoise = Math.abs(firstRun.smooth - optionsBase.smooth);
                baseline = average(firstRun, optionsBase);
            }
        }
        recommended = formatWinner;
        recommendedConfigBeforeFinal = null;
        final StringBuilder why = new StringBuilder();
        if (formatWinner == null) {
            recommendation = "No test finished, so there is nothing to recommend.";
            return;
        }
        if (!formatWhy.isEmpty()) {
            why.append(formatWhy).append('\n');
        }
        if (optionsBase == null) {
            recommendation = why + (running && !finalPlanned
                    ? "Best format so far: " + formatWinner.config.describe() + ". The latency options are tested on the best format next."
                    : "Best format: " + formatWinner.config.describe() + ". (The latency options were not tested.)");
            return;
        }

        // Pacing: of the pacing variants that clearly beat the baseline, the fastest one
        // (an option only gets here when it kept the picture smooth)
        Result pacing = null;
        for (Result r : results) {
            if (r.config.stage != STAGE_OPTIONS || r.config.extraKeys || r.config.syncDecode || r == optionsBase ||
                    r.config.standardOptions()) {
                continue;
            }
            if (helps(r) && (pacing == null || r.latencyMs < pacing.latencyMs)) {
                pacing = r;
            }
        }
        final Result keys = findOption(true, false);
        final Result sync = findOption(false, true);
        final boolean useKeys = helps(keys);
        final boolean useSync = helps(sync);

        final Config base = optionsBase.config;
        final int pacingMode = pacing != null ? pacing.config.pacing : base.pacing;
        final boolean aggressive = pacing != null && pacing.config.aggressive;
        final Config combo = base.with("recommended", STAGE_FINAL, pacingMode, aggressive, useKeys, useSync);

        // What to recommend: the combination if it was measured (final check) and holds up,
        // otherwise the best single helpful option, otherwise the baseline (averaged)
        Result bestSingle = baseline;
        for (Result r : new Result[] { pacing, useKeys ? keys : null, useSync ? sync : null }) {
            if (r != null && (bestSingle == baseline || r.latencyMs < bestSingle.latencyMs)) {
                bestSingle = r;
            }
        }
        Result finalCheck = null;
        for (Result r : results) {
            if (r.ok && r.config.stage == STAGE_FINAL) {
                finalCheck = r;
            }
        }
        final int changes = (pacing != null ? 1 : 0) + (useKeys ? 1 : 0) + (useSync ? 1 : 0);
        if (changes >= 2) {
            recommendedConfigBeforeFinal = combo;
        }
        if (finalCheck != null && finalCheck.latencyMs <= bestSingle.latencyMs + MAX_SLOWER_MS &&
                finalCheck.smooth >= bestSingle.smooth - allowedSmoothLoss() && smoothOk(finalCheck)) {
            recommended = finalCheck;
        } else {
            recommended = bestSingle;
        }
        if (firstRun != null) {
            why.append(String.format(Locale.US,
                    "Same settings measured twice: smoothness %.0f and %.0f, delay %.1f and %.1f ms. The baseline is their average; " +
                            "an option counts when it is %.0f+ ms faster without losing more than %.0f smoothness, or %.0f+ smoother and at most %.0f ms slower.",
                    firstRun.smooth, optionsBase.smooth, firstRun.latencyMs, optionsBase.latencyMs,
                    requiredFaster(), allowedSmoothLoss(), requiredSmoother(), MAX_SLOWER_MS));
        } else {
            why.append(String.format(Locale.US, "An option counts when it is %.0f+ ms faster without losing more than %.0f smoothness, or %.0f+ smoother and at most %.0f ms slower.",
                    requiredFaster(), allowedSmoothLoss(), requiredSmoother(), MAX_SLOWER_MS));
        }
        if (pacing != null) {
            why.append("\n").append(pacing.config.describeOptions()).append(" helped: delay ")
                    .append(signed(pacing.latencyMs - baseline.latencyMs, " ms")).append(", smoothness ")
                    .append(signed(pacing.smooth - baseline.smooth, "")).append('.');
        } else {
            why.append("\nNo other pacing beat TV game clearly.");
        }
        if (keys != null) {
            why.append(String.format(Locale.US, "\nMediaTek extra keys: delay %s, smoothness %s, decode %+.1f ms → %s.",
                    signed(keys.latencyMs - baseline.latencyMs, " ms"), signed(keys.smooth - baseline.smooth, ""),
                    keys.decodeMs - baseline.decodeMs, useKeys ? "use them" : "not enough to count"));
        }
        if (sync != null) {
            why.append(String.format(Locale.US, "\nSync decoding: delay %s, smoothness %s → %s.",
                    signed(sync.latencyMs - baseline.latencyMs, " ms"), signed(sync.smooth - baseline.smooth, ""),
                    useSync ? "use it" : "keep async"));
        }
        if (finalCheck != null) {
            why.append(String.format(Locale.US, "\nFinal check (together): smoothness %.0f, delay %.1f ms%s.", finalCheck.smooth, finalCheck.latencyMs,
                    recommended == finalCheck ? ", so they are used together" : ", not better than the best single option, which is used instead"));
        }
        if (recommended == baseline && baseline.runs > 1) {
            why.append("\nThe figures shown for it are the average of its two runs.");
        }
        recommendation = why.toString();
    }

    public static synchronized Result best() {
        if (recommended == null) {
            decide();
        }
        return recommended != null && recommended.ok ? recommended : null;
    }

    /** All tests, best first (a smooth result always ranks above a stuttering one). */
    public static synchronized List<Result> ranked() {
        final List<Result> list = new ArrayList<>(results);
        Collections.sort(list, (x, y) -> {
            final boolean sx = smoothOk(x), sy = smoothOk(y);
            if (sx != sy) {
                return sx ? -1 : 1;
            }
            if (x.pcLimited != y.pcLimited) {
                return x.pcLimited ? 1 : -1;
            }
            return Double.compare(y.total, x.total);
        });
        return list;
    }

    public static synchronized List<Result> results() {
        return new ArrayList<>(results);
    }

    /** The settings value for a frame pacing mode. */
    static String pacingPrefValue(int mode) {
        switch (mode) {
            case PreferenceConfiguration.FRAME_PACING_TV_SMOOTH: return "tv";
            case PreferenceConfiguration.FRAME_PACING_TV_GAME: return "tvgame";
            case PreferenceConfiguration.FRAME_PACING_BALANCED: return "balanced";
            case PreferenceConfiguration.FRAME_PACING_CAP_FPS: return "cap-fps";
            case PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS: return "smoothness";
            case PreferenceConfiguration.FRAME_PACING_GPU_RAW: return "gpu-raw";
            case PreferenceConfiguration.FRAME_PACING_WARP: return "warp";
            case PreferenceConfiguration.FRAME_PACING_WARP2: return "warp2";
            default: return "latency";
        }
    }

    /** Writes a result's settings as the saved settings. */
    public static void use(Context context, Config c) {
        final SharedPreferences.Editor e = PreferenceManager.getDefaultSharedPreferences(context).edit();
        e.putString("list_resolution", c.width + "x" + c.height);
        e.putString("list_fps", String.valueOf(c.fps));
        e.putInt("seekbar_bitrate_kbps", c.bitrateKbps);
        e.putString("video_format", c.codec == CODEC_AV1 ? "forceav1" : c.codec == CODEC_H264 ? "neverh265" : "forceh265");
        e.putString("frame_pacing", pacingPrefValue(c.pacing));
        e.putBoolean(PreferenceConfiguration.TV_AGGRESSIVE_TIMING_PREF_STRING, c.aggressive);
        e.putBoolean(PreferenceConfiguration.MTK_EXTRA_DECODER_KEYS_PREF_STRING, c.extraKeys);
        e.putBoolean("checkbox_ultra_low_latency", c.ultraLowLatency);
        e.putBoolean("checkbox_async_decode", !c.syncDecode);
        if (c.fps > 60) {
            e.putBoolean("checkbox_unlock_fps", true);
        }
        e.apply();
        LimeLog.info("Artemide TV auto-tune: now using " + c.describe());
    }

    // ---------------- Text and log file ----------------

    private static String f1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static String latencyCell(Result r) {
        return String.format(Locale.US, "%3.0f (%2.0f ms)", r.latency, Math.max(0, r.latencyMs));
    }

    /** A short note on PC-side trouble in a test (gaps where it captured nothing, late deliveries). */
    private static String pcNote(Result r) {
        if (!r.ok) {
            return "";
        }
        if (r.pcLimited) {
            return String.format(Locale.US, "  (the PC sent only %.0f fps)", r.realFps);
        }
        if (!r.hasHost) {
            return "";
        }
        if (r.pcGapsPerMin >= 6) {
            return String.format(Locale.US, "  (PC stalled %.0f/min)", r.pcGapsPerMin);
        }
        if (r.deliveryStallsPerMin >= 6) {
            return String.format(Locale.US, "  (frames arrived late %.0f/min)", r.deliveryStallsPerMin);
        }
        return "";
    }

    public static synchronized String summaryText() {
        final StringBuilder b = new StringBuilder(4096);
        if (!results.isEmpty()) {
            decide();
        }
        // Stage 1
        final List<Result> formats = new ArrayList<>();
        for (Result r : ranked()) {
            if (r.config.stage == STAGE_FORMAT) {
                formats.add(r);
            }
        }
        b.append("FORMATS (all with TV game pacing, except your current settings)\n");
        b.append(String.format(Locale.US, "%-3s %-46s %5s %6s %-13s %7s\n", "#", "Settings", "Total", "Smooth", "Latency", "Picture"));
        int rank = 1;
        for (Result r : formats) {
            final String name = r.config.standardOptions() ? r.config.describeFormat() : r.config.describe();
            if (r.ok) {
                b.append(String.format(Locale.US, "%-3d %-46s %5.0f %6.0f %-13s %7.0f%s%s%s\n", rank, name,
                        r.total, r.smooth, latencyCell(r), r.picture, r.staticPicture ? "  (static picture)" : "",
                        !r.settled && r.settleSecs >= 0 ? "  (never smooth)" : "", pcNote(r)));
            } else {
                b.append(String.format(Locale.US, "%-3s %-46s failed: %s\n", "-", name, r.failure));
            }
            rank++;
        }
        if (!av1Tested) {
            b.append("(No AV1 test: this TV has no AV1 hardware decoder.)\n");
        }
        for (Result r : formats) {
            if (r.ok && r.pcLimited) {
                b.append(String.format(Locale.US,
                        "Note: in the %d fps tests the PC sent only about %.0f fps, so they ranked last. The game or the stream test page " +
                                "drew at %.0f on the PC: the page must be on the virtual display of Apollo or Vibepollo running at %d Hz or more (its corner then says at least %d fps on the PC), " +
                                "a game must be allowed %d fps (with Vibepollo: its frame limiter caps the game at the stream's rate).\n",
                        r.config.fps, r.realFps, r.realFps, r.config.fps, r.config.fps, r.config.fps));
                break;
            }
        }
        {
            double sum = 0, max = 0;
            int n = 0, unsettled = 0;
            for (Result r : results) {
                if (r.ok && r.settleSecs >= 0) {
                    if (!r.settled) {
                        unsettled++;
                        continue;
                    }
                    sum += r.settleSecs;
                    max = Math.max(max, r.settleSecs);
                    n++;
                }
            }
            if (n > 0) {
                b.append(String.format(Locale.US, "Start-up: the tests needed %.0f s on average (at most %.0f s) to be smooth; only the time after that is measured%s.\n",
                        sum / n, max, unsettled > 0 ? String.format(Locale.US, "; %d never became smooth", unsettled) : ""));
            }
        }

        // Re-check
        boolean recheckHeader = false;
        for (Result r : results) {
            if (r.config.stage != STAGE_RECHECK) {
                continue;
            }
            if (!recheckHeader) {
                recheckHeader = true;
                b.append("\nRE-CHECK (measured again; the format is chosen on the averages)\n");
                b.append(String.format(Locale.US, "%-34s %-18s %-18s %s\n", "Format", "1st run", "2nd run", "Average"));
            }
            final Result first = formatRun(r.config);
            if (!r.ok) {
                b.append(String.format(Locale.US, "%-34s failed: %s\n", r.config.describeFormat(), r.failure));
                continue;
            }
            final Result avg = formatResult(first != null ? first : r);
            b.append(String.format(Locale.US, "%-34s %-18s %-18s %s\n", r.config.describeFormat(),
                    first != null ? String.format(Locale.US, "%.0f / %.1f ms", first.smooth, first.latencyMs) : "-",
                    String.format(Locale.US, "%.0f / %.1f ms", r.smooth, r.latencyMs),
                    String.format(Locale.US, "smoothness %.0f, delay %.1f ms, total %.0f%s", avg.smooth, avg.latencyMs, avg.total, pcNote(r))));
        }

        // Stage 2
        Result base = null;
        final List<Result> options = new ArrayList<>();
        for (Result r : results) {
            if (r.config.stage == STAGE_OPTIONS) {
                options.add(r);
                if (base == null && r.ok && r.config.standardOptions()) {
                    base = r;
                }
            }
        }
        if (!options.isEmpty()) {
            b.append("\nLATENCY OPTIONS on ").append(options.get(0).config.describeFormat()).append('\n');
            b.append(String.format(Locale.US, "%-46s %5s %6s %-13s %s\n", "Option", "Total", "Smooth", "Latency",
                    baseline != null && baseline.runs > 1 ? "vs. baseline (TV game, average of 2 runs)" : "vs. baseline (TV game)"));
            if (baseline != null && baseline.runs > 1) {
                b.append(String.format(Locale.US, "%-46s %5.0f %6.0f %-13s -\n", "TV game, average of both runs",
                        baseline.total, baseline.smooth, latencyCell(baseline)));
            }
            boolean ranBefore = false;
            for (Result r : formats) {
                if (r.config.sameFormat(options.get(0).config) && r.config.standardOptions()) {
                    ranBefore = true;
                }
            }
            for (Result r : options) {
                final String name = r.config.standardOptions()
                        ? (ranBefore ? "TV game (the same as above, again)" : "TV game (baseline)") : r.config.describeOptions();
                if (!r.ok) {
                    b.append(String.format(Locale.US, "%-46s failed: %s\n", name, r.failure));
                    continue;
                }
                String vs = "-";
                if (baseline != null && r != base) {
                    vs = signed(r.latencyMs - baseline.latencyMs, " ms") + ", smoothness " + signed(r.smooth - baseline.smooth, "") +
                            (helps(r) ? " → helps" : "");
                }
                b.append(String.format(Locale.US, "%-46s %5.0f %6.0f %-13s %s%s%s%s\n", name, r.total, r.smooth, latencyCell(r), vs,
                        !r.settled && r.settleSecs >= 0 ? "  (never smooth)" : "",
                        r.verified.isEmpty() ? "" : "  (not as planned: " + r.verified + ")", pcNote(r)));
            }
        }
        for (Result r : results) {
            if (r.config.stage == STAGE_FINAL) {
                b.append("\nFINAL CHECK: ").append(r.config.describe()).append('\n');
                if (r.ok) {
                    b.append(String.format(Locale.US, "  total %.0f · smoothness %.0f · latency %s · picture %.0f\n",
                            r.total, r.smooth, latencyCell(r), r.picture));
                } else {
                    b.append("  failed: ").append(r.failure).append('\n');
                }
            }
        }

        // The outcome
        if (recommended != null) {
            b.append("\nRECOMMENDED: ").append(recommended.config.describe())
                    .append(recommended.runs > 1 ? " (average of 2 runs)" : "").append('\n');
            b.append(String.format(Locale.US, "  total %.0f · smoothness %.0f · latency %s · picture %.0f\n",
                    recommended.total, recommended.smooth, latencyCell(recommended), recommended.picture));
        }
        if (!recommendation.isEmpty()) {
            b.append(recommendation).append('\n');
        }
        if (abortReason != null) {
            b.append("\nStopped early: ").append(abortReason).append('\n');
        }
        return b.toString();
    }

    /** Why each test got its scores, and whether the decoder really ran in low-latency mode. */
    public static synchronized String detailsText() {
        final StringBuilder b = new StringBuilder(4096);
        int n = 1;
        for (Result r : results) {
            b.append(n++).append(". ").append(r.config.describe());
            if (r.config.stage != STAGE_FORMAT) {
                b.append(" (").append(r.config.label).append(')');
            }
            if (!r.ok) {
                b.append(": failed (").append(r.failure).append(")\n\n");
                continue;
            }
            b.append(String.format(Locale.US, " · screen %.0f Hz · %s · %s\n", r.screenHz, r.codecUsed, r.decoder));
            b.append(r.verified.isEmpty() ? "  Ran as planned (checked against the settings it really used)\n"
                    : "  NOT as planned: " + r.verified + "\n");
            if (r.settleSecs >= 0) {
                b.append(r.settled ? String.format(Locale.US, "  Start: smooth after %.0f s; measured after that\n", r.settleSecs)
                        : String.format(Locale.US, "  Start: NOT smooth within %.0f s; measured anyway (this setting has a problem)\n", r.settleSecs));
            }
            b.append("  Smoothness ").append(r.smoothWhy).append('\n');
            b.append("  Latency ").append(r.latencyWhy).append('\n');
            b.append("  Picture ").append(r.pictureWhy).append('\n');
            b.append("  Decoder ").append(r.decoderWhy);
            final String key = keyVerdict(r.settings);
            if (key != null) {
                b.append(" · ").append(key);
            }
            b.append('\n');
            b.append(String.format(Locale.US, "  Used %.1f Mbps · keyframe requests %d · audio rebuilt by FEC %.2f%%%s\n\n",
                    r.mbpsUsed, r.keyframes, r.audioFecPct, r.staticPicture ? " · STATIC PICTURE: the PC sent little, compare with care" : ""));
        }
        return b.toString();
    }

    /** The "Decoder low-latency key" verdict from a test's settings text. */
    private static String keyVerdict(String settings) {
        if (settings == null) {
            return null;
        }
        for (String line : settings.split("\n")) {
            if (line.startsWith("Decoder low-latency key: ")) {
                return "low-latency key " + line.substring("Decoder low-latency key: ".length());
            }
        }
        return null;
    }

    public static synchronized String fullLog() {
        final StringBuilder b = new StringBuilder(64 * 1024);
        b.append("Artemide TV auto-tune · ").append(startedAt).append(" · ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE).append(" · app ")
                .append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        b.append(String.format(Locale.US, "Each test: waits until the picture is smooth for 3 s (%d to %d s; the first test %d to %d s), " +
                        "then %d s measured. Stage 1 formats, then the best (and a close runner-up) again, stage 2 latency options " +
                        "on the chosen format, stage 3 the helpful options together.\n\n",
                MIN_WARMUP_MS / 1000, MAX_WARMUP_MS / 1000, FIRST_MIN_WARMUP_MS / 1000, FIRST_MAX_WARMUP_MS / 1000,
                MEASURE_MS / 1000));
        b.append("RESULTS\n").append(summaryText()).append('\n');
        b.append("SCORES (0-100, higher is better)\n");
        b.append(String.format(Locale.US,
                " Smoothness = 100 x new pictures/possible - %.3f per ms/min the picture stood still longer than the content's rhythm" +
                        " - %.3f per ms/min of skipped frames and uneven steps (uneven steps at most %.0f) - %.0f per frame loss/min - %.0f per network pause (40 ms+) per minute" +
                        " - %.0f when the frame rate doesn't fit the screen's refresh rate (uneven motion)." +
                        " Which refresh shows which frame comes from SurfaceFlinger's timing (the display's presentation deadline and its refresh grid):" +
                        " this TV's frame-rendered callback only echoes the requested time. Without a refresh grid: %.2f per ms/min of hitches the callback saw.\n",
                FROZEN_POINTS_PER_MS, JUMP_POINTS_PER_MS, UNEVEN_MOTION_POINTS, LOSS_POINTS, PAUSE_POINTS, UNEVEN_MOTION_POINTS, HITCH_POINTS_PER_STALL_MS));
        b.append(String.format(Locale.US, " Latency    = 100 - %.1f x (ms - %.0f), ms = PC (capture to encoded) + round trip/2 + a frame's first to last packet + TV (arrival to the refresh it is shown on) + half a frame interval\n",
                LATENCY_POINTS_PER_MS, LATENCY_FREE_MS));
        b.append(" Picture    = resolution (4K 100, 1440p 85, 1080p 70) x (0.6 + 0.4 x bits-per-pixel/0.15, capped; AV1 x1.3)\n");
        b.append(" Total      = 40% smoothness + 35% latency + 25% picture; tests below 70 smoothness rank last\n");
        b.append(String.format(Locale.US, " Format: the best of stage 1 and a runner-up within %.0f points run again; between two of the same picture the faster one wins" +
                        " unless it is clearly less smooth, otherwise the higher total (both on the averages).\n" +
                        " Stage 2: the baseline is the chosen format measured twice (averaged). An option counts when it is %.0f+ ms faster" +
                        " (or more than the two runs differed) and loses at most %.0f smoothness (or the noise), or when it is %.0f+ smoother and at most %.0f ms slower." +
                        " The final check counts when it is not slower than the best single option by %.0f+ ms and keeps its smoothness.\n\n",
                CLOSE_POINTS, MIN_FASTER_MS, MAX_SMOOTH_LOSS, MIN_SMOOTHER, MAX_SLOWER_MS, MAX_SLOWER_MS));
        b.append("WHY EACH TEST GOT ITS SCORES\n").append(detailsText());
        b.append("DETAILS\n");
        b.append("test;stage;settings;ok;smooth after s;total;smooth;latency;latency ms;picture;new pictures/s;possible fps;PC sent fps;screen Hz;" +
                "frozen refreshes/min;frozen ms/min;longest still ms;PC had nothing newer/min;skipped frames/min;uneven steps/min;frames replaced/s;" +
                "PC fps (capture times);PC gaps/min;longest PC gap ms;late deliveries/min;longest late delivery ms;frames 8 ms+ behind %;" +
                "net pauses/min;longest pause ms;loss events/min;frames lost/min;keyframe requests;PC ms;decode ms;frames in decoder;" +
                "TV ms (to the refresh shown on);max TV ms;callback TV ms;round trip ms;frame first to last packet ms;max ms;buffer ms;Mbps used;audio FEC %;codec used;decoder;note\n");
        int n = 1;
        for (Result r : results) {
            b.append(String.format(Locale.US,
                    "%d;%d;%s;%s;%s;%.0f;%.0f;%.0f;%.1f;%.0f;%s;%.0f;%.1f;%.0f;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;%s;" +
                            "%.1f;%d;%.1f;%.1f;%d;%.1f;%.1f;%.2f;%s;%s;%.1f;%.0f;%s;%s;%.1f;%.1f;%.2f;%s;%s;%s\n",
                    n++, r.config.stage, r.config.describe(), r.ok ? "yes" : "no",
                    r.settleSecs < 0 ? "" : (r.settled ? String.format(Locale.US, "%.0f", r.settleSecs)
                            : String.format(Locale.US, "not within %.0f", r.settleSecs)),
                    r.total, r.smooth, r.latency, r.latencyMs, r.picture,
                    r.hasModel ? f1(r.modelFps) : "", r.expectedFps, r.arrivedFps, r.screenHz,
                    r.hasModel ? f1(r.frozenPerMin) : "", r.hasModel ? f1(r.frozenMsPerMin) : "",
                    r.hasModel ? String.valueOf(r.longestFreezeMs) : "", r.hasModel ? f1(r.frozenPcPerMin) : "",
                    r.hasModel ? f1(r.skippedPerMin) : "", r.hasModel ? f1(r.unevenPerMin) : "", r.hasModel ? f1(r.replacedPerSec) : "",
                    r.hasHost ? String.format(Locale.US, "%.2f", r.pcFps) : "", r.hasHost ? f1(r.pcGapsPerMin) : "",
                    r.hasHost ? String.valueOf(r.longestPcGapMs) : "", r.hasHost ? f1(r.deliveryStallsPerMin) : "",
                    r.hasHost ? String.valueOf(r.longestDeliveryStallMs) : "", r.hasHost ? f1(r.late8Pct) : "",
                    r.netPausesPerMin, r.longestNetPauseMs, r.lossEventsPerMin, r.framesLostPerMin, r.keyframes,
                    r.hostMs, r.decodeMs, r.framesInDecoder,
                    r.modelTvMs > 0 ? f1(r.modelTvMs) : "", r.modelTvMs > 0 ? f1(r.maxModelTvMs) : "",
                    r.screenMs, r.rttMs, r.spreadMs >= 0 ? f1(r.spreadMs) : "", r.spreadMs >= 0 ? f1(r.maxSpreadMs) : "",
                    r.leadMs, r.mbpsUsed, r.audioFecPct, r.codecUsed, r.decoder,
                    !r.ok ? r.failure : (r.staticPicture ? "static picture: compare with care" : "")));
        }
        b.append("\nPER TEST: STREAM REPORT, SETTINGS IN USE AND TIMELINE (one line per second)\n");
        n = 1;
        String firstSettings = null;
        int firstSettingsTest = 0;
        for (Result r : results) {
            b.append("--- Test ").append(n).append(" (stage ").append(r.config.stage).append("): ")
                    .append(r.config.describe()).append(" (").append(r.config.label).append(")\n");
            b.append("Screen at the end: ").append(r.display).append('\n');
            b.append(r.report != null ? r.report : (r.failure != null ? "failed: " + r.failure : "-")).append("\n");
            if (r.settings != null) {
                if (firstSettings == null) {
                    firstSettings = r.settings;
                    firstSettingsTest = n;
                    b.append("Settings in use:\n").append(r.settings);
                } else {
                    final String diff = TvStreamSettings.difference(r.settings, firstSettings);
                    b.append("Settings in use: as in test ").append(firstSettingsTest).append(", except:\n")
                            .append(diff.isEmpty() ? "(nothing)\n" : diff);
                }
            }
            if (r.timeline != null) {
                b.append("Timeline:\n").append(r.timeline);
            }
            b.append('\n');
            n++;
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
    public static synchronized String writeLogFile(Context context) {
        decide();
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
