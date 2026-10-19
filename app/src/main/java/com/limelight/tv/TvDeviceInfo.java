package com.limelight.tv;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Range;
import android.view.Display;
import android.view.WindowManager;

import androidx.preference.PreferenceManager;

import com.limelight.BuildConfig;
import com.limelight.binding.video.TvDiagnostics;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Artemide TV: everything about the TV that matters for streaming, as text for the logs.
 * The TV, its firmware and chip, memory, the screen and every mode it offers, the video
 * decoders and what each says it can do, the network, and the app's settings.
 */
public final class TvDeviceInfo {
    private TvDeviceInfo() {}

    /** True when the TV has a hardware (not software-only) decoder for this type. */
    public static boolean hasHardwareDecoder(String mime) {
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (info.isSoftwareOnly() || info.isAlias())) {
                    continue;
                }
                final String n = info.getName().toLowerCase(Locale.US);
                if (n.startsWith("omx.google.") || n.startsWith("c2.android.")) {
                    continue;
                }
                for (String t : info.getSupportedTypes()) {
                    if (t.equalsIgnoreCase(mime)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** All sections, for a log. */
    public static String describeAll(Context context) {
        final StringBuilder b = new StringBuilder(8192);
        b.append("TV\n").append(describeDevice(context)).append('\n');
        b.append("SCREEN\n").append(describeScreen(context)).append('\n');
        b.append("VIDEO DECODERS\n").append(describeDecoders()).append('\n');
        b.append("NETWORK\n").append(TvDiagnostics.describeNetwork(context)).append("\n\n");
        b.append("SETTINGS\n").append(describeSettings(context)).append('\n');
        return b.toString();
    }

    public static String describeDevice(Context context) {
        final StringBuilder b = new StringBuilder(1024);
        b.append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" · product ").append(Build.PRODUCT).append(" · device ").append(Build.DEVICE)
                .append(" · board ").append(Build.BOARD).append(" · hardware ").append(Build.HARDWARE).append('\n');
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.append("Chip: ").append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL).append('\n');
        }
        b.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            b.append(" · security patch ").append(Build.VERSION.SECURITY_PATCH);
        }
        b.append('\n');
        b.append("Firmware: ").append(Build.DISPLAY).append('\n');
        b.append("Build: ").append(Build.FINGERPRINT).append('\n');
        b.append("CPU: ").append(Runtime.getRuntime().availableProcessors()).append(" cores · ABIs ")
                .append(String.join(",", Build.SUPPORTED_ABIS)).append('\n');
        try {
            final ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            final ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            b.append(String.format(Locale.US, "Memory: %d MB free of %d MB (low below %d MB)%s · app limit %d MB (large %d MB)\n",
                    mi.availMem >> 20, mi.totalMem >> 20, mi.threshold >> 20, mi.lowMemory ? " · LOW NOW" : "",
                    am.getMemoryClass(), am.getLargeMemoryClass()));
        } catch (Throwable ignored) { }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                final PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                b.append("Temperature state: ").append(pm.getCurrentThermalStatus())
                        .append(" (0 normal, 1 warm, 2 hot, 3+ slowing down)\n");
            } catch (Throwable ignored) { }
        }
        b.append(String.format(Locale.US, "Up %d min · app %s (%d, %s)\n",
                SystemClock.elapsedRealtime() / 60000, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                BuildConfig.BUILD_TYPE));
        return b.toString();
    }

    @SuppressWarnings("deprecation")
    public static String describeScreen(Context context) {
        final StringBuilder b = new StringBuilder(1024);
        try {
            final Display d = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            b.append("Now: ").append(TvDiagnostics.describeDisplay(context)).append('\n');
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                final Display.Mode now = d.getMode();
                final TreeMap<String, TreeSet<Float>> modes = new TreeMap<>();
                for (Display.Mode m : d.getSupportedModes()) {
                    final String key = String.format(Locale.US, "%5d x %-5d", m.getPhysicalWidth(), m.getPhysicalHeight());
                    TreeSet<Float> rates = modes.get(key);
                    if (rates == null) {
                        rates = new TreeSet<>();
                        modes.put(key, rates);
                    }
                    rates.add(m.getRefreshRate());
                }
                b.append("Modes offered to apps (current ").append(now.getPhysicalWidth()).append('x')
                        .append(now.getPhysicalHeight()).append(" @ ").append(String.format(Locale.US, "%.3f", now.getRefreshRate()))
                        .append(", id ").append(now.getModeId()).append("):\n");
                for (Map.Entry<String, TreeSet<Float>> e : modes.entrySet()) {
                    b.append("  ").append(e.getKey()).append(" @ ");
                    boolean first = true;
                    for (float r : e.getValue()) {
                        b.append(first ? "" : ", ").append(String.format(Locale.US, "%.2f", r));
                        first = false;
                    }
                    b.append(" Hz\n");
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                final Display.HdrCapabilities hdr = d.getHdrCapabilities();
                if (hdr != null) {
                    b.append("HDR types:");
                    for (int t : hdr.getSupportedHdrTypes()) {
                        switch (t) {
                            case Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION: b.append(" DolbyVision"); break;
                            case Display.HdrCapabilities.HDR_TYPE_HDR10: b.append(" HDR10"); break;
                            case Display.HdrCapabilities.HDR_TYPE_HLG: b.append(" HLG"); break;
                            default: b.append(' ').append(t); break;
                        }
                    }
                    b.append(String.format(Locale.US, " · peak %.0f nits\n", hdr.getDesiredMaxLuminance()));
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                b.append("Game picture mode (minimal post-processing) supported: ")
                        .append(d.isMinimalPostProcessingSupported() ? "yes" : "no").append('\n');
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                b.append("Presentation deadline ").append(d.getPresentationDeadlineNanos() / 1000).append(" µs · app vsync offset ")
                        .append(d.getAppVsyncOffsetNanos() / 1000).append(" µs\n");
            }
        } catch (Throwable t) {
            b.append("? (").append(t).append(")\n");
        }
        return b.toString();
    }

    /** Hardware video decoders: name, types, low-latency feature, sizes and frame rates. */
    public static String describeDecoders() {
        final StringBuilder b = new StringBuilder(4096);
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (!type.startsWith("video/")) {
                        continue;
                    }
                    final boolean interesting = type.equalsIgnoreCase("video/hevc") || type.equalsIgnoreCase("video/av01")
                            || type.equalsIgnoreCase("video/avc");
                    if (!interesting) {
                        continue;
                    }
                    b.append(info.getName()).append(" · ").append(type);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        b.append(info.isSoftwareOnly() ? " · software" : " · hardware");
                        if (info.isAlias()) {
                            b.append(" · alias of ").append(info.getCanonicalName());
                        }
                    }
                    try {
                        final MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            b.append(" · low-latency ").append(caps.isFeatureSupported(
                                    MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency) ? "yes" : "no");
                        }
                        b.append(" · adaptive ").append(caps.isFeatureSupported(
                                MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback) ? "yes" : "no");
                        b.append(" · max instances ").append(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                                ? caps.getMaxSupportedInstances() : -1);
                        final MediaCodecInfo.VideoCapabilities v = caps.getVideoCapabilities();
                        if (v != null) {
                            b.append(" · up to ").append(v.getSupportedWidths().getUpper()).append('x')
                                    .append(v.getSupportedHeights().getUpper())
                                    .append(" · bitrate up to ").append(v.getBitrateRange().getUpper() / 1_000_000).append(" Mbps");
                            b.append('\n');
                            appendRates(b, v, 3840, 2160);
                            appendRates(b, v, 2560, 1440);
                            appendRates(b, v, 1920, 1080);
                        } else {
                            b.append('\n');
                        }
                        b.append("    profiles:");
                        final TreeSet<String> profiles = new TreeSet<>();
                        for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
                            profiles.add(profileName(type, pl.profile));
                        }
                        for (String p : profiles) {
                            b.append(' ').append(p);
                        }
                        b.append('\n');
                    } catch (Throwable t) {
                        b.append(" · capabilities: ").append(t).append('\n');
                    }
                }
            }
        } catch (Throwable t) {
            b.append("? (").append(t).append(")\n");
        }
        return b.toString();
    }

    private static void appendRates(StringBuilder b, MediaCodecInfo.VideoCapabilities v, int w, int h) {
        try {
            if (!v.isSizeSupported(w, h)) {
                b.append(String.format(Locale.US, "    %dx%d: not supported\n", w, h));
                return;
            }
            final Range<Double> fps = v.getSupportedFrameRatesFor(w, h);
            b.append(String.format(Locale.US, "    %dx%d: up to %.0f fps", w, h, fps.getUpper()));
            Range<Double> achievable = null;
            try {
                achievable = v.getAchievableFrameRatesFor(w, h);
            } catch (Throwable ignored) { }
            if (achievable != null) {
                b.append(String.format(Locale.US, " (measured by the maker %.0f-%.0f)", achievable.getLower(), achievable.getUpper()));
            }
            b.append(" · 120 fps ").append(v.areSizeAndRateSupported(w, h, 120) ? "yes" : "no");
            b.append('\n');
        } catch (Throwable t) {
            b.append("    ").append(w).append('x').append(h).append(": ").append(t).append('\n');
        }
    }

    private static String profileName(String type, int p) {
        if (type.equalsIgnoreCase("video/hevc")) {
            switch (p) {
                case MediaCodecInfo.CodecProfileLevel.HEVCProfileMain: return "Main";
                case MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10: return "Main10";
                case MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10: return "Main10HDR10";
                case MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus: return "Main10HDR10+";
                case MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill: return "MainStill";
                default: return "0x" + Integer.toHexString(p);
            }
        }
        if (type.equalsIgnoreCase("video/av01")) {
            switch (p) {
                case MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8: return "Main8";
                case MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10: return "Main10";
                case MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10: return "Main10HDR10";
                case MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus: return "Main10HDR10+";
                default: return "0x" + Integer.toHexString(p);
            }
        }
        if (type.equalsIgnoreCase("video/avc")) {
            switch (p) {
                case MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline: return "Baseline";
                case MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline: return "ConstrainedBaseline";
                case MediaCodecInfo.CodecProfileLevel.AVCProfileMain: return "Main";
                case MediaCodecInfo.CodecProfileLevel.AVCProfileHigh: return "High";
                case MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh: return "ConstrainedHigh";
                default: return "0x" + Integer.toHexString(p);
            }
        }
        return "0x" + Integer.toHexString(p);
    }

    /**
     * The app's settings (keys and values), leaving out anything that identifies the PC or
     * the pairing, and the app's own stored logs.
     */
    public static String describeSettings(Context context) {
        final StringBuilder b = new StringBuilder(4096);
        try {
            final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            final TreeMap<String, ?> all = new TreeMap<>(sp.getAll());
            for (Map.Entry<String, ?> e : all.entrySet()) {
                final String k = e.getKey();
                final String kl = k.toLowerCase(Locale.US);
                if (kl.contains("uuid") || kl.contains("unique") || kl.contains("cert") || kl.contains("pass")
                        || kl.contains("token") || kl.contains("secret") || kl.contains("address")
                        || kl.matches("(.*_)?(pin|mac|ip|ips|server|computer|pc)(_.*)?")
                        || kl.startsWith("artemide_tv_")) {
                    continue;
                }
                String v = String.valueOf(e.getValue());
                if (v.length() > 120) {
                    v = v.substring(0, 120) + "… (" + v.length() + " chars)";
                }
                b.append(k).append(" = ").append(v.replace('\n', ' ')).append('\n');
            }
        } catch (Throwable t) {
            b.append("? (").append(t).append(")\n");
        }
        return b.toString();
    }
}
