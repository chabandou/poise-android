package com.poise.android.ui

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.poise.android.audio.ProcessingStats
import com.poise.android.service.AudioServiceState
import com.poise.android.service.PoiseSettings
import com.poise.android.ui.theme.LocalPalette
import com.poise.android.ui.theme.crossfade
import com.poise.android.ui.theme.Palettes
import com.poise.android.ui.theme.Rajdhani
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

/** What Android allows Poise to do, read again each time the app comes back. */
data class SystemStatus(val batteryUnrestricted: Boolean, val notificationsAllowed: Boolean)

private enum class Page { Home, Settings, About }

private const val GITHUB_URL = "https://github.com/chabandou/poise-android"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
        isProcessing: Boolean,
        stats: ProcessingStats?,
        outputVolume: Float,
        onVolumeChange: (Float) -> Unit,
        onToggleProcessing: (Boolean) -> Unit,
        system: SystemStatus,
        onAllowBattery: () -> Unit,
        onOpenNotificationSettings: () -> Unit,
        onAddTile: (() -> Unit)?, // null where Android cannot add tiles for apps
        versionName: String,
        onOpenUrl: (String) -> Unit
) {
    val pal = LocalPalette.current
    val buzz = rememberBuzz()
    // Android's "Remove animations" or Poise's own Reduce motion
    val reduceMotion by PoiseSettings.reduceMotion.collectAsState()
    val still = rememberReduceMotion() || reduceMotion
    // Every page stays composed beside the others: switching only slides them, where building
    // the next page cost about 95 ms, several dropped frames, on each switch
    val pager = rememberPagerState { PAGES.size }
    val scope = rememberCoroutineScope()
    fun shown(p: Page) = pager.settledPage == p.ordinal || pager.currentPage == p.ordinal
    fun go(p: Page) = scope.launch { pager.animateScrollToPage(p.ordinal, animationSpec = PAGE_SLIDE) }
    // Swiping clicks past the middle between pages, like a detent
    val swiping by pager.interactionSource.collectIsDraggedAsState()
    val swipingNow by rememberUpdatedState(swiping)
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.drop(1).collect { if (swipingNow) buzz(Buzz.Tick) } }
    // Back returns Home. A back swipe (Android 14+) first draws the page back with the gesture
    val backPeek = remember { Animatable(0f) }
    PredictiveBackHandler(enabled = pager.targetPage != Page.Home.ordinal) { swipe ->
        try {
            swipe.collect { backPeek.snapTo(it.progress) }
            scope.launch { backPeek.animateTo(0f, PAGE_SLIDE) }
            go(Page.Home)
        } catch (e: CancellationException) {
            scope.launch { backPeek.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 500f)) }
            throw e
        }
    }

    // Home's clock lives here, so leaving Home mid-session doesn't replay the ignition
    val motion = remember { HomeMotion() }
    SideEffect { motion.still = still }
    val phase = when {
        !isProcessing -> Phase.Idle // first: the stats arrive late, so they may outlast a stop
        stats != null -> Phase.Live
        else -> Phase.Starting
    }
    val ramp = rememberRamp()
    LaunchedEffect(phase) {
        motion.enter(phase, (outputVolume * TICKS).roundToInt())
        // The taps rise with the wave around the ring (Android 11+), or one buzz as it closes
        if (phase == Phase.Live && !still && !ramp(motion.ignitions())) {
            delay(motion.ignitedAt - nowMs())
            buzz(Buzz.Live)
        }
    }
    // The clock runs only while Home is on screen and has the focus: not behind a dialog, the
    // notification shade or another app. Everything is a function of the time, so it resumes
    // where it should be.
    val focused = LocalWindowInfo.current.isWindowFocused
    val resumed = rememberResumed()
    val awake = focused && resumed && shown(Page.Home)
    LaunchedEffect(phase, awake, still) {
        while (awake && motion.moving) {
            motion.restUntil()?.let { delay(it - nowMs()) }
            withFrameMillis { motion.now = nowMs() }
            // Steady loops (the comet, the live charge and pings) at about 20 fps: on this phone
            // each frame costs the same whatever it draws, so fewer frames is what saves CPU
            if (motion.steady) delay(STEADY_EXTRA_MS)
        }
    }

    Column(Modifier.fillMaxSize().background(pal.bg)) {
        Column(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
            Box(Modifier.padding(horizontal = 18.dp)) { Header(still) }
            Spacer(Modifier.height(16.dp))
            HorizontalPager(
                    pager,
                    Modifier.weight(1f).graphicsLayer {
                        val k = backPeek.value
                        scaleX = 1f - 0.06f * k
                        scaleY = scaleX
                        translationX = k * 24.dp.toPx()
                    },
                    beyondBoundsPageCount = PAGES.size - 1
            ) { i ->
                Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                    when (PAGES[i]) {
                        Page.Home -> TextScaleUpTo(1.3f) { HomePage(motion, stats, outputVolume, onVolumeChange, { onToggleProcessing(!isProcessing) }, still) }
                        Page.Settings -> SettingsPage(system, onAllowBattery, onOpenNotificationSettings, onAddTile)
                        Page.About -> AboutPage(versionName, onOpenUrl, still, shown(Page.About))
                    }
                }
            }
        }
        NavBar(
                PAGES[pager.currentPage],
                { pager.currentPage + pager.currentPageOffsetFraction },
                {
                    // Sinks on the way from the page it left to the one it is heading for
                    val from = pager.settledPage
                    val span = abs(pager.targetPage - from).coerceAtLeast(1)
                    val pos = pager.currentPage + pager.currentPageOffsetFraction
                    sin(PI.toFloat() * (abs(pos - from) / span).coerceIn(0f, 1f))
                }
        ) {
            if (it.ordinal != pager.targetPage) buzz(Buzz.Tap)
            go(it)
        }
    }
}

