/**
 * JNI Bridge - Native C++ to Kotlin Interface
 *
 * Provides JNI entry points for the Poise audio processor.
 * ONNX inference is handled on the Kotlin side for easier integration
 * with onnxruntime-android AAR package.
 */

#include "poise_processor.h"
#include "resampler.h"
#include <android/log.h>
#include <cmath>
#include <jni.h>
#include <memory>
#include <mutex>
#include <unordered_map>

#define LOG_TAG "PoiseJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Store processor instances by handle
std::unordered_map<jlong, std::unique_ptr<poise::PoiseProcessor>> processors;
std::unordered_map<jlong, std::unique_ptr<poise::StreamingResampler>>
    inputResamplers;
std::unordered_map<jlong, std::unique_ptr<poise::StreamingResampler>>
    outputResamplers;
std::mutex processorMutex;
jlong nextHandle = 1;

} // anonymous namespace

extern "C" {

/**
 * Initialize a new processor instance.
 * Returns a handle to use for subsequent calls.
 */
JNIEXPORT jlong JNICALL Java_com_poise_android_audio_PoiseProcessor_nativeInit(
    JNIEnv *env, jobject thiz, jfloat vadThresholdDb, jfloat attenLimDb) {
  std::lock_guard<std::mutex> lock(processorMutex);

  jlong handle = nextHandle++;
  processors[handle] =
      std::make_unique<poise::PoiseProcessor>(vadThresholdDb, attenLimDb);

  LOGI("Created processor with handle %lld", handle);
  return handle;
}

/**
 * Setup resampler for input audio.
 */
JNIEXPORT void JNICALL
Java_com_poise_android_audio_PoiseProcessor_nativeSetupInputResampler(
    JNIEnv *env, jobject thiz, jlong handle, jint inputSr, jint targetSr) {
  std::lock_guard<std::mutex> lock(processorMutex);

  if (inputSr != targetSr) {
    inputResamplers[handle] =
        std::make_unique<poise::StreamingResampler>(inputSr, targetSr);
    LOGI("Input resampler created: %d -> %d Hz", inputSr, targetSr);
  }
}

/**
 * Setup resampler for output audio.
 */
JNIEXPORT void JNICALL
Java_com_poise_android_audio_PoiseProcessor_nativeSetupOutputResampler(
    JNIEnv *env, jobject thiz, jlong handle, jint targetSr, jint outputSr) {
  std::lock_guard<std::mutex> lock(processorMutex);

  if (targetSr != outputSr) {
    outputResamplers[handle] =
        std::make_unique<poise::StreamingResampler>(targetSr, outputSr);
    LOGI("Output resampler created: %d -> %d Hz", targetSr, outputSr);
  }
}

/**
 * Process a frame of audio through VAD and post-processing.
 * Returns the processed frame (or input if silence detected).
 *
 * Note: ONNX inference is done on Kotlin side, this method handles
 * VAD check and post-processing only.
 */
JNIEXPORT jfloatArray JNICALL
Java_com_poise_android_audio_PoiseProcessor_nativeProcessPreInference(
    JNIEnv *env, jobject thiz, jlong handle, jfloatArray audioData) {
  std::lock_guard<std::mutex> lock(processorMutex);

  auto it = processors.find(handle);
  if (it == processors.end()) {
    LOGE("Invalid processor handle: %lld", handle);
    return nullptr;
  }

  // Get input data
  jsize len = env->GetArrayLength(audioData);
  std::vector<float> input(len);
  env->GetFloatArrayRegion(audioData, 0, len, input.data());

  // Apply input resampling if configured
  auto resamplerIt = inputResamplers.find(handle);
  if (resamplerIt != inputResamplers.end()) {
    input = resamplerIt->second->process(input, 480);
    if (input.empty()) {
      return nullptr; // Not enough samples yet
    }
  }

  // Normalize frame size to 480 samples
  if (input.size() < 480) {
    input.resize(480, 0.0f);
  } else if (input.size() > 480) {
    input.resize(480);
  }

  // Create output array
  jfloatArray result = env->NewFloatArray(480);
  env->SetFloatArrayRegion(result, 0, 480, input.data());

  return result;
}

/**
 * Check VAD status for a frame.
 * Returns true if speech detected (should run ONNX), false if silence (skip
 * ONNX).
 */
JNIEXPORT jboolean JNICALL
Java_com_poise_android_audio_PoiseProcessor_nativeCheckVAD(
    JNIEnv *env, jobject thiz, jlong handle, jfloatArray audioData) {
  std::lock_guard<std::mutex> lock(processorMutex);

  auto it = processors.find(handle);
  if (it == processors.end()) {
    return JNI_TRUE; // Default to processing if handle invalid
  }

  jsize len = env->GetArrayLength(audioData);
  std::vector<float> audio(len);
  env->GetFloatArrayRegion(audioData, 0, len, audio.data());

  // Calculate RMS energy
  float sumSquares = 0.0f;
  for (float sample : audio) {
    sumSquares += sample * sample;
  }
  float rms = std::sqrt(sumSquares / static_cast<float>(len));

  // Convert to dB (with floor to avoid log(0))
  float energyDb = -100.0f; // Default for silence
  if (rms > 1e-10f) {
    energyDb = 20.0f * std::log10(rms);
  }

  // Get threshold from processor (default -40 dB)
  float vadThreshold = it->second->getVadThresholdDb();

  // Return true if energy above threshold (speech), false if below (silence)
  bool isSpeech = energyDb > vadThreshold;

  return isSpeech ? JNI_TRUE : JNI_FALSE;
}

/**
 * Apply post-processing (soft limiter, clipping, DC removal).
 */
JNIEXPORT jfloatArray JNICALL
Java_com_poise_android_audio_PoiseProcessor_nativePostProcess(
    JNIEnv *env, jobject thiz, jlong handle, jfloatArray audioData) {
  std::lock_guard<std::mutex> lock(processorMutex);

  auto it = processors.find(handle);
  if (it == processors.end()) {
    LOGE("Invalid processor handle: %lld", handle);
    return audioData;
  }

  jsize len = env->GetArrayLength(audioData);
  std::vector<float> audio(len);
  env->GetFloatArrayRegion(audioData, 0, len, audio.data());

  // Apply soft limiter
  float maxVal = 0.0f;
  for (float sample : audio) {
    maxVal = std::max(maxVal, std::abs(sample));
  }
  if (maxVal > 0.98f && maxVal > 0.0f) {
    float scale = 0.98f / maxVal;
    for (float &sample : audio) {
      sample *= scale;
    }
  }

  // Clip and remove DC
  float mean = 0.0f;
  for (float &sample : audio) {
    sample = std::clamp(sample, -1.0f, 1.0f);
    mean += sample;
  }
  mean /= static_cast<float>(audio.size());
  for (float &sample : audio) {
    sample -= mean;
  }

  // Apply output resampling if configured
  auto resamplerIt = outputResamplers.find(handle);
  if (resamplerIt != outputResamplers.end()) {
    int outputSize =
        static_cast<int>(480 * resamplerIt->second->getOutputSampleRate() /
                         resamplerIt->second->getInputSampleRate());
    audio = resamplerIt->second->process(audio, outputSize);
  }

  // Create result array
  jfloatArray result = env->NewFloatArray(static_cast<jsize>(audio.size()));
  env->SetFloatArrayRegion(result, 0, static_cast<jsize>(audio.size()),
                           audio.data());

  return result;
}

/**
 * Get processing statistics.
 */
JNIEXPORT jobject JNICALL
Java_com_poise_android_audio_PoiseProcessor_nativeGetStats(JNIEnv *env,
                                                           jobject thiz,
                                                           jlong handle) {
  std::lock_guard<std::mutex> lock(processorMutex);

  auto it = processors.find(handle);
  if (it == processors.end()) {
    return nullptr;
  }

  auto stats = it->second->getStats();

  // Find and create ProcessingStats class
  jclass statsClass = env->FindClass("com/poise/android/audio/ProcessingStats");
  if (statsClass == nullptr) {
    LOGE("Failed to find ProcessingStats class");
    return nullptr;
  }

  jmethodID constructor = env->GetMethodID(statsClass, "<init>", "(IDFIIIF)V");
  if (constructor == nullptr) {
    LOGE("Failed to find ProcessingStats constructor");
    return nullptr;
  }

  return env->NewObject(statsClass, constructor, stats.frameCount,
                        stats.avgTimeMs, stats.rtf, stats.vadTotal,
                        stats.vadActive, static_cast<int>(stats.vadBypassed),
                        stats.vadBypassRatio);
}

/**
 * Reset processor state.
 */
JNIEXPORT void JNICALL Java_com_poise_android_audio_PoiseProcessor_nativeReset(
    JNIEnv *env, jobject thiz, jlong handle) {
  std::lock_guard<std::mutex> lock(processorMutex);

  auto it = processors.find(handle);
  if (it != processors.end()) {
    it->second->reset();
    LOGI("Processor %lld reset", handle);
  }

  auto inputIt = inputResamplers.find(handle);
  if (inputIt != inputResamplers.end()) {
    inputIt->second->reset();
  }

  auto outputIt = outputResamplers.find(handle);
  if (outputIt != outputResamplers.end()) {
    outputIt->second->reset();
  }
}

/**
 * Destroy processor instance.
 */
JNIEXPORT void JNICALL
Java_com_poise_android_audio_PoiseProcessor_nativeDestroy(JNIEnv *env,
                                                          jobject thiz,
                                                          jlong handle) {
  std::lock_guard<std::mutex> lock(processorMutex);

  processors.erase(handle);
  inputResamplers.erase(handle);
  outputResamplers.erase(handle);

  LOGI("Processor %lld destroyed", handle);
}

// ============================================================================
// GTCRN STFT Processor JNI Methods
// ============================================================================

} // extern "C"

// GTCRN STFT support
#include "stft.h"

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

} // extern "C"
