/**
 * STFT/iSTFT for GTCRN - Implementation
 *
 * Radix-2 FFT implementation for real-time audio processing.
 */

#include "stft.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "STFT"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace poise {

STFTProcessor::STFTProcessor() {
  initWindow();
  reset();
  LOGI("STFTProcessor initialized: FFT=%d, hop=%d", FFT_SIZE, HOP_SIZE);
}

void STFTProcessor::initWindow() {
  // Sqrt-Hanning window for perfect reconstruction with overlap-add
  for (int i = 0; i < FFT_SIZE; i++) {
    float hann = 0.5f * (1.0f - std::cos(2.0f * M_PI * i / FFT_SIZE));
    window_[i] = std::sqrt(hann);
  }
}

void STFTProcessor::reset() {
  std::memset(stftBuffer_, 0, sizeof(stftBuffer_));
  std::memset(overlapBuffer_, 0, sizeof(overlapBuffer_));
}

void STFTProcessor::bitReverse(std::complex<float> *data, int n) {
  int bits = 0;
  while ((1 << bits) < n)
    bits++;

  for (int i = 0; i < n; i++) {
    int j = 0;
    for (int k = 0; k < bits; k++) {
      j = (j << 1) | ((i >> k) & 1);
    }
    if (j > i) {
      std::swap(data[i], data[j]);
    }
  }
}

void STFTProcessor::fft(std::complex<float> *data, int n, bool inverse) {
  bitReverse(data, n);

  // Cooley-Tukey radix-2 FFT
  for (int size = 2; size <= n; size *= 2) {
    float angle = (inverse ? 2.0f : -2.0f) * M_PI / size;
    std::complex<float> wn(std::cos(angle), std::sin(angle));

    for (int start = 0; start < n; start += size) {
      std::complex<float> w(1.0f, 0.0f);
      int half = size / 2;

      for (int k = 0; k < half; k++) {
        std::complex<float> t = w * data[start + k + half];
        std::complex<float> u = data[start + k];
        data[start + k] = u + t;
        data[start + k + half] = u - t;
        w *= wn;
      }
    }
  }

  // Scale for inverse FFT
  if (inverse) {
    for (int i = 0; i < n; i++) {
      data[i] /= static_cast<float>(n);
    }
  }
}

void STFTProcessor::computeSTFT(const float *audioChunk, float *realOut,
                                float *imagOut) {
  // Shift buffer left by HOP_SIZE
  std::memmove(stftBuffer_, stftBuffer_ + HOP_SIZE,
               (FFT_SIZE - HOP_SIZE) * sizeof(float));

  // Add new samples at the end
  std::memcpy(stftBuffer_ + FFT_SIZE - HOP_SIZE, audioChunk,
              HOP_SIZE * sizeof(float));

  // Apply window and copy to FFT buffer
  for (int i = 0; i < FFT_SIZE; i++) {
    fftBuffer_[i] = std::complex<float>(stftBuffer_[i] * window_[i], 0.0f);
  }

  // Compute FFT
  fft(fftBuffer_, FFT_SIZE, false);

  // Extract real and imaginary parts (only first NUM_BINS due to Hermitian
  // symmetry)
  for (int i = 0; i < NUM_BINS; i++) {
    realOut[i] = fftBuffer_[i].real();
    imagOut[i] = fftBuffer_[i].imag();
  }
}

void STFTProcessor::reconstructAudio(const float *realIn, const float *imagIn,
                                     float *audioOut) {
  // Reconstruct full spectrum with Hermitian symmetry
  for (int i = 0; i < NUM_BINS; i++) {
    fftBuffer_[i] = std::complex<float>(realIn[i], imagIn[i]);
  }

  // Apply Hermitian symmetry for real-valued output
  // fftBuffer_[257:512] = conj(fftBuffer_[255:0:-1])
  for (int i = 1; i < NUM_BINS - 1; i++) {
    fftBuffer_[FFT_SIZE - i] = std::conj(fftBuffer_[i]);
  }

  // Inverse FFT
  fft(fftBuffer_, FFT_SIZE, true);

  // Apply window and overlap-add
  for (int i = 0; i < FFT_SIZE; i++) {
    overlapBuffer_[i] += fftBuffer_[i].real() * window_[i];
  }

  // Output the first HOP_SIZE samples
  std::memcpy(audioOut, overlapBuffer_, HOP_SIZE * sizeof(float));

  // Shift overlap buffer
  std::memmove(overlapBuffer_, overlapBuffer_ + HOP_SIZE,
               (FFT_SIZE - HOP_SIZE) * sizeof(float));
  std::memset(overlapBuffer_ + FFT_SIZE - HOP_SIZE, 0,
              HOP_SIZE * sizeof(float));
}

} // namespace poise
