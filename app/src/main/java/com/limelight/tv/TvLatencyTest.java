package com.limelight.tv;

import android.os.SystemClock;

import com.limelight.LimeLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * Artemide TV: button-to-screen latency test. Adapted from the latency tester of moonlight-tcl
 * (pabragin/moonlight-tcl, GPLv3), which measures the same thing on TCL TVs with this chip.
 *
 * On the PC, latency-test.html (from the release page) is open full screen in the stream: black,
 * and covered with a tile pattern while a button is held. With the test on, every press of A, B,
 * X or Y is timed without reading any pixels:
 *   input            controller event time stamp to the app handling it (sent to the PC right then)
 *   PC and network   until the reaction frame is handed to the decoder; the switch to the pattern
 *                    makes that frame several times bigger than the quiet ones before it
 *   decode           until the decoded frame is released to the display
 *   to the screen    until the TV's compositor reports the frame on screen (includes the pacing queue)
 * The panel's own picture processing comes on top and cannot be seen from the app.
 *
 * Nothing is drawn over the video (that alone would add delay on this TV); the result is a line in
 * the stream report. All hooks are cheap no-ops while the test is off.
 */
public final class TvLatencyTest {
    // A press without a reaction frame within this time counts as missed
    private static final long TIMEOUT_NS = 2_000_000_000L;
    // After the reaction frame: how long to wait for its release / on-screen report
    private static final long DISPLAY_WAIT_NS = 1_000_000_000L;
    // The reaction cannot arrive sooner; frames before that were already on their way
    private static final long MIN_REACTION_NS = 15_000_000L;
    // Reaction frame: this many times the quiet frames' average size, and at least this much bigger
    private static final double SIZE_FACTOR = 2.0;
    private static final int SIZE_MIN_DELTA_BYTES = 8000;
    // A host that slows down for a static picture speeds up again when it changes
    private static final double INTERVAL_FACTOR = 0.65;
    private static final double STATIC_INTERVAL_MIN_MS = 25;
    private static final int MAX_SAMPLES = 500;

    private static volatile TvLatencyTest active;   // the running test (null while off)
    private static volatile TvLatencyTest last;     // the last stream's test, for the report

    private static final class Press {
        final long pressNs;      // app handled the button (and sent it)
        final long inputMs;      // controller event time stamp to the app
        long reactionPtsUs = -1;
        long arrivalNs = -1;
        long releaseNs = -1;
        long displayNs = -1;

        Press(long pressNs, long inputMs) {
            this.pressNs = pressNs;
            this.inputMs = inputMs;
        }
    }

    private final Object lock = new Object();
    private Press current;
    private double avgBytes = -1;
    private double avgIntervalMs = -1;
    private long lastArrivalNs;
    private int presses;
    private int missed;
    private int withoutScreenTime;
    // {total ms (input included), input ms, PC+network ms, decode ms (-1), to the screen ms (-1)}
    private final ArrayList<long[]> samples = new ArrayList<>();

    private TvLatencyTest() {}

    /** Starts a new test for the stream that is about to begin. */
    public static void begin() {
        final TvLatencyTest t = new TvLatencyTest();
        last = t;
        active = t;
        LimeLog.info("Artemide TV latency test: on (press A, B, X or Y on latency-test.html)");
    }

    /** Ends the test at the end of the stream; the results stay for the report. */
    public static void end() {
        final TvLatencyTest t = active;
        active = null;
        if (t != null) {
            synchronized (t.lock) {
                if (t.current != null) {
                    t.finish(t.current);
                    t.current = null;
                }
            }
        }
    }

    /** Forgets the last result (a stream without the test). */
    public static void clear() {
        active = null;
        last = null;
    }

    public static boolean isEnabled() {
        return active != null;
    }

