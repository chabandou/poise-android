package com.poise.android.audio

import ai.onnxruntime.*
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * GTCRN (Gated Temporal Convolutional Recurrent Network) processor for speech denoising.
 *
 * This is a lightweight alternative to the DeepFilterNet model with:
 * - 0.25 MB model size (vs ~10 MB)
 * - Spectrogram-based processing (STFT -> ONNX -> iSTFT)
 * - State caching for real-time streaming
 * - Expected RTF < 1.0 on mobile devices
 */
class GTCRNProcessor(context: Context, private val vadThresholdDb: Float = -40f) : AutoCloseable {

    companion object {
        private const val TAG = "GTCRNProcessor"
        // models/gtcrn.onnx in ORT format, which is all the app's minimal ONNX Runtime loads.
        // scripts/build-ort.sh makes both. The .onnx is the desktop app's rewrite of upstream's
        // streaming export (poise-voice-isolator tools/optimize_gtcrn.py), unused opsets stripped.
        private const val ONNX_MODEL_NAME = "gtcrn.ort"

        // Audio parameters
        const val FRAME_SIZE = 256 // hop_length (samples per frame)
        const val FFT_SIZE = 512 // n_fft
        const val NUM_BINS = 257 // FFT_SIZE/2 + 1
        const val SAMPLE_RATE = 16000 // Model expects 16kHz

        // Quiet frames that skip the model are turned down by 20 dB, not passed through as they
        // are. Otherwise a steady hiss the model removes during speech comes back in every pause.
        const val BYPASS_GAIN = 0.1f

        // Cache sizes (from model analysis)
        private const val CONV_CACHE_SIZE = 2 * 1 * 16 * 16 * 33 // 16,896
        private const val TRA_CACHE_SIZE = 2 * 3 * 1 * 1 * 16 // 96
        private const val INTER_CACHE_SIZE = 2 * 1 * 33 * 16 // 1,056

        // Pre-allocated constant shapes for ONNX tensors
        private val MIX_SHAPE = longArrayOf(1, 257, 1, 2)
        private val CONV_SHAPE = longArrayOf(2, 1, 16, 16, 33)
        private val TRA_SHAPE = longArrayOf(2, 3, 1, 1, 16)
        private val INTER_SHAPE = longArrayOf(2, 1, 33, 16)

        init {
            System.loadLibrary("poise_native")
        }
    }

    // Native STFT processor handle
    private var stftHandle: Long = 0

    // ONNX session
    private var ortSession: OrtSession? = null
    private var ortEnv: OrtEnvironment? = null

    // Model inputs live in direct buffers, which ONNX Runtime reads in place. So the input tensors
    // are created once and each frame only rewrites these buffers. (MUST be before init block)
    private val mixInput = directFloats(NUM_BINS * 2)
    // State caches (persisted between frames)
    private val convCache = directFloats(CONV_CACHE_SIZE)
    private val traCache = directFloats(TRA_CACHE_SIZE)
    private val interCache = directFloats(INTER_CACHE_SIZE)
    private var inputs: Map<String, OnnxTensor> = emptyMap()

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

            // Load ONNX model
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
        ortEnv = OrtEnvironment.getEnvironment()

        // Load straight from the APK, so an app update always brings its own model.
        // Older versions cached a copy in filesDir that never updated; remove it.
        val modelBytes = context.assets.open(ONNX_MODEL_NAME).use { it.readBytes() }
        File(context.filesDir, "gtcrn.onnx").delete()

        // Create optimized session options
        val sessionOptions =
                OrtSession.SessionOptions().apply {
                    // Single thread is faster for small models (less overhead)
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)

                    // Maximum optimization
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

                    // Enable memory pattern optimization
                    setMemoryPatternOptimization(true)