private val PAGES = Page.values()
private val PAGE_SLIDE = spring<Float>(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)

private const val STEADY_EXTRA_MS = 33L // two skipped frames at 60 Hz

private val SLIDER_TICK_GAP = 9.dp
private val SLIDER_GIVE = 14.dp // how far the diamond can be pulled past an end, at most

// The theme ring glides by spring, so a tap mid-glide turns it without a stop, and it sinks
// with its speed: low on the way, full size as it settles
private val GLIDE = spring<Float>(dampingRatio = 0.85f, stiffness = 140f)
private const val GLIDE_DIP_SPEED = 4f // slots a second, where the dip is deepest

private fun glideDip(velocity: Float) = (abs(velocity) / GLIDE_DIP_SPEED).coerceAtMost(1f)

@Composable
internal fun rememberResumed(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return resumed
}

// ---- Shared pieces ----

private const val WAVE =
        "M3 12C3 8.1 4.3 3 6.9 3C10.7 3 13.3 21 17.1 21C19.7 21 21 15.9 21 12M3 12H5M19 12H21M15.5 12H16.5M7.5 12H8.5"
internal val WAVE_PATH = PathParser().parsePathString(WAVE).toPath()

/** Draws an SVG path. A [stroke] width (in the path's units) outlines it, otherwise it is filled. */
@Composable
private fun PathIcon(d: String, viewBox: Float, size: Dp, color: Color, stroke: Float? = null) {
    val path = remember(d) { PathParser().parsePathString(d).toPath() }
    Canvas(Modifier.size(size)) {
        val s = this.size.width / viewBox
        scale(s, s, pivot = Offset.Zero) {
            if (stroke == null) drawPath(path, color)
            else drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** A stroke that draws itself on: [progress] 0 shows nothing, 1 the whole of each contour. */
internal fun drawOn(width: Float, progress: Float) = Stroke(
        width,
        cap = StrokeCap.Round,
        join = StrokeJoin.Round,
        pathEffect = if (progress >= 1f) null else PathEffect.dashPathEffect(floatArrayOf(44f * progress + 0.01f, 1000f))
)

/** Plays [to] once when first shown, unless motion is reduced. */
@Composable
private fun rememberIntro(still: Boolean, durationMs: Int, delayMs: Int = 0): Float {
    val a = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(durationMs, delayMs, FastOutSlowInEasing)) }
    return a.value
}

@Composable
private fun Header(still: Boolean) {
    val pal = LocalPalette.current
    val draw = rememberIntro(still, 900, 150)
    Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(28.dp)) {
            val s = size.width / 24f
            scale(s, s, pivot = Offset.Zero) { drawPath(WAVE_PATH, pal.accent, style = drawOn(1.9f, draw)) }
        }
        Spacer(Modifier.width(10.dp))
        Text("Poise", color = pal.ink, fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 26.sp)
    }
}

@Composable
private fun Title(text: String, modifier: Modifier = Modifier, size: Int = 34) {
    Text(
            text,
            modifier = modifier.semantics { heading() },
            color = LocalPalette.current.ink,
            fontFamily = Rajdhani,
            fontWeight = FontWeight.Bold,
            fontSize = size.sp,
            lineHeight = (size * 1.05).sp,
            textAlign = TextAlign.Center
    )
}

/** A bordered panel, as used for every card. */
private fun Modifier.panel(radius: Dp = 20.dp) = composed {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(radius)
    clip(shape).background(pal.panel).border(1.dp, pal.line, shape)
}

private const val PRESS_IN_MS = 90

/**
 * How far [source] is pressed, from 0 to 1. A quick tap still goes all the way in before
 * [release] brings it back, so every touch shows; a press that becomes a scroll just lets go.
 */
@Composable
internal fun rememberPress(
        source: InteractionSource,
        release: AnimationSpec<Float> = spring(dampingRatio = 0.5f, stiffness = 600f)
): Animatable<Float, AnimationVector1D> {
    val press = remember { Animatable(0f) }
    LaunchedEffect(source) {
        source.interactions.filterIsInstance<PressInteraction>().collectLatest {
            when (it) {
                is PressInteraction.Press -> press.animateTo(1f, tween(PRESS_IN_MS))
                is PressInteraction.Release -> {
                    press.animateTo(1f, tween((PRESS_IN_MS * (1f - press.value)).roundToInt()))
                    press.animateTo(0f, release)
                }
                is PressInteraction.Cancel -> press.animateTo(0f, tween(200))
            }
        }
    }
    return press
}

