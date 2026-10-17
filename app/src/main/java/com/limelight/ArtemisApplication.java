package com.limelight;

import android.app.Application;
import android.widget.Toast;

import com.limelight.profiles.ProfilesManager;

public class ArtemisApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        // Artemide TV build: write TV-tuned first-run defaults before any settings UI can
        // persist the stock phone defaults. No-op for every other build type.
        com.limelight.tv.TvDefaults.applyIfNeeded(this);

        ProfilesManager profilesManager = ProfilesManager.getInstance();
        if (!profilesManager.load(this)) {
            Toast.makeText(this, R.string.profile_manager_failed_to_load, Toast.LENGTH_LONG).show();
        }
    }
}