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
    // The track keeps about 'tvTargetFrames' of audio queued, so a network gap shorter than
    // that is never heard. Writes never block (a blocked decoder thread is what made the old
    // code skip audio); instead the queued amount is measured and steered:
    //  - stays above target + TV_TRIM_SLACK_MS for a whole second -> drop one packet (latency)
    //  - stays under a quarter of the target -> refill with a little silence (cushion lost)
    // The target itself adapts: every time the cushion nearly runs dry (Wi-Fi delivered audio
    // in a bigger bunch than it could cover) it grows by TV_GROW_MS, up to TV_MAX_TARGET_MS,
    // and it shrinks again slowly once the network has been calm for a while.
    private static final int TV_CAPACITY_MS = 400;
    private static final int TV_HEADROOM_MS = 160;
    private static final int TV_MAX_TARGET_MS = 200;
    private static final int TV_GROW_MS = 10;
    private static final int TV_SHRINK_MS = 5;
    private static final long TV_GROW_COOLDOWN_MS = 400;
    private static final long TV_SHRINK_AFTER_MS = 30_000;
    // "Nearly dry": less than this much audio left in the track
    private static final int TV_DRY_MS = 4;
    private static final int TV_TRIM_SLACK_MS = 15;
    private static final long TV_REFILL_AFTER_MS = 150;
    private static final long TV_TRIM_SPACING_MS = 250;
    private static final int TV_MAX_PENDING_MS = 200;
    private boolean tvMode;
    private int tvChannelCount;
    private int tvSampleRate;
    private int tvTargetFrames;
    private int tvBaseTargetFrames;
    private long tvLastGrowMs;
    private long tvLastDryMs;
    private long tvLastShrinkMs;
    private long tvFramesWritten;
    private long tvLowSinceMs;
    private boolean tvCushionBuilt;
    private long tvWindowStartMs;
    private long tvWindowMinFrames = Long.MAX_VALUE;
    private long tvLastWindowMinFrames = -1;
    private long tvLastTrimMs;
    private long tvNextLevelReportMs;

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
                PreferenceConfiguration.AUDIO_BUFFER_LARGE.equals(bufferMode) ||
                PreferenceConfiguration.AUDIO_BUFFER_LOW_LAG.equals(bufferMode);
    }

    private int tvBufferTargetMs() {
        if (PreferenceConfiguration.AUDIO_BUFFER_LARGE.equals(bufferMode)) return 60;
        if (PreferenceConfiguration.AUDIO_BUFFER_LOW_LAG.equals(bufferMode)) return 20;
        return 30;
    }

    // How far the adaptive cushion may grow: low lag accepts the odd dropout instead
    private int tvBufferMaxMs() {
        if (PreferenceConfiguration.AUDIO_BUFFER_LARGE.equals(bufferMode)) return TV_MAX_TARGET_MS;
        if (PreferenceConfiguration.AUDIO_BUFFER_LOW_LAG.equals(bufferMode)) return 60;
        return 150;
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

        // Artemide TV: a track that keeps a ~30 ms (or ~60 ms) cushion of audio queued. TVs run
        // sound through extra processing and Wi-Fi delivers audio in bunches; a tiny buffer runs
        // dry (stutter). A low-latency (FAST) track is tried first because it avoids the TV's
        // slower sound path; its capacity is large, the cushion is steered in playDecodedAudioTv.
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

            for (int attempt = 0; attempt < 2 && track == null; attempt++) {
                final boolean lowLatency = (attempt == 0);
                if (lowLatency && (enableAudioFx ||
                        AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) != sampleRate)) {
                    continue;
                }
                try {
                    track = createAudioTrack(channelConfig, sampleRate, capacityBytes, lowLatency);
                    if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                        throw new IllegalStateException("track not initialized");
                    }

                    final int cushionFrames = (int) ((long) sampleRate * targetMs / 1000);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        // Room for the cushion plus bursts; non-blocking writes stop at this size
                        int capacityFrames = track.getBufferCapacityInFrames();
                        // (Before Android 12 playback only starts once this is full, so keep it small there.)
                        int want = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                ? (int) ((long) sampleRate * (TV_MAX_TARGET_MS + TV_HEADROOM_MS) / 1000)
                                : cushionFrames + (int) ((long) sampleRate * 60 / 1000);
                        if (capacityFrames > 0) {
                            want = Math.min(want, capacityFrames);
                        }
                        track.setBufferSizeInFrames(want);
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            // Start playing once the cushion is there (older versions wait for a full buffer)
                            track.setStartThresholdInFrames(cushionFrames);
                        } catch (Throwable ignored) { }
                    }

                    track.play();

                    tvMode = true;
                    tvChannelCount = audioConfiguration.channelCount;
                    tvSampleRate = sampleRate;
                    tvTargetFrames = cushionFrames;
                    tvBaseTargetFrames = cushionFrames;
                    tvLastGrowMs = 0;
                    tvLastDryMs = 0;
                    tvLastShrinkMs = 0;
                    tvFramesWritten = 0;
                    tvLowSinceMs = 0;
                    tvCushionBuilt = false;
                    tvWindowStartMs = 0;
                    tvWindowMinFrames = Long.MAX_VALUE;
                    tvLastWindowMinFrames = -1;
                    tvLastTrimMs = 0;
                    maxPendingAudioMs = TV_MAX_PENDING_MS;
                    LimeLog.info("Audio track configuration (TV " + bufferMode + ", " +
                            (lowLatency ? "low latency" : "normal") + "): capacity " + capacityBytes +
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
            if (tvMode) {
                bufferMs = (int) ((tvTargetFrames * 1000L) / sampleRate);
            }
            final String mode = (tvMode ? "cushion " : "") + (lowLatencyTrack ? "low-latency" : "normal");
            TvDiagnostics.setAudioTrackInfo(mode, bufferMs);
        } catch (Throwable ignored) {}

        return 0;
    }

    // Artemide TV: keep the cushion near its target without ever blocking the decoder thread
    private void playDecodedAudioTv(short[] audioData) {
        final int frames = audioData.length / tvChannelCount;
        final long nowMs = android.os.SystemClock.uptimeMillis();

        final long head = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        long queued = tvFramesWritten - head;
        if (queued < 0) {
            queued = 0;
        }
        final boolean playing = head > 0;

        // Lowest level over each second: the mixer drains in chunks, so only the minimum says
        // how much spare audio there really is
        if (tvWindowStartMs == 0) {
            tvWindowStartMs = nowMs;
        }
        if (queued < tvWindowMinFrames) {
            tvWindowMinFrames = queued;
        }
        if (nowMs - tvWindowStartMs >= 1000) {
            tvLastWindowMinFrames = tvWindowMinFrames;
            tvWindowMinFrames = Long.MAX_VALUE;
            tvWindowStartMs = nowMs;
        }

        if (nowMs >= tvNextLevelReportMs) {
            tvNextLevelReportMs = nowMs + 500;
            TvDiagnostics.setAudioLevel((int) (queued * 1000 / tvSampleRate),
                    tvLastWindowMinFrames < 0 ? -1 : (int) (tvLastWindowMinFrames * 1000 / tvSampleRate));
        }

        // Adapt the cushion to how bunchy the network is
        if (tvCushionBuilt && playing && queued * 1000 < (long) TV_DRY_MS * tvSampleRate) {
            tvLastDryMs = nowMs;
            final int maxTarget = (int) ((long) tvSampleRate * tvBufferMaxMs() / 1000);
            if (tvTargetFrames < maxTarget && nowMs - tvLastGrowMs >= TV_GROW_COOLDOWN_MS) {
                tvTargetFrames = Math.min(maxTarget, tvTargetFrames + tvSampleRate * TV_GROW_MS / 1000);
                tvLastGrowMs = nowMs;
            }
        } else if (tvTargetFrames > tvBaseTargetFrames &&
                nowMs - Math.max(tvLastDryMs, tvLastShrinkMs) >= TV_SHRINK_AFTER_MS) {
            tvTargetFrames = Math.max(tvBaseTargetFrames, tvTargetFrames - tvSampleRate * TV_SHRINK_MS / 1000);
            tvLastShrinkMs = nowMs;
        }
        TvDiagnostics.setAudioTarget((int) ((long) tvTargetFrames * 1000 / tvSampleRate));

        if (queued >= tvTargetFrames / 2) {
            tvCushionBuilt = true;
            tvLowSinceMs = 0;
        } else if (tvCushionBuilt && queued < tvTargetFrames / 4) {
            if (tvLowSinceMs == 0) {
                tvLowSinceMs = nowMs;
            } else if (nowMs - tvLowSinceMs >= TV_REFILL_AFTER_MS) {
                // Audio came back without its backlog (lost, not late): refill the cushion
                final long silenceFrames = tvTargetFrames - queued - frames;
                if (silenceFrames > 0) {
                    final short[] silence = new short[(int) silenceFrames * tvChannelCount];
                    tvFramesWritten += writeTv(silence) / tvChannelCount;
                    TvDiagnostics.onAudioReprimed();
                }
                tvLowSinceMs = 0;
            }
        }

        // More queued than needed for a whole second: drop one packet to bring latency down
        final long slackFrames = (long) tvSampleRate * TV_TRIM_SLACK_MS / 1000;
        if (playing && tvLastWindowMinFrames > tvTargetFrames + slackFrames &&
                nowMs - tvLastTrimMs >= TV_TRIM_SPACING_MS) {
            tvLastTrimMs = nowMs;
            tvLastWindowMinFrames -= frames;
            TvDiagnostics.onAudioTrimmed();
            return;
        }

        // Safety net only: with non-blocking writes nothing should pile up behind the track
        if (MoonBridge.getPendingAudioDuration() >= maxPendingAudioMs) {
            TvDiagnostics.onAudioSkipped();
            return;
        }

        bumpAudioPriority();
        final int written = writeTv(audioData);
        tvFramesWritten += written / tvChannelCount;
        if (written < audioData.length) {
            // The track is full (a burst bigger than the headroom): the rest is dropped
            TvDiagnostics.onAudioSkipped();
        }
    }

    private int writeTv(short[] data) {
        final int n;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            n = track.write(data, 0, data.length, AudioTrack.WRITE_NON_BLOCKING);
        } else {
            n = track.write(data, 0, data.length);
        }
        return Math.max(0, n);
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
        TvDiagnostics.onAudioPacketArrived();
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