                    // CPU only: the minimal runtime has no XNNPACK or NNAPI
                }

        val env = ortEnv!!
        ortSession = env.createSession(modelBytes, sessionOptions)
        inputs =
                mapOf(
                        "mix" to OnnxTensor.createTensor(env, mixInput, MIX_SHAPE),
                        "conv_cache" to OnnxTensor.createTensor(env, convCache, CONV_SHAPE),
                        "tra_cache" to OnnxTensor.createTensor(env, traCache, TRA_SHAPE),
                        "inter_cache" to OnnxTensor.createTensor(env, interCache, INTER_SHAPE)
                )
        Log.i(TAG, "GTCRN ONNX model loaded (${modelBytes.size / 1024} KB)")
    }

    private fun directFloats(size: Int): FloatBuffer =
            ByteBuffer.allocateDirect(size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /**
     * Process a single audio frame through the GTCRN pipeline.
     *
     * @param inputFrame Audio samples (FRAME_SIZE = 256 samples at 16kHz)
     * @return Enhanced audio samples (256 samples)
     */
    fun processFrame(inputFrame: FloatArray): FloatArray? {
        if (ortSession == null || stftHandle == 0L) {
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
                        // 2. Prepare ONNX input: reshape to [1, 257, 1, 2] in pre-allocated buffer
                        prepareOnnxInput(stftResult)

                        // 3. Run ONNX inference (uses pre-allocated buffers)
                        val startTime = System.nanoTime()
                        val modelOutput = runOnnxInference()
                        val inferenceMs = (System.nanoTime() - startTime) / 1_000_000.0

                        // Update EMA (alpha = 0.1 means 10% weight to new sample)
                        if (smoothedInferenceTimeMs == 0.0) {
                            smoothedInferenceTimeMs = inferenceMs
                        } else {
                            smoothedInferenceTimeMs = 0.9 * smoothedInferenceTimeMs + 0.1 * inferenceMs
                        }

                        // A failed run passes the spectrum through, keeping the same delay
                        if (modelOutput == null) {
                            stftResult
                        } else {
                            // Mixed per bin before the iSTFT, so input and output line up
                            if (residualGain > 0f) {
                                for (i in modelOutput.indices) {
                                    modelOutput[i] += residualGain * (stftResult[i] - modelOutput[i])
                                }
                            }
                            modelOutput
                        }
                    } else {
                        for (i in stftResult.indices) stftResult[i] *= BYPASS_GAIN
                        stftResult
                    }

            // 4. Reconstruct audio (native iSTFT with overlap-add)
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

    private fun prepareOnnxInput(stftResult: FloatArray) {
        // stftResult: [257 real, 257 imag] = 514 floats
        // Reshape to [1, 257, 1, 2] in mixInput (reused)
        // The ONNX model expects: [batch, freq_bins, time_frames, real_imag]
        for (i in 0 until NUM_BINS) {
            mixInput.put(i * 2, stftResult[i]) // real at even indices
            mixInput.put(i * 2 + 1, stftResult[i + NUM_BINS]) // imag at odd indices
        }
    }

    private fun runOnnxInference(): FloatArray? {
        val session = ortSession ?: return null

        return try {
            session.run(inputs).use { results ->
                // Carry the model's state to the next frame
                copyOutput(results, "conv_cache_out", convCache)
                copyOutput(results, "tra_cache_out", traCache)
                copyOutput(results, "inter_cache_out", interCache)

                // Convert from [1, 257, 1, 2] back to [257 real, 257 imag] in pre-allocated buffer
                val enh = (results.get("enh").get() as OnnxTensor).floatBuffer
                for (i in 0 until NUM_BINS) {
                    stftOutputBuffer[i] = enh.get(i * 2) // real
                    stftOutputBuffer[i + NUM_BINS] = enh.get(i * 2 + 1) // imag
                }
            }
            stftOutputBuffer
        } catch (e: Exception) {
            Log.e(TAG, "ONNX inference error: ${e.message}", e)
            null
        }
    }

    private fun copyOutput(results: OrtSession.Result, name: String, target: FloatBuffer) {
        target.clear()
        target.put((results.get(name).get() as OnnxTensor).floatBuffer)
        target.rewind()
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
        for (cache in listOf(convCache, traCache, interCache)) {
            for (i in 0 until cache.capacity()) cache.put(i, 0f)
        }
        nativeSTFTReset(stftHandle)
        smoothedInferenceTimeMs = 0.0
        framesSinceActive = hangFrames + 1
        Log.i(TAG, "GTCRNProcessor reset")
    }

    override fun close() {
        inputs.values.forEach { it.close() }
        inputs = emptyMap()
        ortSession?.close()
        ortSession = null
        ortEnv?.close()
        ortEnv = null
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
}
