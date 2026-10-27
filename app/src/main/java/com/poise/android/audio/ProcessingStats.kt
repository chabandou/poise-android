package com.poise.android.audio

/** Live processing statistics shown in the UI. */
data class ProcessingStats(
        val avgTimeMs: Double,
        val rtf: Float,
        val isVadDetected: Boolean = false
)
