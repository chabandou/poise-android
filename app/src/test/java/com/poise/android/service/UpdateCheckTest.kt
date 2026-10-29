package com.poise.android.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test
    fun comparesVersionsByNumberNotText() {
        assertTrue(isNewer("v1.0.2", "1.0.1"))
        assertTrue(isNewer("v1.10.0", "1.9.0")) // as text, "1.10" sorts before "1.9"
        assertTrue(isNewer("v2", "1.9.9"))
        assertFalse(isNewer("v1.0.1", "1.0.1"))
        assertFalse(isNewer("v1.0", "1.0.0"))
        assertFalse(isNewer("v1.0.0", "1.0.1"))
    }

    @Test
    fun ignoresTagsThatArentVersions() {
        assertFalse(isNewer("v1.1.0-beta", "1.0.0"))
        assertFalse(isNewer("nightly", "1.0.0"))
    }
}
