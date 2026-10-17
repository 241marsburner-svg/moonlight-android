package com.limelight.tv;

import android.os.Process;

import com.limelight.LimeLog;
import com.limelight.binding.video.TvDiagnostics;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Artemide TV: raises the scheduling priority of the native streaming threads.
 *
 * moonlight-common-c starts its network, audio and input threads with the default priority.
 * On a TV the four CPU cores are shared with the TV's own services, so those threads can be
 * kept waiting for tens of milliseconds, and then whole bunches of video frames and audio
 * packets are handled at once. That looks exactly like a bad Wi-Fi connection. The threads
 * are found by name in /proc/self/task and given audio/display priorities, like the Java
 * threads of the video and audio path already have.
 */
public final class TvThreadBooster {
    private TvThreadBooster() {}

    private static final String[] NAMES = {
            "AudioRecv", "AudioDec", "VideoRecv", "VideoDec", "InputSend", "ControlRecv",
    };
    private static final int[] PRIORITIES = {
            Process.THREAD_PRIORITY_URGENT_AUDIO,   // AudioRecv
            Process.THREAD_PRIORITY_URGENT_AUDIO,   // AudioDec (also plays the audio)
            Process.THREAD_PRIORITY_AUDIO,          // VideoRecv
            -10,                                    // VideoDec (feeds the decoder)
            -10,                                    // InputSend (controller latency)
            Process.THREAD_PRIORITY_DISPLAY,        // ControlRecv
    };

    /** @return how many streaming threads now run with a raised priority */
    public static int boostStreamingThreads() {
        int boosted = 0;
        try {
            final File[] tasks = new File("/proc/self/task").listFiles();
            if (tasks == null) {
                return 0;
            }
            for (File task : tasks) {
                final String comm = readComm(task);
                if (comm == null) {
                    continue;
                }
                for (int i = 0; i < NAMES.length; i++) {
                    if (NAMES[i].equals(comm)) {
                        try {
                            final int tid = Integer.parseInt(task.getName());
                            Process.setThreadPriority(tid, PRIORITIES[i]);
                            boosted++;
                        } catch (Throwable t) {
                            LimeLog.warning("TV thread boost failed for " + comm + ": " + t);
                        }
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            LimeLog.warning("TV thread boost failed: " + t);
        }
        TvDiagnostics.setBoostedThreads(boosted);
        return boosted;
    }

    private static String readComm(File task) {
        try (BufferedReader r = new BufferedReader(new FileReader(new File(task, "comm")))) {
            final String line = r.readLine();
            return line != null ? line.trim() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
