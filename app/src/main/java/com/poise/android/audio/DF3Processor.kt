package com.poise.android.audio

import android.content.Context
import android.util.Log

/**
 * DeepFilterNet3, hand-written in C++ (df3.cpp) from models/df3.onnx: the desktop app's streaming
 * export as rewritten by its tools/optimize_df3.py (poise-voice-isolator 20fef4a). 48 kHz,
 * 512-sample frames in and out; the STFT, its overlap and the recurrent state all live in the
 * native model. Output sample n is input sample n - [MODEL_DELAY].
 */
class DF3Processor(context: Context) : Denoiser {

    companion object {
        private const val TAG = "DF3Processor"
        // models/df3.onnx's weights for df3.cpp, written by scripts/native-df3.py
        private const val WEIGHTS_NAME = "df3.weights"
        const val SAMPLE_RATE = 48000
        const val FRAME_SIZE = 512
        // Measured by the desktop app (window overlap and the model's two frames of lookahead)
        const val MODEL_DELAY = 3 * FRAME_SIZE

        init {
            System.loadLibrary("poise_native")
        }
    }

    override val sampleRate = SAMPLE_RATE
    override val frameSize = FRAME_SIZE
    override val delaySamples = MODEL_DELAY

    private var handle: Long
    private val output = FloatArray(FRAME_SIZE)

    // The input as it was MODEL_DELAY samples ago, for mixing it back in line with the output
    private val dry = FloatArray(MODEL_DELAY)
    private var dryPos = 0

    private var vadThresholdLinear = 0.01f
    // Only counts quiet frames (for auto-stop): the model runs on every frame, as its state needs
    override var silenceThresholdDb = -40f
        set(value) {
            field = value
            vadThresholdLinear = Math.pow(10.0, value / 20.0).toFloat()
        }
    override var residualGain = 0f
    override var framesSinceActive = 0
        private set
    private val hangFrames = 300 * SAMPLE_RATE / 1000 / FRAME_SIZE

    private var smoothedInferenceTimeMs = 0.0

    init {
        val weights = context.assets.open(WEIGHTS_NAME).use { it.readBytes() }
        handle = nativeInit(weights)
        check(handle != 0L) { "$WEIGHTS_NAME doesn't match the model code" }
        Log.i(TAG, "DF3 loaded (${weights.size / 1024} KB)")
    }

    override fun processFrame(inputFrame: FloatArray): FloatArray {
        checkVAD(inputFrame)
        val start = System.nanoTime()
        nativeRun(handle, inputFrame, output)
        val ms = (System.nanoTime() - start) / 1e6
        smoothedInferenceTimeMs = if (smoothedInferenceTimeMs == 0.0) ms else 0.9 * smoothedInferenceTimeMs + 0.1 * ms

        for (i in 0 until FRAME_SIZE) {
            val delayed = dry[dryPos]
            dry[dryPos] = inputFrame[i]
            dryPos = (dryPos + 1) % MODEL_DELAY
            if (residualGain > 0f) output[i] += residualGain * (delayed - output[i])
        }
        return output
    }

    private fun checkVAD(audio: FloatArray) {
        var sumSquares = 0f
        for (sample in audio) sumSquares += sample * sample
        if (kotlin.math.sqrt(sumSquares / audio.size) > vadThresholdLinear) framesSinceActive = 0 else framesSinceActive++
    }

    override fun getStats(): ProcessingStats {
        val frameMs = FRAME_SIZE * 1000.0 / SAMPLE_RATE
        return ProcessingStats(
                avgTimeMs = smoothedInferenceTimeMs,
                rtf = (smoothedInferenceTimeMs / frameMs).toFloat(),
                isVadDetected = framesSinceActive < hangFrames
        )
    }

    fun reset() {
        nativeReset(handle)
        dry.fill(0f)
        dryPos = 0
        smoothedInferenceTimeMs = 0.0
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0
        }
    }

    external override fun pinToFastCores(): Int

    private external fun nativeInit(weights: ByteArray): Long
    private external fun nativeRun(handle: Long, input: FloatArray, output: FloatArray)
    private external fun nativeReset(handle: Long)
    private external fun nativeDestroy(handle: Long)
}
