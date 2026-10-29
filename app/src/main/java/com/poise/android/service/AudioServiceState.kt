package com.poise.android.service

import android.media.AudioDeviceInfo
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

    // Why the last session ended, when it ended by itself; cleared by the next start
    private val _stopNote = MutableStateFlow<StopReason?>(null)
    val stopNote: StateFlow<StopReason?> = _stopNote.asStateFlow()

    fun setActive(active: Boolean) {
        _isActive.value = active
        if (active) _stopNote.value = null
    }

    /** Records why the session is ending. The first reason wins: the rest follow from it. */
    fun noteStop(why: StopReason) {
        if (_stopNote.value == null) _stopNote.value = why
    }

    // Where the session's sound is playing, once Android has routed it
    private val _routedOutput = MutableStateFlow<AudioDeviceInfo?>(null)
    val routedOutput: StateFlow<AudioDeviceInfo?> = _routedOutput.asStateFlow()

    fun setRoutedOutput(device: AudioDeviceInfo?) {
        _routedOutput.value = device
    }

    fun updateStats(newStats: ProcessingStats?) {
        _stats.value = newStats
    }

    fun reset() {
        _isActive.value = false
        _stats.value = null
        _routedOutput.value = null
    }
}

/** Why a session ended by itself; the UI words it in the current language. */
sealed interface StopReason {
    data object OutputDisconnected : StopReason
    data object SharingEnded : StopReason
    data object CaptureEnded : StopReason
    data object CaptureFailed : StopReason
    data class Silent(val minutes: Int) : StopReason
}
