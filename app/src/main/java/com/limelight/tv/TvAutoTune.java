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
 *  2 Latency options  on the best format: the same settings again (how repeatable the results
 *                     are), TV game with aggressive timing, TV smooth, the stock "lowest latency"
 *                     pacing, MediaTek's extra decoder keys, and synchronous decoding
 *  3 Final check      the options that helped, together (only when more than one helped)
 *
 * Every test waits until its picture has been smooth for 3 seconds in a row (streams often
 * stutter for a few seconds after they start: the TV's display mode switch, Apollo and the game
 * adapting to the new resolution, Wi-Fi rates settling), at least 6 s and at most 25 s (the first
 * test 15 to 40 s, it may have to start the app on the PC), and then measures 35 s. How long it
 * had to wait is in the log. Three scores, 0-100, higher is better:
 *
 *  Smoothness  frames shown per second against what the screen can show, minus the time the
 *              picture stood still beyond its normal cadence (hitches), frame losses (each one
 *              freezes the picture until a new keyframe) and long network pauses
 *  Latency     an estimate of the delay from the PC's screen to the TV's screen: PC (capture to
 *              encoded, as the PC reports it) + half the network round trip + what the TV adds
 *              (arrival to screen: decode, pacing buffer, compositor, measured per frame) + half a
 *              frame interval (a new picture waits that long on average for the next capture)
 *  Picture     resolution (the panel is 4K, the TV scales the rest) and bits per pixel, counting
 *              AV1 as 30 % more efficient than HEVC
 *
 * Total = 40 % smoothness + 35 % latency + 25 % picture. A test that is not smooth (below 70)
 * is never picked as best while a smooth one exists, nor is one whose picture never settled after
 * the start (it is measured anyway, 20 s instead of 35). An option in stage 2 only counts as
 * better when it beats the repeated baseline by more than the measuring noise.
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
    private static final double HITCH_POINTS_PER_STALL_MS = 0.24; // a one-refresh hitch at 60 Hz (16.7 ms) = 4 points
    private static final double LOSS_POINTS = 6;
    private static final double PAUSE_POINTS = 1;
    // A frame rate that doesn't fit the screen's refresh rate (90 fps on 60 or 120 Hz) is shown
    // with frames of unequal length: motion judders even when no frame is late
    private static final double UNEVEN_MOTION_POINTS = 15;
    private static final double LATENCY_POINTS_PER_MS = 1.5;
    private static final double LATENCY_FREE_MS = 25;     // delay below this costs no points
    // Stage 2: an option must beat the repeated baseline by at least this (or by the noise)
    private static final double MIN_GAIN = 2.0;
    // ... and must not cost more smoothness than this
    private static final double MAX_SMOOTH_LOSS = 5.0;

    public static final int STAGE_FORMAT = 1;
    public static final int STAGE_OPTIONS = 2;
    public static final int STAGE_FINAL = 3;

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

    private static void buildPlan(PreferenceConfiguration current) {
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
        addIfNew(Config.format("1440p 90 fps", 2560, 1440, 90, 80_000, CODEC_HEVC));
        addIfNew(Config.format("1080p", 1920, 1080, 60, 50_000, CODEC_HEVC));
        addIfNew(Config.format("1080p 120 fps", 1920, 1080, 120, 80_000, CODEC_HEVC));
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

    /** Stage 2: the latency options, on the best format of stage 1. */
    private static void planOptions() {
        optionsPlanned = true;
        formatWinner = bestOfStage(STAGE_FORMAT);
        if (formatWinner == null) {
            return;
        }
        final Config f = formatWinner.config;
        final int game = PreferenceConfiguration.FRAME_PACING_TV_GAME;
        plan.add(f.with(f.standardOptions() ? "the same again (shows how repeatable the results are)"
                : "TV game with standard options: the baseline for the options", STAGE_OPTIONS, game, false, false, false));
        // Aggressive timing changes nothing when the stream has more frames than the screen refreshes
        final double hz = formatWinner.screenHz > 20 ? formatWinner.screenHz : screenHzSeen;
        final boolean framePerRefresh = f.fps <= hz * 1.1;
        if (framePerRefresh) {
            plan.add(f.with("aggressive timing: frames planned half a refresh closer", STAGE_OPTIONS, game, true, false, false));
        }
        plan.add(f.with("TV smooth: a bigger buffer against Wi-Fi bunching", STAGE_OPTIONS,
                PreferenceConfiguration.FRAME_PACING_TV_SMOOTH, false, false, false));
        plan.add(f.with("lowest latency: every frame shown at once, no pacing", STAGE_OPTIONS,
                PreferenceConfiguration.FRAME_PACING_MIN_LATENCY, false, false, false));
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
        if (!optionsPlanned && results.size() >= plan.size()) {
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
        optionsPlanned = false;
        finalPlanned = false;
        optionsBase = null;
        formatWinner = null;
        recommended = null;
        recommendedConfigBeforeFinal = null;
        recommendation = "";
        noise = -1;
        abortReason = null;
        screenHzSeen = 60f;
        startedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
        buildPlan(PreferenceConfiguration.readPreferences(context));
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
        if (!optionsPlanned) {
            n += 6;
        }
        if (!finalPlanned) {
            n += 1;
        }
        return n;
    }

    public static synchronized boolean planComplete() {
        return optionsPlanned && finalPlanned;
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
        return results.size() >= plan.size() && optionsPlanned && finalPlanned;
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
            return st == STAGE_FORMAT ? "formats" : st == STAGE_OPTIONS ? "latency options" : "final check";
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
        // Smoothness: frames that reached the screen, minus the time the picture stood still
        // beyond its cadence (a one-refresh hitch costs 4 points at 60 Hz, 2 at 120 Hz), frame
        // losses (each freezes the picture until a keyframe) and long network pauses
        final double shown = r.shownFps > 0 ? r.shownFps : r.releasedFps;
        final double ratio = r.expectedFps > 0 ? Math.min(1.0, shown / r.expectedFps) : 0;
        final double frames = 100 * ratio;
        final double hitchP = HITCH_POINTS_PER_STALL_MS * r.stallMsPerMin;
        final double lossP = LOSS_POINTS * r.lossEventsPerMin;
        final double pauseP = PAUSE_POINTS * r.netPausesPerMin;
        final boolean even = evenCadence(r.config.fps, r.screenHz);
        final double unevenP = even ? 0 : UNEVEN_MOTION_POINTS;
        r.smooth = clamp(frames - hitchP - lossP - pauseP - unevenP);
        r.smoothWhy = String.format(Locale.US,
                "%.0f = frames %.0f (%.1f of %.0f fps shown; PC sent %.1f) - hitches %.0f (%.1f/min, picture stood still %.0f ms/min, longest frame %d ms)" +
                        " - losses %.0f (%.1f/min, %.1f frames/min) - network pauses %.0f (%.1f/min, longest %d ms)%s",
                r.smooth, frames, shown, r.expectedFps, r.arrivedFps, hitchP, r.hitchesPerMin, r.stallMsPerMin, r.longestHitchMs,
                lossP, r.lossEventsPerMin, r.framesLostPerMin, pauseP, r.netPausesPerMin, r.longestNetPauseMs,
                even ? "" : String.format(Locale.US, " - uneven motion %.0f (%d fps on a %.0f Hz screen: frames stay up for unequal times)",
                        unevenP, r.config.fps, r.screenHz));

        // Latency: PC + network + TV + the wait for the next capture
        double tvMs = r.screenMs;
        String tvHow = "measured arrival to screen";
        if (tvMs <= 0) {
            // No frame-rendered times on this TV: estimate from decode + buffer + one refresh
            tvMs = Math.max(0, r.decodeMs) + r.leadMs + 1000.0 / Math.max(1.0, r.expectedFps);
            tvHow = "estimated: decode + buffer + 1 refresh";
        }
        final double host = Math.max(0, r.hostMs);
        final double net = Math.max(0, r.rttMs) / 2.0;
        final double capture = 500.0 / Math.max(1, r.config.fps);
        r.latencyMs = host + net + tvMs + capture;
        r.latency = clamp(100 - (r.latencyMs - LATENCY_FREE_MS) * LATENCY_POINTS_PER_MS);
        r.latencyWhy = String.format(Locale.US,
                "%.0f = 100 - %.1f x (%.1f ms - %.0f): PC %s + network %.1f ms [round trip %.0f / 2] + TV %.1f ms [%s; decode %.1f, buffer %.1f, max %.0f] + capture wait %.1f ms",
                r.latency, LATENCY_POINTS_PER_MS, r.latencyMs, LATENCY_FREE_MS,
                r.hostMs >= 0 ? String.format(Locale.US, "%.1f ms [capture to encoded, max %.1f]", r.hostMs, r.maxHostMs)
                        : "? [the PC sent no timing: counted as 0]",
                net, r.rttMs, tvMs, tvHow, r.decodeMs, r.leadMs, r.maxScreenMs, capture);

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

    private static double requiredGain() {
        return Math.max(MIN_GAIN, noise >= 0 ? noise : MIN_GAIN);
    }

    /** Does this option beat the repeated baseline clearly, without costing smoothness? */
    private static boolean helps(Result r) {
        if (r == null || !r.ok || optionsBase == null) {
            return false;
        }
        return r.total >= optionsBase.total + requiredGain() &&
                r.smooth >= optionsBase.smooth - MAX_SMOOTH_LOSS &&
                (smoothOk(r) || !smoothOk(optionsBase));
    }

    /** Works out the recommendation from what has been measured so far. */
    private static void decide() {
        if (!optionsPlanned || formatWinner == null) {
            formatWinner = bestOfStage(STAGE_FORMAT);
        }
        optionsBase = null;
        for (Result r : results) {
            if (r.ok && r.config.stage == STAGE_OPTIONS && r.config.standardOptions()) {
                optionsBase = r;
                break;
            }
        }
        // Noise: the same settings measured in stage 1 and again in stage 2
        noise = -1;
        Result firstRun = null;
        if (optionsBase != null) {
            for (Result r : results) {
                if (r.ok && r.config.stage == STAGE_FORMAT && r.config.sameFormat(optionsBase.config) &&
                        r.config.sameOptions(optionsBase.config)) {
                    firstRun = r;
                    break;
                }
            }
            if (firstRun != null) {
                noise = Math.abs(firstRun.total - optionsBase.total);
            }
        }
        recommended = formatWinner;
        recommendedConfigBeforeFinal = null;
        final StringBuilder why = new StringBuilder();
        if (formatWinner == null) {
            recommendation = "No test finished, so there is nothing to recommend.";
            return;
        }
        if (optionsBase == null) {
            recommendation = running && !finalPlanned
                    ? "Best format so far: " + formatWinner.config.describe() + ". The latency options are tested on the best format next."
                    : "Best format: " + formatWinner.config.describe() + ". (The latency options were not tested.)";
            return;
        }

        // Pacing: the best of the pacing variants that clearly beats the baseline
        Result pacing = null;
        for (Result r : results) {
            if (r.config.stage != STAGE_OPTIONS || r.config.extraKeys || r.config.syncDecode || r == optionsBase) {
                continue;
            }
            if (helps(r) && (pacing == null || r.total > pacing.total)) {
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
        // otherwise the best single measured option, otherwise the baseline
        Result bestSingle = optionsBase;
        for (Result r : new Result[] { pacing, useKeys ? keys : null, useSync ? sync : null }) {
            if (r != null && r.total > bestSingle.total) {
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
        if (finalCheck != null && finalCheck.total >= bestSingle.total - requiredGain() &&
                finalCheck.smooth >= bestSingle.smooth - MAX_SMOOTH_LOSS) {
            recommended = finalCheck;
        } else {
            recommended = bestSingle;
        }
        if (firstRun != null) {
            why.append(String.format(Locale.US, "Same settings measured twice: total %.0f and %.0f, so a difference below %.0f points is noise.",
                    firstRun.total, optionsBase.total, requiredGain()));
        } else {
            why.append(String.format(Locale.US, "A difference below %.0f points counts as noise.", requiredGain()));
        }
        if (pacing != null) {
            why.append("\n").append(pacing.config.describeOptions()).append(" helped: total ")
                    .append(signed1(pacing.total - optionsBase.total)).append(", delay ")
                    .append(signed(pacing.latencyMs - optionsBase.latencyMs, " ms")).append('.');
        } else {
            why.append(String.format(Locale.US, "\nNo other pacing beat TV game by %.0f points or more without losing smoothness.", requiredGain()));
        }
        if (keys != null) {
            why.append(String.format(Locale.US, "\nMediaTek extra keys: total %s, decode %+.1f ms → %s.",
                    signed1(keys.total - optionsBase.total), keys.decodeMs - optionsBase.decodeMs,
                    useKeys ? "use them" : "not enough to count"));
        }
        if (sync != null) {
            why.append(String.format(Locale.US, "\nSync decoding: total %s, delay %s → %s.",
                    signed1(sync.total - optionsBase.total), signed(sync.latencyMs - optionsBase.latencyMs, " ms"),
                    useSync ? "use it" : "keep async"));
        }
        if (finalCheck != null) {
            why.append(String.format(Locale.US, "\nFinal check (together): total %.0f%s.", finalCheck.total,
                    recommended == finalCheck ? ", so they are used together" : ", worse than the best single option, which is used instead"));
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

    private static String latencyCell(Result r) {
        return String.format(Locale.US, "%3.0f (%2.0f ms)", r.latency, Math.max(0, r.latencyMs));
    }

    public static synchronized String summaryText() {
        final StringBuilder b = new StringBuilder(4096);
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
                b.append(String.format(Locale.US, "%-3d %-46s %5.0f %6.0f %-13s %7.0f%s%s\n", rank, name,
                        r.total, r.smooth, latencyCell(r), r.picture, r.staticPicture ? "  (static picture)" : "",
                        !r.settled && r.settleSecs >= 0 ? "  (never smooth)" : ""));
            } else {
                b.append(String.format(Locale.US, "%-3s %-46s failed: %s\n", "-", name, r.failure));
            }
            rank++;
        }
        if (!av1Tested) {
            b.append("(No AV1 test: this TV has no AV1 hardware decoder.)\n");
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
            b.append(String.format(Locale.US, "%-46s %5s %6s %-13s %s\n", "Option", "Total", "Smooth", "Latency", "vs. TV game again"));
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
                if (base != null && r != base) {
                    vs = signed(r.total - base.total, "") + " (" + signed(r.latencyMs - base.latencyMs, " ms") + ")";
                }
                b.append(String.format(Locale.US, "%-46s %5.0f %6.0f %-13s %s%s%s\n", name, r.total, r.smooth, latencyCell(r), vs,
                        !r.settled && r.settleSecs >= 0 ? "  (never smooth)" : "",
                        r.verified.isEmpty() ? "" : "  (not as planned: " + r.verified + ")"));
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
        if (!results.isEmpty()) {
            decide();
        }
        if (recommended != null) {
            b.append("\nRECOMMENDED: ").append(recommended.config.describe()).append('\n');
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
                        "then %d s measured. Stage 1 formats, stage 2 latency options on the best format, " +
                        "stage 3 the helpful options together.\n\n",
                MIN_WARMUP_MS / 1000, MAX_WARMUP_MS / 1000, FIRST_MIN_WARMUP_MS / 1000, FIRST_MAX_WARMUP_MS / 1000,
                MEASURE_MS / 1000));
        b.append("RESULTS\n").append(summaryText()).append('\n');
        b.append("SCORES (0-100, higher is better)\n");
        b.append(String.format(Locale.US,
                " Smoothness = 100 x shown/possible frames - %.2f per ms/min the picture stood still beyond its cadence" +
                        " - %.0f per frame loss/min - %.0f per network pause (40 ms+) per minute" +
                        " - %.0f when the frame rate doesn't fit the screen's refresh rate (uneven motion)\n",
                HITCH_POINTS_PER_STALL_MS, LOSS_POINTS, PAUSE_POINTS, UNEVEN_MOTION_POINTS));
        b.append(String.format(Locale.US, " Latency    = 100 - %.1f x (ms - %.0f), ms = PC (capture to encoded) + round trip/2 + TV (arrival to screen) + half a frame interval\n",
                LATENCY_POINTS_PER_MS, LATENCY_FREE_MS));
        b.append(" Picture    = resolution (4K 100, 1440p 85, 1080p 70) x (0.6 + 0.4 x bits-per-pixel/0.15, capped; AV1 x1.3)\n");
        b.append(" Total      = 40% smoothness + 35% latency + 25% picture; tests below 70 smoothness rank last\n");
        b.append(String.format(Locale.US, " An option counts as better when its total beats the repeated baseline by %.0f+ points (or by the measured noise) and costs at most %.0f smoothness\n\n",
                MIN_GAIN, MAX_SMOOTH_LOSS));
        b.append("WHY EACH TEST GOT ITS SCORES\n").append(detailsText());
        b.append("DETAILS\n");
        b.append("test;stage;settings;ok;smooth after s;total;smooth;latency;latency ms;picture;shown fps;possible fps;PC sent fps;screen Hz;" +
                "hitches/min;stood still ms/min;longest hitch ms;net pauses/min;longest pause ms;loss events/min;frames lost/min;" +
                "keyframe requests;PC ms;decode ms;frames in decoder;TV adds ms (arrival to screen);max TV ms;round trip ms;buffer ms;" +
                "Mbps used;audio FEC %;on time %;codec used;decoder;note\n");
        int n = 1;
        for (Result r : results) {
            b.append(String.format(Locale.US,
                    "%d;%d;%s;%s;%s;%.0f;%.0f;%.0f;%.1f;%.0f;%.1f;%.0f;%.1f;%.0f;%.1f;%.0f;%d;%.1f;%d;%.1f;%.1f;%d;%.1f;%.1f;%.2f;%.1f;%.0f;%.0f;%.1f;%.1f;%.2f;%.0f;%s;%s;%s\n",
                    n++, r.config.stage, r.config.describe(), r.ok ? "yes" : "no",
                    r.settleSecs < 0 ? "" : (r.settled ? String.format(Locale.US, "%.0f", r.settleSecs)
                            : String.format(Locale.US, "not within %.0f", r.settleSecs)),
                    r.total, r.smooth, r.latency, r.latencyMs, r.picture,
                    r.shownFps, r.expectedFps, r.arrivedFps, r.screenHz, r.hitchesPerMin, r.stallMsPerMin, r.longestHitchMs,
                    r.netPausesPerMin, r.longestNetPauseMs, r.lossEventsPerMin, r.framesLostPerMin, r.keyframes,
                    r.hostMs, r.decodeMs, r.framesInDecoder, r.screenMs, r.maxScreenMs, r.rttMs, r.leadMs, r.mbpsUsed,
                    r.audioFecPct, r.onTimePct, r.codecUsed, r.decoder,
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
