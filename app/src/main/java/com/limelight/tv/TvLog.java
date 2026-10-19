package com.limelight.tv;

import android.content.Context;
import android.os.Process;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Artemide TV: keeps the app's own log lines (LimeLog) in memory, so a log can be sent even
 * when the TV's system log has long since overwritten them, and reads the rest of this app's
 * system log (decoder, audio, native streaming code) on demand.
 *
 * Also removes what does not belong in a log that leaves the TV: IP and MAC addresses, keys and
 * IDs in request URLs, e-mail addresses.
 */
public final class TvLog {
    private TvLog() {}

    private static final int MAX_LINES = 2500;
    private static final int MAX_LINE_CHARS = 600;
    private static final ArrayDeque<String> ring = new ArrayDeque<>();
    private static boolean installed;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    // Logger.getLogger keeps loggers only weakly: hold the one LimeLog uses
    private static Logger limeLogger;

    /** Application.onCreate (TV build): start keeping the app's log lines. */
    public static synchronized void install(Context context) {
        if (installed || !BuildConfig.TV_BUILD) {
            return;
        }
        installed = true;
        try {
            limeLogger = Logger.getLogger(LimeLog.class.getName());
            limeLogger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord r) {
                    if (r == null) {
                        return;
                    }
                    final Level l = r.getLevel();
                    final char c = l == null ? 'I' : (l.intValue() >= Level.SEVERE.intValue() ? 'E'
                            : l.intValue() >= Level.WARNING.intValue() ? 'W' : 'I');
                    String msg = r.getMessage();
                    if (msg == null) {
                        msg = "";
                    }
                    if (msg.length() > MAX_LINE_CHARS) {
                        msg = msg.substring(0, MAX_LINE_CHARS) + "…";
                    }
                    add(c, msg);
                }

                @Override
                public void flush() {}

                @Override
                public void close() {}
            });
        } catch (Throwable t) {
            installed = false;
        }
    }

    private static void add(char level, String msg) {
        final String line;
        synchronized (TIME) {
            line = TIME.format(new Date()) + ' ' + level + ' ' + msg;
        }
        synchronized (TvLog.class) {
            if (ring.size() >= MAX_LINES) {
                ring.removeFirst();
            }
            ring.addLast(line);
        }
    }

    /** The app's log lines kept in memory (newest last). */
    public static synchronized String appLog() {
        final StringBuilder b = new StringBuilder(ring.size() * 100 + 64);
        for (String l : ring) {
            b.append(l).append('\n');
        }
        return b.toString();
    }

    /**
     * This app's lines from the system log (the app may read only its own), without the
     * LimeLog lines that appLog() already has.
     */
    public static String systemLog(int maxLines, int maxChars) {
        final StringBuilder b = new StringBuilder(Math.min(maxChars, 256 * 1024));
        java.lang.Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[] {
                    "logcat", "-d", "-v", "threadtime", "--pid=" + Process.myPid(), "-t", String.valueOf(maxLines) });
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains(" LimeLog: ") || line.contains(" LimeLog  : ")) {
                        continue;
                    }
                    if (line.length() > MAX_LINE_CHARS) {
                        line = line.substring(0, MAX_LINE_CHARS) + "…";
                    }
                    b.append(line).append('\n');
                    if (b.length() > maxChars) {
                        // Keep the newest part
                        b.delete(0, b.length() - maxChars);
                        final int nl = b.indexOf("\n");
                        if (nl >= 0) {
                            b.delete(0, nl + 1);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            b.append("(system log not readable: ").append(t).append(")\n");
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) { }
            }
        }
        return b.toString();
    }

    // ---------------- Files ----------------

    private static java.io.File privateDir(Context context) {
        final java.io.File dir = new java.io.File(context.getFilesDir(), "tv-logs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** Saves text in the app's private storage (survives restarts, not visible to other apps). */
    public static void writePrivate(Context context, String name, String text) {
        try (java.io.FileOutputStream os = new java.io.FileOutputStream(new java.io.File(privateDir(context), name))) {
            os.write((text != null ? text : "").getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            LimeLog.warning("Artemide TV: could not save " + name + ": " + t);
        }
    }

    /** @return the text saved with writePrivate, or null */
    public static String readPrivate(Context context, String name) {
        final java.io.File f = new java.io.File(privateDir(context), name);
        if (!f.isFile()) {
            return null;
        }
        try (java.io.FileInputStream is = new java.io.FileInputStream(f)) {
            final java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream((int) Math.min(f.length(), 4 << 20));
            final byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Saves a text file in Download/ArtemideTV (visible to file managers on the TV).
     * @return where it went, for the screen, or null if it could not be written
     */
    public static String saveToDownloads(Context context, String name, String text) {
        final byte[] data = (text != null ? text : "").getBytes(StandardCharsets.UTF_8);
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                final android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/ArtemideTV");
                final android.net.Uri uri = context.getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    try (java.io.OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                        if (os != null) {
                            os.write(data);
                            return "Download/ArtemideTV/" + name;
                        }
                    }
                }
            }
            final java.io.File dir = context.getExternalFilesDir(null);
            if (dir != null) {
                final java.io.File f = new java.io.File(dir, name);
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) {
                    os.write(data);
                }
                return f.getAbsolutePath();
            }
        } catch (Throwable t) {
            LimeLog.warning("Artemide TV: could not write " + name + ": " + t);
        }
        return null;
    }

    // ---------------- Cleaning a log before it leaves the TV ----------------

    private static final Pattern URL_SECRETS = Pattern.compile(
            "(?i)\\b(uniqueid|uuid|pin|salt|clientcert|serverchallengeresp|clientchallenge|clientpairingsecret|rikey|rikeyid|cert|sessionurl0?|token)=([^&\\s\"']+)");
    private static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b");
    private static final Pattern IPV6 = Pattern.compile(
            "\\b(?:[0-9a-fA-F]{1,4}:){4,7}[0-9a-fA-F]{1,4}\\b|\\b[0-9a-fA-F]{1,4}::(?:[0-9a-fA-F]{1,4}:?){0,6}\\b");
    private static final Pattern MAC = Pattern.compile("\\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b");
    private static final Pattern LONG_HEX = Pattern.compile("\\b[0-9A-Fa-f]{24,}\\b");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]{1,64}@[\\w-]{1,63}(?:\\.[\\w-]{1,63})+");

    /** Removes addresses, keys and IDs (keeps whether an address was in the home network). */
    public static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String s = URL_SECRETS.matcher(text).replaceAll("$1=<hidden>");
        s = EMAIL.matcher(s).replaceAll("<e-mail>");
        s = MAC.matcher(s).replaceAll("<mac>");
        s = IPV6.matcher(s).replaceAll("<ipv6>");
        final Matcher m = IPV4.matcher(s);
        final StringBuffer out = new StringBuffer(s.length());
        while (m.find()) {
            final int a = parse(m.group(1)), bb = parse(m.group(2)), c = parse(m.group(3)), d = parse(m.group(4));
            String rep;
            if (a > 255 || bb > 255 || c > 255 || d > 255) {
                rep = m.group(); // a version number or similar
            } else if (a == 127 || (a == 0 && bb == 0 && c == 0 && d == 0) || a == 255) {
                rep = m.group();
            } else if (a == 10 || (a == 172 && bb >= 16 && bb <= 31) || (a == 192 && bb == 168)) {
                rep = "<home-ip>";
            } else if (a == 100 && bb >= 64 && bb <= 127) {
                rep = "<cgnat-ip>";
            } else if (a == 169 && bb == 254) {
                rep = "<link-local-ip>";
            } else {
                rep = "<internet-ip>";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(rep));
        }
        m.appendTail(out);
        return LONG_HEX.matcher(out.toString()).replaceAll("<hex>");
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return 999;
        }
    }
}
