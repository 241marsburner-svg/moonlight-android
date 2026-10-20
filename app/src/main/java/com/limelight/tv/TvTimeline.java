package com.limelight.tv;

import com.limelight.binding.video.TvDiagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Artemide TV: one line per second of the current stream (what the screen showed, what came over
 * the network, what the decoder and the pacer did). Goes into the stream report file and into
 * each auto-tune test, so a log shows *when* something went wrong, not only how often.
 *
 * Fed by Game once a second while the statistics are collected; costs one small string a second.
 */
public final class TvTimeline {
    private TvTimeline() {}

    private static final int MAX_LINES = 1800; // 30 minutes

    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static TvDiagnostics.Sample last;
    private static long startNs;
    private static int dropped;

    // Start-up: a "calm" second showed (nearly) every frame, without a lost frame, a network
    // pause, a keyframe request or more than a small hitch. Streams often stutter for a few
    // seconds after they start (display mode switch, the PC adapting, Wi-Fi rates settling).
    public static final int CALM_SECONDS_FOR_SMOOTH = 3;
    private static final long CALM_MAX_STALL_MS = 25;
    private static int calmStreak;
    private static int smoothAfterSeconds = -1;

    // The screen columns come from SurfaceFlinger's timing (TvScreenModel: which refresh each frame
    // is shown on), the PC columns from the capture time every frame carries.
    public static final String HEADER =
            "t s;screen Hz;new pictures/s;frozen refreshes;frozen: PC had nothing newer;skipped frames;uneven steps;" +
            "longest still ms;frames replaced;sent fps;arrived fps;PC fps;longest PC gap ms;longest arrival gap ms;" +
            "latest vs PC rhythm ms;frame arrival spread ms;net pauses;frames lost;Mbps;decode ms;TV ms;max TV ms;buffer ms;keyframe req;" +
            "audio gaps;round trip ms;PC ms;frames in decoder;note";
    private static final int COLUMNS = HEADER.split(";").length;

    public static synchronized void reset() {
        lines.clear();
        last = null;
        dropped = 0;
        calmStreak = 0;
        smoothAfterSeconds = -1;
        startNs = System.nanoTime();
    }

    /** How many seconds in a row, up to now, were calm. */
    public static synchronized int calmStreak() {
        return calmStreak;
    }

    /** Seconds after the start until the stream became smooth (calm for 3 s in a row), -1 = not yet. */
    public static synchronized int smoothAfterSeconds() {
        return smoothAfterSeconds;
    }

    /** Adds a marker line (e.g. "measuring starts"). */
    public static synchronized void mark(String note) {
        final StringBuilder b = new StringBuilder(String.format(Locale.US, "%.0f", secondsSinceStart()));
        for (int i = 2; i < COLUMNS; i++) {
            b.append(';');
        }
        add(b.append(';').append(note).toString());
    }

