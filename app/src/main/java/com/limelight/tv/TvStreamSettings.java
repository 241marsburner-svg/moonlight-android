package com.limelight.tv;

import com.limelight.nvstream.StreamConfiguration;
import com.limelight.nvstream.jni.MoonBridge;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Artemide TV: the settings a stream really ran with, for the logs.
 *
 * The saved settings are only half the story: the auto-tune replaces some for each test, the app
 * changes others on its own (the display mode it switched to, the decoder options the decoder
 * accepted, the TV's game picture mode, thread priorities, the audio track it got). This collects
 * all of that while the stream starts:
 *   - what was asked of the PC (resolution, frame rate, bitrate, codecs, audio, HDR)
 *   - what the app switched on or changed by itself, and what the TV accepted
 *   - every value of the app's stream settings object, as the stream used it
 *
 * Writers are Game, the decoder and the audio renderer at stream start; readers are the stream
 * report and the auto-tune at the end of a stream.
 */
public final class TvStreamSettings {
    private TvStreamSettings() {}

    private static final LinkedHashMap<String, String> items = new LinkedHashMap<>();
    private static String prefsDump = "";

    /** A new stream starts (Game.onCreate). */
    public static synchronized void reset() {
        items.clear();
        prefsDump = "";
    }

    /** One line of what was used (a later value for the same name replaces the earlier one). */
    public static synchronized void put(String name, String value) {
        items.put(name, value != null ? value.replace('\n', ' ') : "-");
    }

    /** Every public value of the stream's settings object (after the auto-tune's and app's changes). */
    public static synchronized void setSettingsObject(Object prefs) {
        prefsDump = dumpFields(prefs);
    }

    public static synchronized boolean isEmpty() {
        return items.isEmpty() && prefsDump.isEmpty();
    }

    /** All of it as text: the used/changed lines first, then every setting. */
    public static synchronized String text() {
        final StringBuilder b = new StringBuilder(8192);
        for (Map.Entry<String, String> e : items.entrySet()) {
            b.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        try {
            final String auto = com.limelight.binding.video.TvDiagnostics.describeAutomatic();
            if (!auto.isEmpty()) {
                b.append(auto);
            }
        } catch (Throwable ignored) { }
        if (!prefsDump.isEmpty()) {
            b.append("Every stream setting (as used):\n").append(prefsDump);
        }
        return b.toString();
    }

    /**
     * The lines of 'text' that are not in 'reference' (for the auto-tune: what a test changed
     * compared with the first one).
     */
    public static String difference(String text, String reference) {
        if (text == null) {
            return "";
        }
        if (reference == null) {
            return text;
        }
        final Set<String> ref = new HashSet<>(Arrays.asList(reference.split("\n")));
        final StringBuilder b = new StringBuilder();
        for (String line : text.split("\n")) {
            if (!ref.contains(line)) {
                b.append(line).append('\n');
            }
        }
        return b.toString();
    }

    // ---------------- Helpers for the writers ----------------

    /** What the PC is asked for. */
    public static String describeStreamConfig(StreamConfiguration c, boolean metered, int savedBitrateKbps) {
        if (c == null) {
            return "-";
        }
        final StringBuilder b = new StringBuilder(256);
        b.append(c.getWidth()).append('x').append(c.getHeight())
                .append(" · frame rate ").append(c.getRefreshRate())
                .append(" (launch ").append(c.getLaunchRefreshRate()).append(", TV screen ")
                .append(String.format(Locale.US, "%.2f", c.getClientRefreshRateX100() / 100.0)).append(" Hz)")
                .append(" · bitrate ").append(c.getBitrate() / 1000).append(" Mbps");
        if (metered) {
            b.append(" (METERED network: reduced from ").append(savedBitrateKbps / 1000).append(" Mbps)");
        }
        b.append(" · codecs offered ").append(describeFormats(c.getSupportedVideoFormats()))
                .append(" · color ").append(c.getColorSpace() == MoonBridge.COLORSPACE_REC_2020 ? "Rec.2020"
                        : c.getColorSpace() == MoonBridge.COLORSPACE_REC_709 ? "Rec.709" : "Rec.601")
                .append(c.getColorRange() == MoonBridge.COLOR_RANGE_FULL ? " full range" : " limited range")
                .append(" · audio ").append(c.getAudioConfiguration() != null ? c.getAudioConfiguration().channelCount + " ch" : "?")
                .append(c.getPlayLocalAudio() ? " (PC keeps playing its sound)" : "")
                .append(" · virtual display ").append(c.getVirtualDisplay() ? "yes" : "no")
                .append(" · scale ").append(c.getResolutionScaleFactor()).append('%')
                .append(" · packet ").append(c.getMaxPacketSize())
                .append(" · ultra low latency ").append(c.getEnableUltraLowLatency() ? "yes" : "no")
                .append(" · optimize game settings ").append(c.getSops() ? "yes" : "no")
                .append(" · network ").append(c.getRemote() == StreamConfiguration.STREAM_CFG_LOCAL ? "local"
                        : c.getRemote() == StreamConfiguration.STREAM_CFG_REMOTE ? "remote" : "auto");
        return b.toString();
    }

    public static String describeFormats(int mask) {
        final List<String> f = new ArrayList<>();
        if ((mask & MoonBridge.VIDEO_FORMAT_H264) != 0) f.add("H.264");
        if ((mask & MoonBridge.VIDEO_FORMAT_H265) != 0) f.add("HEVC");
        if ((mask & MoonBridge.VIDEO_FORMAT_H265_MAIN10) != 0) f.add("HEVC 10-bit");
        if ((mask & MoonBridge.VIDEO_FORMAT_AV1_MAIN8) != 0) f.add("AV1");
        if ((mask & MoonBridge.VIDEO_FORMAT_AV1_MAIN10) != 0) f.add("AV1 10-bit");
        return f.isEmpty() ? String.format(Locale.US, "0x%x", mask) : String.join(", ", f);
    }

    /** Every public, non-static field of an object as "name = value" lines, sorted by name. */
    static String dumpFields(Object o) {
        if (o == null) {
            return "";
        }
        final TreeMap<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Field f : o.getClass().getFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            String v;
            try {
                final Object x = f.get(o);
                if (x == null) {
                    v = "null";
                } else if (x instanceof int[]) {
                    v = Arrays.toString((int[]) x);
                } else if (x instanceof float[]) {
                    v = Arrays.toString((float[]) x);
                } else if (x instanceof boolean[]) {
                    v = Arrays.toString((boolean[]) x);
                } else if (x instanceof long[]) {
                    v = Arrays.toString((long[]) x);
                } else if (x instanceof Object[]) {
                    v = Arrays.deepToString((Object[]) x);
                } else if (x instanceof MoonBridge.AudioConfiguration) {
                    final MoonBridge.AudioConfiguration a = (MoonBridge.AudioConfiguration) x;
                    v = a.channelCount + " channels (mask 0x" + Integer.toHexString(a.channelMask) + ")";
                } else {
                    v = String.valueOf(x);
                }
            } catch (Throwable t) {
                v = "?";
            }
            if (v.length() > 160) {
                v = v.substring(0, 160) + "…";
            }
            values.put(f.getName(), v.replace('\n', ' '));
        }
        final StringBuilder b = new StringBuilder(values.size() * 32);
        for (Map.Entry<String, String> e : values.entrySet()) {
            b.append("  ").append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        }
        return b.toString();
    }
}
