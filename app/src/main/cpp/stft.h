/**
 * STFT/iSTFT for GTCRN - Header
 *
 * Short-Time Fourier Transform implementation for real-time audio processing.
 * Uses 512-point FFT with 256-sample hop and sqrt-Hanning window.
 */

#ifndef STFT_H
#define STFT_H

#include <cmath>
#include <complex>
#include <vector>

namespace poise {

/**
 * STFT processor for GTCRN model.
 * Handles forward STFT and inverse STFT with overlap-add reconstruction.
 */
class STFTProcessor {
public:
  static constexpr int FFT_SIZE = 512;
  static constexpr int HOP_SIZE = 256;
  static constexpr int NUM_BINS = 257; // FFT_SIZE/2 + 1

  STFTProcessor();
  ~STFTProcessor() = default;

  /**
   * Compute STFT for a single frame.
   * @param audioChunk Input audio samples (HOP_SIZE = 256 samples)
   * @param realOut Output real parts (NUM_BINS = 257 values)
   * @param imagOut Output imaginary parts (NUM_BINS = 257 values)
   */
  void computeSTFT(const float *audioChunk, float *realOut, float *imagOut);

  /**
   * Reconstruct audio from STFT frame using overlap-add.
   * @param realIn Input real parts (NUM_BINS = 257 values)
   * @param imagIn Input imaginary parts (NUM_BINS = 257 values)
   * @param audioOut Output audio samples (HOP_SIZE = 256 samples)
   */
  void reconstructAudio(const float *realIn, const float *imagIn,
                        float *audioOut);

  /**
   * Reset processor state (call when starting new audio stream).
   */
  void reset();

private:
  // Sqrt-Hanning window
  float window_[FFT_SIZE];

  // Input buffer for sliding window STFT
  float stftBuffer_[FFT_SIZE];

  // Output buffer for overlap-add reconstruction
  float overlapBuffer_[FFT_SIZE];

  // FFT working buffers
  std::complex<float> fftBuffer_[FFT_SIZE];

  // Initialize window
  void initWindow();

  // In-place radix-2 FFT
  void fft(std::complex<float> *data, int n, bool inverse = false);

  // Bit reversal for FFT
  void bitReverse(std::complex<float> *data, int n);
};

} // namespace poise

#endif // STFT_H
