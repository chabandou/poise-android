package com.poise.android.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    /**
     * Runs the model on fixed input and compares with recorded output, which catches mistakes in
     * how state caches carry from frame to frame. The actual output is always written to the app's
     * external files dir as gtcrn_actual.f32; after an intended model change, pull it with adb and
     * replace androidTest/assets/gtcrn_golden.f32.
     */
    @Test
    fun modelOutputMatchesGolden() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val hop = GTCRNProcessor.FRAME_SIZE
        val frames = 50
        val noise = java.util.Random(42)
        val input = FloatArray(hop * frames) {
            (0.3 * sin(2 * PI * 300 * it / GTCRNProcessor.SAMPLE_RATE) +
                            0.1 * (noise.nextFloat() * 2 - 1))
                    .toFloat()
        }
        val output = FloatArray(input.size)

        // Threshold far below any signal, so the model runs on every frame
        GTCRNProcessor(instrumentation.targetContext, vadThresholdDb = -200f).use { processor ->
            for (f in 0 until frames) {
                val frame = input.copyOfRange(f * hop, (f + 1) * hop)
                processor.processFrame(frame)!!.copyInto(output, f * hop)
            }
        }

        val bytes = ByteBuffer.allocate(output.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(output)
        File(instrumentation.targetContext.getExternalFilesDir(null), "gtcrn_actual.f32")
                .writeBytes(bytes.array())

        val golden =
                instrumentation.context.assets.open("gtcrn_golden.f32").use {
                    ByteBuffer.wrap(it.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                }
        assertEquals(output.size, golden.remaining())
        for (i in output.indices) {
            assertEquals("sample $i", golden[i], output[i], 1e-4f)
        }
    }
}
