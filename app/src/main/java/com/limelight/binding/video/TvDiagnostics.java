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
    private static volatile int bufferLimitFrames;
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
    private static volatile String decoderPreset = "";
    private static final AtomicLong inputWaits = new AtomicLong();
    private static volatile long maxInputWaitNs;
    private static final AtomicLong keyframeRequests = new AtomicLong();

    // --- Audio ---
    private static volatile String audioTrackMode = "?";
    private static volatile int audioBufferMs;
    private static volatile int audioUnderruns;
    private static final AtomicLong audioSkipped = new AtomicLong();
    private static final AtomicLong audioReprimes = new AtomicLong();
    private static final AtomicLong audioTrimmed = new AtomicLong();
    private static volatile int audioLevelMs = -1;
    private static volatile int audioTargetMs = -1;
    private static volatile int audioMinLevelMs = -1;

    // --- Overlay rate computation (UI thread only) ---
    private static long snapNs;
    private static long snapReleased, snapDisplayed, snapGaps, snapLate, snapTrims;
    private static long snapLeadSum, snapLeadSamples, snapInputWaits, snapKeyframes, snapSkipped;
    private static long snapPresentSamples, snapPresentOffsetSum, snapPresentSlips;
    private static long snapModelPictures, snapModelFrozen, snapModelFrozenPc, snapModelUneven, snapModelSkipped;
    private static long snapModelScreenSum, snapModelScreenN, snapPcGaps, snapDeliveryStalls;
    private static int snapUnderruns;

    // --- Collection switch ---
    // Off: every per-frame hook returns at once (no counters, no clock reads). The cost when on
    // is a few atomic counters and clock reads per frame, well under 0.1 % of one core.
    private static volatile boolean collecting = true;

    public static void setCollecting(boolean on) {
        collecting = on;
    }

    public static boolean isCollecting() {
        return collecting;
    }

    // --- Per-frame figures for the auto-tune (session counters; windows are differences) ---
    private static final AtomicLong frameBytes = new AtomicLong();
    private static final AtomicLong decodeSumUs = new AtomicLong();
    private static final AtomicLong decodeSamples = new AtomicLong();
    private static final AtomicLong screenSumUs = new AtomicLong();
    private static final AtomicLong screenSamples = new AtomicLong();
    // Longest values since beginWindow() (the session maxima above stay untouched)
    private static volatile long windowMaxDisplayIntervalNs;
    private static volatile long windowMaxNetGapMs;
    private static volatile long windowMaxScreenUs;
    private static volatile long tickMaxDisplayIntervalNs;
    private static volatile long tickMaxArrivalGapMs;
    private static volatile long tickMaxScreenUs;
    // Time the picture stood still beyond its normal cadence (sum over all hitches)
    private static final AtomicLong hitchExtraNs = new AtomicLong();
    // PC side: capture to encoded, per frame, as the PC reports it (0.1 ms units, 0 = not sent)
    private static final AtomicLong hostSumTenths = new AtomicLong();
    private static final AtomicLong hostSamples = new AtomicLong();
    private static volatile long windowMaxHostTenths;
    private static volatile long sessionMaxHostTenths;
    // Frames still inside the decoder whenever one comes out (0 = it hands each frame back
    // before the next arrives, which is what a decoder in low-latency mode does)
    private static final AtomicLong inFlightSum = new AtomicLong();
    private static final AtomicLong inFlightSamples = new AtomicLong();
    private static volatile long windowMaxInFlight;
    private static volatile long sessionMaxInFlight;

    /** One frame's processing time on the PC, capture to encoded (0.1 ms units; 0 = unknown). */
    public static void onHostLatency(int tenthsMs) {
        if (!collecting || tenthsMs <= 0 || tenthsMs > 10_000) {
            return;
        }
        hostSumTenths.addAndGet(tenthsMs);
        hostSamples.incrementAndGet();
        if (tenthsMs > windowMaxHostTenths) {
            windowMaxHostTenths = tenthsMs;
        }
        if (tenthsMs > sessionMaxHostTenths) {
            sessionMaxHostTenths = tenthsMs;
        }
    }

    /** A decoded frame came out; this many frames were still inside the decoder. */
    static void onDecoderInFlight(long frames) {
        if (!collecting || frames < 0 || frames > 64) {
            return;
        }
        inFlightSum.addAndGet(frames);
        inFlightSamples.incrementAndGet();
        if (frames > windowMaxInFlight) {
            windowMaxInFlight = frames;
        }
        if (frames > sessionMaxInFlight) {
            sessionMaxInFlight = frames;
        }
    }
    // --- The PC's rhythm: capture times sent with every frame (see TvFrameTimes) ---
    // Receive thread only, except the counters
    private static long lastHostUs = -1, lastHostReceiveUs, lastHostArrivalUs;
    private static long minTransitUs;
    private static int hostChecks, hostMismatches;
    private static volatile boolean hostTimesSynthetic;
    private static volatile long pcPeriodEmaNs;       // the PC's frame interval (its clock), gaps left out
    private static final AtomicLong hostFrames = new AtomicLong();
    private static final AtomicLong hostCadenceSumUs = new AtomicLong();
    private static final AtomicLong hostCadenceN = new AtomicLong();
    // The PC captured nothing for 40 ms+ (frames missing at the source)
    private static final AtomicLong pcGaps = new AtomicLong();
    private static final AtomicLong pcGapExtraMs = new AtomicLong();
    private static volatile long maxPcGapMs, windowMaxPcGapMs, tickMaxPcGapMs;
    // Captured in time, but arrived 40 ms+ after the frame before (encoder, PC network stack, Wi-Fi)
    private static final AtomicLong deliveryStalls = new AtomicLong();
    private static volatile long maxDeliveryStallMs, windowMaxDeliveryStallMs;
    // Frames that arrived later than the PC's rhythm (against the fastest delivery seen lately)
    private static final AtomicLong late4 = new AtomicLong(), late8 = new AtomicLong(), late16 = new AtomicLong();
    private static volatile long tickMaxLateMs;

    /**
     * Receive thread, once per frame: when it arrived (CLOCK_MONOTONIC µs; receive = first
     * packet, arrival = complete) and when the PC captured it (PC clock µs).
     */
    public static void onHostFrame(long receiveUs, long arrivalUs, long hostUs) {
        if (!collecting || hostUs < 0) {
            return;
        }
        final long lastH = lastHostUs, lastR = lastHostReceiveUs, lastA = lastHostArrivalUs;
        lastHostUs = hostUs;
        lastHostReceiveUs = receiveUs;
        lastHostArrivalUs = arrivalUs;
        final long transit = arrivalUs - hostUs;
        if (lastH < 0) {
            minTransitUs = transit;
            return;
        }
        final long dH = hostUs - lastH;
        if (dH <= 0 || dH > 2_000_000L) {
            // New stream or the PC's clock started over
            minTransitUs = transit;
            return;
        }
        // Some PCs send no capture times; the streaming core then makes them up from the
        // receive time, and they would only repeat the arrival pattern
        if (hostChecks < 600) {
            hostChecks++;
            if (Math.abs(dH - (receiveUs - lastR)) > 100) {
                hostMismatches++;
            }
            if (hostChecks == 600) {
                hostTimesSynthetic = hostMismatches == 0;
            }
        }
        if (hostTimesSynthetic) {
            return;
        }
        hostFrames.incrementAndGet();
        final long periodUs = Math.max(1000L, expectedFramePeriodNs / 1000L);
        final long gapUs = Math.max(40_000L, periodUs * 5 / 2);
        if (dH <= gapUs) {
            hostCadenceSumUs.addAndGet(dH);
            hostCadenceN.incrementAndGet();
            final long ema = pcPeriodEmaNs;
            final long dNs = dH * 1000L;
            pcPeriodEmaNs = ema == 0 ? dNs : ema + (dNs - ema) / 32;
        } else {
            pcGaps.incrementAndGet();
            pcGapExtraMs.addAndGet(Math.max(0L, (dH - periodUs) / 1000L));
            final long ms = dH / 1000L;
            if (ms > maxPcGapMs) {
                maxPcGapMs = ms;
            }
            if (ms > windowMaxPcGapMs) {
                windowMaxPcGapMs = ms;
            }
            if (ms > tickMaxPcGapMs) {
                tickMaxPcGapMs = ms;
            }
        }
        final long dA = arrivalUs - lastA;
        if (dA > gapUs && dA < 2_000_000L && dH < dA - Math.max(10_000L, periodUs)) {
            deliveryStalls.incrementAndGet();
            final long ms = dA / 1000L;
            if (ms > maxDeliveryStallMs) {
                maxDeliveryStallMs = ms;
            }
            if (ms > windowMaxDeliveryStallMs) {
                windowMaxDeliveryStallMs = ms;
            }
        }
        // How late against the PC's rhythm. The fastest delivery seen lately counts as on time;
        // it may rise slowly (100 ppm), since the PC's and the TV's clocks drift apart a little.
        long min = minTransitUs + dH / 10_000L;
        if (transit < min) {
            min = transit;
        }
        minTransitUs = min;
        final long late = transit - min;
        if (late >= 4000) {
            late4.incrementAndGet();
            if (late >= 8000) {
                late8.incrementAndGet();
                if (late >= 16000) {
                    late16.incrementAndGet();
                }
            }
        }
        if (late / 1000 > tickMaxLateMs) {
            tickMaxLateMs = late / 1000;
        }
    }

    /** The PC's frame interval (its clock; the stream's frame interval until it is known). */
    public static long pcFramePeriodNs() {
        final long p = pcPeriodEmaNs;
        return p > 0 ? p : expectedFramePeriodNs;
    }

    /** False when the PC sends no capture times (then the PC figures are not available). */
    public static boolean hostTimesAvailable() {
        return !hostTimesSynthetic;
    }

    // --- What reaches the screen, by SurfaceFlinger's timing (TvScreenModel) ---
    private static final AtomicLong modelPictures = new AtomicLong();     // refreshes with a new picture
    private static final AtomicLong modelRefreshes = new AtomicLong();
    private static final AtomicLong modelFrozen = new AtomicLong();
    private static final AtomicLong modelFrozenNs = new AtomicLong();
    private static final AtomicLong modelFrozenPc = new AtomicLong();
    private static final AtomicLong modelFrozenPcNs = new AtomicLong();
    private static final AtomicLong modelSkippedNs = new AtomicLong();
    private static final AtomicLong modelSkipped = new AtomicLong();
    private static final AtomicLong modelUneven = new AtomicLong();
    private static final AtomicLong modelUnevenNs = new AtomicLong();
    private static final AtomicLong modelReplaced = new AtomicLong();
    private static final AtomicLong modelScreenSumUs = new AtomicLong();
    private static final AtomicLong modelScreenN = new AtomicLong();
    private static volatile long modelLongestFreezeNs, windowModelLongestFreezeNs, tickModelLongestFreezeNs;
    private static volatile long windowMaxModelScreenUs, tickMaxModelScreenUs;
    // Frame-rendered callback: does it only repeat the requested time?
    private static final AtomicLong renderEcho = new AtomicLong();
    private static final AtomicLong renderChecked = new AtomicLong();
    private static volatile long displayDeadlineNs, displayAppVsyncOffsetNs;
    // The screen's refresh rate, measured from vsync timestamps over a long time
    private static volatile double measuredRefreshHz;
    private static volatile int measuredRefreshSecs;

    static void setDisplayTiming(long presentationDeadlineNs, long appVsyncOffsetNs) {
        displayDeadlineNs = presentationDeadlineNs;
        displayAppVsyncOffsetNs = appVsyncOffsetNs;
    }

    static void setMeasuredRefresh(double hz, int seconds) {
        measuredRefreshHz = hz;
        measuredRefreshSecs = seconds;
    }

    /** The screen's refresh rate measured over a long time (0 = not yet). */
    public static double measuredRefreshHz() {
        return measuredRefreshHz;
    }

    static void onModelReplaced() {
        modelReplaced.incrementAndGet();
    }

    /** A shown frame: arrival to the refresh it is shown on (µs). */
    static void onModelScreen(long us) {
        if (us > 0 && us < 1_000_000L) {
            modelScreenSumUs.addAndGet(us);
            modelScreenN.incrementAndGet();
            if (us > windowMaxModelScreenUs) {
                windowMaxModelScreenUs = us;
            }
            if (us > tickMaxModelScreenUs) {
                tickMaxModelScreenUs = us;
            }
        }
    }

    /** A new picture after `refreshes` refreshes, see TvScreenModel. */
    static void onModelStep(long refreshes, long frozen, long frozenPc, long skippedNs, long unevenNs, long periodNs) {
        modelPictures.incrementAndGet();
        modelRefreshes.addAndGet(refreshes);
        if (frozen > 0) {
            modelFrozen.addAndGet(frozen);
            modelFrozenNs.addAndGet(frozen * periodNs);
            final long stood = (frozen + 1) * periodNs;
            if (stood > modelLongestFreezeNs) {
                modelLongestFreezeNs = stood;
            }
            if (stood > windowModelLongestFreezeNs) {
                windowModelLongestFreezeNs = stood;
            }
            if (stood > tickModelLongestFreezeNs) {
                tickModelLongestFreezeNs = stood;
            }
        }
        if (frozenPc > 0) {
            modelFrozenPc.addAndGet(frozenPc);
            modelFrozenPcNs.addAndGet(frozenPc * periodNs);
        }
        if (skippedNs > 0) {
            modelSkippedNs.addAndGet(skippedNs);
            modelSkipped.addAndGet(Math.max(1L, Math.round(skippedNs / (double) Math.max(1L, pcFramePeriodNs()))));
        }
        if (unevenNs > 0) {
            modelUneven.incrementAndGet();
            modelUnevenNs.addAndGet(unevenNs);
        }
    }

    static void onRenderEchoCheck(boolean echo) {
        renderChecked.incrementAndGet();
        if (echo) {
            renderEcho.incrementAndGet();
        }
    }

    // The screen's refresh period (fallback when the pacer has no vsync grid)
    private static volatile long displayPeriodNs;
    private static volatile String decoderName = "?";

    /** The stream's frame rate (as the pacer was told). */
    public static double expectedFps() {
        final long p = expectedFramePeriodNs;
        return p > 0 ? 1e9 / p : 60.0;
    }

    /** The screen's refresh rate, from the display mode (Game, at start and once a second). */
    public static void setDisplayRefreshHz(float hz) {
        if (hz > 20f && hz < 1000f) {
            displayPeriodNs = (long) (1e9 / hz);
        }
    }

    /** The decoder that is running, e.g. "c2.mtk.hevc.decoder (video/hevc)". */
    public static void setDecoderName(String name) {
        decoderName = name != null ? name : "?";
    }

    public static String decoderName() {
        return decoderName;
    }

    /** Picture data of one frame went to the decoder. */
    public static void onFrameBytes(int bytes) {
        if (collecting && bytes > 0) {
            frameBytes.addAndGet(bytes);
        }
    }

    /** Decoder time of one frame (queued to output ready). */
    public static void onDecodeTime(long decodeUs) {
        if (collecting && decodeUs >= 0 && decodeUs < 1_000_000L) {
            decodeSumUs.addAndGet(decodeUs);
            decodeSamples.incrementAndGet();
        }
    }

    /**
     * A frame reached the screen: ptsUs is its arrival time (CLOCK_MONOTONIC microseconds, see
     * callbacks.c), renderNs when the display showed it. The difference is everything the TV adds:
     * decoding, the pacing buffer and the compositor.
     */
    static void onFrameOnScreen(long ptsUs, long renderNs) {
        if (!collecting || ptsUs <= 0) {
            return;
        }
        final long us = renderNs / 1000L - ptsUs;
        if (us > 0 && us < 1_000_000L) {
            screenSumUs.addAndGet(us);
            screenSamples.incrementAndGet();
            if (us > windowMaxScreenUs) {
                windowMaxScreenUs = us;
            }
            if (us > tickMaxScreenUs) {
                tickMaxScreenUs = us;
            }
        }
    }

    /** Cumulative counters at one moment; two of them give the figures for the time between. */
    public static final class Sample {
        public long atNs;
        public long released, displayed, displayGaps, netGaps, framesLost, lossEvents;
        public long bytes, decodeSumUs, decodeSamples, screenSumUs, screenSamples;
        public long rttSumMs, rttSamples, leadSumNs, leadSamples, presentSamples, presentSlips;
        public long unplannedDrops, keyframeRequests, audioGaps, inputWaits;
        public long maxDisplayIntervalNs, maxNetGapMs, maxScreenUs; // since beginWindow()
        public long arrivals, hitchExtraNs;
        public long hostSumTenths, hostSamples, maxHostTenths;          // max: since beginWindow()
        public long inFlightSum, inFlightSamples, maxInFlight;          // max: since beginWindow()
        // The PC's rhythm (capture times)
        public long hostFrames, hostCadenceSumUs, hostCadenceN, pcGaps, pcGapExtraMs, deliveryStalls;
        public long late4, late8, late16, maxPcGapMs, maxDeliveryStallMs; // max: since beginWindow()
        // The screen by SurfaceFlinger's timing
        public long modelPictures, modelRefreshes, modelFrozen, modelFrozenNs, modelFrozenPc, modelFrozenPcNs;
        public long modelSkipped, modelSkippedNs, modelUneven, modelUnevenNs, modelReplaced, modelScreenSumUs, modelScreenN;
        public long maxModelScreenUs, modelLongestFreezeNs;              // since beginWindow()
        // Longest values since the previous tick() (timeline)
        public long tickMaxFrameMs, tickMaxArrivalGapMs, tickMaxScreenUs;
        public long tickMaxPcGapMs, tickMaxLateMs, tickMaxModelScreenUs, tickModelLongestFreezeMs;
    }

    public static Sample sample() {
        final Sample s = new Sample();
        s.atNs = System.nanoTime();
        s.released = framesReleased.get();
        s.displayed = framesDisplayed.get();
        s.displayGaps = displayGaps.get();
        s.netGaps = videoNetGaps.get();
        s.framesLost = framesLostTotal.get();
        s.lossEvents = frameLossEvents.get();
        s.bytes = frameBytes.get();
        s.decodeSumUs = decodeSumUs.get();
        s.decodeSamples = decodeSamples.get();
        s.screenSumUs = screenSumUs.get();
        s.screenSamples = screenSamples.get();
        s.rttSumMs = rttSumMs.get();
        s.rttSamples = rttSamples.get();
        s.leadSumNs = leadSumNs.get();
        s.leadSamples = leadSamples.get();
        s.presentSamples = presentSamples.get();
        s.presentSlips = presentSlips.get();
        s.unplannedDrops = unplannedDrops.get();
        s.keyframeRequests = keyframeRequests.get();
        s.audioGaps = audioArrivalGaps.get();
        s.inputWaits = inputWaits.get();
        s.maxDisplayIntervalNs = windowMaxDisplayIntervalNs;
        s.maxNetGapMs = windowMaxNetGapMs;
        s.maxScreenUs = windowMaxScreenUs;
        s.arrivals = videoArrivals.get();
        s.hitchExtraNs = hitchExtraNs.get();
        s.hostSumTenths = hostSumTenths.get();
        s.hostSamples = hostSamples.get();
        s.maxHostTenths = windowMaxHostTenths;
        s.inFlightSum = inFlightSum.get();
        s.inFlightSamples = inFlightSamples.get();
        s.maxInFlight = windowMaxInFlight;
        s.hostFrames = hostFrames.get();
        s.hostCadenceSumUs = hostCadenceSumUs.get();
        s.hostCadenceN = hostCadenceN.get();
        s.pcGaps = pcGaps.get();
        s.pcGapExtraMs = pcGapExtraMs.get();
        s.deliveryStalls = deliveryStalls.get();
        s.late4 = late4.get();
        s.late8 = late8.get();
        s.late16 = late16.get();
        s.maxPcGapMs = windowMaxPcGapMs;
        s.maxDeliveryStallMs = windowMaxDeliveryStallMs;
        s.modelPictures = modelPictures.get();
        s.modelRefreshes = modelRefreshes.get();
        s.modelFrozen = modelFrozen.get();
        s.modelFrozenNs = modelFrozenNs.get();
        s.modelFrozenPc = modelFrozenPc.get();
        s.modelFrozenPcNs = modelFrozenPcNs.get();
        s.modelSkipped = modelSkipped.get();
        s.modelSkippedNs = modelSkippedNs.get();
        s.modelUneven = modelUneven.get();
        s.modelUnevenNs = modelUnevenNs.get();
        s.modelReplaced = modelReplaced.get();
        s.modelScreenSumUs = modelScreenSumUs.get();
        s.modelScreenN = modelScreenN.get();
        s.maxModelScreenUs = windowMaxModelScreenUs;
        s.modelLongestFreezeNs = windowModelLongestFreezeNs;
        return s;
    }

    /** sample() plus the longest values since the last tick, which start again from zero. */
    public static Sample tick() {
        final Sample s = sample();
        s.tickMaxFrameMs = tickMaxDisplayIntervalNs / 1_000_000L;
        s.tickMaxArrivalGapMs = tickMaxArrivalGapMs;
        s.tickMaxScreenUs = tickMaxScreenUs;
        s.tickMaxPcGapMs = tickMaxPcGapMs;
        s.tickMaxLateMs = tickMaxLateMs;
        s.tickMaxModelScreenUs = tickMaxModelScreenUs;
        s.tickModelLongestFreezeMs = tickModelLongestFreezeNs / 1_000_000L;
        tickMaxDisplayIntervalNs = 0;
        tickMaxArrivalGapMs = 0;
        tickMaxScreenUs = 0;
        tickMaxPcGapMs = 0;
        tickMaxLateMs = 0;
        tickMaxModelScreenUs = 0;
        tickModelLongestFreezeNs = 0;
        return s;
    }

    /** Starts a measuring window: the window maxima start from zero. */
    public static void beginWindow() {
        windowMaxDisplayIntervalNs = 0;
        windowMaxNetGapMs = 0;
        windowMaxScreenUs = 0;
        windowMaxHostTenths = 0;
        windowMaxInFlight = 0;
        windowMaxPcGapMs = 0;
        windowMaxDeliveryStallMs = 0;
        windowMaxModelScreenUs = 0;
        windowModelLongestFreezeNs = 0;
    }

    // --- Network loss and round trip (session, for the stream report) ---
    private static final AtomicLong framesLostTotal = new AtomicLong();
    private static final AtomicLong frameLossEvents = new AtomicLong();
    private static final AtomicLong rttSumMs = new AtomicLong();
    private static final AtomicLong rttSamples = new AtomicLong();
    private static volatile int maxRttMs;
    private static volatile int lastRttVarianceMs;

    /** Frames that never arrived complete (network loss FEC could not repair). */
    public static void onFramesLost(int frames) {
        if (collecting && frames > 0) {
            framesLostTotal.addAndGet(frames);
            frameLossEvents.incrementAndGet();
        }
    }

    /** @param rttInfo MoonBridge.getEstimatedRttInfo(): rtt << 32 | variance, -1 = unknown */
    public static void addRttSample(long rttInfo) {
        if (!collecting || rttInfo < 0) {
            return;
        }
        final int rtt = (int) (rttInfo >> 32);
        if (rtt <= 0 || rtt > 5000) {
            return;
        }
        rttSumMs.addAndGet(rtt);
        rttSamples.incrementAndGet();
        if (rtt > maxRttMs) {
            maxRttMs = rtt;
        }
        lastRttVarianceMs = (int) (rttInfo & 0xFFFFFFFFL);
    }

    // --- Session (for the stream report) ---
    private static volatile long sessionStartNs;
    private static long gcCountAtStart = -1, gcTimeAtStart, blockingGcCountAtStart, blockingGcTimeAtStart;
    private static volatile String adpfState = "off";

    // TV memory at stream start and the thermal state (0 none ... 3 severe, see PowerManager)
    private static volatile String memoryInfo = "";
    private static volatile int thermalNow = -1;
    private static volatile int thermalMax = -1;

    public static void setMemoryInfo(String info) {
        memoryInfo = info != null ? info : "";
    }

    public static void onThermalStatus(int status) {
        thermalNow = status;
        if (status > thermalMax) {
            thermalMax = status;
        }
    }

    private static String thermalName(int s) {
        switch (s) {
            case 0: return "normal";
            case 1: return "warm";
            case 2: return "hot (may slow down)";
            case 3: return "very hot (slowing down)";
            case 4: return "critical";
            case 5: return "emergency";
            case 6: return "shutting down";
            default: return "?";
        }
    }

    public static void setAdpfState(String state) {
        adpfState = (state != null) ? state : "?";
    }

    public static void resetVideo() {
        sessionStartNs = System.nanoTime();
        gcCountAtStart = gcStat("art.gc.gc-count");
        gcTimeAtStart = gcStat("art.gc.gc-time");
        blockingGcCountAtStart = gcStat("art.gc.blocking-gc-count");
        blockingGcTimeAtStart = gcStat("art.gc.blocking-gc-time");
        adpfState = "off";
        thermalMax = thermalNow;
        framesLostTotal.set(0);
        frameLossEvents.set(0);
        frameBytes.set(0);
        decodeSumUs.set(0);
        decodeSamples.set(0);
        screenSumUs.set(0);
        screenSamples.set(0);
        windowMaxDisplayIntervalNs = 0;
        windowMaxNetGapMs = 0;
        windowMaxScreenUs = 0;
        tickMaxDisplayIntervalNs = 0;
        tickMaxArrivalGapMs = 0;
        tickMaxScreenUs = 0;
        hitchExtraNs.set(0);
        hostSumTenths.set(0);
        hostSamples.set(0);
        windowMaxHostTenths = 0;
        sessionMaxHostTenths = 0;
        inFlightSum.set(0);
        inFlightSamples.set(0);
        windowMaxInFlight = 0;
        sessionMaxInFlight = 0;
        lastHostUs = -1;
        hostChecks = 0;
        hostMismatches = 0;
        hostTimesSynthetic = false;
        pcPeriodEmaNs = 0;
        hostFrames.set(0);
        hostCadenceSumUs.set(0);
        hostCadenceN.set(0);
        pcGaps.set(0);
        pcGapExtraMs.set(0);
        maxPcGapMs = 0;
        windowMaxPcGapMs = 0;
        tickMaxPcGapMs = 0;
        deliveryStalls.set(0);
        maxDeliveryStallMs = 0;
        windowMaxDeliveryStallMs = 0;
        late4.set(0);
        late8.set(0);
        late16.set(0);
        tickMaxLateMs = 0;
        modelPictures.set(0);
        modelRefreshes.set(0);
        modelFrozen.set(0);
        modelFrozenNs.set(0);
        modelFrozenPc.set(0);
        modelFrozenPcNs.set(0);
        modelSkipped.set(0);
        modelSkippedNs.set(0);
        modelUneven.set(0);
        modelUnevenNs.set(0);
        modelReplaced.set(0);
        modelScreenSumUs.set(0);
        modelScreenN.set(0);
        modelLongestFreezeNs = 0;
        windowModelLongestFreezeNs = 0;
        tickModelLongestFreezeNs = 0;
        windowMaxModelScreenUs = 0;
        tickMaxModelScreenUs = 0;
        renderEcho.set(0);
        renderChecked.set(0);
        rttSumMs.set(0);
        rttSamples.set(0);
        maxRttMs = 0;
        lastRttVarianceMs = 0;
        framesReleased.set(0);
        framesDisplayed.set(0);
        displayGaps.set(0);
        lastDisplayNs = 0;
        maxDisplayIntervalNs = 0;
        lastReleaseNs = 0;
        releaseIntervalEmaNs = 0;
        lastVideoReceiveMs = 0;
        lastGapAtMs = 0;
        gapPeriodEmaMs = 0;
        maxSubmitNs = 0;
        slowSubmits.set(0);
        snapSlowSubmits = 0;
        gapsAfterOverlay.set(0);
        snapGcCount = -1;
        videoArrivals.set(0); videoBunched.set(0); videoNetGaps.set(0); maxVideoNetGapMs = 0;
        inAppSumMs.set(0); inAppSamples.set(0); maxInAppMs = 0;
        lastAudioArrivalNs = 0; audioArrivalGaps.set(0); maxAudioArrivalGapMs = 0;
        snapArrivals = 0; snapBunched = 0; snapNetGaps = 0; snapInAppSum = 0; snapInAppSamples = 0; snapAudioGaps = 0;
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

    // Smoothed interval between frames handed to the display (the real stream rate, which
    // can be lower than the configured one, e.g. an idle desktop sent at 30 fps)
    private static volatile long lastReleaseNs;
    private static volatile long releaseIntervalEmaNs;

    static void onFrameReleased() {
        if (!collecting) {
            return;
        }
        framesReleased.incrementAndGet();
        final long now = System.nanoTime();
        final long last = lastReleaseNs;
        lastReleaseNs = now;
        if (last != 0 && now > last) {
            final long d = now - last;
            if (d < 250_000_000L) {
                final long ema = releaseIntervalEmaNs;
                releaseIntervalEmaNs = (ema == 0) ? d : ema + (d - ema) / 16;
            }
        }
    }

    // --- Arrival (where does bunching start?) ---
    private static long lastVideoReceiveMs;
    private static final AtomicLong videoArrivals = new AtomicLong();
    private static final AtomicLong videoBunched = new AtomicLong();
    private static final AtomicLong videoNetGaps = new AtomicLong();
    private static volatile long maxVideoNetGapMs;
    private static final AtomicLong inAppSumMs = new AtomicLong();
    private static final AtomicLong inAppSamples = new AtomicLong();
    private static volatile long maxInAppMs;
    private static long lastAudioArrivalNs;
    private static final AtomicLong audioArrivalGaps = new AtomicLong();
    private static volatile long maxAudioArrivalGapMs;
    private static volatile int boostedThreads = -1;

    // How long handing a frame to the decoder took (decode thread)
    private static volatile long maxSubmitNs;
    private static final AtomicLong slowSubmits = new AtomicLong();
    private static long snapSlowSubmits;

    // Do the arrival gaps line up with overlay updates (i.e. does the overlay cause them)?
    private static volatile long lastOverlayBuildMs;
    private static final AtomicLong gapsAfterOverlay = new AtomicLong();

    // Java garbage collection, from ART's runtime stats
    private static long snapGcCount = -1, snapGcTimeMs, snapBlockingGcCount, snapBlockingGcTimeMs;

    // Network description is a binder call: refresh it every few seconds only
    private static String cachedNetwork = "?";
    private static long cachedNetworkAtMs;
    private static long lastGapAtMs;
    private static volatile long gapPeriodEmaMs;
    private static long snapArrivals, snapBunched, snapNetGaps, snapInAppSum, snapInAppSamples, snapAudioGaps;

    /** Video decoder thread: one complete frame came out of the network stack. */
    public static void onVideoFrameArrived(long receiveTimeMs) {
        if (!collecting) {
            return;
        }
        final long nowMs = System.nanoTime() / 1_000_000L;
        videoArrivals.incrementAndGet();
        final long inApp = nowMs - receiveTimeMs;
        if (inApp >= 0 && inApp < 2000) {
            inAppSumMs.addAndGet(inApp);
            inAppSamples.incrementAndGet();
            if (inApp > maxInAppMs) {
                maxInAppMs = inApp;
            }
        }
        final long last = lastVideoReceiveMs;
        lastVideoReceiveMs = receiveTimeMs;
        if (last > 0 && receiveTimeMs >= last) {
            final long d = receiveTimeMs - last;
            final long periodMs = Math.max(1L, Math.max(expectedFramePeriodNs, releaseIntervalEmaNs) / 1_000_000L);
            if (d <= 2 && periodMs >= 6) {
                videoBunched.incrementAndGet();
            }
            if (d > tickMaxArrivalGapMs && d < 2000) {
                tickMaxArrivalGapMs = d;
            }
            // A pause is a gap of 2.5 frames, but never less than 40 ms: at 90 or 120 fps,
            // 2.5 frames is only 20-28 ms, which Wi-Fi's normal bunching reaches all the time
            // (and such short gaps don't show on screen: the frames still make their refresh)
            if (d > Math.max(40L, (periodMs * 5) / 2) && d < 2000) {
                // How regular are the stalls? ~1024 ms points at Wi-Fi power saving (DTIM),
                // ~1280 ms at Bluetooth scanning, other steady values at a timer somewhere
                final long lastGap = lastGapAtMs;
                lastGapAtMs = receiveTimeMs;
                if (lastGap > 0 && receiveTimeMs - lastGap < 10_000) {
                    final long iv = receiveTimeMs - lastGap;
                    final long ema = gapPeriodEmaMs;
                    gapPeriodEmaMs = (ema == 0) ? iv : ema + (iv - ema) / 4;
                }
                videoNetGaps.incrementAndGet();
                final long sinceOverlay = last - lastOverlayBuildMs;
                if (lastOverlayBuildMs > 0 && sinceOverlay >= 0 && sinceOverlay <= 250) {
                    gapsAfterOverlay.incrementAndGet();
                }
                if (d > maxVideoNetGapMs) {
                    maxVideoNetGapMs = d;
                }
                if (d > windowMaxNetGapMs) {
                    windowMaxNetGapMs = d;
                }
            }
        }
    }

    /** The once-a-second overlay update was just handed to the UI thread. */
    public static void markOverlayDispatch() {
        lastOverlayBuildMs = System.nanoTime() / 1_000_000L;
    }

    public static void onSubmitDone(long durationNs) {
        if (!collecting) {
            return;
        }
        if (durationNs > maxSubmitNs) {
            maxSubmitNs = durationNs;
        }
        if (durationNs > 20_000_000L) {
            slowSubmits.incrementAndGet();
        }
    }

    private static long gcStat(String name) {
        try {
            final String v = android.os.Debug.getRuntimeStat(name);
            return (v != null) ? Long.parseLong(v) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Audio decoder thread: one decoded audio packet (normally 5 ms) is ready to play. */
    public static void onAudioPacketArrived() {
        if (!collecting) {
            return;
        }
        final long now = System.nanoTime();
        final long last = lastAudioArrivalNs;
        lastAudioArrivalNs = now;
        if (last != 0 && now > last) {
            final long gapMs = (now - last) / 1_000_000L;
            if (gapMs > 20 && gapMs < 2000) {
                audioArrivalGaps.incrementAndGet();
                if (gapMs > maxAudioArrivalGapMs) {
                    maxAudioArrivalGapMs = gapMs;
                }
            }
        }
    }

    public static void setBoostedThreads(int count) {
        boostedThreads = count;
    }

    // TV picture mode request (setPreferMinimalPostProcessing)
    private static volatile String gameModeState = "off";

    public static void setMinimalPostProcessing(boolean requested, boolean supported) {
        gameModeState = !requested ? "off" : (supported ? "on" : "requested, TV says unsupported");
    }

    /** Called from MediaCodec.OnFrameRenderedListener with the real display time. */
    static void onFrameDisplayed(long renderNs) {
        if (!collecting) {
            return;
        }
        framesDisplayed.incrementAndGet();
        final long last = lastDisplayNs;
        lastDisplayNs = renderNs;
        if (last != 0 && renderNs > last) {
            final long interval = renderNs - last;
            if (interval > tickMaxDisplayIntervalNs && interval < 1_000_000_000L) {
                tickMaxDisplayIntervalNs = interval;
            }
            // A hitch is a frame that stayed on screen longer than the stream's cadence allows.
            // The cadence is the frame time rounded up to whole refreshes: 60 fps on 60 Hz is one
            // refresh per frame, 90 fps on 120 Hz is one or two (two is normal there, not a hitch),
            // 90 fps on 60 Hz is one. Anything half a refresh beyond that is a hitch.
            // Long pauses (menus, static desktop) above 250 ms are ignored.
            final long content = Math.max(expectedFramePeriodNs, releaseIntervalEmaNs);
            long refresh = vsyncPeriodNs > 0 ? vsyncPeriodNs : displayPeriodNs;
            if (refresh <= 0) {
                refresh = content;
            }
            final long refreshes = Math.max(1L, (content - refresh / 20 + refresh - 1) / refresh);
            final long natural = refreshes * refresh;
            if (interval > natural + refresh / 2 && interval < 250_000_000L) {
                hitchExtraNs.addAndGet(interval - natural);
                displayGaps.incrementAndGet();
                if (interval > maxDisplayIntervalNs) {
                    maxDisplayIntervalNs = interval;
                }
                if (interval > windowMaxDisplayIntervalNs) {
                    windowMaxDisplayIntervalNs = interval;
                }
            }
        }
    }

    static void onFrameScheduled(long leadNs, boolean late, boolean trimmed, boolean capped) {
        if (!collecting) {
            return;
        }
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

    static void setBufferLimitFrames(int frames) {
        bufferLimitFrames = frames;
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
        if (!collecting) {
            return;
        }
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

    static void setDecoderConfigTry(int tryNumber, String description) {
        decoderConfigTry = tryNumber;
        decoderPreset = (description != null) ? description : "";
    }

    static void onInputWait(long waitedNs) {
        if (!collecting) {
            return;
        }
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
        audioTrimmed.set(0);
        audioLevelMs = -1;
        audioMinLevelMs = -1;
        audioTargetMs = -1;
        snapUnderruns = 0;
        snapSkipped = 0;
    }

    /** The audio cushion had to be refilled with silence (audio was lost, not just late). */
    public static void onAudioReprimed() {
        audioReprimes.incrementAndGet();
    }

    /** One packet dropped on purpose because more audio than needed was queued (latency). */
    public static void onAudioTrimmed() {
        audioTrimmed.incrementAndGet();
    }

    /** Audio queued in the track now, and the lowest level during the last second (ms). */
    public static void setAudioTarget(int targetMs) {
        audioTargetMs = targetMs;
    }

    public static void setAudioLevel(int nowMs, int minMs) {
        audioLevelMs = nowMs;
        audioMinLevelMs = minMs;
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
        sb.append(" · pacing ").append(describePacing(prefs))
                .append(" · TV game mode ").append(gameModeState)
                .append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");

        // What reached the screen, by SurfaceFlinger's timing (see TvScreenModel)
        {
            final long mp = modelPictures.get(), mf = modelFrozen.get(), mfp = modelFrozenPc.get();
            final long mu = modelUneven.get(), ms = modelSkipped.get();
            final long msSum = modelScreenSumUs.get(), msN = modelScreenN.get();
            final long pg = pcGaps.get(), ds = deliveryStalls.get();
            if (haveRates && mp > snapModelPictures) {
                sb.append(String.format(Locale.US,
                        "Screen: %.1f new pictures/s · frozen %d (PC had nothing newer %d) · skipped %d · uneven %d · TV %.1f ms · PC gaps %d · late deliveries %d\n",
                        (mp - snapModelPictures) / secs, mf - snapModelFrozen, mfp - snapModelFrozenPc,
                        ms - snapModelSkipped, mu - snapModelUneven,
                        msN > snapModelScreenN ? (msSum - snapModelScreenSum) / 1000.0 / (msN - snapModelScreenN) : 0.0,
                        pg - snapPcGaps, ds - snapDeliveryStalls));
            }
            snapModelPictures = mp; snapModelFrozen = mf; snapModelFrozenPc = mfp; snapModelUneven = mu; snapModelSkipped = ms;
            snapModelScreenSum = msSum; snapModelScreenN = msN; snapPcGaps = pg; snapDeliveryStalls = ds;
        }
        if (displayed > 0) {
            sb.append(String.format(Locale.US, "Frame-rendered callbacks %.1f fps (sent %.1f) · gaps %.1f/s, longest %d ms\n",
                    shownFps, releasedFps, gapsPerSec, maxDisplayIntervalNs / 1_000_000L));
        } else {
            sb.append(String.format(Locale.US, "Sent to display %.1f fps (no render callbacks)\n", releasedFps));
        }

        {
            final long arrivals = videoArrivals.get(), bunched = videoBunched.get(), netGaps = videoNetGaps.get();
            final long inSum = inAppSumMs.get(), inN = inAppSamples.get(), aGaps = audioArrivalGaps.get();
            final long dArr = arrivals - snapArrivals;
            final double bunchedPct = dArr > 0 ? 100.0 * (bunched - snapBunched) / dArr : 0;
            final double netGapsPerSec = haveRates ? (netGaps - snapNetGaps) / secs : 0;
            final double inAvg = (inN - snapInAppSamples) > 0 ? (inSum - snapInAppSum) / (double) (inN - snapInAppSamples) : 0;
            final double aGapsPerSec = haveRates ? (aGaps - snapAudioGaps) / secs : 0;
            snapArrivals = arrivals; snapBunched = bunched; snapNetGaps = netGaps;
            snapInAppSum = inSum; snapInAppSamples = inN; snapAudioGaps = aGaps;
            sb.append(String.format(Locale.US,
                    "Arrival: video bunched %.0f%% · gaps %.1f/s (longest %d ms%s) · in app %.1f ms (max %d) · audio gaps %.1f/s (longest %d ms) · boosted threads %d\n",
                    bunchedPct, netGapsPerSec, maxVideoNetGapMs,
                    gapPeriodEmaMs > 0 ? String.format(Locale.US, ", about every %d ms", gapPeriodEmaMs) : "",
                    inAvg, maxInAppMs, aGapsPerSec, maxAudioArrivalGapMs, boostedThreads));
            final long nowMsForNet = System.nanoTime() / 1_000_000L;
            if (nowMsForNet - cachedNetworkAtMs >= 5000) {
                cachedNetwork = describeNetwork(context);
                cachedNetworkAtMs = nowMsForNet;
            }
            sb.append("Network: ").append(cachedNetwork).append('\n');

            // Where could a once-a-second stall come from inside the app?
            final long slow = slowSubmits.get();
            final long gcCount = gcStat("art.gc.gc-count"), gcTime = gcStat("art.gc.gc-time");
            final long bCount = gcStat("art.gc.blocking-gc-count"), bTime = gcStat("art.gc.blocking-gc-time");
            String gcText = "?";
            if (gcCount >= 0 && snapGcCount >= 0 && haveRates) {
                gcText = String.format(Locale.US, "%.1f/s (%d ms), blocking %d (%d ms)",
                        (gcCount - snapGcCount) / secs, gcTime - snapGcTimeMs,
                        bCount - snapBlockingGcCount, bTime - snapBlockingGcTimeMs);
            }
            snapGcCount = gcCount; snapGcTimeMs = gcTime; snapBlockingGcCount = bCount; snapBlockingGcTimeMs = bTime;
            sb.append(String.format(Locale.US,
                    "App: decoder hand-off max %d ms (slow %.1f/s) · gaps right after an overlay update %d of %d · GC %s\n",
                    maxSubmitNs / 1_000_000L, haveRates ? (slow - snapSlowSubmits) / secs : 0,
                    gapsAfterOverlay.get(), netGaps, gcText));
            snapSlowSubmits = slow;
        }

        if (prefs != null && PreferenceConfiguration.isTvPacing(prefs.framePacing)) {
            sb.append(String.format(Locale.US, "Jitter buffer +%.1f ms (max %d ms, limit %d frames) · late %.1f/s · trims %d · capped %d\n",
                    avgLeadMs, maxLeadNs / 1_000_000L, bufferLimitFrames, latePerSec, trims, pacerCapped.get()));

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
                .append(" · MTK setup ").append(mtkFullSpeedHintsApplied ? "TV ladder" : "stock")
                .append(" · preset ").append(decoderConfigTry).append(" (").append(decoderPreset).append(')')
                .append(String.format(Locale.US, " · input waits %d (max %d ms) · keyframe req %d\n",
                        waits, maxInputWaitNs / 1_000_000L, keyframes));

        sb.append("Audio: ").append(audioTrackMode).append(' ').append(audioBufferMs).append(" ms")
                .append(" · underruns ").append(underruns)
                .append(" · refilled ").append(audioReprimes.get())
                .append(" · trimmed ").append(audioTrimmed.get())
                .append(" · skipped ").append(skipped);
        if (audioLevelMs >= 0) {
            sb.append(" · queued ").append(audioLevelMs).append(" ms (low ").append(audioMinLevelMs).append(" ms)");
        }
        if (audioTargetMs >= 0) {
            sb.append(" · cushion now ").append(audioTargetMs).append(" ms");
        }

        return sb.toString();
    }

    /**
     * The stream report shown after a stream (TV build): session totals, measured while nothing
     * needed to be drawn over the video (unlike the overlay, which costs the TV's compositor
     * about 25 ms per frame on TCL TVs).
     *
     * @param stream     e.g. "2560x1440 60 fps HEVC 50 Mbps"
     * @param decodeMs   average decoder latency of the session, ms (-1 = unknown)
     * @param overlayOn  whether the performance overlay was visible during the stream
     */
    public static String buildSessionReport(Context context, PreferenceConfiguration prefs,
                                            String stream, int decodeMs, boolean overlayOn) {
        final long now = System.nanoTime();
        final double secs = Math.max(1.0, (now - sessionStartNs) / 1e9);
        final long mins = (long) (secs / 60), remSecs = (long) (secs % 60);

        final long released = framesReleased.get();
        final long displayed = framesDisplayed.get();
        final long leadN = leadSamples.get();
        final double avgLeadMs = leadN > 0 ? (leadSumNs.get() / (double) leadN) / 1e6 : 0;
        final long arrivals = videoArrivals.get();
        final double bunchedPct = arrivals > 0 ? 100.0 * videoBunched.get() / arrivals : 0;
        final long netGaps = videoNetGaps.get();
        final long inN = inAppSamples.get();
        final double inAvg = inN > 0 ? inAppSumMs.get() / (double) inN : 0;

        final StringBuilder sb = new StringBuilder(900);
        sb.append(String.format(Locale.US, "%s · %d min %02d s · %s%s\n",
                stream != null ? stream : "?", mins, remSecs, describePacing(prefs),
                overlayOn ? " · overlay was ON (adds delay)" : ""));

        // What reached the screen, by SurfaceFlinger's timing (the frame-rendered callback of this
        // TV's decoders only echoes the requested time, see TvScreenModel)
        final float screenHz = displayRefreshHz(context);
        final boolean fasterThanScreen = prefs != null && screenHz > 1f && prefs.fps > screenHz * 1.1f;
        final long pictures = modelPictures.get();
        if (pictures > 0) {
            sb.append(String.format(Locale.US,
                    "Screen: %,d new pictures (%.1f/s) · frozen refreshes %d (%d ms in all, longest %d ms; the PC had nothing newer for %d of them) · skipped frames %d · uneven steps %d · frames replaced %,d%s\n",
                    pictures, pictures / secs, modelFrozen.get(), modelFrozenNs.get() / 1_000_000L,
                    modelLongestFreezeNs / 1_000_000L, modelFrozenPc.get(), modelSkipped.get(), modelUneven.get(),
                    modelReplaced.get(),
                    fasterThanScreen ? String.format(Locale.US, " (normal: the stream is faster than the %.0f Hz screen)", screenHz) : ""));
        }
        if (displayed > 0) {
            final long pN = presentSamples.get();
            final String onTime = pN > 0
                    ? String.format(Locale.US, " · %.0f%% on time", 100.0 * (pN - presentSlips.get()) / pN)
                    : "";
            final long checked = renderChecked.get(), echo = renderEcho.get();
            final boolean echoes = checked >= 100 && echo * 10 >= checked * 9;
            sb.append(String.format(Locale.US, "%s %,d of %,d frames%s · %d hitches (longest %d ms, stood still %d ms in all) · display drops %d%s%s\n",
                    pictures > 0 ? "Frame-rendered callbacks:" : "Screen:",
                    displayed, released, onTime, displayGaps.get(), maxDisplayIntervalNs / 1_000_000L,
                    hitchExtraNs.get() / 1_000_000L,
                    unplannedDrops.get(),
                    checked > 0 ? String.format(Locale.US, " · %,d of %,d report exactly the requested time%s", echo, checked,
                            echoes ? " (the TV only echoes the request: these say nothing about the screen)" : "") : "",
                    fasterThanScreen && pictures == 0 ? String.format(Locale.US, " (stream faster than the %.0f Hz screen: it can show %.0f per second)",
                            screenHz, screenHz) : ""));
        } else if (pictures == 0) {
            sb.append(String.format(Locale.US, "Screen: %,d frames sent to the display\n", released));
        }

        // The PC's side, from the capture time every frame carries
        final String pc = describePcRhythm(secs);
        if (pc != null) {
            sb.append(pc).append('\n');
        }
        final String rates = describeRateMatch();
        if (rates != null) {
            sb.append(rates).append('\n');
        }

        // Start-up: how long until the picture was smooth (3 calm seconds in a row)
        final int smoothAfter = com.limelight.tv.TvTimeline.smoothAfterSeconds();
        if (secs >= 10) {
            sb.append(smoothAfter < 0 ? "Start: never smooth for 3 seconds in a row"
                    : smoothAfter <= 1 ? "Start: smooth from the first seconds"
                    : "Start: smooth after " + smoothAfter + " s (the seconds before had hitches or pauses)").append('\n');
        }

        // Network arrival
        sb.append(String.format(Locale.US, "Network: %d pauses (%.1f/min, longest %d ms%s) · %.0f%% of frames bunched · audio pauses %d (longest %d ms)\n",
                netGaps, netGaps * 60.0 / secs, maxVideoNetGapMs,
                gapPeriodEmaMs > 0 ? String.format(Locale.US, ", about every %d ms", gapPeriodEmaMs) : "",
                bunchedPct, audioArrivalGaps.get(), maxAudioArrivalGapMs));

        // Loss and round trip: lost frames are what FEC could not repair; rebuilt audio packets
        // (5 ms each, small) show how lossy the link is even when nothing visible happened
        final StringBuilder loss = new StringBuilder("Loss: ");
        loss.append(String.format(Locale.US, "%d video frames lost (%d times)", framesLostTotal.get(), frameLossEvents.get()));
        try {
            final int[] a = com.limelight.nvstream.jni.MoonBridge.getAudioRtpStats();
            if (a != null && a.length >= 4 && a[0] > 0) {
                loss.append(String.format(Locale.US, " · audio packets rebuilt by FEC %d of %,d (%.2f%%), lost %d",
                        a[2], a[0], 100.0 * a[2] / a[0], a[3]));
            }
        } catch (Throwable ignored) { }
        final long rttN = rttSamples.get();
        if (rttN > 0) {
            loss.append(String.format(Locale.US, " · round trip %d ms (max %d, jitter %d)",
                    Math.round(rttSumMs.get() / (double) rttN), maxRttMs, lastRttVarianceMs));
        }
        sb.append(loss).append('\n');

        // Delay contributions
        final long hostN = hostSamples.get();
        final long scrN = screenSamples.get();
        final long modelN = modelScreenN.get();
        final String tv;
        if (modelN > 0) {
            tv = String.format(Locale.US, "%.1f ms (arrival to the refresh it is shown on, SurfaceFlinger's timing%s)",
                    modelScreenSumUs.get() / 1000.0 / modelN,
                    scrN > 0 ? String.format(Locale.US, "; the frame-rendered callback said %.1f", screenSumUs.get() / 1000.0 / scrN) : "");
        } else {
            tv = scrN > 0 ? String.format(Locale.US, "%.1f ms (arrival to screen)", screenSumUs.get() / 1000.0 / scrN) : "?";
        }
        sb.append(String.format(Locale.US, "Delay: PC %s · TV %s · decode %s · jitter buffer +%.1f ms (max %d ms, late %d, trims %d, replaced %d) · in app %.1f ms (max %d)\n",
                hostN > 0 ? String.format(Locale.US, "%.1f ms (capture to encoded, max %.1f)",
                        hostSumTenths.get() / 10.0 / hostN, sessionMaxHostTenths / 10.0) : "? (the PC sent no timing)",
                tv,
                decodeMs >= 0 ? decodeMs + " ms" : "?", avgLeadMs, maxLeadNs / 1_000_000L,
                pacerLate.get(), pacerTrims.get(), pacerCapped.get(), inAvg, maxInAppMs));

        // Button-to-screen latency test, if it ran in this stream
        final String latency = com.limelight.tv.TvLatencyTest.reportLine();
        if (latency != null) {
            sb.append(latency).append('\n');
        }

        // Decoder
        sb.append("Decoder: ").append(decoderName).append(" · ")
                .append(decoderPreset.isEmpty() ? ("preset " + decoderConfigTry) : decoderPreset)
                .append(String.format(Locale.US, " · input waits %d (max %d ms) · keyframe requests %d · hand-off max %d ms (slow %d)\n",
                        inputWaits.get(), maxInputWaitNs / 1_000_000L, keyframeRequests.get(),
                        maxSubmitNs / 1_000_000L, slowSubmits.get()));
        sb.append(describeDecoderBehavior()).append('\n');

        // Audio
        sb.append("Audio: ").append(audioTrackMode);
        if (!audioTrackMode.startsWith("off")) {
            sb.append(' ').append(audioBufferMs).append(" ms");
            if (audioTargetMs >= 0) {
                sb.append(" (grew to ").append(audioTargetMs).append(" ms)");
            }
            sb.append(" · underruns ").append(audioUnderruns)
                    .append(" · refilled ").append(audioReprimes.get())
                    .append(" · trimmed ").append(audioTrimmed.get())
                    .append(" · skipped ").append(audioSkipped.get());
        }
        sb.append('\n');

        // App and system
        final long gcCount = gcStat("art.gc.gc-count"), gcTime = gcStat("art.gc.gc-time");
        final long bCount = gcStat("art.gc.blocking-gc-count"), bTime = gcStat("art.gc.blocking-gc-time");
        String gc = "?";
        if (gcCount >= 0 && gcCountAtStart >= 0) {
            gc = String.format(Locale.US, "%d (%d ms), blocking %d (%d ms)",
                    gcCount - gcCountAtStart, gcTime - gcTimeAtStart,
                    bCount - blockingGcCountAtStart, bTime - blockingGcTimeAtStart);
        }
        sb.append("App: GC ").append(gc)
                .append(" · boosted threads ").append(boostedThreads)
                .append(" · CPU hints ").append(adpfState).append('\n');

        // The TV itself: memory at the start, how hot the chip got
        if (!memoryInfo.isEmpty() || thermalMax >= 0) {
            sb.append("TV:");
            if (!memoryInfo.isEmpty()) {
                sb.append(" memory ").append(memoryInfo);
            }
            if (thermalMax >= 0) {
                sb.append(memoryInfo.isEmpty() ? " " : " · ").append("temperature ").append(thermalName(thermalNow));
                if (thermalMax > thermalNow) {
                    sb.append(" (peak ").append(thermalName(thermalMax)).append(')');
                }
            }
            sb.append('\n');
        }

        // Settings that cost delay or smoothness on this TV (nothing printed when all is well)
        final String check = settingsCheck(prefs);
        if (!check.isEmpty()) {
            sb.append("Check: ").append(check).append('\n');
        }

        sb.append(describeNetwork(context)).append(" · ").append(describeDisplay(context))
                .append(" · Android ").append(Build.VERSION.RELEASE);
        return sb.toString();
    }

    /**
     * The PC's side from the capture times: its frame rate, gaps where it captured nothing, and
     * frames it captured in time that arrived late. Null when the PC sends no capture times.
     */
    public static String describePcRhythm(double secs) {
        final long frames = hostFrames.get();
        if (hostTimesSynthetic) {
            return "PC: sends no capture times (PC-side stalls and late deliveries can't be told apart)";
        }
        if (frames < 30) {
            return null;
        }
        final long n = hostCadenceN.get();
        final double pcFps = n > 0 ? 1e6 * n / hostCadenceSumUs.get() : 0;
        return String.format(Locale.US,
                "PC: captures %.2f fps (its clock) · %d gaps where it captured nothing 40 ms+ (%.1f/min, longest %d ms) · " +
                        "%d late deliveries 40 ms+ (captured in time, arrived late: encoder, PC network or Wi-Fi; longest %d ms) · " +
                        "frames later than the PC's rhythm: %.1f%% by 4 ms+, %.1f%% by 8 ms+, %.1f%% by 16 ms+",
                pcFps, pcGaps.get(), pcGaps.get() * 60.0 / Math.max(1.0, secs), maxPcGapMs,
                deliveryStalls.get(), maxDeliveryStallMs,
                100.0 * late4.get() / frames, 100.0 * late8.get() / frames, 100.0 * late16.get() / frames);
    }

    /**
     * Screen refresh against the PC's frame rate, both measured precisely: when they don't match
     * exactly, the picture slips by one frame now and then (a repeated or a dropped frame, or with
     * two frames per refresh one uneven step). Null until both are known.
     */
    public static String describeRateMatch() {
        final double hz = measuredRefreshHz;
        final long n = hostCadenceN.get();
        if (hz <= 20 || n < 600 || hostTimesSynthetic) {
            return hz > 20 ? String.format(Locale.US, "Refresh: screen %.3f Hz (measured over %d s)", hz, measuredRefreshSecs) : null;
        }
        final double pcFps = 1e6 * n / hostCadenceSumUs.get();
        final StringBuilder b = new StringBuilder(String.format(Locale.US,
                "Refresh: screen %.3f Hz (measured over %d s) · PC %.3f fps", hz, measuredRefreshSecs, pcFps));
        final double ratio = pcFps / hz;
        final long k = Math.round(ratio);
        if (k >= 1 && Math.abs(ratio - k) < 0.02) {
            final double beat = pcFps - k * hz; // frames per second the PC is ahead (+) or behind (-)
            if (Math.abs(beat) < 0.001) {
                b.append(" → in step (no slips to expect)");
            } else {
                final double every = 1.0 / Math.abs(beat);
                b.append(String.format(Locale.US, " → about every %.0f s %s (the two rates differ by %.3f; the PC's clock may be off by a few ppm)",
                        every,
                        k == 1 ? (beat < 0 ? "the screen shows a frame twice" : "a frame is left out")
                                : "the frame groups shift by one (one uneven step)",
                        Math.abs(beat)));
            }
        } else if (ratio > 1.05) {
            b.append(String.format(Locale.US, " → %.2f frames per refresh: frames are shown for unequal times (judder) unless it is a whole number", ratio));
        }
        return b.toString();
    }

    /**
     * Does the decoder behave like it is in low-latency mode? Such a decoder hands every frame
     * back before the next one arrives: its decode time is below one frame interval and no
     * other frame waits inside it. A decoder that keeps frames back (to reorder them, or to
     * work in batches) shows both: decode times of a frame interval or more, frames waiting.
     */
    public static String describeDecoderBehavior() {
        final long decN = decodeSamples.get();
        final long inN = inFlightSamples.get();
        if (decN == 0) {
            return "Decoder low-latency check: no frames measured";
        }
        final double decodeMs = decodeSumUs.get() / 1000.0 / decN;
        final double frameMs = Math.max(expectedFramePeriodNs, releaseIntervalEmaNs) / 1e6;
        final double held = inN > 0 ? inFlightSum.get() / (double) inN : -1;
        final boolean fast = decodeMs < frameMs * 0.75;
        final boolean noBacklog = held < 0 || held < 0.5;
        return String.format(Locale.US,
                "Decoder low-latency check: %.1f ms per frame (a frame comes every %.1f ms) · frames waiting inside: %s → %s",
                decodeMs, frameMs,
                held >= 0 ? String.format(Locale.US, "%.2f on average, at most %d", held, sessionMaxInFlight) : "?",
                fast && noBacklog ? "works like low-latency mode (every frame comes back before the next one)"
                        : fast ? "fast, but keeps frames back now and then"
                        : "NOT low latency: the decoder holds frames back");
    }

    /**
     * What the app switched on or chose by itself for this stream, and what the TV accepted
     * (for the "settings in use" part of the logs). One "Name: value" per line.
     */
    public static String describeAutomatic() {
        final StringBuilder b = new StringBuilder(600);
        b.append("Decoder: ").append(decoderName)
                .append(" · try ").append(decoderConfigTry).append(" (").append(decoderPreset.isEmpty() ? "-" : decoderPreset).append(')')
                .append(" · Android low-latency feature ").append(decoderLowLatencyFeature)
                .append(" · MediaTek ladder ").append(mtkFullSpeedHintsApplied ? "yes" : "no").append('\n');
        b.append("TV game picture mode: ").append(gameModeState).append('\n');
        b.append("Audio: ").append(audioTrackMode);
        if (!audioTrackMode.startsWith("off") && !"?".equals(audioTrackMode)) {
            b.append(", ").append(audioBufferMs).append(" ms");
        }
        b.append('\n');
        final long period = vsyncPeriodNs;
        b.append("Pacing: buffer limit ").append(bufferLimitFrames).append(" frames · refresh grid ")
                .append(period > 0 ? String.format(Locale.US, "%.2f Hz (%s)", 1e9 / period,
                        vsyncPresentationGrid ? "present times" : "vsync") : "none")
                .append(String.format(Locale.US, " · SF lead %.1f ms · learned extra margin %.1f ms",
                        assumedPipelineNs / 1e6, adaptiveMarginNs / 1e6)).append('\n');
        b.append(String.format(Locale.US, "Screen timing: the display needs a frame %.1f ms before its refresh (presentation deadline), app vsync offset %.1f ms%s\n",
                displayDeadlineNs / 1e6, displayAppVsyncOffsetNs / 1e6,
                measuredRefreshHz > 20 ? String.format(Locale.US, " · refresh measured %.3f Hz", measuredRefreshHz) : ""));
        b.append("Thread priorities raised: ").append(boostedThreads < 0 ? "not yet" : String.valueOf(boostedThreads))
                .append(" · CPU hints (ADPF): ").append(adpfState).append('\n');
        if (!memoryInfo.isEmpty()) {
            b.append("TV memory at start: ").append(memoryInfo).append('\n');
        }
        if (thermalMax >= 0) {
            b.append("Chip temperature: ").append(thermalName(thermalNow));
            if (thermalMax > thermalNow) {
                b.append(" (peak ").append(thermalName(thermalMax)).append(')');
            }
            b.append('\n');
        }
        b.append("Stream statistics: ").append(collecting ? "collected" : "off").append('\n');
        return b.toString();
    }

    private static float displayRefreshHz(Context context) {
        try {
            final Display d = (context instanceof Activity)
                    ? ((Activity) context).getWindowManager().getDefaultDisplay()
                    : ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            return d != null ? d.getRefreshRate() : 0f;
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** Settings that are known to hurt on this TV, as a short list (empty = all fine). */
    private static String settingsCheck(PreferenceConfiguration prefs) {
        if (prefs == null) {
            return "";
        }
        final StringBuilder b = new StringBuilder();
        if (!PreferenceConfiguration.isTvPacing(prefs.framePacing)) {
            b.append("frame pacing is not TV game/TV smooth; ");
        }
        if (prefs.videoUpscaleEnable || prefs.gpuPathMode) {
            b.append("GPU path/upscaling is on (extra delay on this GPU); ");
        }
        if (prefs.mtkExtraDecoderKeys) {
            b.append("MediaTek extra decoder keys are on (experimental); ");
        }
        if (prefs.width >= 3840 && prefs.fps > 60) {
            b.append("4K above 60 fps is more than the decoder can do; ");
        }
        if (prefs.bitrate > 150_000) {
            b.append("bitrate above 150 Mbps; ");
        }
        if (b.length() > 2) {
            b.setLength(b.length() - 2);
        }
        return b.toString();
    }

    private static String describePacing(PreferenceConfiguration prefs) {
        return prefs == null ? "?" : PreferenceConfiguration.describeFramePacing(prefs.framePacing);
    }

    @SuppressWarnings("deprecation")
    public static String describeNetwork(Context context) {
        try {
            final android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                final android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    return "Ethernet";
                }
            }
            final android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return "?";
            }
            final android.net.wifi.WifiInfo info = wm.getConnectionInfo();
            if (info == null || info.getNetworkId() == -1 && info.getRssi() <= -127) {
                return "not on Wi-Fi";
            }
            final int mhz = info.getFrequency();
            final String band = mhz >= 5925 ? "6 GHz" : (mhz >= 4900 ? "5 GHz" : (mhz > 0 ? "2.4 GHz" : "?"));
            final StringBuilder b = new StringBuilder("Wi-Fi ").append(band);
            if (mhz > 0) {
                b.append(" (").append(mhz).append(" MHz)");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                switch (info.getWifiStandard()) {
                    case android.net.wifi.ScanResult.WIFI_STANDARD_11N: b.append(" · Wi-Fi 4"); break;
                    case android.net.wifi.ScanResult.WIFI_STANDARD_11AC: b.append(" · Wi-Fi 5"); break;
                    case android.net.wifi.ScanResult.WIFI_STANDARD_11AX: b.append(" · Wi-Fi 6"); break;
                    default: break;
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                b.append(" · link ").append(info.getRxLinkSpeedMbps()).append('/')
                        .append(info.getTxLinkSpeedMbps()).append(" Mbps");
            } else {
                b.append(" · link ").append(info.getLinkSpeed()).append(" Mbps");
            }
            b.append(" · signal ").append(info.getRssi()).append(" dBm");
            return b.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    @SuppressWarnings("deprecation")
    public static String describeDisplay(Context context) {
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
                // Which refresh rates does the TV offer apps at this resolution? (120 Hz would
                // allow a 120 fps stream to be shown frame by frame.)
                final java.util.TreeSet<Integer> rates = new java.util.TreeSet<>();
                for (Display.Mode c : d.getSupportedModes()) {
                    if (c.getPhysicalWidth() == m.getPhysicalWidth() && c.getPhysicalHeight() == m.getPhysicalHeight()) {
                        rates.add(Math.round(c.getRefreshRate()));
                    }
                }
                return String.format(Locale.US, "%dx%d @ %.2f Hz (offers %s Hz)",
                        m.getPhysicalWidth(), m.getPhysicalHeight(), hz, rates.descendingSet().toString());
            }
            return String.format(Locale.US, "%.2f Hz", hz);
        } catch (Throwable t) {
            return "?";
        }
    }
}
