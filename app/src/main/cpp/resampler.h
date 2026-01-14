/**
 * Audio Resampler - Header
 */

#ifndef RESAMPLER_H
#define RESAMPLER_H

#include <vector>

namespace poise {

class StreamingResampler {
public:
  StreamingResampler(int inputSr, int outputSr);

  // Process input samples and return resampled output
  // Returns empty vector if not enough samples accumulated
  std::vector<float> process(const std::vector<float> &input, int outputSize);

  // Reset internal state
  void reset();

  int getInputSampleRate() const { return inputSampleRate_; }
  int getOutputSampleRate() const { return outputSampleRate_; }

private:
  int inputSampleRate_;
  int outputSampleRate_;
  double ratio_;
  double phase_;
  std::vector<float> accumulator_;
};

} // namespace poise

#endif // RESAMPLER_H
