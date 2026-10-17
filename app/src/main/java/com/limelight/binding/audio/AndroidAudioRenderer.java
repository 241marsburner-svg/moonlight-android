package com.limelight.binding.audio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;
import android.os.Build;

import com.limelight.LimeLog;
import com.limelight.binding.video.TvDiagnostics;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

import android.os.Process;

public class AndroidAudioRenderer implements AudioRenderer {

// Ensure audio thread runs with highest real-time priority to avoid glitches
private static void bumpAudioPriority() {
    try {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
    } catch (Throwable ignored) {}
}


    private final Context context;
    private final boolean enableAudioFx;

    // Artemide TV: "lowlatency" (stock behavior), "normal" or "large"
    private final String bufferMode;
    // Pending (not yet written) audio above this is skipped to bound latency
    private int maxPendingAudioMs = 40;
    private long nextUnderrunPollMs;

    // ---- Artemide TV jitter cushion ----
    // The track keeps 'tvTargetFrames' of audio queued (its effective buffer size), so a
    // network gap shorter than that is never heard. If the cushion is lost (packets lost
    // rather than delayed), it is rebuilt with a little silence.
    private static final int TV_CAPACITY_MS = 250;
    // Backlog allowed behind a full cushion before audio is skipped (at least this much, or
    // the cushion size if larger: TVs with big mixer periods briefly hold writes back)
    private static final int TV_MIN_MAX_PENDING_MS = 40;
    private static final long TV_REPRIME_AFTER_MS = 300;
    private boolean tvMode;
    private int tvChannelCount;
    private int tvTargetFrames;
    private long tvFramesWritten;
    private long tvLowSinceMs;
    private boolean tvCushionBuilt;

    private AudioTrack track;

    public AndroidAudioRenderer(Context context, boolean enableAudioFx) {
        this(context, enableAudioFx, PreferenceConfiguration.AUDIO_BUFFER_LOW_LATENCY);
    }

    public AndroidAudioRenderer(Context context, boolean enableAudioFx, String bufferMode) {
        this.context = context;
        this.enableAudioFx = enableAudioFx;
        this.bufferMode = (bufferMode != null) ? bufferMode : PreferenceConfiguration.AUDIO_BUFFER_LOW_LATENCY;
    }

    private boolean isTvBufferMode() {
        return PreferenceConfiguration.AUDIO_BUFFER_NORMAL.equals(bufferMode) ||
                PreferenceConfiguration.AUDIO_BUFFER_LARGE.equals(bufferMode);
    }

    private int tvBufferTargetMs() {
        return PreferenceConfiguration.AUDIO_BUFFER_LARGE.equals(bufferMode) ? 100 : 50;
    }

    private AudioTrack createAudioTrack(int channelConfig, int sampleRate, int bufferSize, boolean lowLatency) {
        AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME);
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Use FLAG_LOW_LATENCY on L through N
            if (lowLatency) {
                attributesBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioTrack.Builder trackBuilder = new AudioTrack.Builder()
                    .setAudioFormat(format)
                    .setAudioAttributes(attributesBuilder.build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize);

            // Use PERFORMANCE_MODE_LOW_LATENCY on O and later
            if (lowLatency) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }

