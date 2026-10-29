package com.poise.android.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 16 kHz to 48 kHz for the output track: a 48 kHz track can get Android's fast output path, a
 * 16 kHz one stays on the normal mixer. Polyphase windowed-sinc low-pass, 96 taps (2 ms of delay at
 * most, 1 ms on average), cutting off at 7.5 kHz below the first image at 8 kHz.
 */
class Upsampler {
    companion object {
        const val FACTOR = 3
        private const val TAPS_PER_PHASE = 32
        private const val TAPS = FACTOR * TAPS_PER_PHASE
        private const val CUTOFF = 7500.0 / 48000.0 // cycles per output sample
    }

    // phases[p][k] = h[k * FACTOR + p]; gain FACTOR makes up for the inserted zeros
    private val phases =
            Array(FACTOR) { p ->
                FloatArray(TAPS_PER_PHASE) { k ->
                    val n = k * FACTOR + p
                    val x = n - (TAPS - 1) / 2.0
                    val sinc = if (x == 0.0) 2 * CUTOFF else sin(2 * PI * CUTOFF * x) / (PI * x)
                    val blackman = 0.42 - 0.5 * cos(2 * PI * n / (TAPS - 1)) + 0.08 * cos(4 * PI * n / (TAPS - 1))
                    (FACTOR * sinc * blackman).toFloat()
                }
            }

    // The last TAPS_PER_PHASE - 1 input samples, then the current frame
    private var history = FloatArray(0)

    /** [out] must hold FACTOR times as many samples as [input]. */
    fun process(input: FloatArray, out: FloatArray) {
        val keep = TAPS_PER_PHASE - 1
        if (history.size != keep + input.size) history = FloatArray(keep + input.size)
        System.arraycopy(input, 0, history, keep, input.size)
        for (i in input.indices) {
            for (p in 0 until FACTOR) {
                val h = phases[p]
                var acc = 0f
                for (k in 0 until TAPS_PER_PHASE) acc += h[k] * history[keep + i - k]
                out[i * FACTOR + p] = acc
            }
        }
        System.arraycopy(history, input.size, history, 0, keep)
    }
}
