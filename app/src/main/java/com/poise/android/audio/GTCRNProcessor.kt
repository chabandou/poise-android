package com.poise.android.audio

import android.content.Context
import android.util.Log
import java.io.File

/**
 * GTCRN (Gated Temporal Convolutional Recurrent Network) processor for speech denoising.
 *
 * This is a lightweight alternative to the DeepFilterNet model with:
 * - 0.25 MB model size (vs ~10 MB)
 * - Spectrogram-based processing (STFT -> model -> iSTFT), all native
 * - State caching for real-time streaming
 * - Expected RTF < 1.0 on mobile devices
 */
class GTCRNProcessor(context: Context, private val vadThresholdDb: Float = -40f) : AutoCloseable {

    companion object {
        private const val TAG = "GTCRNProcessor"
        // The weights of models/gtcrn.onnx for the model code in gtcrn.cpp, written by
        // scripts/native-gtcrn.py. The .onnx is the desktop app's rewrite of upstream's streaming
        // export (poise-voice-isolator tools/optimize_gtcrn.py).
        private const val WEIGHTS_NAME = "gtcrn.weights"

        // Audio parameters
        const val FRAME_SIZE = 256 // hop_length (samples per frame)
        const val FFT_SIZE = 512 // n_fft
        const val NUM_BINS = 257 // FFT_SIZE/2 + 1
        const val SAMPLE_RATE = 16000 // Model expects 16kHz

        // Quiet frames that skip the model are turned down by 20 dB, not passed through as they
        // are. Otherwise a steady hiss the model removes during speech comes back in every pause.
        const val BYPASS_GAIN = 0.1f

        init {
            System.loadLibrary("poise_native")
        }
    }

    // Native STFT processor handle
    private var stftHandle: Long = 0

    // Native model handle; the model keeps its state from frame to frame
    private var modelHandle: Long = 0

    private val stftOutputBuffer = FloatArray(NUM_BINS * 2)

    // VAD
    private var vadThresholdLinear = Math.pow(10.0, vadThresholdDb / 20.0).toFloat()
    /** Frames quieter than this skip the model. */
    var silenceThresholdDb = vadThresholdDb
        set(value) {
            field = value
            vadThresholdLinear = Math.pow(10.0, value / 20.0).toFloat()
        }
    /** Share of the input's spectrum mixed back under the model's output (0 = none). */
    var residualGain = 0f
    /** Quiet frames in a row, since the last frame above [silenceThresholdDb]. */
    var framesSinceActive = 0
        private set
    private val hangFrames = ((300.0 * SAMPLE_RATE / 1000) / FRAME_SIZE).toInt() // 300ms hang time

    // Statistics
    private var smoothedInferenceTimeMs = 0.0 // EMA for live stats