/** Sinks a little while pressed and springs back: the press feedback for buttons and cards. */
internal fun Modifier.pressScale(source: InteractionSource, by: Float = 0.96f) = composed {
    val press = rememberPress(source)
    graphicsLayer {
        val s = 1f - (1f - by) * press.value
        scaleX = s
        scaleY = s
    }
}

/** Lights up while pressed and fades after: the press feedback for full-width rows. */
private fun Modifier.pressWash(source: InteractionSource) = composed {
    val pal = LocalPalette.current
    val press = rememberPress(source, tween(320))
    drawBehind { if (press.value > 0f) drawRect(pal.ink.copy(alpha = 0.05f * press.value)) }
}

@Composable
private fun Marker(color: Color, turn: Float = 0f) {
    Box(Modifier.size(8.dp).rotate(45f + 90f * turn).background(color, RoundedCornerShape(1.dp)))
}

/** A titled row with the diamond marker on the left and [trailing] on the right. */
@Composable
private fun SettingRow(
        title: String,
        subtitle: String,
        modifier: Modifier = Modifier,
        markerTurn: Float = 0f,
        trailing: @Composable () -> Unit
) {
    val pal = LocalPalette.current
    Row(
            modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        Marker(pal.secondary, markerTurn)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            RowTitle(title)
            Subtitle(subtitle)
        }
        Spacer(Modifier.width(12.dp))
        TextScaleUpTo(1.3f, trailing) // short values and buttons: the title gets the room
    }
}

/** Swaps its text by rolling the old one up (or down) and the new one in. */
@Composable
private fun RollText(text: String, style: TextStyle, up: Boolean = true) {
    AnimatedContent(
            text,
            transitionSpec = {
                (slideInVertically(tween(260)) { h -> if (up) h / 2 else -h / 2 } + fadeIn(tween(200))) togetherWith
                        (slideOutVertically(tween(260)) { h -> if (up) -h / 2 else h / 2 } + fadeOut(tween(160))) using
                        SizeTransform(clip = false)
            },
            contentAlignment = Alignment.CenterEnd,
            label = "roll"
    ) { Text(it, style = style) }
}

// A row's title and the muted line under it. At large text sizes they take more lines, hyphenated,
// rather than lose words; Settings scrolls, so the rows can grow
@Composable
private fun RowTitle(text: String) {
    Text(text, color = LocalPalette.current.ink, fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, style = WRAPPED)
}

@Composable
private fun Subtitle(text: String) {
    Text(text, color = LocalPalette.current.muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, style = WRAPPED)
}

private val WRAPPED = TextStyle(hyphens = Hyphens.Auto)

/**
 * Lets the user's text size grow [content]'s text to at most [max] times, for places that can't
 * grow with it: Home, a panel that doesn't scroll; the row of five theme tiles; and the short
 * values and buttons beside a row's title, so the title gets the room.
 */
@Composable
private fun TextScaleUpTo(max: Float, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, min(d.fontScale, max)), content = content)
}

/**
 * Like [RollText], but each character rolls on its own, so -45 to -46 turns only the last digit.
 * Characters are matched from the right, where the units sit.
 */
@Composable
private fun RollDigits(text: String, style: TextStyle, up: Boolean) {
    Row {
        text.forEachIndexed { i, c ->
            key(text.length - i) {
                AnimatedContent(
                        c,
                        transitionSpec = {
                            (slideInVertically(spring(dampingRatio = 0.6f, stiffness = 700f)) { h -> if (up) h else -h } + fadeIn(tween(150))) togetherWith
                                    (slideOutVertically(tween(180)) { h -> if (up) -h else h } + fadeOut(tween(120))) using
                                    SizeTransform(clip = true)
                        },
                        label = "digit"
                ) { Text(it.toString(), style = style) }
            }
        }
    }
}

private fun autoStopLabel(minutes: Int) = when {
    minutes == 0 -> "Never"
    minutes == 60 -> "1 hour"
    else -> "$minutes min"
}

@Composable
private fun RowValue(text: String) {
    RollText(text, TextStyle(color = LocalPalette.current.secondary, fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.line))
}

// ---- Home ----

