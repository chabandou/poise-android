/**
 * Voice Activity Detection (VAD) - Header
 */

#ifndef VAD_H
#define VAD_H

#include <vector>

namespace poise {

struct VADStats {
  int total = 0;
  int active = 0;
  int bypassed = 0;
  float bypassRatio = 0.0f;
};

class VoiceActivityDetector {
public:
  VoiceActivityDetector(float thresholdDb = -40.0f, float hangTimeMs = 300.0f,
                        int sampleRate = 48000);

  // Returns true if speech detected, false if silence
  bool isSpeech(const std::vector<float> &audio);

  // Get statistics
  VADStats getStats() const;

  // Reset state
  void reset();

private:
  float thresholdDb_;
  float thresholdLinear_;
  int hangFrames_;
  int framesSinceActive_;

  // Statistics
  int totalFrames_;
  int activeFrames_;
  int bypassedFrames_;
};

} // namespace poise

#endif // VAD_H
