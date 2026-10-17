package com.limelight.binding.video;

import android.view.Display;

/**
 * "TV smooth" frame pacing.
 *
 * Every decoded frame is handed to the display right away (the app never holds on to decoder
 * output buffers) with a presentation time from MediaCodec.releaseOutputBuffer(index, timeNs).
 * SurfaceFlinger keeps a frame that arrives early until its time has come, so a bunch of frames
 * that arrive together over Wi-Fi is shown one per refresh instead of being thrown away.
 *
 * Presentation times are placed on the display's refresh grid ({@link TvVsyncClock}): each frame
 * gets its own refresh slot, and the timestamp handed to SurfaceFlinger sits in the middle
 * between two refreshes, so small timing noise can never make two frames land on the same
 * refresh (one of them would be dropped) or leave a refresh without a new frame.
 *
 * A frame is only planned for a refresh SurfaceFlinger can still make: it has to be queued a
 * "pipeline" ahead of that refresh. Android 13+ reports that deadline; before that it is assumed
 * to be one refresh plus a margin. If the display drops frames we planned anyway (it tells us
 * through MediaCodec's frame-rendered callback), the margin grows for the rest of the session.
 *
 * Latency control:
 *  - the schedule never runs more than MAX_LEAD_FRAMES ahead of the earliest possible refresh;
 *    past that, a new frame replaces the newest queued one;
 *  - if every frame for TRIM_WINDOW_NS had at least two spare frames queued ahead of it, one
 *    frame of that buffer is removed. One spare frame is kept: that is what hides Wi-Fi jitter.
 *
 * All times use the System.nanoTime() timebase, which is what MediaCodec expects.
 */
final class TvFramePacer {
    private static final float MAX_LEAD_FRAMES = 3.0f;
    private static final long TRIM_WINDOW_NS = 1_500_000_000L;
    private static final long TRIM_COOLDOWN_NS = 1_500_000_000L;
    // Margin between queueing a buffer and SurfaceFlinger's deadline for it
    private static final long QUEUE_SAFETY_NS = 2_000_000L;
    // Before Android 13 SurfaceFlinger's deadline is unknown: assume one refresh plus this
    private static final long LEGACY_PIPELINE_EXTRA_NS = 4_000_000L;
    // The adaptive margin grows by a quarter refresh per step, at most this often
    private static final long ADAPT_COOLDOWN_NS = 300_000_000L;

    // TV game mode: at most one frame of buffer, trimmed quickly, no extra pipeline margin
    // Game mode buffer limit adapts between these: it grows by a frame when frames keep having
    // to replace each other (Wi-Fi bunches bigger than the buffer), and shrinks after a calm spell
    private static final int GAME_MIN_LEAD_FRAMES = 1;
    private static final int GAME_MAX_LEAD_FRAMES = 3;
    private static final long GAME_GROW_IF_CAPPED_WITHIN_NS = 2_000_000_000L;
    private static final long GAME_SHRINK_AFTER_NS = 20_000_000_000L;
    private int gameLeadFrames = GAME_MIN_LEAD_FRAMES;
    private long lastCapNs;          // last small overflow (see capRun)
    private long lastLeadChangeNs;
    // Consecutive capped frames. A short run means a bunch slightly bigger than the buffer
    // (a bigger buffer would have shown those frames); a long run is the backlog after a long
    // network stall, which no small buffer can save, so it must not grow the buffer.
    private int capRun;
    private static final int GAME_SMALL_CAP_RUN = 2;
    // Game mode trims after this many frames in a row had a spare frame queued
    private static final int GAME_TRIM_AFTER_FRAMES = 60;
    private int spareStreak;
    private static final long GAME_TRIM_WINDOW_NS = 1_000_000_000L;

    private volatile boolean gameMode;

    private final TvVsyncClock vsyncClock = new TvVsyncClock();
    private final long[] grid = new long[4];

    private long framePeriodNs = 16_666_667L;

