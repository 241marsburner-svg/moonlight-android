package com.limelight.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.LimeLog;

import java.util.List;
import java.util.Locale;

/**
 * Artemide TV: the auto-tune screen. Launches one test stream after the other (each is a normal
 * stream with the test's settings, see TvAutoTune), shows progress between them and, at the end,
 * the ranking with separate picture, smoothness and latency scores. "Use best" or any single
 * test can be made the saved settings; the full log goes to Download/ArtemideTV.
 */
public class TvAutoTuneActivity extends Activity {
    public static final String EXTRA_GAME_INTENT = "artemide_tv_game_intent";
    private static final long NEXT_TEST_DELAY_MS = 5000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView title;
    private TextView status;
    private TextView table;
    private LinearLayout buttons;
    private boolean launchPending;
    private boolean finishedShown;
    private String logLocation;

    private final Runnable launchNext = new Runnable() {
        @Override
        public void run() {
            launchPending = false;
            if (isFinishing()) {
                return;
            }
            final Intent next = TvAutoTune.nextStep();
            if (next == null) {
                showFinished();
                return;
            }
            try {
                startActivity(next);
            } catch (Throwable t) {
                LimeLog.warning("Artemide TV auto-tune: could not start a test stream: " + t);
                TvAutoTune.onStepFailed(TvAutoTuneActivity.this, "could not start the stream");
                TvAutoTune.onStepClosed();
                scheduleNext();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        final Intent gameIntent = getIntent().getParcelableExtra(EXTRA_GAME_INTENT);
        if (gameIntent != null && savedInstanceState == null && !TvAutoTune.isRunning()) {
            TvAutoTune.begin(this, gameIntent);
        }
    }

    private void buildUi() {
        final int pad = dp(40);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(24), pad, dp(24));
        root.setBackgroundColor(0xFF101418);

        title = new TextView(this);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setText("Auto-tune");
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(0xFFCFD8DC);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setPadding(0, dp(8), 0, dp(12));
        root.addView(status);

        final ScrollView scroll = new ScrollView(this);
        table = new TextView(this);
        table.setTextColor(0xFFECEFF1);
        table.setTypeface(Typeface.MONOSPACE);
        table.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        scroll.addView(table);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.START);
        buttons.setPadding(0, dp(12), 0, 0);
        root.addView(buttons);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from a test stream?
        TvAutoTune.onStepClosed();

        if (TvAutoTune.isRunning()) {
            if (TvAutoTune.finishedAll()) {
                showFinished();
            } else {
                scheduleNext();
            }
        } else {
            showFinished();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Don't start a stream behind the user's back if they left this screen
        if (launchPending && !isFinishing()) {
            handler.removeCallbacks(launchNext);
            launchPending = false;
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(launchNext);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (TvAutoTune.isRunning()) {
            handler.removeCallbacks(launchNext);
            launchPending = false;
            TvAutoTune.abort("stopped by you");
            showFinished();
        } else {
            super.onBackPressed();
        }
    }

    private void scheduleNext() {
        if (launchPending) {
            return;
        }
        launchPending = true;
        final int done = TvAutoTune.completedCount();
        final int total = TvAutoTune.planSize();
        title.setText(String.format(Locale.US, "Auto-tune: test %d of %d", done + 1, total));
        status.setText(String.format(Locale.US,
                "Next: %s, starting in %d s. Each test takes about a minute. Leave the game (or the stream " +
                        "test page) running on the PC and don't touch the controller. Back stops the auto-tune.",
                TvAutoTune.nextLabel(), NEXT_TEST_DELAY_MS / 1000));
        table.setText(done > 0 ? "Results so far:\n\n" + TvAutoTune.summaryText() : "");
        setButtons(new String[] { "Stop" }, new Runnable[] { this::onBackPressed });
        handler.postDelayed(launchNext, NEXT_TEST_DELAY_MS);
    }

    private void showFinished() {
        handler.removeCallbacks(launchNext);
        launchPending = false;
        final List<TvAutoTune.Result> results = TvAutoTune.results();
        if (results.isEmpty()) {
            final String last = TvAutoTune.lastSummary(this);
            title.setText("Auto-tune");
            status.setText(last != null
                    ? "Last auto-tune results:"
                    : "No results yet. Start a stream, open the stream menu and choose Auto-tune.");
            table.setText(last != null ? last : "");
            setButtons(new String[] { "Close" }, new Runnable[] { this::finish });
            return;
        }

        if (!finishedShown) {
            finishedShown = true;
            if (TvAutoTune.isRunning()) {
                TvAutoTune.abort(null); // completed: nothing left to run
            }
            logLocation = TvAutoTune.writeLogFile(this);
        }

        final TvAutoTune.Result best = TvAutoTune.best();
        title.setText("Auto-tune results");
        final StringBuilder s = new StringBuilder();
        if (best != null) {
            s.append(String.format(Locale.US, "Best overall: %s (total %.0f: picture %.0f, smoothness %.0f, latency %.0f).",
                    best.config.describe(), best.total, best.picture, best.smooth, best.latency));
        } else {
            s.append("No test finished. Check that the PC is reachable and try again.");
        }
        if (logLocation != null) {
            s.append("\nFull log with every measurement: ").append(logLocation);
        }
        status.setText(s);
        table.setText(TvAutoTune.summaryText());

        if (best != null) {
            setButtons(new String[] { "Use best", "Pick a test", "Keep my settings" },
                    new Runnable[] { () -> use(best), this::pick, this::finish });
        } else {
            setButtons(new String[] { "Close" }, new Runnable[] { this::finish });
        }
    }

    private void use(TvAutoTune.Result r) {
        TvAutoTune.use(this, r.config);
        Toast.makeText(this, "Now using " + r.config.describe(), Toast.LENGTH_LONG).show();
        finish();
    }

    private void pick() {
        final List<TvAutoTune.Result> ranked = TvAutoTune.ranked();
        final java.util.ArrayList<TvAutoTune.Result> usable = new java.util.ArrayList<>();
        final java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        for (TvAutoTune.Result r : ranked) {
            if (r.ok) {
                usable.add(r);
                labels.add(String.format(Locale.US, "%s  ·  total %.0f · picture %.0f · smooth %.0f · latency %.0f",
                        r.config.describe(), r.total, r.picture, r.smooth, r.latency));
            }
        }
        if (usable.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Use which settings?")
                .setItems(labels.toArray(new String[0]), (d, which) -> use(usable.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setButtons(String[] labels, Runnable[] actions) {
        buttons.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            final Button b = new Button(this);
            b.setText(labels[i]);
            b.setAllCaps(false);
            final Runnable action = actions[i];
            b.setOnClickListener(v -> action.run());
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(16));
            buttons.addView(b, lp);
        }
        if (buttons.getChildCount() > 0) {
            final View first = buttons.getChildAt(0);
            first.post(first::requestFocus);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
