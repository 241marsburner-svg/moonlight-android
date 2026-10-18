package com.limelight.tv;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.preference.PreferenceManager;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.binding.video.TvDiagnostics;
import com.limelight.preferences.PreferenceConfiguration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
 *  Smoothness  frames shown per second against what the screen can show, minus hitches (a frame
 *              that stayed up too long), frame losses (each one freezes the picture until a new
 *              keyframe) and network pauses
 *  Latency     what the TV adds (arrival to screen: decode, pacing buffer, compositor, measured per
 *              frame) + half the network round trip + half a frame interval (the PC waits for the
 *              next frame to capture, so more frames per second answer sooner)
 *  Picture     resolution (the panel is 4K, the TV scales the rest) and bits per pixel
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
    public static final long MEASURE_MS = 35_000;
    public static final long CONNECT_TIMEOUT_MS = 40_000;
    private static final String PREF_LAST_LOG = "artemide_tv_autotune_last_log";
    private static final String PREF_LAST_SUMMARY = "artemide_tv_autotune_last_summary";

    public static final class Config {
        public final String label;
        public final int width, height, fps, bitrateKbps;
        public final boolean smoothPacing; // TV smooth instead of TV game

        Config(String label, int width, int height, int fps, int bitrateKbps, boolean smoothPacing) {
            this.label = label;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.bitrateKbps = bitrateKbps;
            this.smoothPacing = smoothPacing;
        }

        String describe() {
            return String.format(Locale.US, "%s %d fps %d Mbps %s", resolutionName(width, height), fps,
                    bitrateKbps / 1000, smoothPacing ? "TV smooth" : "TV game");
        }

        boolean sameAs(Config o) {
            return o != null && width == o.width && height == o.height && fps == o.fps &&
                    bitrateKbps == o.bitrateKbps && smoothPacing == o.smoothPacing;
        }
    }

    public static final class Result {
        public final Config config;
        public boolean ok;
        public String failure;
        // Measured over the window
        public double secs, shownFps, releasedFps, expectedFps, hitchesPerMin, netPausesPerMin;
        public double lossEventsPerMin, framesLostPerMin, decodeMs, screenMs, maxScreenMs, rttMs;
        public double leadMs, mbpsUsed, audioFecPct, onTimePct;
        public long longestHitchMs, longestNetPauseMs, keyframes;
        public boolean staticPicture;
        // Scores 0-100
        public double smooth, latency, picture, total;
        public String report;

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

    // ---------------- Plan ----------------

    private static void buildPlan(PreferenceConfiguration current) {
        plan.clear();
        if (current != null && current.width > 0 && current.height > 0) {
            plan.add(new Config("your current settings", current.width, current.height,
                    Math.round(current.fps), current.bitrate,
                    current.framePacing == PreferenceConfiguration.FRAME_PACING_TV_SMOOTH));
        }
        addIfNew(new Config("1440p baseline", 2560, 1440, 60, 60_000, false));
        addIfNew(new Config("1440p more bitrate", 2560, 1440, 60, 80_000, false));
        addIfNew(new Config("1440p high bitrate", 2560, 1440, 60, 100_000, false));
        addIfNew(new Config("1440p smooth pacing", 2560, 1440, 60, 80_000, true));
        addIfNew(new Config("1440p 90 fps", 2560, 1440, 90, 80_000, false));
        addIfNew(new Config("1080p", 1920, 1080, 60, 50_000, false));
        addIfNew(new Config("1080p 120 fps", 1920, 1080, 120, 80_000, false));
        addIfNew(new Config("4K", 3840, 2160, 60, 80_000, false));
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
        LimeLog.info("Artemide TV auto-tune: " + plan.size() + " tests");
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
    }

    /** Game, at the end of the measured window. */
    public static synchronized void onWindowEnd(Context context, TvDiagnostics.Sample end, int[] audioRtp,
                                                float screenHz, String report) {
        if (!isStepActiveLocked() || stepDone) {
            return;
        }
        final Result r = new Result(plan.get(index));
        if (!measuring || windowStart == null) {
            r.ok = false;
            r.failure = "no measurement";
        } else {
            measure(r, windowStart, end, audioStart, audioRtp, screenHz);
            r.ok = true;
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
        final double hz = screenHz > 20f ? screenHz : 60.0;
        r.expectedFps = Math.min(r.config.fps, hz);
        r.hitchesPerMin = (b.displayGaps - a.displayGaps) / mins;
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
            return;
        }
        // Smoothness
        final double shown = r.shownFps > 0 ? r.shownFps : r.releasedFps;
        final double ratio = r.expectedFps > 0 ? Math.min(1.0, shown / r.expectedFps) : 0;
        r.smooth = clamp(100 * ratio - 4 * r.hitchesPerMin - 6 * r.lossEventsPerMin - 2 * r.netPausesPerMin);

        // Latency: what the TV adds + network + the PC waiting for the next frame
        double tvMs = r.screenMs;
        if (tvMs <= 0) {
            // No frame-rendered times on this TV: estimate from decode + buffer + one refresh
            tvMs = Math.max(0, r.decodeMs) + r.leadMs + 1000.0 / Math.max(1.0, r.expectedFps);
        }
        final double total = tvMs + Math.max(0, r.rttMs) / 2.0 + 500.0 / Math.max(1, r.config.fps);
        r.latency = clamp(100 - (total - 20) * 1.5);

        // Picture: resolution and bits per pixel (0.15 bpp is plenty for HEVC game streaming)
        final double res = r.config.height >= 2160 ? 100 : r.config.height >= 1440 ? 85
                : r.config.height >= 1080 ? 70 : 50;
        final double bpp = r.config.bitrateKbps * 1000.0 /
                ((double) r.config.width * r.config.height * Math.max(1, r.config.fps));
        r.picture = clamp(res * (0.6 + 0.4 * Math.min(1.0, bpp / 0.15)));

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
        b.append(String.format(Locale.US, "%-3s %-30s %5s %6s %7s %7s\n", "#", "Settings", "Total", "Smooth", "Latency", "Picture"));
        int rank = 1;
        for (Result r : list) {
            if (r.ok) {
                b.append(String.format(Locale.US, "%-3d %-30s %5.0f %6.0f %7.0f %7.0f%s\n", rank, r.config.describe(),
                        r.total, r.smooth, r.latency, r.picture, r.staticPicture ? "  (static picture)" : ""));
            } else {
                b.append(String.format(Locale.US, "%-3s %-30s failed: %s\n", "-", r.config.describe(), r.failure));
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
        if (abortReason != null) {
            b.append("\nStopped early: ").append(abortReason).append('\n');
        }
        return b.toString();
    }

    public static synchronized String fullLog() {
        final StringBuilder b = new StringBuilder(8192);
        b.append("Artemide TV auto-tune · ").append(startedAt).append(" · ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE).append(" · app ")
                .append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        b.append(String.format(Locale.US, "Each test: %d s warm-up, %d s measured.\n\n", WARMUP_MS / 1000, MEASURE_MS / 1000));
        b.append("RANKING\n").append(summaryText()).append('\n');
        b.append("SCORES (0-100, higher is better)\n");
        b.append(" Smoothness = 100 x shown/possible frames - 4 per hitch/min - 6 per frame loss/min - 2 per network pause/min\n");
        b.append(" Latency    = 100 - 1.5 x (ms the TV adds + round trip/2 + half a frame interval - 20)\n");
        b.append(" Picture    = resolution (4K 100, 1440p 85, 1080p 70) x (0.6 + 0.4 x bits-per-pixel/0.15, capped)\n");
        b.append(" Total      = 40% smoothness + 35% latency + 25% picture; tests below 70 smoothness rank last\n\n");
        b.append("DETAILS\n");
        b.append("test;settings;ok;total;smooth;latency;picture;shown fps;possible fps;hitches/min;longest hitch ms;" +
                "net pauses/min;longest pause ms;loss events/min;frames lost/min;keyframe requests;decode ms;" +
                "TV adds ms (arrival to screen);max TV ms;round trip ms;buffer ms;Mbps used;audio FEC %;on time %;note\n");
        int n = 1;
        for (Result r : results) {
            b.append(String.format(Locale.US,
                    "%d;%s;%s;%.0f;%.0f;%.0f;%.0f;%.1f;%.0f;%.1f;%d;%.1f;%d;%.1f;%.1f;%d;%.1f;%.1f;%.0f;%.0f;%.1f;%.1f;%.2f;%.0f;%s\n",
                    n++, r.config.describe(), r.ok ? "yes" : "no", r.total, r.smooth, r.latency, r.picture,
                    r.shownFps, r.expectedFps, r.hitchesPerMin, r.longestHitchMs, r.netPausesPerMin, r.longestNetPauseMs,
                    r.lossEventsPerMin, r.framesLostPerMin, r.keyframes, r.decodeMs, r.screenMs, r.maxScreenMs,
                    r.rttMs, r.leadMs, r.mbpsUsed, r.audioFecPct, r.onTimePct,
                    !r.ok ? r.failure : (r.staticPicture ? "static picture: compare with care" : "")));
        }
        b.append("\nSTREAM REPORTS\n");
        n = 1;
        for (Result r : results) {
            b.append("--- Test ").append(n++).append(": ").append(r.config.describe()).append('\n');
            b.append(r.report != null ? r.report : (r.failure != null ? "failed: " + r.failure : "-")).append("\n\n");
        }
        return b.toString();
    }

    private static void saveProgress(Context context) {
        try {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putString(PREF_LAST_LOG, fullLog())
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
        return PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_LAST_LOG, null);
    }

    /**
     * Saves the log as a text file in Downloads/ArtemideTV (visible to file managers on the TV).
     * @return where it went, for the screen, or null if it could not be written
     */
    public static String writeLogFile(Context context) {
        final String text = fullLog();
        saveProgress(context);
        final String name = "autotune-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                final ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ArtemideTV");
                final Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                        if (os != null) {
                            os.write(text.getBytes(StandardCharsets.UTF_8));
                            return "Download/ArtemideTV/" + name;
                        }
                    }
                }
            }
            final File dir = context.getExternalFilesDir(null);
            if (dir != null) {
                final File f = new File(dir, name);
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(text.getBytes(StandardCharsets.UTF_8));
                }
                return f.getAbsolutePath();
            }
        } catch (Throwable t) {
            LimeLog.warning("Artemide TV auto-tune: could not write the log file: " + t);
        }
        return null;
    }

    static String resolutionName(int w, int h) {
        if (w == 3840 && h == 2160) return "4K";
        if (w == 2560 && h == 1440) return "1440p";
        if (w == 1920 && h == 1080) return "1080p";
        if (w == 1280 && h == 720) return "720p";
        return w + "x" + h;
    }
}
