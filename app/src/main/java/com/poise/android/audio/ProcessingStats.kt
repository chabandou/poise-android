package com.poise.android.audio

/**
 * Processing statistics from native processor.
 */
data class ProcessingStats(
    val frameCount: Int,
    val avgTimeMs: Double,
    val rtf: Float,
    val vadTotal: Int,
    val vadActive: Int,
    val vadBypassed: Int,
    val vadBypassRatio: Float
) {
    val isRealTime: Boolean get() = rtf < 1.0f
    
    val vadBypassPercent: Float get() = vadBypassRatio * 100f
}
