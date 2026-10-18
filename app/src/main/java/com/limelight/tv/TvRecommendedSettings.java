package com.limelight.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import androidx.preference.PreferenceManager;

import com.limelight.LimeLog;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/**
 * Artemide TV: one tap to the settings that work best on the TCL C7K (Pentonic 700).
 *
 * 1440p is decoded in about 7 ms on this chip against 13 ms for 4K (moonlight-tcl's measurements
 * on the same platform) and the TV scales it to the panel, so 1440p60 HEVC with TV game pacing is
 * the base. The bitrate follows the connection: the TV's own LAN port is only 100 Mbit and Wi-Fi
 * depends on the link. Audio is left as it is (TV speakers or sound on the PC is the user's call).
 */
public final class TvRecommendedSettings {
    private TvRecommendedSettings() {}

    /** Writes the recommended settings and returns a one-line summary of what was set. */
    public static String apply(Context context) {
        final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        final int bitrateKbps = recommendedBitrateKbps(context);
        final SharedPreferences.Editor e = sp.edit();

        // Stream
        e.putString("list_resolution", "2560x1440");
        e.putString("list_fps", "60");
        e.putString("video_format", "forceh265");
        e.putInt("seekbar_bitrate_kbps", bitrateKbps);

        // Pacing: TV game (smallest buffer that still hides Wi-Fi jitter), no frame-dropping helpers
        e.putString("frame_pacing", "tvgame");
        e.putBoolean("checkbox_tv_aggressive_timing", false);
        e.putBoolean("checkbox_async_decode", true);
        e.putBoolean("checkbox_async_prefer_lower_delays", false);
        e.putBoolean("checkbox_sync_lfr", false);
        e.putBoolean("checkbox_immediate_frame_delivery", false);
        e.putBoolean("checkbox_custom_decoder_output_timeout_us", false);
        e.putBoolean("checkbox_Vsync", false);
        e.putBoolean("checkbox_fastVsync", false);

        // The TV's own video path: no GL upscaling, no extra layer over the video
        e.putBoolean("pref_video_upscale_enable", false);
        e.putBoolean("checkbox_gpu_path_mode", false);
        e.putBoolean("checkbox_enable_perf_overlay", false);
        e.putBoolean("checkbox_enable_perf_overlay_lite", false);
        e.putBoolean("checkbox_tv_compositor_workaround", false);

        // Decoder: the set measured fastest on this chip
        e.putBoolean("checkbox_mtk_full_speed_hints", true);
        e.putBoolean("checkbox_mtk_extra_decoder_keys", false);

        // Display, network, Apollo
        e.putBoolean("checkbox_tv_game_mode", true);
        e.putBoolean("checkbox_enforce_display_mode", true);
        e.putBoolean("checkbox_unlock_fps", true);
        e.putBoolean("checkbox_use_virtual_display", true);
        e.putBoolean("checkbox_prevent_packet_loss", true);

        // Figures after each stream, measured with nothing over the video
        e.putBoolean("checkbox_tv_stream_report", true);
        e.apply();

        final String summary = String.format(Locale.US,
                "1440p · 60 fps · HEVC · %d Mbps (%s) · TV game pacing", bitrateKbps / 1000, describeLink(context));
        LimeLog.info("Artemide TV: recommended settings applied: " + summary);
        return summary;
    }

    /**
     * 80 Mbps when the link carries it easily, less on a slower link. The TV's built-in Ethernet
     * port is 100 Mbit: with FEC and audio on top a stream above ~60 Mbps would not fit.
     */
    static int recommendedBitrateKbps(Context context) {
        try {
            final ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                final Network net = cm.getActiveNetwork();
                final NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    final int speed = ethernetSpeedMbps(cm, net);
                    return speed >= 1000 ? 80_000 : 60_000;
                }
            }
            final WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            final WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
            final int link = wifiLinkMbps(info);
            if (link > 0) {
                if (link >= 600) {
                    return 80_000;
                }
                if (link >= 300) {
                    return 60_000;
                }
                return 40_000;
            }
        } catch (Throwable t) {
            LimeLog.warning("Artemide TV: link check failed: " + t);
        }
        return 60_000;
    }

    private static String describeLink(Context context) {
        try {
            final ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                final Network net = cm.getActiveNetwork();
                final NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    final int speed = ethernetSpeedMbps(cm, net);
                    return speed > 0 ? "Ethernet " + speed + " Mbit" : "Ethernet";
                }
            }
            final WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            final WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
            final int link = wifiLinkMbps(info);
            if (link > 0) {
                return "Wi-Fi link " + link + " Mbit";
            }
        } catch (Throwable ignored) { }
        return "connection unknown";
    }

    /** The receive rate (what the stream uses) when Android reports it, else the link speed. */
    private static int wifiLinkMbps(WifiInfo info) {
        if (info == null) {
            return 0;
        }
        int link = info.getLinkSpeed();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            final int rx = info.getRxLinkSpeedMbps();
            if (rx > 0) {
                link = rx;
            }
        }
        return Math.max(0, link);
    }

    /** Link speed from sysfs when the TV lets apps read it, otherwise 0. */
    private static int ethernetSpeedMbps(ConnectivityManager cm, Network net) {
        try {
            final LinkProperties lp = cm.getLinkProperties(net);
            final String iface = lp != null ? lp.getInterfaceName() : null;
            if (iface == null || iface.isEmpty() || iface.contains("/") || iface.contains("..")) {
                return 0;
            }
            try (BufferedReader r = new BufferedReader(new FileReader("/sys/class/net/" + iface + "/speed"))) {
                final String line = r.readLine();
                return line != null ? Math.max(0, Integer.parseInt(line.trim())) : 0;
            }
        } catch (Throwable t) {
            return 0;
        }
    }
}
