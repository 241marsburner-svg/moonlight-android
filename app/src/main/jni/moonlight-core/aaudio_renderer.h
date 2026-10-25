#pragma once

#include <stdint.h>
#include <stdbool.h>

// Native AAudio output path, ported from pabragin/moonlight-tcl. Decoded PCM from moonlight-common-c's
// AudioDec thread goes into a lock-free ring that the AAudio data callback drains, so no JNI and no
// AudioTrack.write() sit between the decoder and the audio HAL.
//
// Artemide TV builds for Android 5.0+, so libaaudio.so (Android 8.0+) is loaded at run time with
// dlopen() instead of being linked; where it is missing, setup fails and Java falls back to AudioTrack.

// Returns 0 on success or a negative value (AAudio result or loader error).
int AAudioRenderer_Setup(int channelCount, int sampleRate, int samplesPerFrame, int maxPendingMs);
int AAudioRenderer_Start(void);
void AAudioRenderer_Stop(void);
void AAudioRenderer_Cleanup(void);
bool AAudioRenderer_IsActive(void);
int AAudioRenderer_GetMaxPendingMs(void);

// Producer side (AudioDec thread): interleaved int16 frames.
void AAudioRenderer_Write(const int16_t* frames, int frameCount);
void AAudioRenderer_CountDroppedPacket(void);

// {latencyMs, underruns, dropped, reopens}
void AAudioRenderer_GetStats(int stats[4]);
