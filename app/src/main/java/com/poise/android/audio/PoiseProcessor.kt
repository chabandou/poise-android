package com.poise.android.audio

import ai.onnxruntime.*
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Kotlin wrapper for native Poise audio processor. Handles ONNX model loading and inference,
 * delegating pre/post processing to native code.
 */
class PoiseProcessor(
        context: Context,
        private val vadThresholdDb: Float = -40f,
        private val attenLimDb: Float = -60f
) : AutoCloseable {

    companion object {
        private const val TAG = "PoiseProcessor"
        private const val ONNX_MODEL_NAME = "denoiser_model.onnx"
        private const val FRAME_SIZE = 480
        private const val STATE_SIZE = 45304
        private const val SAMPLE_RATE = 48000

        init {
            System.loadLibrary("poise_native")
        }
    }

    private var nativeHandle: Long = 0
    private var ortSession: OrtSession? = null
    private var ortEnv: OrtEnvironment? = null
    private var states: FloatArray = FloatArray(STATE_SIZE) { 0f }

    private var frameCount = 0
    private var totalInferenceTimeMs = 0.0

    init {
        try {
            // Initialize native processor
            nativeHandle = nativeInit(vadThresholdDb, attenLimDb)
            Log.i(TAG, "Native processor initialized, handle=$nativeHandle")

            // Load ONNX model
            loadModel(context)

            Log.i(TAG, "PoiseProcessor initialized")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}", e)
            throw RuntimeException("Native library not found: poise_native", e)
        } catch (e: Exception) {
            Log.e(TAG, "PoiseProcessor initialization failed: ${e.message}", e)
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

        // Create session options for optimized inference
        val sessionOptions =
                OrtSession.SessionOptions().apply {
                    // Use more threads for parallelism
                    setIntraOpNumThreads(4)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

                    // Try to use NNAPI (Android Neural Networks API) for hardware acceleration
                    try {
                        addNnapi()
                        Log.i(TAG, "NNAPI acceleration enabled")
                    } catch (e: Exception) {
                        Log.w(TAG, "NNAPI not available, using CPU: ${e.message}")
                    }
                }

        ortSession = ortEnv?.createSession(modelFile.absolutePath, sessionOptions)
        Log.i(TAG, "ONNX model loaded")
    }

    /** Setup resampler for input audio if device sample rate differs from model. */
    fun setupInputResampler(inputSampleRate: Int) {
        if (inputSampleRate != SAMPLE_RATE) {
            nativeSetupInputResampler(nativeHandle, inputSampleRate, SAMPLE_RATE)
        }
    }

    /** Setup resampler for output audio if device sample rate differs from model. */
    fun setupOutputResampler(outputSampleRate: Int) {
        if (outputSampleRate != SAMPLE_RATE) {
            nativeSetupOutputResampler(nativeHandle, SAMPLE_RATE, outputSampleRate)
        }
    }

    /**
     * Process a single audio frame through the denoiser pipeline.
     *
     * @param inputFrame Audio samples (will be resampled/padded to 480 samples)
     * @return Enhanced audio samples (may be different size if output resampling)
     */
    fun processFrame(inputFrame: FloatArray): FloatArray? {
        if (ortSession == null) {
            Log.e(TAG, "ONNX session not initialized")
            return inputFrame
        }

        if (nativeHandle == 0L) {
            Log.e(TAG, "Native processor not initialized")
            return inputFrame
        }

        // Track total frames processed
        totalFrames++

        return try {
            // Pre-process and check VAD (native)
            val preprocessed =
                    nativeProcessPreInference(nativeHandle, inputFrame)
                            ?: return null // Not enough samples for resampling

            // Check VAD - if silence, skip ONNX inference
            val isSpeech = nativeCheckVAD(nativeHandle, preprocessed)
            if (!isSpeech) {
                vadBypassed++
                // Still apply post-processing for consistent output
                return nativePostProcess(nativeHandle, preprocessed)
            }

            // Run ONNX inference
            val enhanced = runOnnxInference(preprocessed) ?: preprocessed

            // Post-process (native)
            nativePostProcess(nativeHandle, enhanced)
        } catch (e: Exception) {
            Log.e(TAG, "processFrame error: ${e.message}", e)
            inputFrame // Return original on error
        }
    }

    // Track total frames including VAD bypass
    private var totalFrames: Int = 0
    private var vadBypassed: Int = 0

    private fun runOnnxInference(inputFrame: FloatArray): FloatArray? {
        val session = ortSession ?: return null
        val env = ortEnv ?: return null

        val startTime = System.nanoTime()

        try {
            // Prepare inputs
            val inputTensor = OnnxTensor.createTensor(env, inputFrame)
            val statesTensor = OnnxTensor.createTensor(env, states)
            val attenTensor = OnnxTensor.createTensor(env, floatArrayOf(attenLimDb))

            val inputs =
                    mapOf(
                            "input_frame" to inputTensor,
                            "states" to statesTensor,
                            "atten_lim_db" to attenTensor
                    )

            // Run inference
            val outputs = session.run(inputs)

            // Extract outputs
            val enhancedAudio = (outputs[0].value as FloatArray)
            val newStates = (outputs[1].value as FloatArray)

            // Update states for next frame
            states = newStates

            // Cleanup tensors
            inputTensor.close()
            statesTensor.close()
            attenTensor.close()
            outputs.close()

            // Track statistics
            val inferenceTimeMs = (System.nanoTime() - startTime) / 1_000_000.0
            frameCount++
            totalInferenceTimeMs += inferenceTimeMs

            return enhancedAudio
        } catch (e: Exception) {
            Log.e(TAG, "ONNX inference error: ${e.message}")
            return null
        }
    }

    /** Get processing statistics. */
    fun getStats(): ProcessingStats {
        // Always use our Kotlin-side counters - they're more reliable
        val displayFrames = totalFrames
        val vadBypassRatio = if (totalFrames > 0) vadBypassed.toFloat() / totalFrames else 0f

        // Calculate RTF from ONNX inference time
        val rtf =
                if (frameCount > 0) {
                    val avgMs = totalInferenceTimeMs / frameCount
                    val frameDurationMs = (FRAME_SIZE.toFloat() / SAMPLE_RATE) * 1000
                    (avgMs / frameDurationMs).toFloat()
                } else {
                    0f
                }

        return ProcessingStats(
                frameCount = displayFrames,
                avgTimeMs = if (frameCount > 0) totalInferenceTimeMs / frameCount else 0.0,
                rtf = rtf,
                vadTotal = totalFrames,
                vadActive = totalFrames - vadBypassed,
                vadBypassed = vadBypassed,
                vadBypassRatio = vadBypassRatio
        )
    }

    /** Reset processor state. */
    fun reset() {
        states = FloatArray(STATE_SIZE) { 0f }
        frameCount = 0
        totalInferenceTimeMs = 0.0
        nativeReset(nativeHandle)
        Log.i(TAG, "Processor reset")
    }

    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0
        }
        ortSession?.close()
        ortSession = null
        Log.i(TAG, "PoiseProcessor closed")
    }

    // Native methods
    private external fun nativeInit(vadThresholdDb: Float, attenLimDb: Float): Long
    private external fun nativeSetupInputResampler(handle: Long, inputSr: Int, targetSr: Int)
    private external fun nativeSetupOutputResampler(handle: Long, targetSr: Int, outputSr: Int)
    private external fun nativeProcessPreInference(handle: Long, audioData: FloatArray): FloatArray?
    private external fun nativeCheckVAD(handle: Long, audioData: FloatArray): Boolean
    private external fun nativePostProcess(handle: Long, audioData: FloatArray): FloatArray
    private external fun nativeGetStats(handle: Long): ProcessingStats?
    private external fun nativeReset(handle: Long)
    private external fun nativeDestroy(handle: Long)
}
