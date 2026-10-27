package com.poise.android.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Dp
import com.poise.android.ui.theme.LocalPalette
import com.poise.android.ui.theme.Rajdhani
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

internal enum class Phase { Idle, Starting, Live }

/** The Home card's motion runs this much slower than the desktop's, which felt quick on a phone. */
internal const val PACE = 1.2f

/** [ms] of the desktop's timing, at the card's [PACE]. */
internal fun paced(ms: Int) = (ms * PACE).roundToInt()

// Motion, in ms, from the desktop dial (widgets/dial.py) and lattice (widgets/pattern_card.py), at PACE
private val SWEEP_DELAY_MS = paced(300).toLong() // the ring sweeps on from the quiet end...
private val SWEEP_MS = paced(1150) // ...whenever Home is shown
private val COMET_MS = paced(990).toFloat() // starting: one sweep of the comet round the ring...
private val COMET_LAP_MS = paced(1320).toLong() // ...and a rest before the next
private val COMET_FLASH_MS = paced(69).toFloat() // a tick it passes flashes...
private val COMET_TAIL_MS = paced(178).toFloat() // ...and settles back
private val IGNITE_MIN_MS = paced(420).toFloat() // live: the entrance's wave recolours the lit ticks, at its pace but never quicker than this...
private val POP_MS = paced(480).toFloat() // ...then the disc pops
private val RIPPLE_MS = paced(1050).toFloat() // ...and a ripple leaves it, with each ping
private val CHARGE_LEAD_MS = paced(1144).toFloat() // the disc charges up this long before each later ping
private val CHARGE_FADE_MS = paced(624).toFloat()
private val CHARGE_DROP_MS = paced(420).toFloat() // a charge caught by Stop fades out
private val DRAIN_MS = paced(320).toFloat() // stopping: the accent drains back from the needle
private val PLUCK_MS = paced(230)
private val PLUCK_BIG_MS = paced(420) // every tenth step
private val GLYPH_MS = paced(420)
private val DIM_MS = paced(300).toFloat() // starting: the lit ticks dim until the session is live
private const val SWEEP_FADE_DEG = 10f // entrance: a tick fades in as the edge crosses this...
private const val SWEEP_CREST_DEG = 40f // ...and its crest settles over this, behind it
private val BUMP_MS = paced(340)
private val TRAIL_MS = paced(620)

/** Three hops off the base, each lower than the last, landing at rest by [u] = 1. */
private fun bounce(u: Float): Float =
        if (u <= 0f || u >= 1f) 0f else abs(sin(3f * PI.toFloat() * u)) * exp(-3.2f * u)

// A passed tick flashes, peaking 14% in, then settles
private fun trailFlash(u: Float) =
        if (u < 0.14f) easeOut(u / 0.14f) else 1f - FastOutSlowInEasing.transform(clamp01((u - 0.14f) / 0.86f))
private const val PING_PERIOD_MS = 5200L // a rhythm, not a speed, so not paced
private const val PING_SLOW = 1.3f // the lattice pulse, slower again than the rest of the card
private val PING_FIRST_MS = paced(1600) * PING_SLOW
private val PING_LOOP_MS = paced(2340) * PING_SLOW // must end before the next charge, 3.8 s in
private val PING_FADE_MS = paced(420).toFloat()
private const val PING_FROM = 70f
private const val PING_TO = 720f // ring radius, dp
private const val PING_BAND = 80f // clear this far inside the ring's edge...
private const val PING_CORE = 22f // ...full strength this far inside it

internal const val TICKS = 54 // 55 ticks, 5° apart, from 135° round to 405°

internal fun nowMs() = System.nanoTime() / 1_000_000

/** The entrance's crest on a tick [behind] degrees behind the edge: rises, then settles. */
private fun crestAt(behind: Float) = when {
    behind <= 0f || behind >= SWEEP_FADE_DEG + SWEEP_CREST_DEG -> 0f
    behind < SWEEP_FADE_DEG -> behind / SWEEP_FADE_DEG
    else -> 1f - easeOut((behind - SWEEP_FADE_DEG) / SWEEP_CREST_DEG)
}

