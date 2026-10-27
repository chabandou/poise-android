package com.poise.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RampTest {
    @Test
    fun tapsFollowTheWaveAndEndInAClick() {
        // 7 ticks lit 100 ms apart, starting 50 ms from now; taps last 10 ms, the click 20
        val times = LongArray(7) { 1050L + 100 * it }
        val steps = rampSteps(times, now = 1000, tickMs = 10, clickMs = 20)

        // Ticks 0, 3 and the last (6)
        assertEquals(listOf(false, false, true), steps.map { it.click })
        // Each wait counts from the end of the tap before: 50, then 300 - 10, then 300 - 10
        assertEquals(listOf(50, 290, 290), steps.map { it.waitMs })
        // The taps rise, and the click is full strength
        assertTrue(steps[0].strength < steps[1].strength)
        assertEquals(1f, steps.last().strength)
    }

    @Test
    fun oneTickIsJustTheClick() {
        val steps = rampSteps(longArrayOf(900), now = 1000, tickMs = 10, clickMs = 20)
        assertEquals(1, steps.size)
        assertTrue(steps[0].click)
        assertEquals(0, steps[0].waitMs) // already due: never a negative wait
    }
}
