// Native AAudio output, ported from pabragin/moonlight-tcl (GPLv3) for Artemide TV.
//
// Differences from the original: AAudio is loaded with dlopen() (this library is built for Android 5.0,
// AAudio exists from 8.0), the channel layout is given as a channel count (the channel-mask call only
// exists from Android 12L), and calls that are newer than Android 8.0 are optional.

#include "aaudio_renderer.h"

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/resource.h>
#include <unistd.h>

#define TAG "MoonlightAAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// ---- Minimal AAudio ABI (values from <aaudio/AAudio.h>, stable since Android 8.0) ----
typedef struct MlAAudioStream MlAAudioStream;
typedef struct MlAAudioStreamBuilder MlAAudioStreamBuilder;
typedef int32_t ml_aaudio_result_t;

#define ML_AAUDIO_OK 0
#define ML_AAUDIO_DIRECTION_OUTPUT 0
#define ML_AAUDIO_FORMAT_PCM_I16 1
#define ML_AAUDIO_SHARING_MODE_SHARED 1
#define ML_AAUDIO_PERFORMANCE_MODE_LOW_LATENCY 12
#define ML_AAUDIO_USAGE_GAME 14
#define ML_AAUDIO_CONTENT_TYPE_MUSIC 2
#define ML_AAUDIO_CALLBACK_RESULT_CONTINUE 0

typedef int32_t (*MlDataCallback)(MlAAudioStream* stream, void* userData, void* audioData, int32_t numFrames);
typedef void (*MlErrorCallback)(MlAAudioStream* stream, void* userData, ml_aaudio_result_t error);

static struct {
    bool loaded;
    bool failed;
    ml_aaudio_result_t (*createStreamBuilder)(MlAAudioStreamBuilder** builder);
    const char* (*convertResultToText)(ml_aaudio_result_t result);
    void (*builderSetDirection)(MlAAudioStreamBuilder*, int32_t);
    void (*builderSetFormat)(MlAAudioStreamBuilder*, int32_t);
    void (*builderSetSampleRate)(MlAAudioStreamBuilder*, int32_t);
    void (*builderSetChannelCount)(MlAAudioStreamBuilder*, int32_t);
    void (*builderSetSharingMode)(MlAAudioStreamBuilder*, int32_t);
    void (*builderSetPerformanceMode)(MlAAudioStreamBuilder*, int32_t);
    void (*builderSetUsage)(MlAAudioStreamBuilder*, int32_t);          // Android 9+, optional
    void (*builderSetContentType)(MlAAudioStreamBuilder*, int32_t);    // Android 9+, optional
    void (*builderSetDataCallback)(MlAAudioStreamBuilder*, MlDataCallback, void*);
    void (*builderSetErrorCallback)(MlAAudioStreamBuilder*, MlErrorCallback, void*);
    ml_aaudio_result_t (*builderOpenStream)(MlAAudioStreamBuilder*, MlAAudioStream**);
    ml_aaudio_result_t (*builderDelete)(MlAAudioStreamBuilder*);
    ml_aaudio_result_t (*streamClose)(MlAAudioStream*);
    ml_aaudio_result_t (*streamRequestStart)(MlAAudioStream*);
    ml_aaudio_result_t (*streamRequestStop)(MlAAudioStream*);
    int32_t (*streamGetChannelCount)(MlAAudioStream*);
    int32_t (*streamGetSampleRate)(MlAAudioStream*);
    int32_t (*streamGetFormat)(MlAAudioStream*);
    int32_t (*streamGetFramesPerBurst)(MlAAudioStream*);
    ml_aaudio_result_t (*streamSetBufferSizeInFrames)(MlAAudioStream*, int32_t);
    int32_t (*streamGetBufferSizeInFrames)(MlAAudioStream*);
    int32_t (*streamGetBufferCapacityInFrames)(MlAAudioStream*);
    int32_t (*streamGetPerformanceMode)(MlAAudioStream*);
    int32_t (*streamGetSharingMode)(MlAAudioStream*);
    int64_t (*streamGetFramesWritten)(MlAAudioStream*);
    ml_aaudio_result_t (*streamGetTimestamp)(MlAAudioStream*, clockid_t, int64_t*, int64_t*);
} A;

