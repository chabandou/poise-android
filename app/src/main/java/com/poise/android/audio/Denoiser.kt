package com.poise.android.audio

import android.content.Context
import com.poise.android.service.PoiseSettings

/** A speech denoising model, run one frame at a time at its own sample rate. */
interface Denoiser : AutoCloseable {
    val sampleRate: Int
    /** Samples per call to [processFrame]. */
    val frameSize: Int
    /** Output sample n is input sample n - this. */
    val delaySamples: Int

    /** Frames quieter than this count as silence. */
    var silenceThresholdDb: Float
    /** Share of the input mixed back under the model's output (0 = none). */
    var residualGain: Float
    /** Quiet frames in a row, since the last frame above [silenceThresholdDb]. */
    val framesSinceActive: Int

    /** One frame of [frameSize] samples in, one out; the returned array may be reused by the next call. */
    fun processFrame(inputFrame: FloatArray): FloatArray?

    fun getStats(): ProcessingStats

    /** Keeps the calling thread off the slowest cores; how many it may use, 0 if none are slower, or -errno. */
    fun pinToFastCores(): Int

    companion object {
        fun create(context: Context, model: PoiseSettings.Model): Denoiser =
                when (model) {
                    PoiseSettings.Model.GTCRN -> GTCRNProcessor(context)
                    PoiseSettings.Model.DF3 -> DF3Processor(context)
                }
    }
}
