package com.poise.android.audio

import ai.onnxruntime.*
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * GTCRN (Gated Temporal Convolutional Recurrent Network) processor for speech denoising.
 *
 * This is a lightweight alternative to the DeepFilterNet model with:
 * - 0.34 MB model size (vs ~10 MB)
 * - Spectrogram-based processing (STFT -> ONNX -> iSTFT)
 * - State caching for real-time streaming
 * - Expected RTF < 1.0 on mobile devices
 */
class GTCRNProcessor(context: Context, private val vadThresholdDb: Float = -40f) : AutoCloseable {

    companion object {
        private const val TAG = "GTCRNProcessor"
        private const val ONNX_MODEL_NAME = "gtcrn.onnx"

        // Audio parameters
        const val FRAME_SIZE = 256 // hop_length (samples per frame)
        const val FFT_SIZE = 512 // n_fft
        const val NUM_BINS = 257 // FFT_SIZE/2 + 1
        const val SAMPLE_RATE = 16000 // Model expects 16kHz

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

    // State caches (persisted between frames)
    private var convCache = FloatArray(CONV_CACHE_SIZE) { 0f }
    private var traCache = FloatArray(TRA_CACHE_SIZE) { 0f }
    private var interCache = FloatArray(INTER_CACHE_SIZE) { 0f }

    // Pre-allocated buffers to avoid per-frame allocations (MUST be before init block)
    private val mixInputBuffer = FloatArray(514)
    private val stftOutputBuffer = FloatArray(514)
    private val enhArrayBuffer = FloatArray(514)

    // VAD
    private var vadThresholdLinear = Math.pow(10.0, vadThresholdDb / 20.0).toFloat()
    private var framesSinceActive = 0
    private val hangFrames = ((300.0 * SAMPLE_RATE / 1000) / FRAME_SIZE).toInt() // 300ms hang time

    // Statistics
    private var frameCount = 0
    private var totalInferenceTimeMs = 0.0
    private var vadBypassed = 0

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

        // Copy model from assets to internal storage
        val modelFile = File(context.filesDir, ONNX_MODEL_NAME)
        if (!modelFile.exists()) {
            context.assets.open(ONNX_MODEL_NAME).use { input ->
                FileOutputStream(modelFile).use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "Model copied to: ${modelFile.absolutePath}")
        }

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

                    // Try XNNPACK first (optimized for ARM), then NNAPI
                    try {
                        // XNNPACK is often faster than NNAPI for small models
                        addXnnpack(mapOf("intra_op_num_threads" to "1"))
                        Log.i(TAG, "XNNPACK acceleration enabled")
                    } catch (e: Exception) {
                        Log.w(TAG, "XNNPACK not available, trying NNAPI: ${e.message}")
                        try {
                            addNnapi()
                            Log.i(TAG, "NNAPI acceleration enabled")
                        } catch (e2: Exception) {
                            Log.w(TAG, "NNAPI not available, using CPU: ${e2.message}")
                        }
                    }
                }