#define LOAD_REQUIRED(field, name) \
    do { *(void**)(&A.field) = dlsym(lib, name); if (A.field == NULL) { LOGW("AAudio: %s missing", name); ok = false; } } while (0)
#define LOAD_OPTIONAL(field, name) \
    do { *(void**)(&A.field) = dlsym(lib, name); } while (0)

// lifecycleLock must be held
static bool loadAAudioLocked(void) {
    if (A.loaded) {
        return true;
    }
    if (A.failed) {
        return false;
    }
    void* lib = dlopen("libaaudio.so", RTLD_NOW | RTLD_LOCAL);
    if (lib == NULL) {
        LOGW("AAudio is not available on this device (%s)", dlerror());
        A.failed = true;
        return false;
    }
    bool ok = true;
    LOAD_REQUIRED(createStreamBuilder, "AAudio_createStreamBuilder");
    LOAD_REQUIRED(convertResultToText, "AAudio_convertResultToText");
    LOAD_REQUIRED(builderSetDirection, "AAudioStreamBuilder_setDirection");
    LOAD_REQUIRED(builderSetFormat, "AAudioStreamBuilder_setFormat");
    LOAD_REQUIRED(builderSetSampleRate, "AAudioStreamBuilder_setSampleRate");
    LOAD_REQUIRED(builderSetChannelCount, "AAudioStreamBuilder_setChannelCount");
    LOAD_REQUIRED(builderSetSharingMode, "AAudioStreamBuilder_setSharingMode");
    LOAD_REQUIRED(builderSetPerformanceMode, "AAudioStreamBuilder_setPerformanceMode");
    LOAD_OPTIONAL(builderSetUsage, "AAudioStreamBuilder_setUsage");
    LOAD_OPTIONAL(builderSetContentType, "AAudioStreamBuilder_setContentType");
    LOAD_REQUIRED(builderSetDataCallback, "AAudioStreamBuilder_setDataCallback");
    LOAD_REQUIRED(builderSetErrorCallback, "AAudioStreamBuilder_setErrorCallback");
    LOAD_REQUIRED(builderOpenStream, "AAudioStreamBuilder_openStream");
    LOAD_REQUIRED(builderDelete, "AAudioStreamBuilder_delete");
    LOAD_REQUIRED(streamClose, "AAudioStream_close");
    LOAD_REQUIRED(streamRequestStart, "AAudioStream_requestStart");
    LOAD_REQUIRED(streamRequestStop, "AAudioStream_requestStop");
    LOAD_REQUIRED(streamGetChannelCount, "AAudioStream_getChannelCount");
    LOAD_REQUIRED(streamGetSampleRate, "AAudioStream_getSampleRate");
    LOAD_REQUIRED(streamGetFormat, "AAudioStream_getFormat");
    LOAD_REQUIRED(streamGetFramesPerBurst, "AAudioStream_getFramesPerBurst");
    LOAD_REQUIRED(streamSetBufferSizeInFrames, "AAudioStream_setBufferSizeInFrames");
    LOAD_REQUIRED(streamGetBufferSizeInFrames, "AAudioStream_getBufferSizeInFrames");
    LOAD_REQUIRED(streamGetBufferCapacityInFrames, "AAudioStream_getBufferCapacityInFrames");
    LOAD_REQUIRED(streamGetPerformanceMode, "AAudioStream_getPerformanceMode");
    LOAD_REQUIRED(streamGetSharingMode, "AAudioStream_getSharingMode");
    LOAD_REQUIRED(streamGetFramesWritten, "AAudioStream_getFramesWritten");
    LOAD_REQUIRED(streamGetTimestamp, "AAudioStream_getTimestamp");
    if (!ok) {
        // Keep the library loaded: dlclose() of a system library is pointless and some ROMs dislike it
        A.failed = true;
        return false;
    }
    A.loaded = true;
    return true;
}

