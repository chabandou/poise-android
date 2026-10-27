package com.poise.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.poise.android.R
import com.poise.android.ui.theme.LocalPalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The seal's beats, in ms, from the desktop emblem (widgets/emblem.py, SEAL)
private const val NEEDLE_MS = 340f
private const val TICK_FROM = 160f
private const val TICK_STEP = 11f
private const val TICK_MS = 170f
private const val DASH_FROM = 560f
private const val DASH_STEP = 110f
private const val DASH_MS = 100f
private const val WAVE_FROM = 820f
private const val WAVE_TO = 1480f
private const val DISC_FROM = 1080f
private const val DISC_TO = 1700f
private const val SQ1_FROM = 1600f // the diagonal square...
private const val SQ1_TO = 2160f
private const val SQ2_FROM = 2340f // ...then the upright one
private const val SQ2_TO = 2900f
private const val WRITE_FROM = 3350f
private const val WRITE_TO = 4550f
private const val SEAL_END = 4650f
private const val BACK_SPEED = 1.6f // a second tap plays it all back, faster
private const val SPIN = 405f // how far each square turns on its way in
private const val HALF = 96f // half side of each square: points reach r 136, where the ticks ended
private const val LINE = 2.4f // each square's line...
private const val INNER_INSET = 5f // ...and the finer one inside it
private const val INNER_LINE = 0.8f
private val GLOW_BANDS = arrayOf(4f to 0.22f, 9f to 0.10f, 16f to 0.05f) // wider, fainter bands round the line
private const val FINALE_FROM = 250f // sealed: the squares swell with light once, after this...
private const val FINALE_MS = 1200f // ...over this
private const val BREATH_MS = 5200f // ...then breathe slowly
private const val BREATH = 0.3f
private const val BREATH_FRAME_MS = 33L // the breath alone needs no more than 20 frames a second

// The lettering's box in the 300-unit square (the baked image is centred, scaled 0.29)
private const val TEXT_X = 121.3f
private const val TEXT_Y = 78.7f
private const val TEXT_W = 57.4f
private const val TEXT_H = 145f

private fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
private fun easeIn(t: Float) = t.pow(3)
private fun easeOut(t: Float) = 1f - (1f - t).pow(3)
private fun smooth(t: Float) = t * t * (3f - 2f * t)
private fun bell(t: Float) = if (t <= 0f || t >= 1f) 0f else 0.5f - 0.5f * cos(2f * PI.toFloat() * t)

/** A square's glow as it locks: up quickly, then fading slowly ([ms] after it arrives). */
private fun lockGlow(ms: Float) = when {
    ms < 0f -> 0f
    ms < 90f -> 0.5f * smooth(ms / 90f)
    else -> 0.5f * exp(-(ms - 90f) / 450f)
}

/**
 * The lock: a square arriving at full speed runs on past its seat, is pulled back and settles,
 * like a part clicking home (degrees, [ms] after it arrives).
 */
private fun lock(ms: Float) = if (ms < 0f) 0f else 10f * exp(-ms / 110f) * sin(2f * PI.toFloat() * ms / 260f)

/**
 * The About emblem, holding a light take on the desktop's easter egg, the seal. Tap it and the
 * dial unwinds: the gold needle lifts off, the ticks fly out anticlockwise, the long dashes are
 * taken back one by one, the wave un-writes and the face shrinks away. Two squares then spin in
 * and lock into a rub el hizb with a flash of light, and تَمَّ بِحَمْدِ اللَّهِ ("it is finished, with praise to God",
 * the line scribes closed a manuscript with) is written inside from the bottom up. The squares
 * then swell with light and keep breathing while the page is in view. Tap again and
 * it plays back. It stays still until tapped, and leaving the page shows the plain emblem again.
 */