            return trackBuilder.build();
        }
        else {
            return new AudioTrack(attributesBuilder.build(),
                    format,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
        }
    }

    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        int channelConfig;
        int bytesPerFrame;

        switch (audioConfiguration.channelCount)
        {
            case 2:
                channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                break;
            case 4:
                channelConfig = AudioFormat.CHANNEL_OUT_QUAD;
                break;
            case 6:
                channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
                break;
            case 8:
                // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND isn't available until Android 6.0,
                // yet the CHANNEL_OUT_SIDE_LEFT and CHANNEL_OUT_SIDE_RIGHT constants were added
                // in 5.0, so just hardcode the constant so we can work on Lollipop.
                channelConfig = 0x000018fc; // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                break;
            default:
                LimeLog.severe("Decoder returned unhandled channel count");
                return -1;
        }

        LimeLog.info("Audio channel config: "+String.format("0x%X", channelConfig));

        bytesPerFrame = audioConfiguration.channelCount * samplesPerFrame * 2;

        // Artemide TV: a normal (non-FAST) track that keeps a ~50 ms (or ~100 ms) cushion of
        // audio queued. TVs run their sound through extra processing and often can't keep the
        // tiny low-latency buffer fed, and Wi-Fi delivers audio in bunches: both are heard as
        // stutter. The track's capacity is larger; its effective buffer size is the cushion,
        // which is also the level playback starts at.
        if (isTvBufferMode()) {
            final int targetMs = tvBufferTargetMs();
            final int sampleFrameBytes = audioConfiguration.channelCount * 2;
            int capacityBytes = (int) (((long) sampleRate * TV_CAPACITY_MS / 1000) * sampleFrameBytes);
            final int minSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
            if (minSize > 0) {
                capacityBytes = Math.max(capacityBytes, minSize);
            }
            // Round to whole Opus frames
            capacityBytes = (((capacityBytes + (bytesPerFrame - 1)) / bytesPerFrame) * bytesPerFrame);

            try {
                track = createAudioTrack(channelConfig, sampleRate, capacityBytes, false);

                final int wantFrames = (int) ((long) sampleRate * targetMs / 1000);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    track.setBufferSizeInFrames(wantFrames);
                }
                // The system may round the cushion up to what its mixer needs
                int cushionFrames = wantFrames;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    final int effective = track.getBufferSizeInFrames();
                    if (effective > 0) {
                        cushionFrames = effective;
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        track.setStartThresholdInFrames(cushionFrames);
                    } catch (Throwable ignored) { }
                }

                track.play();

                tvMode = true;
                tvChannelCount = audioConfiguration.channelCount;
                tvTargetFrames = cushionFrames;
                tvFramesWritten = 0;
                tvLowSinceMs = 0;
                tvCushionBuilt = false;
                maxPendingAudioMs = Math.max(TV_MIN_MAX_PENDING_MS,
                        (int) ((cushionFrames * 1000L) / sampleRate));
                LimeLog.info("Audio track configuration (TV " + bufferMode + "): capacity " + capacityBytes +
                        " bytes, cushion " + cushionFrames + " frames");
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    if (track != null) {
                        track.release();
                    }
                } catch (Exception ignored) {}
                track = null;
                tvMode = false;
            }
        }

        // We're not supposed to request less than the minimum
        // buffer size for our buffer, but it appears that we can
        // do this on many devices and it lowers audio latency.
        // We'll try the small buffer size first and if it fails,
        // use the recommended larger buffer size.

        for (int i = 0; i < 4 && track == null; i++) {
            boolean lowLatency;
            int bufferSize;

            // We will try:
            // 1) Small buffer, low latency mode
            // 2) Large buffer, low latency mode
            // 3) Small buffer, standard mode
            // 4) Large buffer, standard mode

            switch (i) {
                case 0:
                case 1:
                    lowLatency = true;
                    break;
                case 2:
                case 3:
                    lowLatency = false;
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            switch (i) {
                case 0:
                case 2:
                    bufferSize = bytesPerFrame * 2;
                    break;

                case 1:
                case 3:
                    // Try the larger buffer size
                    bufferSize = Math.max(AudioTrack.getMinBufferSize(sampleRate,
                            channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT),
                            bytesPerFrame * 2);

                    // Round to next frame
                    bufferSize = (((bufferSize + (bytesPerFrame - 1)) / bytesPerFrame) * bytesPerFrame);
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            // Skip low latency options if hardware sample rate doesn't match the content
            if (AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) != sampleRate && lowLatency) {
                continue;
            }

            // Skip low latency options when using audio effects, since low latency mode
            // precludes the use of the audio effect pipeline (as of Android 13).
            if (enableAudioFx && lowLatency) {
                continue;
            }

            try {
                track = createAudioTrack(channelConfig, sampleRate, bufferSize, lowLatency);
                track.play();

                // Successfully created working AudioTrack. We're done here.
                LimeLog.info("Audio track configuration: "+bufferSize+" "+lowLatency);
                break;
            } catch (Exception e) {
                // Try to release the AudioTrack if we got far enough
                e.printStackTrace();
                try {
                    if (track != null) {
                        track.release();
                        track = null;
                    }
                } catch (Exception ignored) {}
            }
        }

        if (track == null) {
            // Couldn't create any audio track for playback
            return -2;
        }

        // Artemide TV diagnostics: what we actually got from the audio system
        try {
            boolean lowLatencyTrack = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                lowLatencyTrack = track.getPerformanceMode() == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY;
            }
            int bufferMs = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                bufferMs = (int) ((track.getBufferSizeInFrames() * 1000L) / sampleRate);
            }
            final String mode = tvMode ? "cushion" : (lowLatencyTrack ? "low-latency" : "normal");
            TvDiagnostics.setAudioTrackInfo(mode, bufferMs);
        } catch (Throwable ignored) {}

        return 0;
    }

    // Artemide TV: keep the cushion, rebuild it with silence if it stays half empty
    private void playDecodedAudioTv(short[] audioData) {
        final int frames = audioData.length / tvChannelCount;

        long queued = tvFramesWritten - (track.getPlaybackHeadPosition() & 0xFFFFFFFFL);
        if (queued < 0) {
            queued = 0;
        }

        if (queued >= tvTargetFrames / 2) {
            tvCushionBuilt = true;
            tvLowSinceMs = 0;
        } else if (tvCushionBuilt) {
            final long nowMs = android.os.SystemClock.uptimeMillis();
            if (tvLowSinceMs == 0) {
                tvLowSinceMs = nowMs;
            } else if (nowMs - tvLowSinceMs >= TV_REPRIME_AFTER_MS) {
                // Audio came back without the backlog (lost, not late): refill the cushion
                final long silenceFrames = tvTargetFrames - queued - frames;
                if (silenceFrames > 0) {
                    final short[] silence = new short[(int) silenceFrames * tvChannelCount];
                    track.write(silence, 0, silence.length);
                    tvFramesWritten += silenceFrames;
                    TvDiagnostics.onAudioReprimed();
                }
                tvLowSinceMs = 0;
            }
        }

        // A backlog behind a full cushion means extra latency: skip it instead
        if (MoonBridge.getPendingAudioDuration() < maxPendingAudioMs) {
            bumpAudioPriority();
            // Blocks while the cushion is full; that is what keeps it at its target size
            track.write(audioData, 0, audioData.length);
            tvFramesWritten += frames;
        } else {
            TvDiagnostics.onAudioSkipped();
        }
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
        if (tvMode) {
            playDecodedAudioTv(audioData);
            pollUnderruns();
            return;
        }

        // Only queue up to 40 ms of pending audio data in addition to what AudioTrack
        // is buffering for us.
        if (MoonBridge.getPendingAudioDuration() < maxPendingAudioMs) {
            // This will block until the write is completed. That can cause a backlog
            // of pending audio data, so we do the above check to be able to bound
            // latency in that situation.
            bumpAudioPriority();
            track.write(audioData, 0, audioData.length);
        }
        else {
            TvDiagnostics.onAudioSkipped();
            LimeLog.info("Too much pending audio data: " + MoonBridge.getPendingAudioDuration() +" ms");
        }

        pollUnderruns();
    }

    // Artemide TV diagnostics: underruns are what you hear as crackles/stutter
    private void pollUnderruns() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            final long nowMs = android.os.SystemClock.uptimeMillis();
            if (nowMs >= nextUnderrunPollMs) {
                nextUnderrunPollMs = nowMs + 500;
                try {
                    TvDiagnostics.setAudioUnderruns(track.getUnderrunCount());
                } catch (Throwable ignored) {}
            }
        }
    }

    @Override
    public void start() {
        if (enableAudioFx) {
            // Open an audio effect control session to allow equalizers to apply audio effects
            Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            i.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME);
            context.sendBroadcast(i);
        }
    }

    @Override
    public void stop() {
        if (enableAudioFx) {
            // Close our audio effect control session when we're stopping
            Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            context.sendBroadcast(i);
        }
    }

    @Override
    public void cleanup() {
        // Immediately drop all pending data
        track.pause();
        track.flush();

        track.release();
    }
}