static const char* resultText(ml_aaudio_result_t res) {
    return (A.convertResultToText != NULL) ? A.convertResultToText(res) : "?";
}

static pthread_mutex_t lifecycleLock = PTHREAD_MUTEX_INITIALIZER;

static MlAAudioStream* stream;
static int channels;
static int sampleRate;
static int samplesPerFrame;
static int framesPerBurst;
static int maxPendingMs = 40;

// Single-producer (AudioDec thread) / single-consumer (AAudio callback) ring of interleaved frames.
// Indices grow monotonically; position = index % ringFrames.
static int16_t* ring;
static int ringFrames;
static int highWaterFrames;
static _Atomic uint32_t readIdx;
static _Atomic uint32_t writeIdx;

static _Atomic bool active;
static _Atomic bool started;
static _Atomic bool producing;
static _Atomic bool priorityApplied;
static _Atomic int lastLoggedUnderruns;
static _Atomic int lastLoggedDropped;
static int64_t lastStatsLogNs;
static _Atomic bool needReopen;
// A silent host sends no packets at all, so an empty ring is normal, not a fault. Only a gap that
// interrupts audio that is actually playing counts as an underrun, and it counts once, not once per
// callback: three empty bursts (~12 ms) end the current run of audio.
#define GAP_CALLBACKS_TO_END_RUN 3
static _Atomic int emptyCallbacks;
static _Atomic int underruns;
static _Atomic int dropped;
static _Atomic int reopens;

static int minInt(int a, int b) { return a < b ? a : b; }
static int maxInt(int a, int b) { return a > b ? a : b; }

