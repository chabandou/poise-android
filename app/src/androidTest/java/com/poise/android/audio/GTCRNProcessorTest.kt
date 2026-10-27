package com.poise.android.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GTCRNProcessorTest {

    /** Quiet frames skip the model but keep STFT/iSTFT, so output = input delayed by one hop. */
    @Test
    fun bypassedFramesReconstructInputWithOneHopDelay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val hop = GTCRNProcessor.FRAME_SIZE
        val frames = 10
        // Frame f holds 8 + f whole sine periods, so each frame differs and a delay shows.
        // A 40 Hz bass tone gives every frame a non-zero mean, so per-frame DC removal shows too.
        val input = FloatArray(hop * frames) {
            (0.4 * sin(2 * PI * (8 + it / hop) * (it % hop) / hop) +
                            0.3 * sin(2 * PI * 40 * it / GTCRNProcessor.SAMPLE_RATE))
                    .toFloat()
        }
        val output = FloatArray(input.size)

        // Threshold far above any signal, so every frame bypasses the model
        GTCRNProcessor(context, vadThresholdDb = 100f).use { processor ->
            processor.reset()
            for (f in 0 until frames) {
                val frame = input.copyOfRange(f * hop, (f + 1) * hop)
                processor.processFrame(frame)!!.copyInto(output, f * hop)
            }
        }

        for (i in hop until input.size) {
            assertEquals("sample $i", input[i - hop], output[i], 1e-4f)
        }
    }
}