@Composable
private fun ColumnScope.HomePage(
        motion: HomeMotion,
        stats: ProcessingStats?,
        volume: Float,
        onVolumeChange: (Float) -> Unit,
        onToggle: () -> Unit,
        still: Boolean
) {
    val pal = LocalPalette.current
    val phase = motion.phase
    val latticeColor by animateColorAsState(if (phase == Phase.Live) pal.accent else pal.secondary, tween(paced(600)), label = "lattice")
    // The dial's centre within the stage. Only the difference is kept, so it stays put while a
    // page transition slides both, and writing the same value recomposes nothing.
    val coords = remember { arrayOfNulls<LayoutCoordinates>(2) } // stage, dial
    var pingOrigin by remember { mutableStateOf(Offset.Zero) }
    fun place() {
        val (stage, dial) = coords
        if (stage?.isAttached == true && dial?.isAttached == true) {
            pingOrigin = stage.localPositionOf(dial, Offset(dial.size.width / 2f, dial.size.height / 2f))
        }
    }

    // Centred as a group; on short screens the stage gives up height first
    Column(
            Modifier.weight(1f).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Box(
                Modifier.fillMaxWidth().weight(1f, fill = false).height(452.dp).panel(24.dp)
                        .onGloballyPositioned { coords[0] = it; place() }
        ) {
            Lattice({ latticeColor }, motion, { pingOrigin }, Modifier.matchParentSize())
            Column(
                    Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
            ) {
                StageTitle(motion)
                BoxWithConstraints(
                        Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { coords[1] = it; place() },
                        contentAlignment = Alignment.Center
                ) {
                    Dial(volume, motion, onVolumeChange, onToggle, minOf(maxWidth, maxHeight, 300.dp), still)
                }
            }
        }

        Box(Modifier.fillMaxWidth().heightIn(min = 62.dp).panel()) {
            SettingRow("Output", "Wired or Bluetooth") {
                RowValue(if (rememberHeadphonesConnected()) "Headphones" else "Earpiece")
            }
        }

        // After a session that ended by itself, the idle stats give their place to the reason
        val stopNote by AudioServiceState.stopNote.collectAsState()
        AnimatedContent(
                stopNote.takeIf { phase == Phase.Idle },
                Modifier.fillMaxWidth().height(62.dp).panel().semantics { liveRegion = LiveRegionMode.Polite },
                transitionSpec = {
                    (slideInVertically(tween(paced(380), easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(paced(300)))) togetherWith
                            (slideOutVertically(tween(paced(380), easing = FastOutSlowInEasing)) { -it / 2 } + fadeOut(tween(paced(200))))
                },
                label = "statsOrNote"
        ) { note ->
            Row(Modifier.fillMaxSize()) {
                val cell = Modifier.weight(1f).fillMaxHeight()
                if (note != null) {
                    StatCell("Stopped", note, pal.secondary, cell)
                } else {
                    val voice = stats?.isVadDetected == true
                    StatCell("Load", stats?.let { "${(it.rtf * 100).roundToInt()}%" }, pal.ink, cell) // the model's share of each frame
                    Box(Modifier.width(1.dp).fillMaxHeight().background(pal.line))
                    StatCell("Frame", stats?.let { "%.1f ms".format(it.avgTimeMs) }, pal.ink, cell)
                    Box(Modifier.width(1.dp).fillMaxHeight().background(pal.line))
                    StatCell("Voice", stats?.let { if (voice) "Yes" else "Quiet" }, if (voice) pal.secondary else pal.muted, cell, roll = true)
                }
            }
        }
    }
}

/** Standby / Starting... / Isolating, rolling from one to the next. Starting's dots follow the comet. */
@Composable
private fun StageTitle(motion: HomeMotion) {
    val pal = LocalPalette.current
    AnimatedContent(
            motion.phase,
            Modifier.semantics { liveRegion = LiveRegionMode.Polite }, // TalkBack says each change
            transitionSpec = {
                (slideInVertically(tween(paced(380), easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(paced(300)))) togetherWith
                        (slideOutVertically(tween(paced(380), easing = FastOutSlowInEasing)) { -it / 2 } + fadeOut(tween(paced(200))))
            },
            label = "title"
    ) { p ->
        when (p) {
            Phase.Idle -> Title("Standby")
            Phase.Live -> Title("Isolating")
            Phase.Starting -> Row(verticalAlignment = Alignment.Bottom) {
                Title("Starting")
                for (k in 1..3) {
                    Canvas(Modifier.padding(start = 3.dp, bottom = 9.dp).size(5.dp).graphicsLayer()) {
                        val lit = motion.comet(k * TICKS / 4)
                        drawCircle(pal.ink.copy(alpha = 0.3f + 0.7f * lit), size.width / 2f, center.copy(y = center.y - 3.dp.toPx() * lit))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String?, color: Color, modifier: Modifier, roll: Boolean = false) {
    val pal = LocalPalette.current
    val shown by animateColorAsState(if (value == null) pal.tick else color, tween(300), label = "stat")
    Column(modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.Center) {
        Text(label.uppercase(), color = pal.muted, fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.2.em)
        val style = TextStyle(color = shown, fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 22.sp)
        if (roll || value == null) RollText(value ?: "—", style) else Text(value, style = style)
    }
}

private val HEADPHONES =
        setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLE_HEADSET
        )

@Composable
private fun rememberHeadphonesConnected(): Boolean {
    val audio = LocalContext.current.getSystemService(AudioManager::class.java)
    fun check() = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONES }
    var connected by remember { mutableStateOf(check()) }
    DisposableEffect(audio) {
        val callback =
                object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { connected = check() }
                    override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { connected = check() }
                }
        audio.registerAudioDeviceCallback(callback, null)
        onDispose { audio.unregisterAudioDeviceCallback(callback) }
    }
    return connected
}

// ---- Settings ----

@Composable
private fun Caption(text: String) {
    val pal = LocalPalette.current
    Row(Modifier.padding(top = 2.dp).semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), color = pal.muted, fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.24.em)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(pal.line))
    }
}

/**
 * A slider whose readout rolls and ticks under the finger once per [number] step. The square thumb
 * turns into the row markers' diamond while held.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliderCard(
        title: String,
        subtitle: String,
        number: Int,
        unit: String,
        position: Float,
        range: ClosedFloatingPointRange<Float>,
        ends: Pair<String, String>,
        onChange: (Float) -> Unit
) {
    val pal = LocalPalette.current
    val buzz = rememberBuzz()
    val scope = rememberCoroutineScope()
    val source = remember { MutableInteractionSource() }
    var pressed by remember { mutableStateOf(false) }
    val dragged by source.collectIsDraggedAsState() // read first: behind || it would miss the drag's start
    val held = pressed || dragged
    val turn by animateFloatAsState(if (held) 45f else 0f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "thumb")
    val grow by animateFloatAsState(if (held) 1.25f else 1f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "thumbSize")
    val lift by animateFloatAsState(if (held) 1f else 0f, spring(stiffness = 500f), label = "rulerLift")
    val stretch = remember { Animatable(0f) } // px past either end, rubber-banded
    val current by rememberUpdatedState(position)
    val change by rememberUpdatedState(onChange)
    val track = remember { floatArrayOf(0f, 1f) } // the thumb's travel in px, set as it draws
    var dragX by remember { mutableFloatStateOf(0f) }
    val give = with(LocalDensity.current) { SLIDER_GIVE.toPx() }
    fun moveTo(x: Float) {
        val (from, to) = track
        val f = (x - from) / (to - from)
        val past = x - x.coerceIn(from, to)
        scope.launch { stretch.snapTo(give * past / (abs(past) + give * 4f)) }
        val v = range.start + (range.endInclusive - range.start) * f.coerceIn(0f, 1f)
        if (v != current) {
            if (v == range.start || v == range.endInclusive) buzz(Buzz.Edge)
            change(v)
        }
    }
    fun letGo() = scope.launch { stretch.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 420f)) }
    var last by remember { mutableIntStateOf(number) }
    val up = number >= last
    LaunchedEffect(number) {
        if (held && number != last) buzz(Buzz.Tick) // a tick for each step the readout moves
        last = number
    }

    Column(Modifier.fillMaxWidth().panel().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RowTitle(title)
                Subtitle(subtitle)
            }
            TextScaleUpTo(1.3f) { RollDigits("$number $unit", TextStyle(color = pal.accent, fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 22.sp), up) }
        }
        // A ruler lit up to the value, with the gold diamond riding on it. Drag or tap anywhere; the
        // ticks swell around the diamond, more while held, and past either end it gives a little
        Canvas(
                Modifier.fillMaxWidth()
                        .height(40.dp)
                        .semantics {
                            contentDescription = title
                            progressBarRangeInfo = ProgressBarRangeInfo(position, range)
                            setProgress { change(it.coerceIn(range)); true }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                                    onTap = { moveTo(it.x) }
                            )
                        }
                        .draggable(
                                rememberDraggableState { dragX += it; moveTo(dragX) },
                                Orientation.Horizontal,
                                interactionSource = source,
                                onDragStarted = { dragX = it.x; moveTo(it.x) },
                                onDragStopped = { letGo() }
                        )
        ) {
            val inset = 8.dp.toPx()
            val from = inset
            val to = size.width - inset
            track[0] = from
            track[1] = to
            val f = (position - range.start) / (range.endInclusive - range.start)
            val thumbX = from + f * (to - from) + stretch.value
            val cy = size.height / 2f
            val n = ((to - from) / SLIDER_TICK_GAP.toPx()).toInt()
            val step = (to - from) / n
            val reach = 28.dp.toPx()
            for (i in 0..n) {
                val x = from + i * step
                // Lit up to the diamond, with one tick's width of blend so the edge glides
                val lit = ((thumbX - x) / step + 0.5f).coerceIn(0f, 1f)
                val near = (1f - abs(x - thumbX) / reach).coerceAtLeast(0f)
                val h = 5.dp.toPx() * (1f + (0.35f + 0.5f * lift) * near * near)
                drawLine(lerp(pal.tick, pal.accent, lit), Offset(x, cy - h), Offset(x, cy + h), 2.dp.toPx(), StrokeCap.Round)
            }
            val side = 16.dp.toPx() * grow
            rotate(turn, Offset(thumbX, cy)) {
                drawRoundRect(pal.secondary, Offset(thumbX - side / 2, cy - side / 2), Size(side, side), CornerRadius(3.dp.toPx()))
            }
        }
        Row {
            Text(ends.first, color = pal.muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(ends.second, color = pal.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val pal = LocalPalette.current
    val buzz = rememberBuzz()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val knobX by animateDpAsState(if (checked) 22.dp else 0.dp, spring(dampingRatio = 0.55f, stiffness = 500f), label = "knob")
    val stretch by animateDpAsState(if (pressed) 5.dp else 0.dp, spring(stiffness = 700f), label = "knobStretch")
    val track by animateColorAsState(if (checked) pal.accent else pal.tick, tween(200), label = "track")
    val wash by animateColorAsState(if (checked) pal.accentWash else Color.Transparent, tween(200), label = "wash")
    val turn by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "marker")
    SettingRow(
            title,
            subtitle,
            Modifier.pressWash(source).toggleable(checked, source, indication = null, role = Role.Switch) {
                buzz(if (it) Buzz.On else Buzz.Off)
                onChange(it)
            },
            markerTurn = turn
    ) {
        val shape = RoundedCornerShape(8.dp)
        // 5 dp all round (border included), so the knob sits centred at either end
        Box(Modifier.size(50.dp, 28.dp).background(wash, shape).border(1.dp, track, shape).padding(5.dp)) {
            // The knob stretches toward where it is going while pressed
            Box(
                    Modifier.offset(x = knobX - if (checked) stretch else 0.dp)
                            .size(18.dp + stretch, 18.dp)
                            .background(track, RoundedCornerShape(5.dp))
            )
        }
    }
}

@Composable
private fun SmallButton(text: String, icon: String, onClick: () -> Unit, armed: Boolean = false) {
    val pal = LocalPalette.current
    val buzz = rememberBuzz()
    val shape = RoundedCornerShape(10.dp)
    val source = remember { MutableInteractionSource() }
    // Armed (waiting for a second tap), it fills with gold and its label takes the deep background colour
    val fill by animateColorAsState(if (armed) pal.secondary else Color.Transparent, tween(260), label = "buttonFill")
    val edge by animateColorAsState(if (armed) pal.secondary else pal.tick, tween(260), label = "buttonEdge")
    val ink by animateColorAsState(if (armed) pal.bg else pal.ink, tween(260), label = "buttonInk")
    val mark by animateColorAsState(if (armed) pal.bg else pal.secondary, tween(260), label = "buttonIcon")
    Row(
            Modifier.pressScale(source).height(36.dp).clip(shape).background(fill).border(1.dp, edge, shape)
                    .clickable(source, null) { buzz(Buzz.Tap); onClick() }
                    .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        PathIcon(icon, 24f, 14.dp, mark, stroke = 2f)
        Spacer(Modifier.width(6.dp))
        RollText(text, TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium))
    }
}

private const val BATTERY = "M5 7H17A2 2 0 0 1 19 9V15A2 2 0 0 1 17 17H5A2 2 0 0 1 3 15V9A2 2 0 0 1 5 7Z M22 11v2M7 10v4M10.5 10v4"
private const val PLUS = "M12 5v14M5 12h14"
private const val THEME_FADE_MS = 300
private const val RESET = "M4 12a8 8 0 1 0 2.34-5.66M4 4v4h4"

@Composable
private fun ColumnScope.SettingsPage(
        system: SystemStatus,
        onAllowBattery: () -> Unit,
        onOpenNotificationSettings: () -> Unit,
        onAddTile: (() -> Unit)?
) {
    val pal = LocalPalette.current
    val buzz = rememberBuzz()
    val strength by PoiseSettings.strength.collectAsState()
    val silenceDb by PoiseSettings.silenceDb.collectAsState()
    val autoStop by PoiseSettings.autoStopMinutes.collectAsState()
    val stopOnUnplug by PoiseSettings.stopOnUnplug.collectAsState()
    val theme by PoiseSettings.theme.collectAsState()
    val haptics by PoiseSettings.haptics.collectAsState()
    val reduceMotion by PoiseSettings.reduceMotion.collectAsState()
    val still = rememberReduceMotion() || reduceMotion
    val view = LocalView.current

    Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Title("Settings")

        Caption("Isolation")
        SliderCard(
                "Reduction strength", "How much noise is removed",
                PoiseSettings.reductionDb(strength).roundToInt(), "dB",
                strength, 0f..1f, "gentle" to "strong"
        ) { PoiseSettings.setStrength((it * 54).roundToInt() / 54f) } // in 1 dB steps
        SliderCard(
                "Silence threshold", "Quieter sound is skipped",
                silenceDb, "dB",
                silenceDb.toFloat(), -70f..-30f, "-70 dB" to "-30 dB"
        ) { PoiseSettings.setSilenceDb(it.roundToInt()) }

        Caption("Session")
        Column(Modifier.panel()) {
            var choosing by remember { mutableStateOf(false) }
            val source = remember { MutableInteractionSource() }
            Box {
                SettingRow("Auto-stop", "After this long in silence", Modifier.pressWash(source).clickable(source, null) { buzz(Buzz.Tap); choosing = true }) {
                    RowValue(autoStopLabel(autoStop))
                }
                if (choosing) {
                    val choices = PoiseSettings.AUTO_STOP_MINUTES
                    CardPicker(
                            choices.map(::autoStopLabel),
                            choices.indexOf(autoStop),
                            { PoiseSettings.setAutoStopMinutes(choices[it]) },
                            { choosing = false }
                    )
                }
            }
            RowDivider()
            SwitchRow("Stop on unplug", "Headphones removed", stopOnUnplug, PoiseSettings::setStopOnUnplug)
        }

        Caption("Look and feel")
        // One gold ring glides from the old swatch to the new one, sinking a little on the way,
        // while the old colours dissolve into the new
        val ringAt = remember { Animatable(theme.toFloat()) }
        LaunchedEffect(theme) { ringAt.animateTo(theme.toFloat(), GLIDE) }
        Row(
                Modifier.selectableGroup().drawWithContent {
                    drawContent()
                    val gap = 8.dp.toPx()
                    val n = Palettes.size
                    val slot = (size.width - (n - 1) * gap) / n
                    val k = 1f - 0.12f * glideDip(ringAt.velocity)
                    val w = 2.dp.toPx()
                    val r = 12.dp.toPx() * k
                    val ring = Size(slot * k - w, size.height * k - w)
                    drawRoundRect(
                            pal.secondary,
                            Offset(ringAt.value * (slot + gap) + (slot - ring.width) / 2, (size.height - ring.height) / 2),
                            ring,
                            CornerRadius(r),
                            style = Stroke(w)
                    )
                },
                horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextScaleUpTo(1.3f) {
                Palettes.forEachIndexed { i, p ->
                    ThemeTile(p.name, p.panel, p.accent, i == theme, Modifier.weight(1f)) {
                        if (i != theme) {
                            buzz(Buzz.Tap)
                            if (still) PoiseSettings.setTheme(i) else view.crossfade(THEME_FADE_MS.toLong()) { PoiseSettings.setTheme(i) }
                        }
                    }
                }
            }
        }
        Column(Modifier.panel()) {
            SwitchRow("Haptics", "Dial ticks and button taps", haptics, PoiseSettings::setHaptics)
            RowDivider()
            SwitchRow("Reduce motion", "Keeps the dial still", reduceMotion, PoiseSettings::setReduceMotion)
        }

        Caption("Setup")
        Column(Modifier.panel()) {
            SettingRow("No battery limits", "Runs with screen off") {
                if (system.batteryUnrestricted) RowValue("Allowed") else SmallButton("Allow", BATTERY, onAllowBattery)
            }
            RowDivider()
            SettingRow("Notifications", "For the stop button") {
                if (system.notificationsAllowed) RowValue("Allowed") else SmallButton("Allow", PLUS, onOpenNotificationSettings)
            }
            if (onAddTile != null) {
                RowDivider()
                SettingRow("Quick tile", "Start from the shade") {
                    SmallButton("Add tile", PLUS, onAddTile)
                }
            }
        }

        Column(Modifier.panel()) {
            val atDefaults = strength == PoiseSettings.DEFAULT_STRENGTH && silenceDb == PoiseSettings.DEFAULT_SILENCE_DB &&
                    autoStop == PoiseSettings.DEFAULT_AUTO_STOP_MINUTES && stopOnUnplug == PoiseSettings.DEFAULT_STOP_ON_UNPLUG &&
                    theme == PoiseSettings.DEFAULT_THEME && haptics == PoiseSettings.DEFAULT_HAPTICS &&
                    reduceMotion == PoiseSettings.DEFAULT_REDUCE_MOTION
            // The first tap asks, a second within a few seconds resets
            var confirming by remember { mutableStateOf(false) }
            LaunchedEffect(confirming) {
                if (confirming) {
                    delay(3000)
                    confirming = false
                }
            }
            SettingRow("Reset to defaults", if (confirming) "Tap again to reset" else "Isolation, session and look") {
                if (atDefaults) RowValue("Default")
                else SmallButton(if (confirming) "Confirm" else "Reset", RESET, armed = confirming, onClick = {
                    if (confirming) PoiseSettings.resetToDefaults()
                    confirming = !confirming
                })
            }
        }
    }
}

/** A theme swatch; choosing it pops its accent dot. */
@Composable
private fun ThemeTile(name: String, panel: Color, accent: Color, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    val dot = remember { Animatable(1f) }
    LaunchedEffect(on) {
        if (on) {
            dot.snapTo(0.4f)
            dot.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 400f))
        }
    }
    Column(
            modifier.pressScale(source, 0.94f)
                    .clip(shape)
                    .background(pal.panel)
                    .border(1.dp, pal.line, shape) // the gold ring is drawn by the row, gliding between tiles
                    .selectable(on, source, null, role = Role.RadioButton, onClick = onClick)
                    .padding(top = 10.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(34.dp)) {
            Box(Modifier.fillMaxSize().background(panel, CircleShape).border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape))
            Box(
                    Modifier.align(Alignment.BottomEnd).offset(2.dp, 2.dp).size(13.dp).scale(dot.value)
                            .background(pal.panel, CircleShape).padding(2.dp).background(accent, CircleShape)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(name, color = if (on) pal.ink else pal.muted, fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.04.em)
    }
}

