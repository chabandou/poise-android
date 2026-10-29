package com.poise.android.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.poise.android.service.PoiseSettings

/** The kinds of haptic feedback, from lightest to firmest. */
internal enum class Buzz {
    Tick, // one step of the dial or a slider
    Step, // every tenth step, felt a little more
    Edge, // the end of a dial or slider
    Tap, // tabs, buttons, choices
    On, // a switch turning on
    Off, // ...or off
    Live, // the dial lighting up once isolation runs
    Start,
    Stop;

    // The newer, more specific constants where Android has them
    val constant: Int
        get() {
            val v = Build.VERSION.SDK_INT
            return when (this) {
                Tick -> if (v >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
                Step -> if (v >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CONTEXT_CLICK
                Edge -> HapticFeedbackConstants.VIRTUAL_KEY
                Tap -> HapticFeedbackConstants.KEYBOARD_TAP
                On -> if (v >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CLOCK_TICK
                Off -> if (v >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
                Live -> if (v >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CONTEXT_CLICK
                Start -> if (v >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
                Stop -> HapticFeedbackConstants.LONG_PRESS
            }
        }

    /** Its length as a plain pulse, for motors that can only switch on and off: the length is all they can vary. */
    val pulseMs: Long
        get() = when (this) {
            Tick -> 15
            Step -> 25
            Edge -> 35
            Tap, On -> 30
            Off -> 22
            Live, Start, Stop -> 45 // what Samsung's own touch feedback plays on such a motor
        }
}

/** Plays a [Buzz] when haptics are on in Settings (and in Android's own touch feedback setting). */
@Composable
internal fun rememberBuzz(): (Buzz) -> Unit {
    val view = LocalView.current
    val pulses = remember(view) { basicVibrator(view.context) }
    // Reads the setting when played, so gesture handlers that keep this lambda stay current
    return remember(view) {
        { b: Buzz ->
            if (PoiseSettings.haptics.value) {
                if (pulses != null) pulse(view.context, pulses, b.pulseMs) else view.performHapticFeedback(b.constant)
            }
        }
    }
}

/**
 * The vibrator when it says it has no click effect (a Galaxy A16's on/off motor): there Android
 * drops nearly every performHapticFeedback constant as unsupported, all but LONG_PRESS. Null where
 * the constants play, or where Android can't tell (before 11, or an unknown answer).
 */
private fun basicVibrator(context: Context): Vibrator? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val vibrator = context.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() } ?: return null
    val click = vibrator.areEffectsSupported(VibrationEffect.EFFECT_CLICK)[0]
    return vibrator.takeIf { click == Vibrator.VIBRATION_EFFECT_SUPPORT_NO }
}

/** A pulse of [ms] as touch feedback, so Android's touch vibration settings apply to it. */
private fun pulse(context: Context, vibrator: Vibrator, ms: Long) {
    if (!touchFeedbackOn(context)) return
    val effect = VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
    }
}

/** Android's own touch feedback setting, which performHapticFeedback follows by itself. */
private fun touchFeedbackOn(context: Context) =
        Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

private const val RAMP_EVERY = 3 // ticks per tap: denser runs blur into a buzz
private const val RAMP_FROM = 0.15f // the first tap's strength; they rise to RAMP_TO...
private const val RAMP_TO = 0.6f // ...and a full click lands as the ring closes

/**
 * Plays a rising run of light taps at [times] (in [nowMs] time), one per few ticks the ignition
 * wave lights, ending in a firm click at the last. Returns false where it can't (before Android
 * 11, or without the primitives), so the caller plays its single [Buzz.Live] instead.
 */
@Composable
internal fun rememberRamp(): (LongArray) -> Boolean {
    val context = LocalContext.current
    return remember(context) { { times: LongArray -> playRamp(context, times) } }
}

private fun playRamp(context: Context, times: LongArray): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || times.isEmpty()) return false
    val tick = VibrationEffect.Composition.PRIMITIVE_LOW_TICK
    val click = VibrationEffect.Composition.PRIMITIVE_CLICK
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return false
    if (!vibrator.areAllPrimitivesSupported(tick, click)) return false
    // Off in Poise or in Android's own touch feedback setting: played, but silently
    if (!PoiseSettings.haptics.value || !touchFeedbackOn(context)) return true
    // Each delay counts from the end of the tap before, so take its length off where Android says it
    val (tickMs, clickMs) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) vibrator.getPrimitiveDurations(tick, click).toList() else listOf(0, 0)
    val effect = VibrationEffect.startComposition()
    for (step in rampSteps(times, nowMs(), tickMs, clickMs)) {
        effect.addPrimitive(if (step.click) click else tick, step.strength, step.waitMs)
    }
    vibrator.vibrate(effect.compose())
    return true
}

internal class RampStep(val click: Boolean, val strength: Float, val waitMs: Int)

/** The taps for [times] from [now]: every few ticks a rising tap, then the click at the last. */
internal fun rampSteps(times: LongArray, now: Long, tickMs: Int, clickMs: Int): List<RampStep> {
    val picks = times.indices.filter { it % RAMP_EVERY == 0 && it < times.lastIndex } + times.lastIndex
    var at = now
    var busy = 0
    return picks.mapIndexed { n, i ->
        val last = i == times.lastIndex
        val step = RampStep(
                last,
                if (last) 1f else RAMP_FROM + (RAMP_TO - RAMP_FROM) * n / (picks.size - 1).coerceAtLeast(1),
                (times[i] - at - busy).coerceAtLeast(0).toInt()
        )
        at = times[i]
        busy = if (last) clickMs else tickMs
        step
    }
}

/** True when Android's "Remove animations" is on: ambient motion then stays still. */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}
