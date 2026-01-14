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
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _stats = MutableStateFlow<ProcessingStats?>(null)
    val stats: StateFlow<ProcessingStats?> = _stats.asStateFlow()

    private val _rtf = MutableStateFlow(0f)
    val rtf: StateFlow<Float> = _rtf.asStateFlow()

    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

    fun setRunning(running: Boolean) {
        _isRunning.value = running
        if (running) {
            _isStarting.value = false
        }
    }

    fun setStarting(starting: Boolean) {
        _isStarting.value = starting
    }

    fun updateStats(newStats: ProcessingStats?) {
        _stats.value = newStats
        newStats?.let { _rtf.value = it.rtf }
    }

    fun reset() {
        _isRunning.value = false
        _isStarting.value = false
        _stats.value = null
        _rtf.value = 0f
    }
}