    // Ideal (unsnapped) presentation time of the next frame; 0 = no schedule yet
    private long nextIdealNs;
    // Refresh slot of the previous frame; 0 = none
    private long lastSlotNs;

    private long windowStartNs;
    private long windowMinLeadNs = Long.MAX_VALUE;
    private long lastTrimNs;

    // Extra pipeline margin learned from frames the display dropped (kept for the session)
    private long adaptiveExtraNs;
    private long lastAdaptNs;
    private long lastPeriodNs = 16_666_667L;

    // Planned refresh per released frame, matched against frame-rendered callbacks.
    // planSlotNs == 0 means "nothing pending" (rendered, or deliberately replaced by us).
    private static final int PLAN_RING = 64; // power of two
    private static final int PLAN_MASK = PLAN_RING - 1;
    private final long[] planPtsUs = new long[PLAN_RING];
    private final long[] planSlotNs = new long[PLAN_RING];
    private int planPos;

    void setGameMode(boolean enabled) {
        gameMode = enabled;
    }

    private volatile boolean aggressiveTiming;

    void setAggressiveTiming(boolean enabled) {
        aggressiveTiming = enabled;
    }

    void startVsyncClock(Display display) {
        vsyncClock.start(display);
    }

    void stopVsyncClock() {
        vsyncClock.stop();
    }

    synchronized void reset() {
        nextIdealNs = 0L;
        lastSlotNs = 0L;
        windowStartNs = 0L;
        windowMinLeadNs = Long.MAX_VALUE;
        lastTrimNs = 0L;
        capRun = 0;
        spareStreak = 0;
        for (int i = 0; i < PLAN_RING; i++) {
            planSlotNs[i] = 0L;
        }
        // adaptiveExtraNs is kept: it describes the device, not the stream position
    }

    synchronized void setStreamFps(float fps) {
        if (fps > 1f && fps < 1000f) {
            framePeriodNs = (long) (1_000_000_000.0 / fps);
        }
        TvDiagnostics.setExpectedFramePeriodNs(framePeriodNs);
    }

    /** Floor division for a positive divisor (Math.floorDiv needs API 24). */
    private static long floorDiv(long a, long b) {
        long q = a / b;
        if (a % b < 0) {
            q--;
        }
        return q;
    }

    /** Smallest grid instant >= t. */
    private static long gridCeil(long t, long ref, long period) {
        return ref + floorDiv(t - ref + period - 1, period) * period;
    }

    /** Nearest grid instant to t. */
    private static long gridRound(long t, long ref, long period) {
        return ref + floorDiv(t - ref + period / 2, period) * period;
    }

