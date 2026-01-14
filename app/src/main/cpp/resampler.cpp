/**
 * Audio Resampler - C++ Implementation
 *
 * Streaming resampler for handling sample rate differences.
 * Uses linear interpolation for simplicity (Android devices typically use
 * 48kHz).
 */

#include "resampler.h"
#include <android/log.h>
#include <cmath>

#define LOG_TAG "PoiseResampler"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace poise {

StreamingResampler::StreamingResampler(int inputSr, int outputSr)
    : inputSampleRate_(inputSr), outputSampleRate_(outputSr),
      ratio_(static_cast<double>(outputSr) / static_cast<double>(inputSr)),
      phase_(0.0) {
  LOGI("Resampler created: %d Hz -> %d Hz (ratio: %.4f)", inputSr, outputSr,
       ratio_);
}

std::vector<float> StreamingResampler::process(const std::vector<float> &input,
                                               int outputSize) {
  if (inputSampleRate_ == outputSampleRate_) {
    // No resampling needed
    return input;
  }

  // Add input to accumulator
  accumulator_.insert(accumulator_.end(), input.begin(), input.end());

  // Calculate how many output samples we can generate
  int availableOutputSamples =
      static_cast<int>((accumulator_.size() - 1) * ratio_);

  if (availableOutputSamples < outputSize) {
    // Not enough samples yet
    return {};
  }

  // Generate output samples using linear interpolation
  std::vector<float> output(outputSize);

  for (int i = 0; i < outputSize; i++) {
    double srcPos = (i + phase_) / ratio_;
    int srcIndex = static_cast<int>(srcPos);
    float frac = static_cast<float>(srcPos - srcIndex);

    if (srcIndex + 1 < static_cast<int>(accumulator_.size())) {
      output[i] = accumulator_[srcIndex] * (1.0f - frac) +
                  accumulator_[srcIndex + 1] * frac;
    } else if (srcIndex < static_cast<int>(accumulator_.size())) {
      output[i] = accumulator_[srcIndex];
    } else {
      output[i] = 0.0f;
    }
  }

  // Update phase and remove consumed samples
  double consumedInputSamples = outputSize / ratio_;
  int samplesToRemove = static_cast<int>(consumedInputSamples);
  phase_ = std::fmod(phase_ + consumedInputSamples - samplesToRemove, 1.0);

  if (samplesToRemove > 0 &&
      samplesToRemove <= static_cast<int>(accumulator_.size())) {
    accumulator_.erase(accumulator_.begin(),
                       accumulator_.begin() + samplesToRemove);
  }

  return output;
}

void StreamingResampler::reset() {
  accumulator_.clear();
  phase_ = 0.0;
}

} // namespace poise
