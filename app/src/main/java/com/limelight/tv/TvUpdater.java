package com.limelight.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/**
 * Artemide TV: updates from inside the app. Looks at the newest GitHub release of this build
 * (tv-build-N, made by the CI for every change), downloads ArtemideTV.apk and hands it to the
 * TV's package installer. Android asks once to allow installs from this app, and confirms each
 * update. The download is checked before installing: same app, newer build, same signing key.
 */
public final class TvUpdater {
    private TvUpdater() {}

    private static final String REPO = "241marsburner-svg/moonlight-android";
    private static final String LATEST_API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String APK_NAME = "ArtemideTV.apk";
    // CI builds get versionCode 570000 + the build number (see .github/workflows/build-tv.yml)
    private static final int CI_VERSION_BASE = 570000;
    private static final long AUTO_CHECK_EVERY_MS = 6L * 3600 * 1000;

    public static final String AUTO_CHECK_PREF = "checkbox_tv_auto_update_check";
    private static final String PREF_LAST_CHECK = "artemide_tv_update_last_check";
    private static final String PREF_SKIPPED_BUILD = "artemide_tv_update_skipped_build";

    private static volatile boolean busy;

    public static final class Release {
        public int build;
        public String tag = "", name = "", notes = "", apkUrl;
        public long apkSize;
    }

    /** Build number of this app (0 for a build that did not come from the CI). */
    public static int currentBuild() {
        final int b = BuildConfig.VERSION_CODE - CI_VERSION_BASE;
        return b > 0 ? b : 0;
    }

    // ---------------- Checking ----------------