/** When [SWEEP_EASE] reaches [f]: its inverse, by bisection. */
private fun sweepTime(f: Float): Float {
    var lo = 0f
    var hi = 1f
    repeat(20) { val m = (lo + hi) / 2; if (SWEEP_EASE.transform(m) < f) lo = m else hi = m }
    return hi
}

private fun clamp01(v: Float) = v.coerceIn(0f, 1f)
private fun easeOut(t: Float) = 1f - (1f - clamp01(t)).pow(3)
private fun easeIn(t: Float) = clamp01(t).pow(3)

/**
 * The Home scene's clock: when the session started, went live and stopped, and what each moment
 * looks like from there. Everything is a function of [now], so reading it in a draw redraws on
 * each frame while [moving].
 */
@Stable
internal class HomeMotion {
    /** Reduce motion: each phase shows its resting look, with no loops or sweeps. */
    var still by mutableStateOf(false)
    var now by mutableLongStateOf(nowMs())
    var phase by mutableStateOf(Phase.Idle)
        private set
    private var startingAt = 0L
    private var liveAt = 0L
    private var stoppedAt = -100_000L
    private var stoppedLive = false
    private var lit = 1 // lit ticks as the session went live or stopped, which pace the sweeps
    private var waveSpan = 1f // live: the ignition wave's travel in degrees...
    private var waveMs = 1f // ...and its length
    private var ignitedAfter = 0L
    private var dimFrom = 0f // how dim the lit ticks were as the phase changed

    /** When the wave has turned the last lit tick: the disc pops and the pings begin. */
    val ignitedAt get() = liveAt + ignitedAfter

    /** When the ignition wave has fully turned each lit tick, in ms of [nowMs] time. */
    fun ignitions() = LongArray(lit) { liveAt + litAfter(it) }

    private fun litAfter(i: Int) = (waveMs * sweepTime((5f * i + SWEEP_FADE_DEG) / waveSpan)).toLong()

    fun enter(p: Phase, litTicks: Int) {
        now = nowMs()
        lit = max(litTicks, 1)
        dimFrom = if (phase == Phase.Starting) dim(0) else 0f
        when (p) {
            Phase.Starting -> startingAt = now
            Phase.Live -> {
                liveAt = now
                // The entrance's edge and crest, over just the lit arc
                waveSpan = 5f * lit + SWEEP_FADE_DEG + SWEEP_CREST_DEG
                waveMs = max(SWEEP_MS * waveSpan / (270f + SWEEP_FADE_DEG + SWEEP_CREST_DEG), IGNITE_MIN_MS)
                ignitedAfter = litAfter(lit - 1)
            }
            Phase.Idle -> {
                stoppedLive = phase == Phase.Live
                stoppedAt = now
            }
        }
        phase = p
    }

    /** True while something is still in motion, so the frame clock can rest. */
    val moving get() = !still && (phase != Phase.Idle || now - stoppedAt < PING_LOOP_MS + 200)

    /**
     * Live, between a ping's flight and the next charge nothing moves: when that is now, the time
     * the next charge begins, so the clock can sleep instead of redrawing (the audio shares the CPU).
     */
    fun restUntil(): Long? {
        if (still || phase != Phase.Live) return null
        val since = now - ignitedAt
        if (since < 0) return null
        val into = since % PING_PERIOD_MS
        val busy = max(if (since < PING_PERIOD_MS) PING_FIRST_MS else PING_LOOP_MS, RIPPLE_MS)
        val wake = PING_PERIOD_MS - CHARGE_LEAD_MS.toLong()
        return if (into > busy && into < wake) now + (wake - into) else null
    }

    /** True in the steady loops, which run at half the frame rate; transitions get every frame. */
    val steady get() = when (phase) {
        Phase.Starting -> now - startingAt > paced(400)
        Phase.Live -> now - ignitedAt > POP_MS && now - liveAt > waveMs
        Phase.Idle -> now - stoppedAt > 2 * DRAIN_MS
    }

    /** Starting: how bright the comet makes tick [i] (0 to 1). */
    fun comet(i: Int): Float {
        if (still || phase != Phase.Starting) return 0f
        val passed = (now - startingAt) % COMET_LAP_MS - i * COMET_MS / TICKS
        return when {
            passed < 0 -> 0f
            passed < COMET_FLASH_MS -> 1f
            else -> 1f - clamp01((passed - COMET_FLASH_MS) / COMET_TAIL_MS)
        }
    }