    /**
     * One second of the stream.
     * @param hz        the screen's refresh rate now
     * @param rttMs     the latest round-trip estimate (-1 unknown)
     * @param note      optional (e.g. the thermal state when it changed), may be null
     */
    public static synchronized void tick(float hz, int rttMs, String note) {
        final TvDiagnostics.Sample s = TvDiagnostics.tick();
        final TvDiagnostics.Sample p = last;
        last = s;
        if (p == null || s.displayed < p.displayed || s.released < p.released || s.bytes < p.bytes) {
            return; // first tick, or the counters were reset (decoder restart)
        }
        final double secs = (s.atNs - p.atNs) / 1e9;
        if (secs < 0.2) {
            return;
        }
        // Was this second calm? (By SurfaceFlinger's timing when the screen model has the
        // refresh grid; the frame-rendered callback of this TV only echoes the requested times.)
        final double shownFps = (s.displayed - p.displayed) / secs;
        final double sentFps = (s.released - p.released) / secs;
        final double streamFps = TvDiagnostics.expectedFps();
        final double possible = hz > 20f ? Math.min(streamFps, hz) : streamFps;
        final boolean model = s.modelPictures > p.modelPictures;
        final double pictures = (s.modelPictures - p.modelPictures) / secs;
        final double seen = model ? pictures : (shownFps > 0 ? shownFps : sentFps);
        final long stalledMs = model ? (s.modelFrozenNs - p.modelFrozenNs) / 1_000_000L
                : (s.hitchExtraNs - p.hitchExtraNs) / 1_000_000L;
        final boolean calm = possible > 0 && seen >= possible * 0.9 && stalledMs <= CALM_MAX_STALL_MS &&
                s.framesLost == p.framesLost && s.netGaps == p.netGaps && s.keyframeRequests == p.keyframeRequests;
        calmStreak = calm ? calmStreak + 1 : 0;
        if (smoothAfterSeconds < 0 && calmStreak >= CALM_SECONDS_FOR_SMOOTH) {
            smoothAfterSeconds = Math.max(0, (int) Math.round(secondsSinceStart()) - CALM_SECONDS_FOR_SMOOTH);
        }

        final long decN = s.decodeSamples - p.decodeSamples;
        final long scrN = s.screenSamples - p.screenSamples;
        final long modelScrN = s.modelScreenN - p.modelScreenN;
        final long leadN = s.leadSamples - p.leadSamples;
        final long hostN = s.hostSamples - p.hostSamples;
        final long inN = s.inFlightSamples - p.inFlightSamples;
        final long cadN = s.hostCadenceN - p.hostCadenceN;
        final long cadUs = s.hostCadenceSumUs - p.hostCadenceSumUs;
        final String tvMs = modelScrN > 0 ? fmt1((s.modelScreenSumUs - p.modelScreenSumUs) / (double) modelScrN / 1000.0)
                : scrN > 0 ? fmt1((s.screenSumUs - p.screenSumUs) / (double) scrN / 1000.0) : "";
        final String maxTvMs = modelScrN > 0 ? fmt1(s.tickMaxModelScreenUs / 1000.0)
                : scrN > 0 ? fmt1(s.tickMaxScreenUs / 1000.0) : "";
        add(String.format(Locale.US,
                "%.0f;%.0f;%s;%s;%s;%s;%s;%s;%s;%.1f;%.1f;%s;%s;%d;%s;%s;%d;%d;%.1f;%s;%s;%s;%s;%d;%d;%s;%s;%s;%s",
                secondsSinceStart(), hz,
                model ? fmt1(pictures) : "",
                model ? String.valueOf(s.modelFrozen - p.modelFrozen) : "",
                model ? String.valueOf(s.modelFrozenPc - p.modelFrozenPc) : "",
                model ? String.valueOf(s.modelSkipped - p.modelSkipped) : "",
                model ? String.valueOf(s.modelUneven - p.modelUneven) : "",
                model ? String.valueOf(s.tickModelLongestFreezeMs) : "",
                model ? String.valueOf(s.modelReplaced - p.modelReplaced) : "",
                sentFps,
                (s.arrivals - p.arrivals) / secs,
                cadN > 0 && cadUs > 0 ? fmt1(1e6 * cadN / cadUs) : "",
                s.hostFrames > p.hostFrames ? String.valueOf(s.tickMaxPcGapMs) : "",
                s.tickMaxArrivalGapMs,
                s.hostFrames > p.hostFrames ? String.valueOf(s.tickMaxLateMs) : "",
                s.spreadN > p.spreadN ? fmt1((s.spreadSumUs - p.spreadSumUs) / 1000.0 / (s.spreadN - p.spreadN)) : "",
                s.netGaps - p.netGaps,
                s.framesLost - p.framesLost,
                (s.bytes - p.bytes) * 8.0 / secs / 1e6,
                decN > 0 ? fmt1((s.decodeSumUs - p.decodeSumUs) / (double) decN / 1000.0) : "",
                tvMs,
                maxTvMs,
                leadN > 0 ? fmt1((s.leadSumNs - p.leadSumNs) / (double) leadN / 1e6) : "",
                s.keyframeRequests - p.keyframeRequests,
                s.audioGaps - p.audioGaps,
                rttMs >= 0 ? String.valueOf(rttMs) : "",
                hostN > 0 ? fmt1((s.hostSumTenths - p.hostSumTenths) / 10.0 / hostN) : "",
                inN > 0 ? String.format(Locale.US, "%.2f", (s.inFlightSum - p.inFlightSum) / (double) inN) : "",
                note != null ? note : ""));
    }

    private static String fmt1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static double secondsSinceStart() {
        return startNs == 0 ? 0 : (System.nanoTime() - startNs) / 1e9;
    }

    private static void add(String line) {
        if (lines.size() >= MAX_LINES) {
            lines.removeFirst();
            dropped++;
        }
        lines.addLast(line);
    }

    public static synchronized List<String> lines() {
        return new ArrayList<>(lines);
    }

    /** The whole timeline as text, with the column header. */
    public static synchronized String text() {
        final StringBuilder b = new StringBuilder(lines.size() * 90 + 300);
        if (dropped > 0) {
            b.append("(first ").append(dropped).append(" seconds dropped)\n");
        }
        b.append(HEADER).append('\n');
        for (String l : lines) {
            b.append(l).append('\n');
        }
        return b.toString();
    }
}