static int32_t dataCallback(MlAAudioStream* s, void* userData, void* audioData, int32_t numFrames) {
    (void)s; (void)userData;
    int16_t* out = (int16_t*)audioData;
    uint32_t r = atomic_load_explicit(&readIdx, memory_order_relaxed);
    uint32_t w = atomic_load_explicit(&writeIdx, memory_order_acquire);
    int avail = (int)(w - r);
    int n = minInt(avail, numFrames);
    if (n > 0) {
        int pos = (int)(r % (uint32_t)ringFrames);
        int first = minInt(n, ringFrames - pos);
        memcpy(out, ring + (size_t)pos * channels, (size_t)first * channels * sizeof(int16_t));
        if (n > first) {
            memcpy(out + (size_t)first * channels, ring, (size_t)(n - first) * channels * sizeof(int16_t));
        }
        atomic_store_explicit(&readIdx, r + (uint32_t)n, memory_order_release);
    }
    if (n < numFrames) {
        memset(out + (size_t)n * channels, 0, (size_t)(numFrames - n) * channels * sizeof(int16_t));
        if (atomic_load(&producing)) {
            int empty = atomic_fetch_add(&emptyCallbacks, 1) + 1;
            if (empty == 1) {
                // First empty callback of this gap: audio was playing and we ran out
                atomic_fetch_add(&underruns, 1);
            }
            else if (empty >= GAP_CALLBACKS_TO_END_RUN) {
                // Long enough to be a silent host rather than a late packet; wait for the next
                // write before calling anything an underrun again
                atomic_store(&producing, false);
            }
        }
    }
    else {
        atomic_store(&emptyCallbacks, 0);
    }
    return ML_AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void errorCallback(MlAAudioStream* s, void* userData, ml_aaudio_result_t error) {
    (void)s; (void)userData;
    // Typical cause: HDMI/ARC or Bluetooth route change. Never touch the stream from here.
    LOGW("AAudio stream error %d (%s); will reopen on the next write", error, resultText(error));
    atomic_store(&needReopen, true);
}

// lifecycleLock must be held
static int openStreamLocked(void) {
    MlAAudioStreamBuilder* builder = NULL;
    ml_aaudio_result_t res = A.createStreamBuilder(&builder);
    if (res != ML_AAUDIO_OK) {
        LOGW("AAudio_createStreamBuilder failed: %d", res);
        return res;
    }
    A.builderSetDirection(builder, ML_AAUDIO_DIRECTION_OUTPUT);
    A.builderSetFormat(builder, ML_AAUDIO_FORMAT_PCM_I16);
    A.builderSetSampleRate(builder, sampleRate);
    // Opus surround comes in the standard channel order for its count (FL FR FC LFE BL BR [SL SR]),
    // which is AAudio's default layout for that many channels
    A.builderSetChannelCount(builder, channels);
    // Exclusive MMAP is not available over HDMI on TVs and would steal the device from everything else
    A.builderSetSharingMode(builder, ML_AAUDIO_SHARING_MODE_SHARED);
    A.builderSetPerformanceMode(builder, ML_AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    if (A.builderSetUsage != NULL) {
        A.builderSetUsage(builder, ML_AAUDIO_USAGE_GAME);
    }
    if (A.builderSetContentType != NULL) {
        A.builderSetContentType(builder, ML_AAUDIO_CONTENT_TYPE_MUSIC);
    }
    A.builderSetDataCallback(builder, dataCallback, NULL);
    A.builderSetErrorCallback(builder, errorCallback, NULL);

    res = A.builderOpenStream(builder, &stream);
    A.builderDelete(builder);
    if (res != ML_AAUDIO_OK) {
        LOGW("AAudioStreamBuilder_openStream failed: %d (%s)", res, resultText(res));
        stream = NULL;
        return res;
    }
    if (A.streamGetChannelCount(stream) != channels || A.streamGetSampleRate(stream) != sampleRate ||
        A.streamGetFormat(stream) != ML_AAUDIO_FORMAT_PCM_I16) {
        LOGW("AAudio gave ch=%d rate=%d format=%d instead of ch=%d rate=%d I16; not using it",
             A.streamGetChannelCount(stream), A.streamGetSampleRate(stream), A.streamGetFormat(stream),
             channels, sampleRate);
        A.streamClose(stream);
        stream = NULL;
        return -2;
    }
    framesPerBurst = maxInt(A.streamGetFramesPerBurst(stream), 1);
    // Three bursts (12 ms at 192-frame bursts): two underran audibly on the C8K with the ring alone
    A.streamSetBufferSizeInFrames(stream, framesPerBurst * 3);
    LOGI("AAudio open: ch=%d rate=%d burst=%d buffer=%d capacity=%d perf=%d sharing=%d",
         channels, sampleRate, framesPerBurst, A.streamGetBufferSizeInFrames(stream),
         A.streamGetBufferCapacityInFrames(stream), A.streamGetPerformanceMode(stream),
         A.streamGetSharingMode(stream));
    return 0;
}

int AAudioRenderer_Setup(int channelCount, int rate, int spf, int maxPending) {
    pthread_mutex_lock(&lifecycleLock);
    if (!loadAAudioLocked()) {
        pthread_mutex_unlock(&lifecycleLock);
        return -100;
    }
    channels = channelCount;
    sampleRate = rate;
    samplesPerFrame = spf;
    maxPendingMs = maxPending > 0 ? maxPending : 40;
    atomic_store(&needReopen, false);
    atomic_store(&started, false);
    atomic_store(&producing, false);
    atomic_store(&emptyCallbacks, 0);
    atomic_store(&priorityApplied, false);
    atomic_store(&lastLoggedUnderruns, 0);
    atomic_store(&lastLoggedDropped, 0);
    lastStatsLogNs = 0;
    atomic_store(&underruns, 0);
    atomic_store(&dropped, 0);
    atomic_store(&reopens, 0);
    // The ring is sized from the burst, so it is created after the stream; the callback can't run
    // before requestStart(), so the stream may exist briefly without a ring
    int res = openStreamLocked();
    if (res != 0) {
        pthread_mutex_unlock(&lifecycleLock);
        return res;
    }
    ringFrames = maxInt(16 * samplesPerFrame, 8 * framesPerBurst);
    // Latency bound for the ring itself (~40 ms at 48 kHz); the common-c queue is bounded separately
    highWaterFrames = 4 * framesPerBurst + 4 * samplesPerFrame;
    ring = (int16_t*)calloc((size_t)ringFrames * channels, sizeof(int16_t));
    if (ring == NULL) {
        A.streamClose(stream);
        stream = NULL;
        pthread_mutex_unlock(&lifecycleLock);
        return -3;
    }
    atomic_store(&readIdx, 0);
    atomic_store(&writeIdx, 0);
    atomic_store(&active, true);
    pthread_mutex_unlock(&lifecycleLock);
    return 0;
}

int AAudioRenderer_Start(void) {
    pthread_mutex_lock(&lifecycleLock);
    int res = -1;
    if (stream != NULL) {
        res = A.streamRequestStart(stream);
        if (res == ML_AAUDIO_OK) {
            atomic_store(&started, true);
        } else {
            LOGW("AAudioStream_requestStart failed: %d", res);
        }
    }
    pthread_mutex_unlock(&lifecycleLock);
    return res;
}

void AAudioRenderer_Stop(void) {
    pthread_mutex_lock(&lifecycleLock);
    atomic_store(&started, false);
    if (stream != NULL) {
        A.streamRequestStop(stream);
    }
    pthread_mutex_unlock(&lifecycleLock);
}

void AAudioRenderer_Cleanup(void) {
    pthread_mutex_lock(&lifecycleLock);
    bool wasActive = atomic_load(&active);
    atomic_store(&active, false);
    atomic_store(&started, false);
    if (stream != NULL) {
        // Synchronous: no callback runs after this returns
        A.streamClose(stream);
        stream = NULL;
    }
    free(ring);
    ring = NULL;
    ringFrames = 0;
    pthread_mutex_unlock(&lifecycleLock);
    if (wasActive) {
        LOGI("AAudio closed: underruns=%d dropped=%d reopens=%d", atomic_load(&underruns), atomic_load(&dropped), atomic_load(&reopens));
    }
}

bool AAudioRenderer_IsActive(void) {
    return atomic_load(&active);
}

int AAudioRenderer_GetMaxPendingMs(void) {
    return maxPendingMs;
}

static void reopenLocked(void) {
    if (stream != NULL) {
        A.streamClose(stream);
        stream = NULL;
    }
    atomic_store(&readIdx, 0);
    atomic_store(&writeIdx, 0);
    atomic_store(&producing, false);
    atomic_store(&emptyCallbacks, 0);
    if (openStreamLocked() == 0 && atomic_load(&started)) {
        A.streamRequestStart(stream);
    }
    atomic_fetch_add(&reopens, 1);
    atomic_store(&needReopen, false);
}

static int64_t nowNs(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

void AAudioRenderer_Write(const int16_t* frames, int frameCount) {
    if (!atomic_load(&active) || frameCount <= 0) {
        return;
    }
    if (!atomic_load(&priorityApplied)) {
        // This is moonlight-common-c's AudioDec thread; give it audio priority so 5 ms packets are not
        // decoded late on a loaded TV SoC (Android lets a process raise its own threads this far)
        atomic_store(&priorityApplied, true);
        if (setpriority(PRIO_PROCESS, (id_t)gettid(), -16) != 0) {
            LOGW("Could not raise the audio decode thread priority");
        }
    }
    int64_t t = nowNs();
    if (t - lastStatsLogNs > 10000000000LL) {
        int windowMs = lastStatsLogNs == 0 ? 0 : (int)((t - lastStatsLogNs) / 1000000LL);
        lastStatsLogNs = t;
        int u = atomic_load(&underruns), d = atomic_load(&dropped);
        // Quiet while the stream is clean; one line per window in which the counters moved
        if (u != atomic_load(&lastLoggedUnderruns) || d != atomic_load(&lastLoggedDropped)) {
            LOGI("AAudio stats: underruns +%d (total %d), dropped +%d (total %d), queued %d frames, over %d ms",
                 u - atomic_load(&lastLoggedUnderruns), u, d - atomic_load(&lastLoggedDropped), d,
                 (int)(atomic_load(&writeIdx) - atomic_load(&readIdx)), windowMs);
            atomic_store(&lastLoggedUnderruns, u);
            atomic_store(&lastLoggedDropped, d);
        }
    }
    if (atomic_load(&needReopen)) {
        pthread_mutex_lock(&lifecycleLock);
        if (atomic_load(&active) && atomic_load(&needReopen)) {
            reopenLocked();
        }
        bool haveStream = stream != NULL;
        pthread_mutex_unlock(&lifecycleLock);
        if (!haveStream) {
            return;
        }
    }
    uint32_t r = atomic_load_explicit(&readIdx, memory_order_acquire);
    uint32_t w = atomic_load_explicit(&writeIdx, memory_order_relaxed);
    int used = (int)(w - r);
    if (used >= highWaterFrames || used + frameCount > ringFrames) {
        // The HAL is behind (route change, stall): bound the latency by dropping this packet
        atomic_fetch_add(&dropped, 1);
        return;
    }
    int pos = (int)(w % (uint32_t)ringFrames);
    int first = minInt(frameCount, ringFrames - pos);
    memcpy(ring + (size_t)pos * channels, frames, (size_t)first * channels * sizeof(int16_t));
    if (frameCount > first) {
        memcpy(ring, frames + (size_t)first * channels, (size_t)(frameCount - first) * channels * sizeof(int16_t));
    }
    atomic_store_explicit(&writeIdx, w + (uint32_t)frameCount, memory_order_release);
    atomic_store(&emptyCallbacks, 0);
    atomic_store(&producing, true);
}

void AAudioRenderer_CountDroppedPacket(void) {
    atomic_fetch_add(&dropped, 1);
}

void AAudioRenderer_GetStats(int stats[4]) {
    int latencyMs = -1;
    pthread_mutex_lock(&lifecycleLock);
    if (stream != NULL && sampleRate > 0) {
        uint32_t r = atomic_load(&readIdx);
        uint32_t w = atomic_load(&writeIdx);
        int64_t queued = (int64_t)(w - r);
        int64_t framePos = 0, timeNs = 0;
        int64_t pipeline = A.streamGetBufferSizeInFrames(stream);
        if (A.streamGetTimestamp(stream, CLOCK_MONOTONIC, &framePos, &timeNs) == ML_AAUDIO_OK) {
            int64_t elapsedFrames = (nowNs() - timeNs) * sampleRate / 1000000000LL;
            int64_t written = A.streamGetFramesWritten(stream);
            pipeline = written - (framePos + elapsedFrames);
            if (pipeline < 0) pipeline = 0;
        }
        latencyMs = (int)((queued + pipeline) * 1000 / sampleRate);
    }
    pthread_mutex_unlock(&lifecycleLock);
    stats[0] = latencyMs;
    stats[1] = atomic_load(&underruns);
    stats[2] = atomic_load(&dropped);
    stats[3] = atomic_load(&reopens);
}

// ---- JNI (com.limelight.binding.audio.AAudioRenderer) ----

JNIEXPORT jint JNICALL
Java_com_limelight_binding_audio_AAudioRenderer_nativeSetup(JNIEnv* env, jclass clazz, jint channelCount, jint rate, jint spf, jint maxPending) {
    (void)env; (void)clazz;
    return AAudioRenderer_Setup(channelCount, rate, spf, maxPending);
}

JNIEXPORT jint JNICALL
Java_com_limelight_binding_audio_AAudioRenderer_nativeStart(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return AAudioRenderer_Start();
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_audio_AAudioRenderer_nativeStop(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    AAudioRenderer_Stop();
}

JNIEXPORT void JNICALL
Java_com_limelight_binding_audio_AAudioRenderer_nativeCleanup(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    AAudioRenderer_Cleanup();
}

JNIEXPORT jboolean JNICALL
Java_com_limelight_binding_audio_AAudioRenderer_nativeIsActive(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return AAudioRenderer_IsActive() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_com_limelight_binding_audio_AAudioRenderer_nativeGetStats(JNIEnv* env, jclass clazz) {
    (void)clazz;
    int stats[4];
    AAudioRenderer_GetStats(stats);
    jintArray arr = (*env)->NewIntArray(env, 4);
    if (arr != NULL) {
        (*env)->SetIntArrayRegion(env, arr, 0, 4, stats);
    }
    return arr;
}