        ortSession = ortEnv?.createSession(modelFile.absolutePath, sessionOptions)
        Log.i(TAG, "GTCRN ONNX model loaded (${modelFile.length() / 1024} KB)")
    }

    // Pre-allocated input/output name arrays
    private val inputNames = arrayOf("mix", "conv_cache", "tra_cache", "inter_cache")
    private val outputNames = arrayOf("enh", "conv_cache_out", "tra_cache_out", "inter_cache_out")

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

        // VAD check
        if (!checkVAD(frame)) {
            vadBypassed++
            frameCount++
            return frame // Pass through silent audio
        }

        return try {
            // 1. Compute STFT (native) -> 514 floats (257 real + 257 imag)
            val stftResult = nativeComputeSTFT(stftHandle, frame) ?: return frame

            // 2. Prepare ONNX input: reshape to [1, 257, 1, 2] in pre-allocated buffer
            prepareOnnxInput(stftResult)

            // 3. Run ONNX inference (uses pre-allocated buffers)
            val startTime = System.nanoTime()
            val enhancedStft = runOnnxInference() ?: return frame
            val inferenceMs = (System.nanoTime() - startTime) / 1_000_000.0
            totalInferenceTimeMs += inferenceMs

            // 4. Reconstruct audio (native iSTFT with overlap-add)
            val enhanced = nativeReconstruct(stftHandle, enhancedStft)

            frameCount++

            // Post-process
            postProcess(enhanced ?: frame)
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
        // Reshape to [1, 257, 1, 2] in mixInputBuffer (reused)
        // The ONNX model expects: [batch, freq_bins, time_frames, real_imag]
        for (i in 0 until NUM_BINS) {
            mixInputBuffer[i * 2] = stftResult[i] // real at even indices
            mixInputBuffer[i * 2 + 1] = stftResult[i + NUM_BINS] // imag at odd indices
        }
    }

    private fun runOnnxInference(): FloatArray? {
        val env = ortEnv ?: return null
        val session = ortSession ?: return null

        return try {
            // Create input tensors (shapes are constant)
            val mixTensor =
                    OnnxTensor.createTensor(
                            env,
                            java.nio.FloatBuffer.wrap(mixInputBuffer),
                            MIX_SHAPE
                    )
            val convTensor =
                    OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(convCache), CONV_SHAPE)
            val traTensor =
                    OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(traCache), TRA_SHAPE)
            val interTensor =
                    OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(interCache), INTER_SHAPE)

            // Use pre-allocated map for inputs (avoid map creation each frame)
            val inputs =
                    mapOf(
                            "mix" to mixTensor,
                            "conv_cache" to convTensor,
                            "tra_cache" to traTensor,
                            "inter_cache" to interTensor
                    )

            val results = session.run(inputs)

            // Get outputs - rewind buffers before reading
            val enhOutput = (results.get("enh").get() as OnnxTensor).floatBuffer
            val convCacheOut = (results.get("conv_cache_out").get() as OnnxTensor).floatBuffer
            val traCacheOut = (results.get("tra_cache_out").get() as OnnxTensor).floatBuffer
            val interCacheOut = (results.get("inter_cache_out").get() as OnnxTensor).floatBuffer

            // Update state caches for next frame (reuse existing arrays)
            convCacheOut.rewind()
            convCacheOut.get(convCache)
            traCacheOut.rewind()
            traCacheOut.get(traCache)
            interCacheOut.rewind()
            interCacheOut.get(interCache)

            // Extract enhanced STFT into pre-allocated buffer
            enhOutput.rewind()
            enhOutput.get(enhArrayBuffer)

            // Close tensors
            mixTensor.close()
            convTensor.close()
            traTensor.close()
            interTensor.close()
            results.close()

            // Convert from [1, 257, 1, 2] back to [257 real, 257 imag] in pre-allocated buffer
            for (i in 0 until NUM_BINS) {
                stftOutputBuffer[i] = enhArrayBuffer[i * 2] // real
                stftOutputBuffer[i + NUM_BINS] = enhArrayBuffer[i * 2 + 1] // imag
            }
            stftOutputBuffer
        } catch (e: Exception) {
            Log.e(TAG, "ONNX inference error: ${e.message}", e)
            null
        }
    }

    private fun postProcess(audio: FloatArray): FloatArray {
        if (audio.isEmpty()) return audio

        // Soft limiter
        var maxVal = 0f
        for (sample in audio) {
            val abs = kotlin.math.abs(sample)
            if (abs > maxVal) maxVal = abs
        }
        if (maxVal > 0.98f) {
            val scale = 0.98f / maxVal
            for (i in audio.indices) {
                audio[i] *= scale
            }
        }

        // Clip
        for (i in audio.indices) {
            audio[i] = audio[i].coerceIn(-1f, 1f)
        }

        // DC removal
        var mean = 0f
        for (sample in audio) {
            mean += sample
        }
        mean /= audio.size
        for (i in audio.indices) {
            audio[i] -= mean
        }

        return audio
    }

    /** Get processing statistics. */
    fun getStats(): ProcessingStats {
        val displayFrames = frameCount
        val vadBypassRatio = if (frameCount > 0) vadBypassed.toFloat() / frameCount else 0f
        val onnxFrames = frameCount - vadBypassed

        val rtf =
                if (onnxFrames > 0) {
                    val avgMs = totalInferenceTimeMs / onnxFrames
                    val frameDurationMs = (FRAME_SIZE.toFloat() / SAMPLE_RATE) * 1000
                    (avgMs / frameDurationMs).toFloat()
                } else {
                    0f
                }

        return ProcessingStats(
                frameCount = displayFrames,
                avgTimeMs = if (onnxFrames > 0) totalInferenceTimeMs / onnxFrames else 0.0,
                rtf = rtf,
                vadTotal = frameCount,
                vadActive = frameCount - vadBypassed,
                vadBypassed = vadBypassed,
                vadBypassRatio = vadBypassRatio
        )
    }

    /** Reset processor state. */
    fun reset() {
        convCache.fill(0f)
        traCache.fill(0f)
        interCache.fill(0f)
        nativeSTFTReset(stftHandle)
        frameCount = 0
        totalInferenceTimeMs = 0.0
        vadBypassed = 0
        framesSinceActive = hangFrames + 1
        Log.i(TAG, "GTCRNProcessor reset")
    }

    override fun close() {
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