    /** Input path: a face button went down. eventTimeMs is the event's uptime time stamp. */
    public static void onButtonDown(long eventTimeMs) {
        final TvLatencyTest t = active;
        if (t == null) {
            return;
        }
        final long now = System.nanoTime();
        final long inputMs = Math.max(0, Math.min(1000, SystemClock.uptimeMillis() - eventTimeMs));
        synchronized (t.lock) {
            if (t.current != null) {
                t.finish(t.current);
            }
            t.current = new Press(now, inputMs);
            t.presses++;
        }
    }

    /** Decoder input: a frame of sizeBytes with this presentation time goes to the decoder. */
    public static void onFrameReceived(int sizeBytes, long ptsUs) {
        final TvLatencyTest t = active;
        if (t == null) {
            return;
        }
        final long now = System.nanoTime();
        synchronized (t.lock) {
            final double intervalMs = t.lastArrivalNs != 0 ? (now - t.lastArrivalNs) / 1e6 : -1;
            t.lastArrivalNs = now;

            final Press p = t.current;
            if (p != null) {
                if (p.reactionPtsUs < 0) {
                    final long sincePress = now - p.pressNs;
                    if (sincePress > TIMEOUT_NS) {
                        t.finish(p);
                        t.current = null;
                    } else if (sincePress >= MIN_REACTION_NS) {
                        final boolean sizeJump = t.avgBytes >= 0 &&
                                sizeBytes >= t.avgBytes * SIZE_FACTOR &&
                                sizeBytes >= t.avgBytes + SIZE_MIN_DELTA_BYTES;
                        final boolean rateJump = t.avgIntervalMs >= STATIC_INTERVAL_MIN_MS &&
                                intervalMs > 0 && intervalMs <= t.avgIntervalMs * INTERVAL_FACTOR;
                        if (sizeJump || rateJump) {
                            p.reactionPtsUs = ptsUs;
                            p.arrivalNs = now;
                            return; // the reaction itself is not a quiet frame
                        }
                    }
                } else if (now - p.arrivalNs > DISPLAY_WAIT_NS) {
                    // No on-screen report came for the reaction frame: count it up to its release
                    t.finish(p);
                    t.current = null;
                }
            }

            // Learn what the quiet frames look like
            t.avgBytes = t.avgBytes < 0 ? sizeBytes : t.avgBytes * 0.9 + sizeBytes * 0.1;
            if (intervalMs > 0 && intervalMs < 1000) {
                t.avgIntervalMs = t.avgIntervalMs < 0 ? intervalMs : t.avgIntervalMs * 0.9 + intervalMs * 0.1;
            }
        }
    }

    /** Renderer: the decoded frame with this presentation time is released to the display. */
    public static void onFrameReleased(long ptsUs) {
        final TvLatencyTest t = active;
        if (t == null) {
            return;
        }
        final long now = System.nanoTime();
        synchronized (t.lock) {
            final Press p = t.current;
            if (p != null && p.reactionPtsUs >= 0 && p.releaseNs < 0 && ptsUs >= p.reactionPtsUs) {
                p.releaseNs = now;
            }
        }
    }

    // The screen model (SurfaceFlinger's timing) gives the refresh each frame is shown on; when it
    // runs, the frame-rendered callback is not used (on this TV it only echoes the requested time)
    private static volatile boolean modelTimes;

    /** Screen model: the frame with this presentation time is shown on the refresh at slotNs. */
    public static void onFrameShownModel(long ptsUs, long slotNs) {
        if (!modelTimes) {
            modelTimes = true;
        }
        complete(ptsUs, slotNs);
    }

    /** Frame-rendered callback: the frame with this presentation time was on screen at displayNs. */
    public static void onFrameDisplayed(long ptsUs, long displayNs) {
        if (modelTimes) {
            return;
        }
        complete(ptsUs, displayNs);
    }