// ---- About ----

private const val GITHUB =
        "M9 19c-4 1.3-4-2-6-2.5M15 21v-3.5c0-1 .1-1.4-.5-2 2.8-.3 5.5-1.4 5.5-6a4.6 4.6 0 0 0-1.3-3.2 4.2 4.2 0 0 0-.1-3.2s-1-.3-3.4 1.3a11.6 11.6 0 0 0-6.2 0C6.6 2.8 5.6 3.1 5.6 3.1a4.2 4.2 0 0 0-.1 3.2A4.6 4.6 0 0 0 4.2 9.5c0 4.6 2.7 5.7 5.5 6-.6.6-.6 1.2-.5 2V21"
private const val WARNING = "M12 3l9.5 16.5h-19z M12 10v4M12 17v.01"

@Composable
private fun ColumnScope.AboutPage(versionName: String, onOpenUrl: (String) -> Unit, still: Boolean, shown: Boolean) {
    val pal = LocalPalette.current

    Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
    ) {
        Emblem(still, shown)
        Spacer(Modifier.height(10.dp))
        Title("Poise Voice Isolator", size = 30)
        Text("v$versionName", color = pal.secondary, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text("Voice isolation, 100% on-device", color = pal.ink, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
                "Denoising: GTCRN (Rong et al., 2024)\nBuilt with Kotlin and ONNX Runtime",
                color = pal.muted,
                fontSize = 13.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LinkButton("GitHub", GITHUB) { onOpenUrl(GITHUB_URL) }
            LinkButton("Report an issue", WARNING) { onOpenUrl("$GITHUB_URL/issues") }
        }
    }
}