    /**
     * Returns the presentation timestamp for the frame that is about to be released.
     */
    synchronized long scheduleNextFrame(final long nowNs, final long ptsUs) {
        final long frame = framePeriodNs;
        final boolean game = gameMode;
        final boolean haveGrid = vsyncClock.snapshot(nowNs, grid);
        final long refresh = haveGrid ? grid[1] : frame;
        final boolean presentationGrid = haveGrid && grid[3] != 0L;
        final long gridRef = grid[0];
        lastPeriodNs = refresh;

        // Frames faster than the display (e.g. 90 fps on 60 Hz) always replace each other;
        // that says nothing about the network, so don't grow the buffer for it
        final boolean streamFasterThanDisplay = frame < (refresh * 9) / 10;
        if (game && !streamFasterThanDisplay && gameLeadFrames > GAME_MIN_LEAD_FRAMES &&
                nowNs - Math.max(lastCapNs, lastLeadChangeNs) >= GAME_SHRINK_AFTER_NS) {
            gameLeadFrames--;
            lastLeadChangeNs = nowNs;
        }
        final float maxLeadFrames = game ? (streamFasterThanDisplay ? GAME_MIN_LEAD_FRAMES : gameLeadFrames)
                : MAX_LEAD_FRAMES;
        final long trimWindowNs = game ? GAME_TRIM_WINDOW_NS : TRIM_WINDOW_NS;
        final long trimCooldownNs = game ? GAME_TRIM_WINDOW_NS : TRIM_COOLDOWN_NS;
        // Smooth mode keeps one spare frame; game mode keeps none
        final long keepSpareNs = game ? 0L : frame;

        // SurfaceFlinger takes a buffer for a refresh at (refresh time - pipeline)
        final long basePipeline = (presentationGrid && grid[2] > 0L)
                ? grid[2]
                : (aggressiveTiming ? refresh / 2 : refresh + (game ? 0L : LEGACY_PIPELINE_EXTRA_NS));
        final long pipeline = basePipeline + adaptiveExtraNs;
        final long minPresentNs = nowNs + pipeline + QUEUE_SAFETY_NS;

        // Earliest refresh this frame can still make
        final long earliest = haveGrid ? gridCeil(minPresentNs, gridRef, refresh) : minPresentNs;

        long ideal = nextIdealNs;
        boolean late = false;
        if (ideal == 0L || ideal < earliest - refresh / 2) {
            // First frame, or this frame came out of the decoder after its refresh had passed:
            // show it as soon as possible and restart the schedule from there.
            late = (ideal != 0L);
            ideal = earliest;
        }

        long slot = haveGrid ? gridRound(ideal, gridRef, refresh) : ideal;
        if (slot < earliest) {
            slot = earliest;
        }

        // One frame per refresh. (Only the cap and the trim below may replace a queued frame.)
        if (lastSlotNs != 0L && slot < lastSlotNs + refresh / 2) {
            slot = lastSlotNs + refresh;
            ideal = slot;
        }

        long lead = slot - earliest;

        // Hard cap on the latency the buffer may add
        boolean capped = false;
        // Game mode with more frames than refreshes (e.g. 90 fps on 60 Hz): no buffer at all.
        // Every frame goes to the earliest refresh it can make and the newest one wins there.
        final long maxLead = (game && streamFasterThanDisplay) ? 0L : (haveGrid
                ? Math.max(1L, Math.round(maxLeadFrames * frame / (double) refresh)) * refresh
                : (long) (maxLeadFrames * frame));
        if (lead > maxLead) {
            slot = earliest + maxLead;
            // Replace at most the previous frame, never frames queued before it
            if (lastSlotNs != 0L && slot < lastSlotNs) {
                slot = lastSlotNs;
            }
            ideal = slot;
            lead = slot - earliest;
            capped = true;
            capRun++;
        } else if (capRun > 0) {
            if (game && !streamFasterThanDisplay && capRun <= GAME_SMALL_CAP_RUN) {
                if (lastCapNs != 0L && nowNs - lastCapNs < GAME_GROW_IF_CAPPED_WITHIN_NS &&
                        gameLeadFrames < GAME_MAX_LEAD_FRAMES) {
                    gameLeadFrames++;
                    lastLeadChangeNs = nowNs;
                }
                lastCapNs = nowNs;
            }
            capRun = 0;
        }

        // Trim buffer that has gone unused for a whole window, keeping one spare frame
        boolean trimmed = false;
        if (windowStartNs == 0L) {
            windowStartNs = nowNs;
        }
        if (lead < windowMinLeadNs) {
            windowMinLeadNs = lead;
        }
        // Game mode: a spare frame that stays queued for GAME_TRIM_AFTER_FRAMES frames in a row
        // is pure latency (e.g. the leftover of a burst after a network stall): remove it.
        boolean gameTrimDue = false;
        if (game) {
            if (!capped && lead >= frame - refresh / 2) {
                if (++spareStreak >= GAME_TRIM_AFTER_FRAMES) {
                    gameTrimDue = true;
                    spareStreak = 0;
                }
            } else {
                spareStreak = 0;
            }
        }
        final boolean windowDone = nowNs - windowStartNs >= trimWindowNs;
        if (gameTrimDue || (!game && windowDone)) {
            // (A capped frame has already given up its extra lead.)
            if (!capped && (gameTrimDue || (windowMinLeadNs >= keepSpareNs + frame - refresh / 2 &&
                    (nowNs - lastTrimNs) >= trimCooldownNs))) {
                final long step = haveGrid
                        ? Math.max(1L, Math.round(frame / (double) refresh)) * refresh
                        : frame;
                long trimmedSlot = slot - step;
                if (trimmedSlot < earliest) {
                    trimmedSlot = earliest;
                }
                // Replace at most the previous frame, never frames queued before it
                if (lastSlotNs != 0L && trimmedSlot < lastSlotNs) {
                    trimmedSlot = lastSlotNs;
                }
                if (trimmedSlot < slot) {
                    slot = trimmedSlot;
                    ideal = slot;
                    lead = slot - earliest;
                    trimmed = true;
                    lastTrimNs = nowNs;
                }
            }
        }
        if (windowDone) {
            windowStartNs = nowNs;
            windowMinLeadNs = Long.MAX_VALUE;
        }

        // A frame planned for the previous frame's refresh (or earlier) replaces it on purpose:
        // don't count that one as a drop when the display never shows it.
        if (lastSlotNs != 0L && slot < lastSlotNs + refresh / 2) {
            planSlotNs[(planPos - 1) & PLAN_MASK] = 0L;
        }

        lastSlotNs = slot;
        nextIdealNs = ideal + frame;

        // Remember the plan so the frame-rendered callback can tell whether it was kept
        final int pos = planPos;
        planPtsUs[pos] = ptsUs;
        planSlotNs[pos] = slot;
        planPos = (pos + 1) & PLAN_MASK;

        TvDiagnostics.onFrameScheduled(lead, late, trimmed, capped);
        TvDiagnostics.setVsyncInfo(haveGrid ? refresh : 0L, presentationGrid, pipeline);
        TvDiagnostics.setBufferLimitFrames(game ? (int) maxLeadFrames : (int) MAX_LEAD_FRAMES);

        // Mid-way between the previous refresh and the planned one
        return haveGrid ? slot - refresh / 2 : slot;
    }