    init {
        try {
            // Initialize native STFT processor
            stftHandle = nativeSTFTInit()
            Log.i(TAG, "STFT processor initialized, handle=$stftHandle")

            loadModel(context)

            Log.i(TAG, "GTCRNProcessor initialized")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}", e)
            throw RuntimeException("Native library not found: poise_native", e)
        } catch (e: Exception) {
            Log.e(TAG, "GTCRNProcessor initialization failed: ${e.message}", e)
            throw e
        }
    }

    private fun loadModel(context: Context) {
        // Load straight from the APK, so an app update always brings its own model.
        // Older versions cached a copy in filesDir that never updated; remove it.
        val weights = context.assets.open(WEIGHTS_NAME).use { it.readBytes() }
        File(context.filesDir, "gtcrn.onnx").delete()
        modelHandle = nativeModelInit(weights)
        check(modelHandle != 0L) { "$WEIGHTS_NAME doesn't match the model code" }
        Log.i(TAG, "GTCRN model loaded (${weights.size / 1024} KB)")
    }

    /**
     * Process a single audio frame through the GTCRN pipeline.
     *
     * @param inputFrame Audio samples (FRAME_SIZE = 256 samples at 16kHz)
     * @return Enhanced audio samples (256 samples)
     */
    fun processFrame(inputFrame: FloatArray): FloatArray? {
        if (modelHandle == 0L || stftHandle == 0L) {
            Log.e(TAG, "Processor not initialized")
            return inputFrame
        }

        // Ensure correct frame size
        val frame =
                if (inputFrame.size == FRAME_SIZE) {
                    inputFrame
                } else if (inputFrame.size < FRAME_SIZE) {
                    inputFrame.copyOf(FRAME_SIZE)
                } else {
                    inputFrame.copyOfRange(0, FRAME_SIZE)
                }

        // VAD check: quiet frames skip only the model. They still go through STFT and iSTFT, so
        // the output delay and overlap-add state stay the same when sound comes back, and the
        // overlapping windows fade smoothly between model output and the turned-down input.
        val isActive = checkVAD(frame)

        return try {
            // 1. Compute STFT (native) -> 514 floats (257 real + 257 imag)
            val stftResult = nativeComputeSTFT(stftHandle, frame) ?: return frame

            val enhancedStft =
                    if (isActive) {
                        // 2. Run the model
                        val startTime = System.nanoTime()
                        val modelOutput = stftOutputBuffer
                        nativeModelRun(modelHandle, stftResult, modelOutput)
                        val inferenceMs = (System.nanoTime() - startTime) / 1_000_000.0

                        // Update EMA (alpha = 0.1 means 10% weight to new sample)
                        if (smoothedInferenceTimeMs == 0.0) {
                            smoothedInferenceTimeMs = inferenceMs
                        } else {
                            smoothedInferenceTimeMs = 0.9 * smoothedInferenceTimeMs + 0.1 * inferenceMs
                        }

                        // Mixed per bin before the iSTFT, so input and output line up
                        if (residualGain > 0f) {
                            for (i in modelOutput.indices) {
                                modelOutput[i] += residualGain * (stftResult[i] - modelOutput[i])
                            }
                        }
                        modelOutput
                    } else {
                        for (i in stftResult.indices) stftResult[i] *= BYPASS_GAIN
                        stftResult
                    }

            // 3. Reconstruct audio (native iSTFT with overlap-add)
            val enhanced = nativeReconstruct(stftHandle, enhancedStft)

            // No per-frame limiter or DC removal: a different gain or offset per 16 ms frame buzzes.
            // AudioPipeline clamps each sample after volume.
            enhanced ?: frame
        } catch (e: Exception) {
            Log.e(TAG, "processFrame error: ${e.message}", e)
            frame
        }
    }

    private fun checkVAD(audio: FloatArray): Boolean {
        // RMS energy calculation
        var sumSquares = 0.0f
        for (sample in audio) {
            sumSquares += sample * sample
        }
        val rms = kotlin.math.sqrt(sumSquares / audio.size)

        val isActive = rms > vadThresholdLinear

        if (isActive) {
            framesSinceActive = 0
            return true
        } else {
            framesSinceActive++
            return framesSinceActive < hangFrames
        }
    }

    /** Get processing statistics. */
    fun getStats(): ProcessingStats {
        val rtf =
                if (smoothedInferenceTimeMs > 0) {
                    val frameDurationMs = (FRAME_SIZE.toFloat() / SAMPLE_RATE) * 1000
                    (smoothedInferenceTimeMs / frameDurationMs).toFloat()
                } else {
                    0f
                }

        return ProcessingStats(
                avgTimeMs = smoothedInferenceTimeMs, // smoothed current value, not a global average
                rtf = rtf,
                isVadDetected = framesSinceActive < hangFrames // Active if within hang time
        )
    }

    /** Reset processor state. */
    fun reset() {
        nativeModelReset(modelHandle)
        nativeSTFTReset(stftHandle)
        smoothedInferenceTimeMs = 0.0
        framesSinceActive = hangFrames + 1
        Log.i(TAG, "GTCRNProcessor reset")
    }

    override fun close() {
        if (modelHandle != 0L) {
            nativeModelDestroy(modelHandle)
            modelHandle = 0
        }
        if (stftHandle != 0L) {
            nativeSTFTDestroy(stftHandle)
            stftHandle = 0
        }
        Log.i(TAG, "GTCRNProcessor closed")
    }

    // Native methods
    private external fun nativeSTFTInit(): Long
    private external fun nativeComputeSTFT(handle: Long, audioChunk: FloatArray): FloatArray?
    private external fun nativeReconstruct(handle: Long, stftData: FloatArray): FloatArray?
    private external fun nativeSTFTReset(handle: Long)
    private external fun nativeSTFTDestroy(handle: Long)
    private external fun nativeModelInit(weights: ByteArray): Long
    private external fun nativeModelRun(handle: Long, spectrum: FloatArray, result: FloatArray)
    private external fun nativeModelReset(handle: Long)
    private external fun nativeModelDestroy(handle: Long)
}