@Composable
internal fun Emblem(still: Boolean) {
    val pal = LocalPalette.current
    val buzz = rememberBuzz()
    val scope = rememberCoroutineScope()
    val lettering = ImageBitmap.imageResource(R.drawable.seal_text)
    val t = remember { Animatable(0f) } // ms into the seal
    var sealed by remember { mutableStateOf(false) }
    // Once sealed, ms since: the squares swell with light, then breathe while the page is in view
    var since by remember { mutableFloatStateOf(0f) }
    val done by remember { derivedStateOf { sealed && t.value >= SEAL_END } }
    val awake = LocalWindowInfo.current.isWindowFocused && rememberResumed()
    LaunchedEffect(done, still, awake) {
        if (!done || still || !awake) return@LaunchedEffect
        val from = withFrameMillis { it } - since.toLong()
        while (true) {
            withFrameMillis { since = (it - from).toFloat() }
            delay(BREATH_FRAME_MS)
        }
    }
    LaunchedEffect(sealed) { if (!sealed) since = 0f }

    // A firm tick as each square locks
    LaunchedEffect(Unit) {
        var last = t.value
        snapshotFlow { t.value }.collect { v ->
            if (v > last) for (at in floatArrayOf(SQ1_TO, SQ2_TO)) if (last < at && v >= at) buzz(Buzz.Step)
            last = v
        }
    }

    fun toggle() {
        buzz(Buzz.Tap)
        sealed = !sealed
        scope.launch {
            val target = if (sealed) SEAL_END else 0f
            if (still) t.snapTo(target)
            else t.animateTo(target, tween((abs(target - t.value) / if (sealed) 1f else BACK_SPEED).roundToInt(), easing = LinearEasing))
        }
    }

    Canvas(
            Modifier.size(210.dp)
                    .graphicsLayer()
                    .semantics {
                        contentDescription = "Poise emblem"
                        onClick { toggle(); true }
                    }
                    .pointerInput(Unit) { detectTapGestures { toggle() } }
    ) {
        val s = size.width / 300f
        val ms = t.value
        val c = Offset(150f, 150f)
        scale(s, s, pivot = Offset.Zero) {
            val ring = pal.accent.copy(alpha = 0.85f)
            // 72 fine ticks and 12 long ones, 1.3° wide, centred on the top. Both leave anticlockwise
            for (k in 0 until 72) {
                val j = (72 - k) % 72
                val p = seg(ms, TICK_FROM + j * TICK_STEP, TICK_FROM + j * TICK_STEP + TICK_MS)
                if (p < 1f) {
                    val r = 124f + 36f * easeIn(p)
                    drawArc(ring.copy(alpha = 0.85f * (1f - p)), -90.65f + k * 5, 1.3f, false, Offset(150f - r, 150f - r), Size(2 * r, 2 * r), style = Stroke(18f))
                }
                if (k % 6 == 0) {
                    val m = (12 - k / 6) % 12
                    val q = seg(ms, DASH_FROM + m * DASH_STEP, DASH_FROM + m * DASH_STEP + DASH_MS)
                    if (q < 1f) drawArc(ring, -90.65f + k * 5, 1.3f, false, Offset(30f, 30f), Size(240f, 240f), style = Stroke(26f * (1f - q)))
                }
            }

            val lift = seg(ms, 0f, NEEDLE_MS)
            if (lift < 1f) {
                translate(0f, -18f * easeIn(lift)) {
                    drawLine(pal.secondary.copy(alpha = 1f - lift), Offset(150f, 40f), Offset(150f, 14f), 5f, StrokeCap.Round)
                }
            }

            // The face swells a touch, then shrinks away
            val d = seg(ms, DISC_FROM, DISC_TO)
            val r = 88f * max(0f, 1f + 0.1f * sin(PI.toFloat() * d) - d.pow(2.2f))
            if (r > 0f) {
                drawCircle(pal.face, r, c)
                drawCircle(pal.line, r, c, style = Stroke(2f))
            }
            val w = seg(ms, WAVE_FROM, WAVE_TO)
            if (w < 1f) {
                translate(99.6f, 99.6f) {
                    scale(4.2f, 4.2f, pivot = Offset.Zero) { drawPath(WAVE_PATH, pal.accent, style = drawOn(1.9f, 1f - w)) }
                }
            }

            // The squares spin in, fast then slower (never crawling), and lock with a flash of
            // light. Sealed, they swell with light once and then breathe (the desktop seal's glow)
            val ambient = if (done && !still) 0.9f * bell((since - FINALE_FROM) / FINALE_MS) +
                    BREATH * (0.5f - 0.5f * cos(2f * PI.toFloat() * since / BREATH_MS)) else 0f
            val light = lerp(pal.accent, Color.White, 0.55f)
            for ((from, to, seat) in listOf(Triple(SQ1_FROM, SQ1_TO, 45f), Triple(SQ2_FROM, SQ2_TO, 0f))) {
                if (ms < from) continue
                val u = seg(ms, from, to)
                val wob = lock(ms - to)
                val turn = seat - SPIN * (1f - (1.35f * u - 0.35f * u * u)) + wob
                val grow = 0.7f + 0.3f * easeOut(u) - 0.025f * abs(wob) / 6f
                val glow = (lockGlow(ms - to) + ambient).coerceAtMost(1.4f)
                rotate(turn, c) {
                    scale(grow, pivot = c) {
                        val a = (u * 4f).coerceAtMost(1f)
                        fun square(inset: Float, color: Color, width: Float) = drawRect(
                                color, Offset(150f - HALF + inset, 150f - HALF + inset), Size(2 * (HALF - inset), 2 * (HALF - inset)),
                                style = Stroke(width, join = StrokeJoin.Round)
                        )
                        square(0f, pal.accent.copy(alpha = a), LINE)
                        square(INNER_INSET, pal.accent.copy(alpha = 0.45f * a), INNER_LINE)
                        if (glow > 0.01f) {
                            for ((extra, alpha) in GLOW_BANDS) square(0f, light.copy(alpha = (alpha * glow * a).coerceAtMost(1f)), LINE + extra)
                            square(0f, light.copy(alpha = (0.7f * glow * a).coerceAtMost(1f)), LINE)
                        }
                    }
                }
            }
        }

        // The closing line, written from the bottom up in white
        val write = seg(ms, WRITE_FROM, WRITE_TO)
        if (write > 0f) {
            val top = (TEXT_Y + TEXT_H * (1f - easeOut(write))) * s
            clipRect(top = top - 4f * s) {
                drawImage(
                        lettering,
                        dstOffset = IntOffset((TEXT_X * s).roundToInt(), (TEXT_Y * s).roundToInt()),
                        dstSize = IntSize((TEXT_W * s).roundToInt(), (TEXT_H * s).roundToInt()),
                        colorFilter = ColorFilter.tint(pal.ink)
                )
            }
        }
    }
}
