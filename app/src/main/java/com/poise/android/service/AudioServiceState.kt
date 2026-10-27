package com.poise.android.service

import com.poise.android.audio.ProcessingStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared state holder for audio service state. This singleton ensures UI can observe state even
 * before service starts.
 */
object AudioServiceState {
    // On from the Start tap (permission dialogs included) until the session ends or fails.
    // One flag, so the UI cannot get stuck between "starting" and "running".
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _stats = MutableStateFlow<ProcessingStats?>(null)
    val stats: StateFlow<ProcessingStats?> = _stats.asStateFlow()

    // Output volume (0.0 to 1.0, 38% until the user sets it; saved in PoiseSettings)
    private val _outputVolume = MutableStateFlow(0.38f)
    val outputVolume: StateFlow<Float> = _outputVolume.asStateFlow()

    fun setOutputVolume(volume: Float) {
        _outputVolume.value = volume.coerceIn(0f, 1f)
        PoiseSettings.saveVolume(_outputVolume.value) // remembered for the next launch
    }

    fun setActive(active: Boolean) {
        _isActive.value = active
    }

    fun updateStats(newStats: ProcessingStats?) {
        _stats.value = newStats
    }

    fun reset() {
        _isActive.value = false
        _stats.value = null
    }
}