@Composable
private fun LinkButton(text: String, icon: String, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val buzz = rememberBuzz()
    val shape = RoundedCornerShape(10.dp)
    val source = remember { MutableInteractionSource() }
    Row(
            Modifier.pressScale(source).height(44.dp).clip(shape).background(pal.panel).border(1.dp, pal.tick, shape)
                    .clickable(source, null) { buzz(Buzz.Tap); onClick() }
                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        PathIcon(icon, 24f, 16.dp, pal.secondary, stroke = 1.8f)
        Spacer(Modifier.width(8.dp))
        Text(text, color = pal.ink, fontSize = 14.sp)
    }
}

// ---- Navigation ----

// Phosphor "waveform" and "info", as in the desktop sidebar
private const val HOME_ICON =
        "M56,96v64a8,8,0,0,1-16,0V96a8,8,0,0,1,16,0ZM88,24a8,8,0,0,0-8,8V224a8,8,0,0,0,16,0V32A8,8,0,0,0,88,24Zm40,32a8,8,0,0,0-8,8V192a8,8,0,0,0,16,0V64A8,8,0,0,0,128,56Zm40,32a8,8,0,0,0-8,8v64a8,8,0,0,0,16,0V96A8,8,0,0,0,168,88Zm40-16a8,8,0,0,0-8,8v96a8,8,0,0,0,16,0V80A8,8,0,0,0,208,72Z"