    /** Blocking. */
    static Release fetchLatest() throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(LATEST_API).openConnection();
        try {
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "ArtemideTV/" + BuildConfig.VERSION_CODE);
            final int code = c.getResponseCode();
            if (code != 200) {
                throw new Exception("GitHub answered " + code);
            }
            final String json;
            try (InputStream in = c.getInputStream()) {
                json = readAll(in, 4 << 20);
            }
            final JSONObject o = new JSONObject(json);
            final Release r = new Release();
            r.tag = o.optString("tag_name", "");
            r.name = o.optString("name", r.tag);
            r.notes = o.optString("body", "");
            final String digits = r.tag.replaceAll("[^0-9]", "");
            r.build = digits.isEmpty() ? 0 : Integer.parseInt(digits);
            final JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    final JSONObject a = assets.getJSONObject(i);
                    if (APK_NAME.equals(a.optString("name"))) {
                        r.apkUrl = a.optString("browser_download_url", null);
                        r.apkSize = a.optLong("size", 0);
                    }
                }
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    /** What the release says changed (the part before the install instructions). */
    private static String whatsNew(Release r) {
        String n = r.notes != null ? r.notes.replace("\r", "") : "";
        final int cut = n.indexOf("\n---");
        if (cut >= 0) {
            n = n.substring(0, cut);
        }
        n = n.trim();
        if (n.length() > 1500) {
            n = n.substring(0, 1500) + "…";
        }
        return n;
    }

    /** App start: at most every few hours, quiet unless there is a newer build. */
    public static void checkOnStart(final Activity activity) {
        if (!BuildConfig.TV_BUILD || activity == null || busy) {
            return;
        }
        final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(activity);
        if (!sp.getBoolean(AUTO_CHECK_PREF, true) || currentBuild() == 0) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - sp.getLong(PREF_LAST_CHECK, 0) < AUTO_CHECK_EVERY_MS) {
            return;
        }
        sp.edit().putLong(PREF_LAST_CHECK, now).apply();
        busy = true;
        new Thread(() -> {
            try {
                final Release r = fetchLatest();
                if (r.build > currentBuild() && r.apkUrl != null && r.build != sp.getInt(PREF_SKIPPED_BUILD, -1)) {
                    activity.runOnUiThread(() -> {
                        if (!activity.isFinishing()) {
                            offer(activity, r, true);
                        }
                    });
                }
            } catch (Throwable t) {
                LimeLog.info("Artemide TV: update check failed: " + t);
            } finally {
                busy = false;
            }
        }, "TvUpdateCheck").start();
    }

    /** Settings: check now and say what was found. */
    public static void checkNow(final Activity activity) {
        if (activity == null) {
            return;
        }
        if (busy) {
            Toast.makeText(activity, "Already checking…", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        Toast.makeText(activity, "Checking for updates…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Release found = null;
            String error = null;
            try {
                found = fetchLatest();
            } catch (Throwable t) {
                error = t.getMessage() != null ? t.getMessage() : t.toString();
            } finally {
                busy = false;
            }
            final Release r = found;
            final String err = error;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) {
                    return;
                }
                if (r == null) {
                    new AlertDialog.Builder(activity)
                            .setTitle("Update")
                            .setMessage("Could not reach GitHub: " + err + "\n\nCheck the TV's internet connection.")
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                } else if (r.build > currentBuild() && r.apkUrl != null) {
                    offer(activity, r, false);
                } else {
                    new AlertDialog.Builder(activity)
                            .setTitle("Up to date")
                            .setMessage(String.format(Locale.US, "You have build %d, the newest is build %d.",
                                    currentBuild(), r.build))
                            .setPositiveButton(android.R.string.ok, null)
                            .setNeutralButton("Install again", (d, w) -> {
                                if (r.apkUrl != null) {
                                    download(activity, r);
                                }
                            })
                            .show();
                }
            });
        }, "TvUpdateCheck").start();
    }

    private static void offer(final Activity activity, final Release r, boolean automatic) {
        final String notes = whatsNew(r);
        final StringBuilder msg = new StringBuilder();
        msg.append(String.format(Locale.US, "Build %d is out (you have build %d).", r.build, currentBuild()));
        if (r.apkSize > 0) {
            msg.append(String.format(Locale.US, " Download %.0f MB.", r.apkSize / 1e6));
        }
        if (!notes.isEmpty()) {
            msg.append("\n\n").append(notes);
        }
        msg.append("\n\nYour settings stay as they are.");
        final AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle("Update Artemide TV")
                .setView(scrollingText(activity, msg.toString()))
                .setPositiveButton("Update now", (d, w) -> download(activity, r))
                .setNegativeButton("Later", null);
        if (automatic) {
            b.setNeutralButton("Skip this build", (d, w) ->
                    PreferenceManager.getDefaultSharedPreferences(activity).edit()
                            .putInt(PREF_SKIPPED_BUILD, r.build).apply());
        }
        b.show();
    }

    private static ScrollView scrollingText(Activity activity, String text) {
        final TextView v = new TextView(activity);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        v.setLineSpacing(0f, 1.15f);
        final int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        v.setPadding(pad, pad / 2, pad, 0);
        final ScrollView sv = new ScrollView(activity);
        sv.addView(v);
        return sv;
    }

    // ---------------- Downloading ----------------

    private static File apkFile(Context context, int build) {
        final File dir = new File(context.getCacheDir(), "updates");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "ArtemideTV-build-" + build + ".apk");
    }

    private static void download(final Activity activity, final Release r) {
        final TextView status = new TextView(activity);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        final int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
        status.setPadding(pad, pad, pad, pad / 2);
        status.setText("Downloading build " + r.build + "…");
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Update Artemide TV")
                .setView(status)
                .setNegativeButton(android.R.string.cancel, null)
                .setCancelable(false)
                .create();
        final boolean[] cancelled = { false };
        dialog.setOnDismissListener(d -> cancelled[0] = true);
        dialog.show();

        final Context app = activity.getApplicationContext();
        new Thread(() -> {
            final File out = apkFile(app, r.build);
            final File part = new File(out.getPath() + ".part");
            String error = null;
            try {
                // Old downloads
                final File[] old = out.getParentFile() != null ? out.getParentFile().listFiles() : null;
                if (old != null) {
                    for (File f : old) {
                        if (!f.equals(out)) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                    }
                }
                if (!(out.isFile() && r.apkSize > 0 && out.length() == r.apkSize)) {
                    downloadTo(r.apkUrl, part, r.apkSize, (done, total) -> activity.runOnUiThread(() -> {
                        if (!cancelled[0]) {
                            status.setText(total > 0
                                    ? String.format(Locale.US, "Downloading build %d… %d %%  (%.0f of %.0f MB)",
                                    r.build, (int) (100 * done / total), done / 1e6, total / 1e6)
                                    : String.format(Locale.US, "Downloading build %d… %.0f MB", r.build, done / 1e6));
                        }
                    }), cancelled);
                    if (cancelled[0]) {
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                        return;
                    }
                    if (!part.renameTo(out)) {
                        throw new Exception("could not store the download");
                    }
                }
                error = verify(app, out, r.build);
            } catch (Throwable t) {
                error = t.getMessage() != null ? t.getMessage() : t.toString();
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
            final String err = error;
            activity.runOnUiThread(() -> {
                if (cancelled[0] || activity.isFinishing()) {
                    return;
                }
                if (err != null) {
                    status.setText("The update did not work: " + err);
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    dialog.setCancelable(true);
                    final Button b = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                    if (b != null) {
                        b.setText("Close");
                    }
                    return;
                }
                dialog.setOnDismissListener(null);
                dialog.dismiss();
                install(activity, out);
            });
        }, "TvUpdateDownload").start();
    }

    private interface Progress {
        void onProgress(long done, long total);
    }

    private static void downloadTo(String url, File dest, long expected, Progress progress, boolean[] cancelled) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(20_000);
            c.setReadTimeout(60_000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "ArtemideTV/" + BuildConfig.VERSION_CODE);
            final int code = c.getResponseCode();
            if (code != 200) {
                throw new Exception("download answered " + code);
            }
            long total = c.getContentLengthLong();
            if (total <= 0) {
                total = expected;
            }
            long done = 0, lastReport = 0;
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(dest)) {
                final byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled[0]) {
                        return;
                    }
                    os.write(buf, 0, n);
                    done += n;
                    if (done - lastReport >= 1_000_000) {
                        lastReport = done;
                        progress.onProgress(done, total);
                    }
                }
            }
            if (expected > 0 && done != expected) {
                throw new Exception(String.format(Locale.US, "download incomplete (%d of %d bytes)", done, expected));
            }
        } finally {
            c.disconnect();
        }
    }

    /** @return null if the file is a newer build of this app signed with the same key, else why not */
    @SuppressWarnings("deprecation")
    private static String verify(Context context, File apk, int build) {
        try {
            final PackageManager pm = context.getPackageManager();
            final int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            final PackageInfo file = pm.getPackageArchiveInfo(apk.getPath(), flags);
            if (file == null) {
                return "the downloaded file is not an app";
            }
            if (!context.getPackageName().equals(file.packageName)) {
                return "the download is a different app (" + file.packageName + ")";
            }
            final PackageInfo mine = pm.getPackageInfo(context.getPackageName(), flags);
            final long fileCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? file.getLongVersionCode() : file.versionCode;
            final long myCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? mine.getLongVersionCode() : mine.versionCode;
            if (fileCode < myCode) {
                return "the download is older than the installed app";
            }
            final byte[] a = signerDigest(file), b = signerDigest(mine);
            if (a != null && b != null && !Arrays.equals(a, b)) {
                return "the download is signed with a different key; uninstall the app first to switch";
            }
            return null;
        } catch (Throwable t) {
            // Could not check: let the package installer decide (it refuses mismatches itself)
            LimeLog.warning("Artemide TV: update check of the file failed: " + t);
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static byte[] signerDigest(PackageInfo p) {
        try {
            android.content.pm.Signature[] sigs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (p.signingInfo == null) {
                    return null;
                }
                sigs = p.signingInfo.getApkContentsSigners();
            } else {
                sigs = p.signatures;
            }
            if (sigs == null || sigs.length == 0) {
                return null;
            }
            return MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- Installing ----------------

    private static boolean mayInstall(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.getPackageManager().canRequestPackageInstalls();
    }

    private static void install(final Activity activity, final File apk) {
        if (!mayInstall(activity)) {
            final AlertDialog d = new AlertDialog.Builder(activity)
                    .setTitle("Allow updates")
                    .setMessage("The TV must allow Artemide TV to install apps (only needed once).\n\n" +
                            "Press \"Open settings\", switch Artemide TV on, come back with Back, then press \"Install\".\n\n" +
                            "If the settings page does not open: TV Settings > Privacy (or Apps) > Security & restrictions > " +
                            "Unknown sources > Artemide TV.")
                    .setPositiveButton("Install", null)
                    .setNeutralButton("Open settings", null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            d.setOnShowListener(x -> {
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> openInstallPermission(activity));
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    if (mayInstall(activity)) {
                        d.dismiss();
                        startInstaller(activity, apk);
                    } else {
                        Toast.makeText(activity, "Not allowed yet: switch Artemide TV on in the settings first",
                                Toast.LENGTH_LONG).show();
                    }
                });
            });
            d.show();
            return;
        }
        startInstaller(activity, apk);
    }

    private static void openInstallPermission(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        } catch (Throwable ignored) { }
        try {
            activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            return;
        } catch (Throwable ignored) { }
        try {
            activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Throwable t) {
            Toast.makeText(activity, "Could not open the settings", Toast.LENGTH_LONG).show();
        }
    }

    private static void startInstaller(Activity activity, File apk) {
        LimeLog.info("Artemide TV: installing update " + apk.getName());
        // The package installer screen (what the Downloader app uses too)
        try {
            final Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", apk);
            final Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
            return;
        } catch (ActivityNotFoundException e) {
            LimeLog.warning("Artemide TV: no installer screen for APK files, using a session");
        } catch (Throwable t) {
            LimeLog.warning("Artemide TV: installer screen failed: " + t);
        }
        installWithSession(activity, apk);
    }

    /** Fallback: a PackageInstaller session (the system asks for confirmation). */
    private static void installWithSession(Activity activity, File apk) {
        try {
            final PackageInstaller pi = activity.getPackageManager().getPackageInstaller();
            final PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(activity.getPackageName());
            params.setSize(apk.length());
            final int id = pi.createSession(params);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                    final byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    s.fsync(out);
                }
                final Intent cb = new Intent(activity, TvUpdateReceiver.class).setAction(TvUpdateReceiver.ACTION);
                int piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    piFlags |= android.app.PendingIntent.FLAG_MUTABLE;
                }
                final android.app.PendingIntent p = android.app.PendingIntent.getBroadcast(activity, id, cb, piFlags);
                s.commit(p.getIntentSender());
            }
        } catch (Throwable t) {
            LimeLog.warning("Artemide TV: session install failed: " + t);
            new AlertDialog.Builder(activity)
                    .setTitle("Update")
                    .setMessage("The TV did not start the installation: " + t.getMessage())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private static String readAll(InputStream in, int max) throws Exception {
        final ByteArrayOutputStream bo = new ByteArrayOutputStream();
        final byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > max) {
                break;
            }
        }
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }
}
