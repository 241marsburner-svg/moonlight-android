package com.limelight.binding.audio;

/**
 * Thin Java side of the native AAudio output path (aaudio_renderer.c, from pabragin/moonlight-tcl).
 * Decoded PCM never passes through Java: moonlight-common-c's audio thread writes into the native
 * ring directly.
 */
public final class AAudioRenderer {
    static {
        // libmoonlight-core is loaded by MoonBridge; make sure that happened before our natives are bound
        try {
            Class.forName("com.limelight.nvstream.jni.MoonBridge");
        } catch (ClassNotFoundException ignored) {
        }
    }

    private AAudioRenderer() {
    }

    private static native int nativeSetup(int channelCount, int sampleRate, int samplesPerFrame, int maxPendingMs);
    private static native int nativeStart();
    private static native void nativeStop();
    private static native void nativeCleanup();
    private static native boolean nativeIsActive();
    private static native int[] nativeGetStats();

    /** @return 0 on success, negative on failure (the caller falls back to AudioTrack). */
    public static int setup(int channelCount, int sampleRate, int samplesPerFrame, int maxPendingMs) {
        try {
            return nativeSetup(channelCount, sampleRate, samplesPerFrame, maxPendingMs);
        } catch (Throwable t) {
            return -101;
        }
    }

    public static int start() {
        try {
            return nativeStart();
        } catch (Throwable t) {
            return -101;
        }
    }

    public static void stop() {
        try {
            nativeStop();
        } catch (Throwable ignored) {
        }
    }

    public static void cleanup() {
        try {
            nativeCleanup();
        } catch (Throwable ignored) {
        }
    }

    public static boolean isActive() {
        try {
            return nativeIsActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /** {latency ms, underruns, dropped packets, stream reopens}, or null. */
    public static int[] stats() {
        try {
            final int[] s = nativeGetStats();
            return (s != null && s.length >= 4) ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** One line for the stream report and the log. */
    public static String describeStats() {
        final int[] s = stats();
        if (s == null) {
            return "AAudio";
        }
        return "AAudio: " + (s[0] >= 0 ? s[0] + " ms queued" : "latency unknown") +
                ", " + s[1] + " underruns, " + s[2] + " dropped packets, " + s[3] + " reopens";
    }
}
