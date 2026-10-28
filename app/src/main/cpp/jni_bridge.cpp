/**
 * JNI Bridge - Native C++ to Kotlin Interface
 *
 * STFT/iSTFT and the GTCRN model (gtcrn.cpp) for GTCRNProcessor.
 */

#include "gtcrn.h"
#include "stft.h"
#include <algorithm>
#include <android/log.h>
#include <cerrno>
#include <climits>
#include <cstdio>
#include <jni.h>
#include <memory>
#include <sched.h>
#include <unistd.h>
#include <mutex>
#include <unordered_map>

#define LOG_TAG "PoiseJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
std::unordered_map<jlong, std::unique_ptr<poise::STFTProcessor>> stftProcessors;
std::mutex stftMutex;
jlong nextStftHandle = 1;
} // namespace

extern "C" {

/**
 * Initialize a new STFT processor for GTCRN.
 */
JNIEXPORT jlong JNICALL
Java_com_poise_android_audio_GTCRNProcessor_nativeSTFTInit(JNIEnv *env,
                                                           jobject thiz) {
  std::lock_guard<std::mutex> lock(stftMutex);

  jlong handle = nextStftHandle++;
  stftProcessors[handle] = std::make_unique<poise::STFTProcessor>();

  LOGI("GTCRN STFT processor created, handle=%lld", handle);
  return handle;
}

/**
 * Compute STFT for a single frame.
 * @param audioChunk Input audio (256 samples)
 * @return Float array with 514 values (257 real + 257 imag)
 */
JNIEXPORT jfloatArray JNICALL
Java_com_poise_android_audio_GTCRNProcessor_nativeComputeSTFT(
    JNIEnv *env, jobject thiz, jlong handle, jfloatArray audioChunk) {
  std::lock_guard<std::mutex> lock(stftMutex);

  auto it = stftProcessors.find(handle);
  if (it == stftProcessors.end()) {
    LOGE("Invalid STFT handle: %lld", handle);
    return nullptr;
  }

  // Get input audio
  jfloat *audioData = env->GetFloatArrayElements(audioChunk, nullptr);

  // Output buffers
  float realOut[257];
  float imagOut[257];

  // Compute STFT
  it->second->computeSTFT(audioData, realOut, imagOut);

  env->ReleaseFloatArrayElements(audioChunk, audioData, 0);

  // Create output array (257 real + 257 imag = 514 floats)
  jfloatArray result = env->NewFloatArray(514);
  env->SetFloatArrayRegion(result, 0, 257, realOut);
  env->SetFloatArrayRegion(result, 257, 257, imagOut);

  return result;
}

/**
 * Reconstruct audio from STFT frame.
 * @param stftData Float array with 514 values (257 real + 257 imag)
 * @return Float array with 256 audio samples
 */
JNIEXPORT jfloatArray JNICALL
Java_com_poise_android_audio_GTCRNProcessor_nativeReconstruct(
    JNIEnv *env, jobject thiz, jlong handle, jfloatArray stftData) {
  std::lock_guard<std::mutex> lock(stftMutex);

  auto it = stftProcessors.find(handle);
  if (it == stftProcessors.end()) {
    LOGE("Invalid STFT handle: %lld", handle);
    return nullptr;
  }

  // Get STFT data
  jfloat *data = env->GetFloatArrayElements(stftData, nullptr);
  float *realIn = data;
  float *imagIn = data + 257;

  // Output buffer
  float audioOut[256];

  // Reconstruct audio
  it->second->reconstructAudio(realIn, imagIn, audioOut);

  env->ReleaseFloatArrayElements(stftData, data, 0);

  // Create output array
  jfloatArray result = env->NewFloatArray(256);
  env->SetFloatArrayRegion(result, 0, 256, audioOut);

  return result;
}

/**
 * Reset STFT processor state.
 */
JNIEXPORT void JNICALL
Java_com_poise_android_audio_GTCRNProcessor_nativeSTFTReset(JNIEnv *env,
                                                            jobject thiz,
                                                            jlong handle) {
  std::lock_guard<std::mutex> lock(stftMutex);

  auto it = stftProcessors.find(handle);
  if (it != stftProcessors.end()) {
    it->second->reset();
    LOGI("STFT processor %lld reset", handle);
  }
}

/**
 * Destroy STFT processor.
 */
JNIEXPORT void JNICALL
Java_com_poise_android_audio_GTCRNProcessor_nativeSTFTDestroy(JNIEnv *env,
                                                              jobject thiz,
                                                              jlong handle) {
  std::lock_guard<std::mutex> lock(stftMutex);

  stftProcessors.erase(handle);
  LOGI("STFT processor %lld destroyed", handle);
}

/** Loads the model from the bytes of assets/gtcrn.weights; 0 if they don't fit the code. */
JNIEXPORT jlong JNICALL
Java_com_poise_android_audio_GTCRNProcessor_nativeModelInit(JNIEnv *env, jobject thiz,
                                                            jbyteArray weights) {
  jbyte *bytes = env->GetByteArrayElements(weights, nullptr);
  Gtcrn *model = gtcrn_create(bytes, env->GetArrayLength(weights));
  env->ReleaseByteArrayElements(weights, bytes, JNI_ABORT);
  if (!model) LOGE("gtcrn.weights doesn't match the model code");
  return reinterpret_cast<jlong>(model);
}

/** spectrum and result: 257 real parts, then 257 imaginary parts. */
JNIEXPORT void JNICALL Java_com_poise_android_audio_GTCRNProcessor_nativeModelRun(
    JNIEnv *env, jobject thiz, jlong handle, jfloatArray spectrum, jfloatArray result) {
  float planar[514], mix[514], enh[514];
  env->GetFloatArrayRegion(spectrum, 0, 514, planar);
  for (int k = 0; k < 257; k++) {
    mix[2 * k] = planar[k];
    mix[2 * k + 1] = planar[257 + k];
  }
  gtcrn_run(reinterpret_cast<Gtcrn *>(handle), mix, enh);
  for (int k = 0; k < 257; k++) {
    planar[k] = enh[2 * k];
    planar[257 + k] = enh[2 * k + 1];
  }
  env->SetFloatArrayRegion(result, 0, 514, planar);
}

JNIEXPORT void JNICALL Java_com_poise_android_audio_GTCRNProcessor_nativeModelReset(
    JNIEnv *env, jobject thiz, jlong handle) {
  gtcrn_reset(reinterpret_cast<Gtcrn *>(handle));
}

JNIEXPORT void JNICALL Java_com_poise_android_audio_GTCRNProcessor_nativeModelDestroy(
    JNIEnv *env, jobject thiz, jlong handle) {
  gtcrn_destroy(reinterpret_cast<Gtcrn *>(handle));
}

/**
 * Keeps the calling thread off the slowest cluster: on a little core a frame takes 3-4x longer, and
 * moving between cores costs spikes of 25-31 ms. Returns how many cores it may use, 0 if all cores
 * look alike (nothing to do), or -errno.
 */
JNIEXPORT jint JNICALL Java_com_poise_android_audio_GTCRNProcessor_pinToFastCores(JNIEnv *env,
                                                                                   jobject thiz) {
  long khz[CPU_SETSIZE] = {};
  long slowest = LONG_MAX;
  int cpus = std::min<long>(sysconf(_SC_NPROCESSORS_CONF), CPU_SETSIZE);
  for (int cpu = 0; cpu < cpus; cpu++) {
    char path[64];
    snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    if (FILE *f = fopen(path, "r")) {
      if (fscanf(f, "%ld", &khz[cpu]) == 1 && khz[cpu] > 0) slowest = std::min(slowest, khz[cpu]);
      fclose(f);
    }
  }
  cpu_set_t fast;
  CPU_ZERO(&fast);
  for (int cpu = 0; cpu < cpus; cpu++)
    if (khz[cpu] > slowest) CPU_SET(cpu, &fast);
  if (CPU_COUNT(&fast) == 0) return 0;
  // 0 is this thread: affinity is per thread on Linux
  return sched_setaffinity(0, sizeof fast, &fast) == 0 ? CPU_COUNT(&fast) : -errno;
}

} // extern "C"
