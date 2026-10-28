package com.poise.android.service

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's settings, kept on disk. Call [init] before reading them. */
object PoiseSettings {
    val AUTO_STOP_MINUTES = listOf(0, 5, 10, 30, 60) // 0 = never

    const val DEFAULT_STRENGTH = 1f
    const val DEFAULT_SILENCE_DB = -40
    const val DEFAULT_AUTO_STOP_MINUTES = 10
    const val DEFAULT_STOP_ON_UNPLUG = true
    const val DEFAULT_THEME = 0
    const val DEFAULT_HAPTICS = true
    const val DEFAULT_REDUCE_MOTION = false

    private var prefs: SharedPreferences? = null

    // 0 = gentle, 1 = strong
    private val _strength = MutableStateFlow(DEFAULT_STRENGTH)
    val strength: StateFlow<Float> = _strength.asStateFlow()

    private val _silenceDb = MutableStateFlow(DEFAULT_SILENCE_DB)
    val silenceDb: StateFlow<Int> = _silenceDb.asStateFlow()

    private val _autoStopMinutes = MutableStateFlow(DEFAULT_AUTO_STOP_MINUTES)
    val autoStopMinutes: StateFlow<Int> = _autoStopMinutes.asStateFlow()

    private val _stopOnUnplug = MutableStateFlow(DEFAULT_STOP_ON_UNPLUG)
    val stopOnUnplug: StateFlow<Boolean> = _stopOnUnplug.asStateFlow()

    private val _theme = MutableStateFlow(DEFAULT_THEME)
    val theme: StateFlow<Int> = _theme.asStateFlow()

    private val _haptics = MutableStateFlow(DEFAULT_HAPTICS)
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()

    private val _reduceMotion = MutableStateFlow(DEFAULT_REDUCE_MOTION)
    val reduceMotion: StateFlow<Boolean> = _reduceMotion.asStateFlow()

    /** The output picked on Home, or null to let Android route. The name shows while it's away. */
    data class Output(val key: String, val name: String)

    private val _output = MutableStateFlow<Output?>(null)
    val output: StateFlow<Output?> = _output.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _strength.value = p.getFloat("strength", _strength.value)
        _silenceDb.value = p.getInt("silence_db", _silenceDb.value)
        _autoStopMinutes.value = p.getInt("auto_stop_min", _autoStopMinutes.value)
        _stopOnUnplug.value = p.getBoolean("stop_on_unplug", _stopOnUnplug.value)
        _theme.value = p.getInt("theme", _theme.value)
        _haptics.value = p.getBoolean("haptics", _haptics.value)
        _reduceMotion.value = p.getBoolean("reduce_motion", _reduceMotion.value)
        _output.value = p.getString("output_key", null)?.let { Output(it, p.getString("output_name", null) ?: "") }
        AudioServiceState.setOutputVolume(p.getFloat("volume", AudioServiceState.outputVolume.value))
        prefs = p
    }

    /** How far the input's leftover sound sits under the voice, from -6 dB (gentle) to -60 dB. */
    fun reductionDb(strength: Float): Float = -6f - 54f * strength

    fun setStrength(v: Float) {
        _strength.value = v.coerceIn(0f, 1f)
        prefs?.edit()?.putFloat("strength", _strength.value)?.apply()
    }

    fun setSilenceDb(v: Int) {
        _silenceDb.value = v.coerceIn(-70, -30)
        prefs?.edit()?.putInt("silence_db", _silenceDb.value)?.apply()
    }

    fun setAutoStopMinutes(v: Int) {
        _autoStopMinutes.value = v
        prefs?.edit()?.putInt("auto_stop_min", v)?.apply()
    }

    fun setStopOnUnplug(v: Boolean) {
        _stopOnUnplug.value = v
        prefs?.edit()?.putBoolean("stop_on_unplug", v)?.apply()
    }

    fun setTheme(v: Int) {
        _theme.value = v
        prefs?.edit()?.putInt("theme", v)?.apply()
    }

    fun setHaptics(v: Boolean) {
        _haptics.value = v
        prefs?.edit()?.putBoolean("haptics", v)?.apply()
    }

    // Kept here so it is saved with the rest; AudioServiceState owns the live value
    fun saveVolume(v: Float) {
        prefs?.edit()?.putFloat("volume", v)?.apply()
    }

    fun setReduceMotion(v: Boolean) {
        _reduceMotion.value = v
        prefs?.edit()?.putBoolean("reduce_motion", v)?.apply()
    }

    fun setOutput(v: Output?) {
        _output.value = v
        prefs?.edit()?.putString("output_key", v?.key)?.putString("output_name", v?.name)?.apply()
    }

    /** Puts every setting on the Settings page back to its default. The volume stays as it is. */
    fun resetToDefaults() {
        setStrength(DEFAULT_STRENGTH)
        setSilenceDb(DEFAULT_SILENCE_DB)
        setAutoStopMinutes(DEFAULT_AUTO_STOP_MINUTES)
        setStopOnUnplug(DEFAULT_STOP_ON_UNPLUG)
        setTheme(DEFAULT_THEME)
        setHaptics(DEFAULT_HAPTICS)
        setReduceMotion(DEFAULT_REDUCE_MOTION)
    }
}