    // Live: how far the ignition wave's edge is past tick [i], in degrees
    private fun waveBehind(i: Int) =
            -SWEEP_FADE_DEG + waveSpan * SWEEP_EASE.transform(clamp01((now - liveAt) / waveMs)) + SWEEP_FADE_DEG - 5f * i

    /** How far lit tick [i] has turned from the idle colour to the live one. */
    fun ignition(i: Int): Float {
        if (phase == Phase.Live) {
            if (still) return 1f
            return clamp01(waveBehind(i) / SWEEP_FADE_DEG)
        }
        if (phase == Phase.Idle && stoppedLive && !still) {
            // Drains from the needle back to the start
            val d = (now - stoppedAt) / DRAIN_MS
            val fromHead = max(lit - 1 - i, 0)
            return 1f - clamp01((d * (lit + 3) - fromHead) / 3f)
        }
        return 0f
    }

    /**
     * How far lit tick [i] is dimmed (0 to 1). It dims while starting, and stays dim until the
     * live wave turns it, so the colour runs gold, dim gold, cyan without stepping back.
     */
    fun dim(i: Int): Float = when (phase) {
        Phase.Starting -> if (still) 1f else clamp01((now - startingAt) / DIM_MS)
        Phase.Live -> dimFrom * (1f - ignition(i))
        Phase.Idle -> if (still) 0f else dimFrom * (1f - clamp01((now - stoppedAt) / DIM_MS))
    }

    /** The ignition wave's crest on lit tick [i], as on the entrance (0 to 1). */
    fun igniteCrest(i: Int): Float {
        if (still || phase != Phase.Live) return 0f
        return crestAt(waveBehind(i))
    }

    private fun chargeAt(t: Long): Float {
        val since = t - ignitedAt
        if (since < 0) return 0f
        val into = (since % PING_PERIOD_MS).toFloat()
        var c = 0f
        val lead = PING_PERIOD_MS - into
        if (lead < CHARGE_LEAD_MS) c = easeIn(1f - lead / CHARGE_LEAD_MS)
        if (since >= PING_PERIOD_MS && into < CHARGE_FADE_MS) c = max(c, 1f - into / CHARGE_FADE_MS)
        return c
    }

    /** The disc's glow before each ping (0 to 1). */
    fun charge(): Float = when {
        still -> 0f
        phase == Phase.Live -> chargeAt(now)
        stoppedLive -> chargeAt(stoppedAt) * (1f - clamp01((now - stoppedAt) / CHARGE_DROP_MS))
        else -> 0f
    }

    /** The disc's pop as the ignition closes, as extra scale. */
    fun pop(): Float {
        if (still || phase != Phase.Live) return 0f
        val p = (now - ignitedAt) / POP_MS
        return if (p in 0f..1f) 0.06f * sin(PI.toFloat() * p) * (1f - 0.5f * p) else 0f
    }

    // The latest ping at or before the session's end, and its age in ms
    private fun latestPing(): Pair<Long, Long>? {
        if (still) return null
        val end = if (phase == Phase.Live) now else if (stoppedLive) stoppedAt else return null
        val since = end - ignitedAt
        if (since < 0) return null
        val k = since / PING_PERIOD_MS
        return k to now - (ignitedAt + k * PING_PERIOD_MS)
    }

    private fun stopFade(fadeMs: Float) =
            if (phase == Phase.Live) 1f else 1f - clamp01((now - stoppedAt) / fadeMs)

    /** The ripple leaving the disc with each ping: its progress (0 to 1), or null. */
    fun ripple(): Float? {
        val (_, age) = latestPing() ?: return null
        val t = age / RIPPLE_MS
        return if (t < 1f && stopFade(PING_FADE_MS) > 0f) t else null
    }

    /** The ring of lattice crossing the card: its radius (dp) and opacity, or null. */
    fun ping(): Pair<Float, Float>? {
        val (k, age) = latestPing() ?: return null
        val t = age / if (k == 0L) PING_FIRST_MS else PING_LOOP_MS
        if (t >= 1f) return null
        val alpha = (if (k == 0L) 0.85f else 0.32f) * (1f - t * t) * stopFade(PING_FADE_MS)
        return (PING_FROM + (PING_TO - PING_FROM) * easeOut(t)) to alpha
    }
}