private const val SETTINGS_ICON = "M4 7h10M18 7h2M4 17h2M10 17h10M18 7a2 2 0 1 1-4 0a2 2 0 1 1 4 0M10 17a2 2 0 1 1-4 0a2 2 0 1 1 4 0"
private const val ABOUT_ICON =
        "M128,24A104,104,0,1,0,232,128,104.11,104.11,0,0,0,128,24Zm0,192a88,88,0,1,1,88-88A88.1,88.1,0,0,1,128,216Zm16-40a8,8,0,0,1-8,8,16,16,0,0,1-16-16V128a8,8,0,0,1,0-16,16,16,0,0,1,16,16v40A8,8,0,0,1,144,176ZM112,84a12,12,0,1,1,12,12A12,12,0,0,1,112,84Z"

@Composable
private fun NavBar(page: Page, position: () -> Float, dip: () -> Float, onSelect: (Page) -> Unit) {
    val pal = LocalPalette.current
    // One highlight rides with the pages, sinking lower on the way and rising as it arrives
    Column(Modifier.fillMaxWidth().background(pal.bar)) {
        RowDivider()
        Row(
                Modifier.fillMaxWidth()
                        .navigationBarsPadding()
                        .height(72.dp)
                        .selectableGroup()
                        .drawBehind {
                            val pad = 12.dp.toPx()
                            val gap = 8.dp.toPx()
                            val n = Page.values().size
                            val slot = (size.width - 2 * pad - (n - 1) * gap) / n
                            val h = size.height * (1f - 0.4f * dip())
                            drawRect(
                                    Brush.verticalGradient(listOf(Color.Transparent, pal.accentWash), startY = size.height - h, endY = size.height),
                                    Offset(pad + position() * (slot + gap), size.height - h),
                                    Size(slot, h)
                            )
                        }
                        .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (p in Page.values()) {
                val on = p == page
                val source = remember { MutableInteractionSource() }
                // Colours follow the highlight's arrival
                val iconColor by animateColorAsState(if (on) pal.accent else pal.muted, tween(paced(320)), label = "tabIcon")
                val textColor by animateColorAsState(if (on) pal.ink else pal.muted, tween(paced(320)), label = "tabText")
                Column(
                        Modifier.weight(1f)
                                .fillMaxHeight()
                                .pressScale(source, 0.92f)
                                .selectable(on, source, null, role = Role.Tab) { onSelect(p) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                ) {
                    when (p) {
                        Page.Home -> PathIcon(HOME_ICON, 256f, 22.dp, iconColor)
                        Page.Settings -> PathIcon(SETTINGS_ICON, 24f, 22.dp, iconColor, stroke = 1.7f)
                        Page.About -> PathIcon(ABOUT_ICON, 256f, 22.dp, iconColor)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(p.name, color = textColor, fontFamily = Rajdhani, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.04.em)
                }
            }
        }
    }
}
