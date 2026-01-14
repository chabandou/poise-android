/**
 * Poise Audio Processor - Header
 */

#ifndef POISE_PROCESSOR_H
#define POISE_PROCESSOR_H

#include "vad.h"
#include <chrono>
#include <functional>
#include <vector>

namespace poise {

struct ProcessingStats {
  int frameCount = 0;
  double avgTimeMs = 0.0;
  float rtf = 0.0f; // Real-time factor
  int vadTotal = 0;
  int vadActive = 0;
  int vadBypassed = 0;
  float vadBypassRatio = 0.0f;
};

// Callback type for ONNX inference (called from native to Kotlin/Java)
using OnnxInferenceCallback = std::function<std::vector<float>(
    const std::vector<float> &inputFrame, std::vector<float> &states,
    float attenLimDb)>;

class PoiseProcessor {
public:
  PoiseProcessor(float vadThresholdDb = -40.0f, float attenLimDb = -60.0f);
  ~PoiseProcessor();

  // Process a single audio frame
  // inferenceCallback is called to run ONNX model (implemented in Kotlin)
  std::vector<float> processFrame(const std::vector<float> &inputFrame,
                                  OnnxInferenceCallback inferenceCallback);

  // Reset processor state
  void reset();

  // Get processing statistics
  ProcessingStats getStats() const;

  // Update ONNX model states (called after inference)
  void updateStates(const std::vector<float> &newStates);

  // Getters
  int getFrameSize() const { return frameSize_; }
  int getSampleRate() const { return sampleRate_; }
  float getVadThresholdDb() const { return vadThresholdDb_; }
  const std::vector<float> &getStates() const { return states_; }

private:
  std::vector<float> normalizeFrameSize(const std::vector<float> &audio);
  std::vector<float> normalizeOutputShape(const std::vector<float> &output,
                                          const std::vector<float> &fallback);
  void postprocessAudio(std::vector<float> &audio);
  double getAverageProcessingTimeMs() const;

  float vadThresholdDb_;
  float attenLimDb_;
  int frameSize_;
  int sampleRate_;

  std::vector<float> states_;

  // Statistics
  int frameCount_;
  double totalProcessingTimeMs_;

  // VAD
  VoiceActivityDetector vad_;
};

} // namespace poise

#endif // POISE_PROCESSOR_H
