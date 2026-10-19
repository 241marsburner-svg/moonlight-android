package com.limelight.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import com.limelight.LimeLog;

/** Artemide TV: result of an update installed through a PackageInstaller session. */
public class TvUpdateReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.limelight.tv.UPDATE_STATUS";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        final int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // The system wants the user to confirm the update
            @SuppressWarnings("deprecation")
            final Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                } catch (Throwable t) {
                    LimeLog.warning("Artemide TV: could not show the update confirmation: " + t);
                }
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            LimeLog.info("Artemide TV: update installed");
        } else {
            final String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            LimeLog.warning("Artemide TV: update failed (" + status + "): " + msg);
            Toast.makeText(context, "Update failed: " + (msg != null ? msg : ("status " + status)), Toast.LENGTH_LONG).show();
        }
    }
}