    /**
     * MediaCodec frame-rendered callback: compares when a frame was shown with the plan, and
     * notices frames the display dropped although we gave each one its own refresh.
     */
    synchronized void onFrameRendered(long ptsUs, long renderNs) {
        final int newest = (planPos - 1) & PLAN_MASK;
        int found = -1;
        for (int i = 0; i < PLAN_RING; i++) {
            final int idx = (newest - i) & PLAN_MASK;
            if (planSlotNs[idx] != 0L && planPtsUs[idx] == ptsUs) {
                found = idx;
                break;
            }
        }
        if (found < 0) {
            return;
        }

        final long offset = renderNs - planSlotNs[found];
        planSlotNs[found] = 0L;
        TvDiagnostics.onPresentOffset(offset, lastPeriodNs);

        // Frames are shown in order, so anything older still pending was dropped
        int dropped = 0;
        for (int i = 1; i < PLAN_RING; i++) {
            final int idx = (found - i) & PLAN_MASK;
            if (idx == newest) {
                break;
            }
            if (planSlotNs[idx] != 0L) {
                planSlotNs[idx] = 0L;
                dropped++;
            }
        }

        if (dropped > 0) {
            TvDiagnostics.onUnplannedDrops(dropped);
            // Most likely those frames were queued after SurfaceFlinger's deadline for their
            // refresh: plan further ahead from now on.
            final long maxExtra = (lastPeriodNs * 3) / 2;
            if (adaptiveExtraNs < maxExtra && (lastAdaptNs == 0L || renderNs - lastAdaptNs >= ADAPT_COOLDOWN_NS)) {
                adaptiveExtraNs = Math.min(maxExtra, adaptiveExtraNs + lastPeriodNs / 4);
                lastAdaptNs = renderNs;
                TvDiagnostics.setAdaptiveMarginNs(adaptiveExtraNs);
            }
        }
    }
}