private val TILE = PathParser().parsePathString(
        "M21 21H43V43H21Z M32 16.44L47.56 32L32 47.56L16.44 32Z " +
                "M32 0V16.4M32 47.6V64M0 32H16.4M47.6 32H64M0 0L21 21M64 0L43 21M0 64L21 43M64 64L43 43"
).toPath()

/**
 * The lattice drawn once per size into an alpha-only bitmap (a quarter of the memory of a colour
 * one). Each frame then only paints it through a gradient, which costs the GPU a texture read
 * instead of the CPU re-stroking some 700 path segments.
 */
private const val LATTICE_SCALE = 0.85f // of the desktop's 64 dp tile

private class LatticeMask {
    private var bitmap: Bitmap? = null

    fun get(size: Size, density: Float): Bitmap {
        val w = size.width.roundToInt().coerceAtLeast(1)
        val h = size.height.roundToInt().coerceAtLeast(1)
        bitmap?.let { if (it.width == w && it.height == h) return it }
        release()
        val u = LATTICE_SCALE * density // the tile's unit; the lines stay 1 dp
        val tile = android.graphics.Path(TILE.asAndroidPath()).apply { transform(Matrix().apply { setScale(u, u) }) }
        val lines = android.graphics.Path()
        val dots = android.graphics.Path()
        val step = 64 * u
        var y = -6 * u
        while (y < h) {
            var x = -14 * u
            while (x < w) {
                lines.addPath(tile, x, y)
                dots.addCircle(x + 32 * u, y + 32 * u, 2.5f * u, android.graphics.Path.Direction.CW)
                x += step
            }
            y += step
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8).also {
            val canvas = android.graphics.Canvas(it)
            canvas.drawPath(lines, paint)
            paint.style = Paint.Style.FILL
            canvas.drawPath(dots, paint)
            bitmap = it
        }
    }

    fun release() {
        bitmap?.recycle()
        bitmap = null
    }
}

/** A radial gradient from [clear] to [color]; [scaleY] stretches the circle into an ellipse. */
private fun radial(cx: Float, cy: Float, r: Float, stops: FloatArray, colors: IntArray, scaleY: Float = 1f) =
        RadialGradient(cx, cy, r, colors, stops, android.graphics.Shader.TileMode.CLAMP).apply {
            if (scaleY != 1f) setLocalMatrix(Matrix().apply { setScale(1f, scaleY, cx, cy) })
        }

/**
 * The desktop's rub el hizb lattice, faded out toward the middle, with the live pings: a ring of
 * brighter lattice leaving [origin] (the dial's centre) and crossing the card. Each half has its
 * own layer, so a ping redraws only the ring and the resting lattice stays as it is.
 */
@Composable
internal fun Lattice(color: () -> Color, motion: HomeMotion, origin: () -> Offset, modifier: Modifier) {
    val accent = LocalPalette.current.accent
    val mask = remember { LatticeMask() }
    DisposableEffect(mask) { onDispose { mask.release() } } // frees it while Home isn't shown

    Spacer(
            modifier.graphicsLayer().drawWithCache {
                val bitmap = mask.get(size, density)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                var painted = Color.Unspecified
                onDrawBehind {
                    // Clear from the centre out to 40%, full at the edge of an ellipse over the card
                    val c = color().copy(alpha = 0.2f)
                    if (c != painted) {
                        painted = c
                        paint.shader = radial(
                                size.width * 0.5f, size.height * 0.55f, 0.72f * size.width,
                                floatArrayOf(0f, 0.4f, 1f), intArrayOf(c.copy(alpha = 0f).toArgb(), c.copy(alpha = 0f).toArgb(), c.toArgb()),
                                scaleY = size.height / size.width
                        )
                    }
                    drawIntoCanvas { it.nativeCanvas.drawBitmap(bitmap, 0f, 0f, paint) }
                }
            }
    )
    Spacer(
            modifier.graphicsLayer().drawWithCache {
                val bitmap = mask.get(size, density)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                onDrawBehind {
                    val (radius, alpha) = motion.ping() ?: return@onDrawBehind
                    val r = radius * density
                    val o = origin()
                    val lit = accent.copy(alpha = alpha).toArgb()
                    val clear = accent.copy(alpha = 0f).toArgb()
                    paint.shader = radial(
                            o.x, o.y, r,
                            floatArrayOf(0f, max((radius - PING_BAND) / radius, 0f), max((radius - PING_CORE) / radius, 0.01f), 0.999f, 1f),
                            intArrayOf(clear, clear, lit, lit, clear)
                    )
                    // Only the ring's square is drawn
                    clipRect(o.x - r, o.y - r, o.x + r, o.y + r) {
                        drawIntoCanvas { it.nativeCanvas.drawBitmap(bitmap, 0f, 0f, paint) }
                    }
                }
            }
    )
}