    private static void complete(long ptsUs, long displayNs) {
        final TvLatencyTest t = active;
        if (t == null) {
            return;
        }
        synchronized (t.lock) {
            final Press p = t.current;
            if (p != null && p.reactionPtsUs >= 0 && ptsUs >= p.reactionPtsUs) {
                p.displayNs = displayNs;
                t.finish(p);
                t.current = null;
            }
        }
    }

    // Called with lock held
    private void finish(Press p) {
        if (p.arrivalNs < 0) {
            missed++;
            LimeLog.info("Artemide TV latency test: no reaction frame within " + (TIMEOUT_NS / 1_000_000L) + " ms");
            return;
        }
        final long endNs = p.displayNs > 0 ? p.displayNs : p.releaseNs;
        if (endNs <= 0) {
            missed++;
            LimeLog.info("Artemide TV latency test: reaction frame received but never shown");
            return;
        }
        if (p.displayNs <= 0) {
            withoutScreenTime++;
        }
        final long pcNetMs = Math.round((p.arrivalNs - p.pressNs) / 1e6);
        final long decodeMs = p.releaseNs > 0 ? Math.round((p.releaseNs - p.arrivalNs) / 1e6) : -1;
        final long screenMs = (p.displayNs > 0 && p.releaseNs > 0)
                ? Math.round((p.displayNs - p.releaseNs) / 1e6) : -1;
        final long totalMs = p.inputMs + Math.round((endNs - p.pressNs) / 1e6);
        if (samples.size() >= MAX_SAMPLES) {
            samples.remove(0);
        }
        samples.add(new long[] { totalMs, p.inputMs, pcNetMs, decodeMs, screenMs });
        LimeLog.info(String.format(Locale.US,
                "Artemide TV latency test: button to screen %d ms (input %d, PC+network %d, decode %d, to the screen %d)",
                totalMs, p.inputMs, pcNetMs, decodeMs, screenMs));
    }

    /** One report line for the last stream, or null if the test was not on. */
    public static String reportLine() {
        final TvLatencyTest t = last;
        if (t == null) {
            return null;
        }
        synchronized (t.lock) {
            if (t.presses == 0) {
                return "Latency test: on, but no A/B/X/Y press was seen";
            }
            final int n = t.samples.size();
            if (n == 0) {
                return String.format(Locale.US,
                        "Latency test: %d presses, no reaction found (is latency-test.html open full screen on the PC?)",
                        t.presses);
            }
            final long[] totals = new long[n];
            long sumInput = 0, sumPcNet = 0, sumDecode = 0, sumScreen = 0;
            int nDecode = 0, nScreen = 0;
            for (int i = 0; i < n; i++) {
                final long[] s = t.samples.get(i);
                totals[i] = s[0];
                sumInput += s[1];
                sumPcNet += s[2];
                if (s[3] >= 0) { sumDecode += s[3]; nDecode++; }
                if (s[4] >= 0) { sumScreen += s[4]; nScreen++; }
            }
            Arrays.sort(totals);
            long sum = 0;
            for (long v : totals) {
                sum += v;
            }
            final StringBuilder b = new StringBuilder(160);
            b.append(String.format(Locale.US,
                    "Button to screen: %d ms average (middle %d, %d–%d ms, %d of %d presses): input %d · PC and network %d",
                    Math.round(sum / (double) n), totals[n / 2], totals[0], totals[n - 1], n, t.presses,
                    Math.round(sumInput / (double) n), Math.round(sumPcNet / (double) n)));
            if (nDecode > 0) {
                b.append(" · decode ").append(Math.round(sumDecode / (double) nDecode));
            }
            if (nScreen > 0) {
                b.append(" · to the screen ").append(Math.round(sumScreen / (double) nScreen));
            }
            b.append(" ms");
            if (modelTimes) {
                b.append(" (screen times: the refresh SurfaceFlinger shows the frame on, from its timing)");
            }
            if (t.withoutScreenTime > 0) {
                b.append(String.format(Locale.US, " (%d counted only to the release)", t.withoutScreenTime));
            }
            return b.toString();
        }
    }
}
