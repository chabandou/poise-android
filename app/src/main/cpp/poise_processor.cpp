/**
 * Poise Audio Processor - Native C++ Implementation
 * 
 * Core audio processing pipeline for the denoiser model.
 * Handles frame processing, state management, and audio normalization.
 */

#include "poise_processor.h"
#include <cmath>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "PoiseProcessor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace poise {

// Constants matching Python implementation
constexpr int ONNX_STATE_SIZE = 45304;
constexpr int DEFAULT_FRAME_SIZE = 480;
constexpr int DEFAULT_SAMPLE_RATE = 48000;
constexpr float SOFT_LIMITER_THRESHOLD = 0.98f;
constexpr float AUDIO_CLIP_MIN = -1.0f;
constexpr float AUDIO_CLIP_MAX = 1.0f;

PoiseProcessor::PoiseProcessor(float vadThresholdDb, float attenLimDb)
    : vadThresholdDb_(vadThresholdDb)
    , attenLimDb_(attenLimDb)
    , frameSize_(DEFAULT_FRAME_SIZE)
    , sampleRate_(DEFAULT_SAMPLE_RATE)
    , frameCount_(0)
    , totalProcessingTimeMs_(0.0)
    , vad_(vadThresholdDb, 300.0f, DEFAULT_SAMPLE_RATE) // 300ms hang time
{
    // Initialize ONNX state buffer
    states_.resize(ONNX_STATE_SIZE, 0.0f);
    LOGI("PoiseProcessor initialized: VAD threshold=%.1f dB, atten limit=%.1f dB",
         vadThresholdDb, attenLimDb);
}

PoiseProcessor::~PoiseProcessor() {
    LOGI("PoiseProcessor destroyed. Processed %d frames, avg time: %.2f ms",
         frameCount_, getAverageProcessingTimeMs());
}

void PoiseProcessor::reset() {
    std::fill(states_.begin(), states_.end(), 0.0f);
    frameCount_ = 0;
    totalProcessingTimeMs_ = 0.0;
    vad_.reset();
    LOGI("PoiseProcessor state reset");
}

std::vector<float> PoiseProcessor::processFrame(
    const std::vector<float>& inputFrame,
    OnnxInferenceCallback inferenceCallback)
{
    // Normalize frame size
    std::vector<float> frame = normalizeFrameSize(inputFrame);
    
    // VAD check - bypass processing if silence
    if (!vad_.isSpeech(frame)) {
        return frame; // Pass through unprocessed
    }
    
    // Run ONNX inference via callback
    auto startTime = std::chrono::high_resolution_clock::now();
    
    std::vector<float> enhancedFrame = inferenceCallback(frame, states_, attenLimDb_);
    
    auto endTime = std::chrono::high_resolution_clock::now();
    double processingTimeMs = std::chrono::duration<double, std::milli>(endTime - startTime).count();
    
    // Update statistics
    frameCount_++;
    totalProcessingTimeMs_ += processingTimeMs;
    
    // Normalize output shape
    enhancedFrame = normalizeOutputShape(enhancedFrame, frame);
    
    // Post-processing
    postprocessAudio(enhancedFrame);
    
    return enhancedFrame;
}

std::vector<float> PoiseProcessor::normalizeFrameSize(const std::vector<float>& audio) {
    std::vector<float> result(frameSize_, 0.0f);
    
    size_t copySize = std::min(audio.size(), static_cast<size_t>(frameSize_));
    std::copy(audio.begin(), audio.begin() + copySize, result.begin());
    
    return result;
}

std::vector<float> PoiseProcessor::normalizeOutputShape(
    const std::vector<float>& output, 
    const std::vector<float>& fallback)
{
    if (output.empty()) {
        return fallback;
    }
    
    std::vector<float> result(frameSize_, 0.0f);
    size_t copySize = std::min(output.size(), static_cast<size_t>(frameSize_));
    std::copy(output.begin(), output.begin() + copySize, result.begin());
    
    return result;
}

void PoiseProcessor::postprocessAudio(std::vector<float>& audio) {
    if (audio.empty()) return;
    
    // Find max value for soft limiter
    float maxVal = 0.0f;
    for (float sample : audio) {
        maxVal = std::max(maxVal, std::abs(sample));
    }
    
    // Apply soft limiter
    if (maxVal > SOFT_LIMITER_THRESHOLD && maxVal > 0.0f) {
        float scale = SOFT_LIMITER_THRESHOLD / maxVal;
        for (float& sample : audio) {
            sample *= scale;
        }
    }
    
    // Clip to valid range
    for (float& sample : audio) {
        sample = std::clamp(sample, AUDIO_CLIP_MIN, AUDIO_CLIP_MAX);
    }
    
    // Remove DC offset
    float mean = 0.0f;
    for (float sample : audio) {
        mean += sample;
    }
    mean /= static_cast<float>(audio.size());
    
    for (float& sample : audio) {
        sample -= mean;
    }
}

ProcessingStats PoiseProcessor::getStats() const {
    ProcessingStats stats;
    stats.frameCount = frameCount_;
    stats.avgTimeMs = getAverageProcessingTimeMs();
    
    // RTF = processing time / frame duration
    float frameDurationMs = static_cast<float>(frameSize_) / static_cast<float>(sampleRate_) * 1000.0f;
    stats.rtf = (frameDurationMs > 0) ? (stats.avgTimeMs / frameDurationMs) : 0.0f;
    
    // VAD stats
    auto vadStats = vad_.getStats();
    stats.vadTotal = vadStats.total;
    stats.vadActive = vadStats.active;
    stats.vadBypassed = vadStats.bypassed;
    stats.vadBypassRatio = vadStats.bypassRatio;
    
    return stats;
}

double PoiseProcessor::getAverageProcessingTimeMs() const {
    return (frameCount_ > 0) ? (totalProcessingTimeMs_ / frameCount_) : 0.0;
}

void PoiseProcessor::updateStates(const std::vector<float>& newStates) {
    if (newStates.size() == states_.size()) {
        states_ = newStates;
    }
}

} // namespace poise
