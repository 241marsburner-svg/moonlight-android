package com.limelight.binding.audio;

import android.content.Context;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * Audio buffer "Native AAudio" (from pabragin/moonlight-tcl): the native AAudio stream when it opens,
 * otherwise the normal AudioTrack renderer in low-latency mode. While AAudio is active the native
 * bridge feeds it directly and {@link #playDecodedAudio(short[])} is never called.
 */
public class NativeAudioRenderer implements AudioRenderer {
    // Same bound moonlight-tcl uses: anything queued beyond this is dropped instead of adding delay
    private static final int MAX_PENDING_MS = 40;

    private final Context context;
    private final boolean enableAudioFx;

    private boolean usingAAudio;
    private AndroidAudioRenderer fallback;

    public NativeAudioRenderer(Context context, boolean enableAudioFx) {
        this.context = context;
        this.enableAudioFx = enableAudioFx;
    }

    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        // The system equalizer needs an AudioTrack session, so AAudio only applies without audio FX
        if (!enableAudioFx) {
            final int result = AAudioRenderer.setup(audioConfiguration.channelCount, sampleRate, samplesPerFrame, MAX_PENDING_MS);
            if (result == 0) {
                usingAAudio = true;
                LimeLog.info("Audio output: native AAudio (" + audioConfiguration.channelCount + " ch, " + sampleRate + " Hz)");
                com.limelight.tv.TvStreamSettings.put("Audio output", "native AAudio, " +
                        audioConfiguration.channelCount + " ch, at most " + MAX_PENDING_MS + " ms queued");
                return 0;
            }
            LimeLog.warning("AAudio setup failed (" + result + "); falling back to AudioTrack");
            AAudioRenderer.cleanup();
            com.limelight.tv.TvStreamSettings.put("Audio output", "AudioTrack (AAudio did not open: " + result + ")");
        } else {
            com.limelight.tv.TvStreamSettings.put("Audio output", "AudioTrack (the system equalizer needs it)");
        }
        fallback = new AndroidAudioRenderer(context, enableAudioFx, PreferenceConfiguration.AUDIO_BUFFER_LOW_LATENCY);
        return fallback.setup(audioConfiguration, sampleRate, samplesPerFrame);
    }

    @Override
    public void start() {
        if (usingAAudio) {
            final int result = AAudioRenderer.start();
            if (result != 0) {
                LimeLog.warning("AAudio start failed: " + result);
            }
        } else if (fallback != null) {
            fallback.start();
        }
    }

    @Override
    public void stop() {
        if (usingAAudio) {
            AAudioRenderer.stop();
        } else if (fallback != null) {
            fallback.stop();
        }
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
        // Only reached on the AudioTrack path; the native bridge bypasses Java for AAudio
        if (fallback != null) {
            fallback.playDecodedAudio(audioData);
        }
    }

    @Override
    public void cleanup() {
        if (usingAAudio) {
            LimeLog.info("Audio output at the end of the stream: " + AAudioRenderer.describeStats());
            AAudioRenderer.cleanup();
            usingAAudio = false;
        } else if (fallback != null) {
            fallback.cleanup();
        }
    }
}
