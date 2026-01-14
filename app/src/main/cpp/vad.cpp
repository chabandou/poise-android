/**
 * Voice Activity Detection (VAD) - C++ Implementation
 *
 * Simple energy-based VAD for skipping processing during silence.
 * Ported from Python vad.py.
 */

#include "vad.h"
#include <android/log.h>
#include <cmath>
#include <numeric>

#define LOG_TAG "PoiseVAD"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace poise {

constexpr int DEFAULT_FRAME_SIZE = 480;

VoiceActivityDetector::VoiceActivityDetector(float thresholdDb,
                                             float hangTimeMs, int sampleRate)
    : thresholdDb_(thresholdDb),
      thresholdLinear_(std::pow(10.0f, thresholdDb / 20.0f)),
      hangFrames_(static_cast<int>(hangTimeMs * sampleRate / 1000.0f /
                                   DEFAULT_FRAME_SIZE)),
      framesSinceActive_(hangFrames_ + 1), totalFrames_(0), activeFrames_(0),
      bypassedFrames_(0) {}

bool VoiceActivityDetector::isSpeech(const std::vector<float> &audio) {
  totalFrames_++;

  // Calculate RMS energy
  float sumSquares = 0.0f;
  for (float sample : audio) {
    sumSquares += sample * sample;
  }
  float rms = std::sqrt(sumSquares / static_cast<float>(audio.size()));

  // Check if above threshold
  bool isActive = rms > thresholdLinear_;

  if (isActive) {
    framesSinceActive_ = 0;
    activeFrames_++;
    return true;
  } else {
    framesSinceActive_++;
    // Use hang time to smooth transitions
    if (framesSinceActive_ < hangFrames_) {
      activeFrames_++;
      return true;
    } else {
      bypassedFrames_++;
      return false;
    }
  }
}

VADStats VoiceActivityDetector::getStats() const {
  VADStats stats;
  stats.total = totalFrames_;
  stats.active = activeFrames_;
  stats.bypassed = bypassedFrames_;
  stats.bypassRatio = (totalFrames_ > 0) ? static_cast<float>(bypassedFrames_) /
                                               static_cast<float>(totalFrames_)
                                         : 0.0f;
  return stats;
}

void VoiceActivityDetector::reset() {
  framesSinceActive_ = hangFrames_ + 1;
  totalFrames_ = 0;
  activeFrames_ = 0;
  bypassedFrames_ = 0;
}

} // namespace poise
