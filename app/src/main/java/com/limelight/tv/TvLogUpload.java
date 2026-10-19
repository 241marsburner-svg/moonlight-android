package com.limelight.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Artemide TV: "Upload log". Puts together everything needed to tune the TV build (auto-tune
 * results with every test's second-by-second timeline, the last stream's report and timeline,
 * the TV, its screen modes and decoders, the settings, and the app's log), removes addresses
 * and keys, uploads it as an unlisted text paste and shows the link and a QR code for it.
 *
 * Paste services: paste.rs (plain text, readable by Claude's web fetch), dpaste.com as fallback
 * (also used when paste.rs cuts a large log short). A copy always goes to Download/ArtemideTV.
 */
public final class TvLogUpload {
    private TvLogUpload() {}

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 90_000;
    private static final int MAX_CHARS = 950_000;

    /** Builds the complete log (not yet cleaned). */
    public static String buildLog(Context context, String reason) {
        final StringBuilder b = new StringBuilder(256 * 1024);
        b.append("=== ARTEMIDE TV LOG ===\n");
        b.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()))
                .append(" · app ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")")
                .append(" · sent from: ").append(reason).append("\n");
        b.append("Sections: 1 auto-tune · 2 last stream · 3 TV, screen, decoders, network, settings · 4 app log · 5 system log\n\n");

        b.append("=== 1 LAST AUTO-TUNE ===\n");
        final String tune = TvAutoTune.lastLog(context);
        b.append(tune != null ? tune : "(no auto-tune yet)\n").append("\n\n");

        b.append("=== 2 LAST STREAM ===\n");
        final String stream = TvStreamReport.lastStreamLog(context);
        b.append(stream != null ? stream : "(no stream report yet)\n").append("\n\n");

        b.append("=== 3 TV ===\n");
        b.append(TvDeviceInfo.describeAll(context)).append("\n");

        b.append("=== 4 APP LOG (newest last) ===\n");
        String app = TvLog.appLog();
        if (app.length() > 300_000) {
            app = "…\n" + app.substring(app.length() - 300_000);
        }
        b.append(app).append("\n");

        final int room = Math.max(50_000, MAX_CHARS - b.length() - 1000);
        b.append("=== 5 SYSTEM LOG OF THE APP (newest last) ===\n");
        b.append(TvLog.systemLog(3000, Math.min(room, 350_000)));
        b.append("\n=== END ===\n");
        String text = b.toString();
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS) + "\n(cut at " + MAX_CHARS + " characters)\n";
        }
        return text;
    }

    public static final class Result {
        public String url;       // null = failed
        public String service;
        public boolean partial;
        public String error;
        public String savedAt;   // local copy
        public int chars;
    }

    /** Blocking: cleans, uploads and saves a copy. Call off the main thread. */
    public static Result upload(Context context, String rawText) {
        final Result r = new Result();
        final String text = TvLog.sanitize(rawText);
        r.chars = text.length();
        r.savedAt = TvLog.saveToDownloads(context, "log-" +
                new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt", text);

        final StringBuilder errors = new StringBuilder();
        // 1) paste.rs: the body is the paste
        String partialUrl = null;
        try {
            final Response p = post("https://paste.rs/", "text/plain; charset=utf-8",
                    text.getBytes(StandardCharsets.UTF_8));
            final String url = p.body.trim();
            if ((p.code == 201 || p.code == 200) && url.startsWith("http")) {
                r.url = url;
                r.service = "paste.rs";
                return r;
            } else if (p.code == 206 && url.startsWith("http")) {
                partialUrl = url;
                errors.append("paste.rs kept only part of it; ");
            } else {
                errors.append("paste.rs answered ").append(p.code).append("; ");
            }
        } catch (Throwable t) {
            errors.append("paste.rs: ").append(t.getClass().getSimpleName()).append(' ').append(t.getMessage()).append("; ");
        }
        // 2) dpaste.com: form upload, kept 30 days
        try {
            final String form = "content=" + URLEncoder.encode(text, "UTF-8") +
                    "&syntax=text&expiry_days=30&title=" + URLEncoder.encode("Artemide TV log", "UTF-8");
            final Response p = post("https://dpaste.com/api/v2/", "application/x-www-form-urlencoded",
                    form.getBytes(StandardCharsets.UTF_8));
            String url = p.body.trim();
            if (!url.startsWith("http") && p.location != null) {
                url = p.location;
            }
            if ((p.code == 201 || p.code == 200) && url.startsWith("http")) {
                r.url = url;
                r.service = "dpaste.com";
                return r;
            }
            errors.append("dpaste.com answered ").append(p.code).append("; ");
        } catch (Throwable t) {
            errors.append("dpaste.com: ").append(t.getClass().getSimpleName()).append(' ').append(t.getMessage()).append("; ");
        }
        if (partialUrl != null) {
            r.url = partialUrl;
            r.service = "paste.rs";
            r.partial = true;
            return r;
        }
        r.error = errors.toString();
        LimeLog.warning("Artemide TV: log upload failed: " + r.error);
        return r;
    }

    private static final class Response {
        int code;
        String body = "";
        String location;
    }

    private static Response post(String url, String contentType, byte[] body) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", contentType);
            c.setRequestProperty("User-Agent", "ArtemideTV/" + BuildConfig.VERSION_CODE + " (log upload)");
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body);
            }
            final Response r = new Response();
            r.code = c.getResponseCode();
            r.location = c.getHeaderField("Location");
            final InputStream is = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (is != null) {
                try (InputStream in = is) {
                    final ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    final byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0 && bo.size() < 64 * 1024) {
                        bo.write(buf, 0, n);
                    }
                    r.body = new String(bo.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    // ---------------- Screen ----------------

    /**
     * Builds, uploads and shows the link with a QR code. Everything slow runs off the main thread.
     * @param reason where it was started from, for the log's first line
     */
    public static void showDialog(final Activity activity, final String reason) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        final float density = activity.getResources().getDisplayMetrics().density;
        final int pad = (int) (20 * density);

        final LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(pad, pad, pad, pad / 2);
        root.setGravity(Gravity.CENTER_VERTICAL);

        final ImageView qr = new ImageView(activity);
        final int qrPx = (int) (Math.min(260 * density,
                activity.getResources().getDisplayMetrics().heightPixels * 0.5f));
        root.addView(qr, new LinearLayout.LayoutParams(qrPx, qrPx));

        final TextView text = new TextView(activity);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        text.setLineSpacing(0f, 1.2f);
        text.setText("Putting the log together and uploading it…\n\nThis takes a few seconds.");
        final LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMarginStart(pad);
        root.addView(text, tp);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Upload log")
                .setView(root)
                .setPositiveButton("Close", null)
                .create();
        dialog.show();

        final Context app = activity.getApplicationContext();
        new Thread(() -> {
            Result res;
            try {
                res = upload(app, buildLog(app, reason));
            } catch (Throwable t) {
                res = new Result();
                res.error = t.toString();
            }
            final Result r = res;
            final Bitmap code = r.url != null ? TvQr.make(r.url, qrPx) : null;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || !dialog.isShowing()) {
                    return;
                }
                if (r.url != null) {
                    if (code != null) {
                        qr.setImageBitmap(code);
                    }
                    final StringBuilder s = new StringBuilder();
                    s.append("Send this link to Claude:\n\n").append(r.url).append("\n\n");
                    s.append("Scan the code with your phone to open it, then paste the link into the chat. ");
                    s.append(String.format(Locale.US, "(%,d characters, on %s", r.chars, r.service));
                    s.append(r.partial ? ", cut short: the file below is complete)" : ")");
                    s.append("\n\nAnyone with the link can read it. Addresses, keys and IDs were removed.");
                    if (r.savedAt != null) {
                        s.append("\nA copy is in ").append(r.savedAt);
                    }
                    text.setText(s);
                    text.setTextIsSelectable(false);
                } else {
                    final StringBuilder s = new StringBuilder("The upload did not work: ")
                            .append(r.error != null ? r.error : "unknown error");
                    if (r.savedAt != null) {
                        s.append("\n\nThe log was saved to ").append(r.savedAt)
                                .append(". You can send that file instead.");
                    }
                    text.setText(s);
                }
            });
        }, "TvLogUpload").start();
    }
}
