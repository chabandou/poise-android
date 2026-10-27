package com.poise.android.ui

import android.os.Build
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
}

/** Plays a [Buzz] when haptics are on in Settings (and in Android's own touch feedback setting). */
@Composable
internal fun rememberBuzz(): (Buzz) -> Unit {
    val view = LocalView.current
    // Reads the setting when played, so gesture handlers that keep this lambda stay current
    return remember(view) { { b: Buzz -> if (PoiseSettings.haptics.value) view.performHapticFeedback(b.constant) } }
}

/** True when Android's "Remove animations" is on: ambient motion then stays still. */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}