private val POWER = PathParser().parsePathString("M150 111.75v20M137.14 122.45a20 20 0 1 0 25.72 0").toPath()
private val SWEEP_EASE = CubicBezierEasing(0.3f, 0f, 0.15f, 1f)
private val EASE_OUT_CUBIC = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

/** The angle of a touch from the dial's centre: 0° at 3 o'clock, clockwise. */
private fun angleOf(p: Offset, c: Offset) =
        Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat()

/**
 * Volume for a touch angle, and how far past either end it is (degrees, + past loud, - past
 * quiet). In the gap at the bottom it keeps whichever end is nearer [previous].
 */
private fun volumeAt(deg: Float, previous: Float): Pair<Float, Float> {
    val past = ((deg - 135f) % 360f + 360f) % 360f - 270f
    return when {
        past <= 0f -> (past + 270f) / 270f to 0f
        previous > 0.5f -> 1f to min(past, 45f)
        else -> 0f to -min(90f - past, 45f)
    }
}

/**
 * The desktop dial: turn the ring for volume, tap the face to start or stop. The needle glides
 * and plucks tick by tick, the readout rolls like an odometer, the ring stretches past either end
 * and springs back, and the ring follows [motion] through starting, live and stopping.
 */
@Composable
internal fun Dial(
        volume: Float,
        motion: HomeMotion,
        onVolumeChange: (Float) -> Unit,
        onToggle: () -> Unit,
        size: Dp,
        still: Boolean
) {
    val pal = LocalPalette.current
    val buzz = rememberBuzz()
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The gesture handlers outlive recompositions, so they read these at call time
    val current by rememberUpdatedState(volume)
    val changeVolume by rememberUpdatedState(onVolumeChange)
    val toggleNow by rememberUpdatedState(onToggle)
    val phase = motion.phase

    // Entrance, as on the desktop (DialButton._sweep_behind and _sweep_crest): an edge sweeps
    // round from the quiet end; each tick fades in as it crosses, led by a crest that lights it
    // toward white and sets it a touch further out, settling over the next ticks. The needle
    // fades in at its place as the edge reaches it.
    val sweep = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(SWEEP_DELAY_MS)
        sweep.animateTo(1f, tween(SWEEP_MS, easing = LinearEasing))
    }
    val shown by animateFloatAsState(volume, spring(dampingRatio = 0.8f, stiffness = 700f), label = "needle")
    val press = remember { Animatable(0f) }
    val touch = remember { Animatable(0f) }
    var touchAngle by remember { mutableFloatStateOf(0f) }
    val stretch = remember { Animatable(0f) }
    val pluck = remember { Animatable(0f) }
    // Each tick the needle passes flashes and settles (desktop TRAIL_MS); the animatable only
    // keeps frames coming while any flash is still fading
    val trail = remember { HashMap<Int, Long>() }
    val trailClock = remember { Animatable(0f) }
    val bump = remember { Animatable(0f) }
    val percent = (volume * 100).roundToInt()
    val roll = remember { Animatable(percent.toFloat()) }
    LaunchedEffect(percent) { roll.animateTo(percent.toFloat(), spring(dampingRatio = 0.55f, stiffness = 380f)) }
    val power by animateFloatAsState(if (phase == Phase.Live) 1f else 0f, tween(paced(600)), label = "power")

    // Start/Stop rolls: the old word rises away as the new one comes up
    val word = if (phase == Phase.Idle) "Start" else "Stop"
    var oldWord by remember { mutableStateOf<String?>(null) }
    var newWord by remember { mutableStateOf(word) }
    val wordT = remember { Animatable(1f) }
    LaunchedEffect(word) {
        if (word == newWord) return@LaunchedEffect
        oldWord = newWord
        newWord = word
        wordT.snapTo(0f)
        wordT.animateTo(1f, tween(GLYPH_MS, easing = EASE_OUT_CUBIC))
        oldWord = null
    }

    fun kick(a: Animatable<Float, *>, ms: Int) = scope.launch {
        a.snapTo(1f)
        a.animateTo(0f, tween(ms, easing = EASE_OUT_CUBIC))
    }

    fun setVolume(v: Float) {
        val old = current
        val oi = (old * TICKS).roundToInt()
        val ni = (v * TICKS).roundToInt()
        if (ni != oi) {
            val tenth = (old * 100).roundToInt() / 10 != (v * 100).roundToInt() / 10
            when {
                ni == 0 || ni == TICKS -> buzz(Buzz.Edge)
                tenth -> buzz(Buzz.Step)
                else -> buzz(Buzz.Tick)
            }
            val t = nowMs()
            for (j in min(oi, ni)..max(oi, ni)) if (j != ni) trail[j] = t
            scope.launch {
                trailClock.snapTo(0f)
                trailClock.animateTo(1f, tween(TRAIL_MS))
            }
            if (tenth) kick(bump, BUMP_MS)
            kick(pluck, if (tenth) PLUCK_BIG_MS else PLUCK_MS)
        }
        changeVolume(v)
    }

    fun toggle() {
        buzz(if (motion.phase == Phase.Idle) Buzz.Start else Buzz.Stop)
        toggleNow()
    }

    val texts = remember(size, density) { DialText(measurer, size.value * density.density / 300f, density) }

    Canvas(
            Modifier.size(size)
                    .graphicsLayer() // its own layer, so each frame redraws only the dial
                    .semantics {
                        contentDescription = if (phase == Phase.Idle) "Start isolating" else "Stop isolating"
                        stateDescription = "Volume $percent%"
                        progressBarRangeInfo = ProgressBarRangeInfo(volume, 0f..1f)
                        setProgress { onVolumeChange(it.coerceIn(0f, 1f)); true }
                        onClick { toggle(); true }
                    }
                    .pointerInput(Unit) {
                        val c = Offset(this.size.width / 2f, this.size.height / 2f)
                        fun inDisc(p: Offset) = hypot(p.x - c.x, p.y - c.y) < 88f * this.size.width / 300f
                        detectTapGestures(
                                onPress = { p ->
                                    val target = if (inDisc(p)) press else touch
                                    if (target === touch) touchAngle = angleOf(p, c)
                                    scope.launch { target.animateTo(1f, tween(110, easing = EASE_OUT_CUBIC)) }
                                    val tapped = tryAwaitRelease() // false when it became a drag
                                    scope.launch {
                                        // A quick tap still sinks all the way before springing back
                                        if (tapped) target.animateTo(1f, tween((110 * (1f - target.value)).roundToInt(), easing = EASE_OUT_CUBIC))
                                        target.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 500f))
                                    }
                                },
                                onTap = { p ->
                                    if (inDisc(p)) toggle() else setVolume(volumeAt(angleOf(p, c), current).first)
                                }
                        )
                    }
                    .pointerInput(Unit) {
                        val c = Offset(this.size.width / 2f, this.size.height / 2f)
                        fun release() = scope.launch {
                            launch { touch.animateTo(0f, tween(360)) }
                            stretch.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 420f))
                        }
                        detectDragGestures(
                                onDragStart = { p ->
                                    touchAngle = angleOf(p, c)
                                    scope.launch { touch.animateTo(1f, tween(110)) }
                                },
                                onDragEnd = { release() },
                                onDragCancel = { release() }
                        ) { change, _ ->
                            touchAngle = angleOf(change.position, c)
                            val (v, past) = volumeAt(touchAngle, current)
                            // Rubber band: the further past the end, the less it gives
                            scope.launch { stretch.snapTo(8f * past / (abs(past) + 20f)) }
                            setVolume(v)
                        }
                    }
    ) {
        val s = this.size.width / 300f
        motion.now // redraws on each frame of the clock
        val center = Offset(150f, 150f)
        trailClock.value // redraws while the trail fades
        val drawNow = nowMs()
        trail.entries.removeAll { drawNow - it.value > TRAIL_MS }
        val knobF = shown * TICKS
        val knob = knobF.roundToInt()
        val litTo = knobF
        val st = stretch.value
        val edge = -SWEEP_FADE_DEG + (270f + SWEEP_FADE_DEG + SWEEP_CREST_DEG) * SWEEP_EASE.transform(sweep.value)
        fun tickAngle(f: Float) = 135f + 5f * f + if (st > 0f) st * f / TICKS else st * (1f - f / TICKS)
        val charge = motion.charge()
        val liveColor = lerp(pal.secondary, pal.accent, power)
        val comet = lerp(pal.secondary, Color.White, 0.75f) // the desktop's comet tone
        val liveComet = lerp(pal.accent, Color.White, 0.75f) // ...and the ignition crest's, in the live colour

        // How far tick angle [a] lifts outward under the finger
        fun lift(a: Float): Float {
            if (touch.value == 0f) return 0f
            val d = abs(((a - touchAngle) % 360f + 540f) % 360f - 180f)
            return 6f * touch.value * clamp01(1f - d / 22f).pow(2)
        }

        scale(s, s, pivot = Offset.Zero) {
            if (charge > 0f) {
                drawCircle(Brush.radialGradient(listOf(pal.accent.copy(alpha = 0.32f * charge), Color.Transparent), center, 132f), 132f, center)
            }
            motion.ripple()?.let { t ->
                drawCircle(pal.accent.copy(alpha = 0.5f * (1f - t)), 88f + 62f * easeOut(t), center, style = Stroke(2f))
            }

            for (i in 0..TICKS) {
                if (i == knob) continue
                // Entrance: fades in as the edge crosses it, with a crest that settles behind
                val behind = if (sweep.value >= 1f) 1e9f else edge + SWEEP_FADE_DEG - 5f * i
                if (behind <= 0f) continue
                val crest = crestAt(behind)
                val lit = i + 0.5f < litTo
                val ignite = if (lit) motion.igniteCrest(i) else 0f
                var color = if (lit) lerp(pal.secondary, pal.accent, motion.ignition(i)) else pal.tick
                if (lit) color = color.copy(alpha = 1f - 0.55f * motion.dim(i))
                val since = trail[i]?.let { (drawNow - it) / TRAIL_MS.toFloat() }
                val passed = since?.let { trailFlash(it) } ?: 0f
                // A passed tick hops outward and bounces back to rest
                val pop = since?.let { 7f * bounce(it) } ?: 0f
                val flash = max(max(motion.comet(i), 0.8f * passed), if (lit) 0.35f * charge else 0f)
                if (flash > 0f) color = lerp(color, pal.ink, flash)
                if (crest > 0f) color = lerp(color, comet, 0.85f * crest)
                if (ignite > 0f) color = lerp(color, liveComet, 0.85f * ignite)
                color = color.copy(alpha = color.alpha * clamp01(behind / SWEEP_FADE_DEG))
                val grow = 1f + 0.04f * max(crest, ignite)
                val deg = tickAngle(i.toFloat())
                val a = Math.toRadians(deg.toDouble())
                val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
                val up = lift(deg) + pop
                drawLine(
                        color,
                        center + dir * (((if (i % 9 == 0) 110f else 118f) + up) * grow),
                        center + dir * ((136f + up) * grow),
                        strokeWidth = 4f + 1.2f * passed,
                        cap = StrokeCap.Round
                )
            }

            // The needle, gliding between ticks, plucked and wobbling as it lands on each
            val deg = tickAngle(knobF)
            val a = Math.toRadians(deg.toDouble())
            val dir = Offset(cos(a).toFloat(), sin(a).toFloat())
            val up = lift(deg)
            drawLine(
                    pal.ink.copy(alpha = if (sweep.value >= 1f) 1f else clamp01((edge + SWEEP_FADE_DEG - 5f * knobF) / SWEEP_FADE_DEG)),
                    center + dir * (110f - 2f * pluck.value + up),
                    center + dir * (136f + 5f * pluck.value + up),
                    strokeWidth = 5f + 1.2f * pluck.value,
                    cap = StrokeCap.Round
            )

            withTransform({ scale(1f - 0.05f * press.value + motion.pop(), pivot = center) }) {
                drawCircle(pal.face, 88f, center)
                drawCircle(lerp(pal.line, liveColor, max(0.6f * press.value, 0.8f * charge)), 88f, center, style = Stroke(2f))
                translate(0f, -3f * press.value) {
                    drawPath(POWER, liveColor, style = Stroke(5.5f, cap = StrokeCap.Round))
                }
            }
        }

        fun label(layout: TextLayoutResult, x: Float, baseline: Float, color: Color, alpha: Float = 1f) {
            drawText(layout, color, Offset(x * s - layout.size.width / 2f, baseline * s - layout.firstBaseline), alpha)
        }

        val t = wordT.value
        oldWord?.let { label(texts.word(it), 150f, 191f - 10f * t, pal.ink, 1f - t) }
        label(texts.word(newWord), 150f, 191f + 10f * (1f - t), pal.ink, clamp01(t))

        // Squash and stretch: wider and flatter, easing back
        withTransform({ scale(1f + 0.14f * bump.value, 1f - 0.08f * bump.value, pivot = Offset(150f * s, 262f * s)) }) {
            odometer(texts, roll.value.coerceIn(0f, 100f), 150f * s, 272f * s, pal.ink)
        }
        label(texts.volume, 150f, 295f, pal.muted)
        label(texts.quiet, 52f, 268f, pal.muted)
        label(texts.loud, 248f, 268f, pal.muted)
    }
}

