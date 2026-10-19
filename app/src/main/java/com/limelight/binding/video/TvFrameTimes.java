package com.limelight.binding.video;

/**
 * Artemide TV: when the PC captured each frame.
 *
 * Every frame carries the PC's capture time (its own clock, microseconds since the stream's first
 * frame). The network may bunch frames together or hold them back, but these times stay in the
 * PC's rhythm, so they tell PC-side stalls (nothing captured) from late deliveries (captured on
 * time, arrived late), and they let the pacer group frames by when they were captured.
 *
 * The receive thread notes the time of the frame it is about to submit; the decoder input side
 * pairs it with the frame's decoder timestamp; the output side looks it up again by that
 * timestamp when the frame comes out of the decoder.
 */
public final class TvFrameTimes {
    private TvFrameTimes() {}

    private static final int RING = 256; // power of two, a couple of seconds at 120 fps
    private static final int MASK = RING - 1;
    private static final long[] ptsUs = new long[RING];
    private static final long[] hostUs = new long[RING];
    private static int pos;

    // Set by the receive thread right before it submits the frame (same thread reads it)
    private static volatile long currentHostUs = -1;

    /** The receive thread: the next frame for the decoder was captured at this PC time. */
    public static void onFrameArrived(long captureUs) {
        currentHostUs = captureUs;
    }

    /** Decoder input: the frame now being queued gets this decoder timestamp. */
    static synchronized void record(long frameTimestampUs) {
        final int p = pos;
        ptsUs[p] = frameTimestampUs;
        hostUs[p] = currentHostUs;
        pos = (p + 1) & MASK;
    }

    /** The PC capture time of a frame by its decoder timestamp, or -1 if unknown. */
    static synchronized long hostUsFor(long frameTimestampUs) {
        int p = (pos - 1) & MASK;
        for (int i = 0; i < RING; i++) {
            if (ptsUs[p] == frameTimestampUs) {
                return hostUs[p];
            }
            p = (p - 1) & MASK;
        }
        return -1;
    }

    static synchronized void reset() {
        for (int i = 0; i < RING; i++) {
            ptsUs[i] = 0;
            hostUs[i] = -1;
        }
        pos = 0;
    }
}
