package com.limelight.tv;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;

/**
 * First-run defaults for the Artemide TV build.
 *
 * The TV build installs as its own app, so it starts with empty settings. These defaults
 * are written once, before any settings screen can persist the stock (phone-oriented)
 * defaults, and only for keys the user hasn't set. Everything stays changeable in Settings.
 */
public final class TvDefaults {
    // Bump when new defaults are added; only keys that are still unset get written.
    private static final int DEFAULTS_VERSION = 2;
    private static final String DEFAULTS_VERSION_KEY = "artemide_tv_defaults_version";

    private TvDefaults() {}

    public static void applyIfNeeded(Context context) {
        if (!BuildConfig.TV_BUILD || context == null) {
            return;
        }

        try {
            final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            final int applied = sp.getInt(DEFAULTS_VERSION_KEY, 0);
            if (applied >= DEFAULTS_VERSION) {
                return;
            }

            final SharedPreferences.Editor e = sp.edit();

            // v2: games first. TV game pacing replaces TV smooth if that was still our default,
            // and the TV is asked for its low-latency picture mode.
            if (applied >= 1 && "tv".equals(sp.getString("frame_pacing", null))) {
                e.putString("frame_pacing", "tvgame");
            }
            putBooleanIfAbsent(sp, e, "checkbox_tv_game_mode", true);

            // Stream: 1440p60 HEVC (the app's own default bitrate for this is 40 Mbps)
            putStringIfAbsent(sp, e, "list_resolution", "2560x1440");
            putStringIfAbsent(sp, e, "list_fps", "60");
            putStringIfAbsent(sp, e, "video_format", "forceh265");

            // Pacing: TV smooth (jitter buffer in SurfaceFlinger), no frame-dropping helpers
            putStringIfAbsent(sp, e, "frame_pacing", "tvgame");
            putBooleanIfAbsent(sp, e, "checkbox_async_prefer_lower_delays", false);
            putBooleanIfAbsent(sp, e, "checkbox_sync_lfr", false);
            putBooleanIfAbsent(sp, e, "checkbox_immediate_frame_delivery", false);
            putBooleanIfAbsent(sp, e, "checkbox_custom_decoder_output_timeout_us", false);
            putBooleanIfAbsent(sp, e, "checkbox_Vsync", false);
            putBooleanIfAbsent(sp, e, "checkbox_fastVsync", false);

            // Keep the TV's own video path: no GL upscaling on the single-core Mali GPU
            putBooleanIfAbsent(sp, e, "pref_video_upscale_enable", false);
            putBooleanIfAbsent(sp, e, "checkbox_gpu_path_mode", false);
            putStringIfAbsent(sp, e, "pref_cpu_warmup_boost", "off");

            // Display: really switch the TV to the matching refresh rate (not just a hint)
            putBooleanIfAbsent(sp, e, "checkbox_enforce_display_mode", true);
            // Show 90/120 FPS options when the TV offers those modes
            putBooleanIfAbsent(sp, e, "checkbox_unlock_fps", true);

            // Apollo virtual display matches the stream exactly
            putBooleanIfAbsent(sp, e, "checkbox_use_virtual_display", true);

            // Network: keep TV Wi-Fi awake between frames
            putBooleanIfAbsent(sp, e, "checkbox_prevent_packet_loss", true);

            // Decoder + audio tuning for MediaTek TV SoCs
            putBooleanIfAbsent(sp, e, "checkbox_mtk_full_speed_hints", true);
            putStringIfAbsent(sp, e, "list_audio_buffer_mode", "normal");

            // Stats on for the first test sessions (toggle in-stream: menu > Advanced > Toggle Performance Overlay)
            putBooleanIfAbsent(sp, e, "checkbox_enable_perf_overlay", true);
            putBooleanIfAbsent(sp, e, "checkbox_enable_perf_overlay_lite", false);

            e.putInt(DEFAULTS_VERSION_KEY, DEFAULTS_VERSION);
            e.apply();

            LimeLog.info("Artemide TV: applied first-run TV defaults v" + DEFAULTS_VERSION);
        } catch (Throwable t) {
            LimeLog.warning("Artemide TV: failed to apply TV defaults: " + t);
        }
    }

    private static void putStringIfAbsent(SharedPreferences sp, SharedPreferences.Editor e, String key, String value) {
        if (!sp.contains(key)) {
            e.putString(key, value);
        }
    }

    private static void putBooleanIfAbsent(SharedPreferences sp, SharedPreferences.Editor e, String key, boolean value) {
        if (!sp.contains(key)) {
            e.putBoolean(key, value);
        }
    }
}
