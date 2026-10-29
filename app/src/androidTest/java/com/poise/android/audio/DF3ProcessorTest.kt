package com.poise.android.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DF3ProcessorTest {

    /**
     * Same input, same output as the desktop's ONNX Runtime: df3_golden.f32 was recorded on the PC
     * from this input (a 300 Hz tone plus LCG noise), states fed back from the desktop's .npz. Catches
     * wrong start states and states carried to the wrong inputs.
     */
    @Test
    fun modelOutputMatchesDesktop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val hop = DF3Processor.FRAME_SIZE
        val frames = 60
        var seed = 1L
        val input = FloatArray(hop * frames) {
            seed = (seed * 1103515245 + 12345) % (1L shl 31)
            (0.3 * sin(2 * PI * 300 * it / DF3Processor.SAMPLE_RATE) + 0.1 * (seed.toDouble() / (1L shl 31) * 2 - 1)).toFloat()
        }
        val output = FloatArray(input.size)
        DF3Processor(instrumentation.targetContext).use { processor ->
            for (f in 0 until frames) {
                processor.processFrame(input.copyOfRange(f * hop, (f + 1) * hop)).copyInto(output, f * hop)
            }
        }

        val golden =
                instrumentation.context.assets.open("df3_golden.f32").use {
                    ByteBuffer.wrap(it.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                }
        assertEquals(output.size, golden.remaining())
        for (i in output.indices) {
            assertEquals("sample $i", golden[i], output[i], 1e-4f)
        }
    }
}