/** The dial's text, laid out once per size; colours are given when drawn, so themes don't re-measure. */
private class DialText(measurer: TextMeasurer, private val s: Float, private val density: Density) {
    private fun style(px: Float, rajdhani: Boolean) = TextStyle(
            fontSize = with(density) { (px * s).toSp() },
            fontFamily = if (rajdhani) Rajdhani else FontFamily.Default,
            fontWeight = if (rajdhani) FontWeight.Bold else FontWeight.Normal
    )

    private val big = style(30f, true)
    val digits = List(10) { measurer.measure("$it", big) }
    val percent = measurer.measure("%", big)
    private val start = measurer.measure("Start", style(21f, true))
    private val stop = measurer.measure("Stop", style(21f, true))
    val volume = measurer.measure("volume", style(14f, false))
    val quiet = measurer.measure("quiet", style(12f, false))
    val loud = measurer.measure("loud", style(12f, false))

    fun word(w: String) = if (w == "Start") start else stop
}

/**
 * Draws [v] percent centred on [cx], each digit column rolling to the next like an odometer: the
 * ones turn with the value, and each higher column turns only while the one below passes 9.
 */
private fun DrawScope.odometer(texts: DialText, v: Float, cx: Float, baseline: Float, color: Color) {
    val zero = texts.digits[0]
    val w = zero.size.width.toFloat()
    val h = zero.size.height * 0.8f
    val columns = when {
        v >= 99f -> 3
        v >= 9f -> 2
        else -> 1
    }
    var x = cx - (columns * w + texts.percent.size.width) / 2f
    for (p in columns - 1 downTo 0) {
        val unit = 10f.pow(p)
        val whole = floor(v / unit)
        val below = v - whole * unit
        val dc = if (p == 0) v % 10f else whole % 10f + max(0f, below - (unit - 1f))
        val d = floor(dc).toInt()
        val f = dc - d
        val leading = p > 0 && whole == 0f // a column that has not rolled over yet shows no 0
        digit(texts.digits[d % 10], x, w, baseline, -f * h, if (leading) 0f else 1f - f, color)
        digit(texts.digits[(d + 1) % 10], x, w, baseline, (1f - f) * h, f, color)
        x += w
    }
    drawText(texts.percent, color, Offset(x, baseline - texts.percent.firstBaseline))
}

private fun DrawScope.digit(layout: TextLayoutResult, x: Float, w: Float, baseline: Float, offset: Float, alpha: Float, color: Color) {
    if (alpha <= 0.01f) return
    drawText(layout, color, Offset(x + (w - layout.size.width) / 2f, baseline - layout.firstBaseline + offset), alpha)
}
