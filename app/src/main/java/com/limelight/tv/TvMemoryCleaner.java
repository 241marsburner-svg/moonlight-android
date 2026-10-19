package com.limelight.tv;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import com.limelight.LimeLog;
import com.limelight.grid.assets.MemoryAssetLoader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Artemide TV: closes other apps that sit in the TV's memory before a stream.
 *
 * The C7K has 2.4 GB of RAM and swaps to compressed memory; on the same chip a browser and the TV
 * settings left in the background pushed the kernel into reclaiming memory 250 times a second
 * during a stream (moonlight-tcl's measurement), and that costs frames. Android does not let an
 * app force-stop others, but it may end their background processes (what the system itself does
 * when memory runs low; they start again when opened). That is done here for every app with a
 * launcher entry, except this app, the home screen, the keyboard and Google Play services. Apps
 * in the foreground or playing something are never touched by Android.
 *
 * Works up to Android 13; from Android 14 Android only lets an app end its own processes.
 */
public final class TvMemoryCleaner {
    private TvMemoryCleaner() {}

    public static final class Result {
        public int appsClosed;
        public long availBeforeMb, availAfterMb, totalMb;
        public boolean lowMemory;
        public final List<String> names = new ArrayList<>();

        public long freedMb() {
            return Math.max(0, availAfterMb - availBeforeMb);
        }

        public String describe() {
            final StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.US, "%d MB free of %d MB", availAfterMb, totalMb));
            if (appsClosed > 0) {
                b.append(String.format(Locale.US, " (other apps' background processes ended: +%d MB)", freedMb()));
            }
            if (lowMemory) {
                b.append(" · TV reports low memory");
            }
            return b.toString();
        }
    }

    private static final String[] NEVER = {
            "com.google.android.gms", "com.google.android.gsf", "android", "com.android.systemui",
            // TV platform parts (picture, sound, inputs): their restart would cost more than it saves
            "com.tcl.", "com.mediatek.", "com.mstar.",
    };

    /** Blocking (about a second): run it off the UI thread. */
    public static Result freeMemory(Context context) {
        final Result r = new Result();
        final ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return r;
        }
        final ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(before);
        r.availBeforeMb = before.availMem >> 20;
        r.totalMb = before.totalMem >> 20;

        // Our own picture cache (posters of the app list) is not needed while streaming
        try {
            new MemoryAssetLoader().clearCache();
        } catch (Throwable ignored) { }

        final PackageManager pm = context.getPackageManager();
        final Set<String> keep = new HashSet<>();
        keep.add(context.getPackageName());
        try {
            final ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
            if (home != null && home.activityInfo != null) {
                keep.add(home.activityInfo.packageName);
            }
        } catch (Throwable ignored) { }
        try {
            final InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
                    keep.add(info.getPackageName());
                }
            }
        } catch (Throwable ignored) { }

        final Set<String> candidates = new HashSet<>();
        for (String category : new String[] { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER }) {
            try {
                final List<ResolveInfo> list = pm.queryIntentActivities(
                        new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
                for (ResolveInfo ri : list) {
                    if (ri.activityInfo != null) {
                        candidates.add(ri.activityInfo.packageName);
                    }
                }
            } catch (Throwable ignored) { }
        }

        for (String pkg : candidates) {
            if (keep.contains(pkg) || isNever(pkg)) {
                continue;
            }
            try {
                am.killBackgroundProcesses(pkg);
                r.appsClosed++;
                r.names.add(label(pm, pkg));
            } catch (Throwable t) {
                LimeLog.warning("Artemide TV: could not close " + pkg + ": " + t);
            }
        }

        // Give the kernel a moment to hand the memory back
        try {
            Thread.sleep(800);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        final ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(after);
        r.availAfterMb = after.availMem >> 20;
        r.lowMemory = after.lowMemory;
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+ ignores the request for other apps; don't claim we closed them
            r.appsClosed = 0;
            r.names.clear();
        }
        LimeLog.info("Artemide TV memory: " + r.describe());
        return r;
    }

    private static boolean isNever(String pkg) {
        for (String n : NEVER) {
            if (n.endsWith(".") ? pkg.startsWith(n) : (pkg.equals(n) || pkg.startsWith(n + "."))) {
                return true;
            }
        }
        return false;
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            final ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            final CharSequence l = pm.getApplicationLabel(ai);
            return l != null ? l.toString() : pkg;
        } catch (Throwable t) {
            return pkg;
        }
    }
}
