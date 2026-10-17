package com.limelight.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * Artemide TV: the report shown after a stream.
 *
 * The figures are collected during the stream with nothing drawn over the video (the overlay
 * would itself cost the TV's compositor about 25 ms per frame), saved when the stream ends and
 * shown on the next screen of the app. A photo of it is all that is needed to tune the TV build.
 */
public final class TvStreamReport {
    private static final String KEY_TEXT = "artemide_tv_report_text";
    private static final String KEY_PENDING = "artemide_tv_report_pending";

    private TvStreamReport() {}

    public static void save(Context context, String text) {
        if (!BuildConfig.TV_BUILD || context == null || text == null) {
            return;
        }
        try {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putString(KEY_TEXT, text)
                    .putBoolean(KEY_PENDING, true)
                    .apply();
        } catch (Throwable t) {
            LimeLog.warning("Artemide TV: could not save the stream report: " + t);
        }
    }

    /** Shows the last stream's report once, if there is a new one and the setting is on. */
    public static void showIfPending(final Activity activity) {
        if (!BuildConfig.TV_BUILD || activity == null || activity.isFinishing()) {
            return;
        }
        try {
            final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(activity);
            if (!sp.getBoolean(KEY_PENDING, false)) {
                return;
            }
            sp.edit().putBoolean(KEY_PENDING, false).apply();
            if (!sp.getBoolean(PreferenceConfiguration.TV_STREAM_REPORT_PREF_STRING, true)) {
                return;
            }
            final String text = sp.getString(KEY_TEXT, null);
            if (text == null || text.isEmpty()) {
                return;
            }

            final TextView view = new TextView(activity);
            view.setText(text);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            view.setLineSpacing(0f, 1.15f);
            view.setTextIsSelectable(false);
            final int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
            view.setPadding(pad, pad / 2, pad, 0);
            final ScrollView scroll = new ScrollView(activity);
            scroll.addView(view);

            new AlertDialog.Builder(activity)
                    .setTitle("Stream report")
                    .setView(scroll)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton("Don't show again", (d, w) ->
                            sp.edit().putBoolean(PreferenceConfiguration.TV_STREAM_REPORT_PREF_STRING, false).apply())
                    .show();
        } catch (Throwable t) {
            LimeLog.warning("Artemide TV: could not show the stream report: " + t);
        }
    }
}
