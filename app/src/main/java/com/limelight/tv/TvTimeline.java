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

    public static final String HEADER =
            "t s;screen Hz;shown fps;sent fps;arrived fps;hitches;longest frame ms;stalled ms;" +
            "net pauses;longest arrival gap ms;frames lost;Mbps;decode ms;TV adds ms;max TV ms;" +
            "buffer ms;display drops;keyframe req;audio gaps;round trip ms;note";

    public static synchronized void reset() {
        lines.clear();
        last = null;
        dropped = 0;
        startNs = System.nanoTime();
    }

    /** Adds a marker line (e.g. "measuring starts"). */
    public static synchronized void mark(String note) {
        add(String.format(Locale.US, "%.0f;;;;;;;;;;;;;;;;;;;;%s", secondsSinceStart(), note));
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
        final long decN = s.decodeSamples - p.decodeSamples;
        final long scrN = s.screenSamples - p.screenSamples;
        final long leadN = s.leadSamples - p.leadSamples;
        add(String.format(Locale.US,
                "%.0f;%.0f;%.1f;%.1f;%.1f;%d;%d;%d;%d;%d;%d;%.1f;%s;%s;%s;%s;%d;%d;%d;%s;%s",
                secondsSinceStart(), hz,
                (s.displayed - p.displayed) / secs,
                (s.released - p.released) / secs,
                (s.arrivals - p.arrivals) / secs,
                s.displayGaps - p.displayGaps,
                s.tickMaxFrameMs,
                (s.hitchExtraNs - p.hitchExtraNs) / 1_000_000L,
                s.netGaps - p.netGaps,
                s.tickMaxArrivalGapMs,
                s.framesLost - p.framesLost,
                (s.bytes - p.bytes) * 8.0 / secs / 1e6,
                decN > 0 ? fmt1((s.decodeSumUs - p.decodeSumUs) / (double) decN / 1000.0) : "",
                scrN > 0 ? fmt1((s.screenSumUs - p.screenSumUs) / (double) scrN / 1000.0) : "",
                scrN > 0 ? fmt1(s.tickMaxScreenUs / 1000.0) : "",
                leadN > 0 ? fmt1((s.leadSumNs - p.leadSumNs) / (double) leadN / 1e6) : "",
                s.unplannedDrops - p.unplannedDrops,
                s.keyframeRequests - p.keyframeRequests,
                s.audioGaps - p.audioGaps,
                rttMs >= 0 ? String.valueOf(rttMs) : "",
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
