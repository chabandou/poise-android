package com.poise.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.poise.android.service.PoiseSettings
import com.poise.android.ui.theme.LocalPalette
import com.poise.android.ui.theme.Rajdhani
import kotlin.math.max
import kotlin.math.pow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Motion, in ms, from the desktop's card dropdown (widgets/combo.py)
private const val OPEN_MS = 220 // the panel fades in, rises and grows; closing plays it backwards
private const val SWITCH_MS = 200 // a pick glides the tint from the old card to the new one...
private const val SWITCH_HOLD_MS = 90L // ...which rests on screen before the panel closes
private const val START_SCALE = 0.95f
private val SLIDE = 10.dp
private val GAP = 6.dp // between the row and the panel
private val INSET = 10.dp // the panel's right edge, in from the row's
private val ROOM = 14.dp // around the panel, for its shadow

/**
 * The desktop's card dropdown: the choices open under the row (or over it, without room) as a
 * stack of cards, the current one tinted gold with a gold bar on its edge. A pick glides the tint
 * to the new card, rests a beat and closes; a tap outside or Back closes it as well. Place it in
 * the row's Box, and compose it only while open: [onClose] says when it has finished closing.
 */
@Composable
internal fun CardPicker(options: List<String>, selected: Int, onPick: (Int) -> Unit, onClose: () -> Unit) {
    val pal = LocalPalette.current
    val buzz = rememberBuzz()
    val reduceMotion by PoiseSettings.reduceMotion.collectAsState()
    val still = rememberReduceMotion() || reduceMotion
    val scope = rememberCoroutineScope()
    val shown = remember { Animatable(0f) }
    var checked by remember { mutableIntStateOf(selected) }
    var closing by remember { mutableStateOf(false) }
    var above by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (still) shown.snapTo(1f) else shown.animateTo(1f, tween(OPEN_MS, easing = LinearEasing)) }
    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            if (still) shown.snapTo(0f) else shown.animateTo(0f, tween(OPEN_MS, easing = LinearEasing))
            onClose()
        }
    }
    fun pick(i: Int) {
        if (closing) return
        buzz(Buzz.Tap)
        if (i == checked) return close()
        checked = i
        onPick(i)
        scope.launch {
            if (!still) delay(SWITCH_MS + SWITCH_HOLD_MS)
            close()
        }
    }

    val density = LocalDensity.current
    val provider = remember(density) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                with(density) {
                    val room = ROOM.roundToPx()
                    val gap = GAP.roundToPx()
                    val x = anchorBounds.right - INSET.roundToPx() - popupContentSize.width + room
                    val below = anchorBounds.bottom + gap - room
                    above = below + popupContentSize.height > windowSize.height
                    return IntOffset(x, if (above) anchorBounds.top - gap - popupContentSize.height + room else below)
                }
            }
        }
    }

    Popup(popupPositionProvider = provider, onDismissRequest = ::close, properties = PopupProperties(focusable = true)) {
        val panelShape = RoundedCornerShape(16.dp)
        Column(
                Modifier.padding(ROOM)
                        .width(200.dp)
                        // Fades in while it rises into place and grows out of the edge nearest the row
                        .graphicsLayer {
                            val t = shown.value
                            val rise = 1f - (1f - t).pow(3)
                            alpha = 1f - (1f - t).pow(2)
                            scaleX = START_SCALE + (1f - START_SCALE) * rise
                            scaleY = scaleX
                            translationY = (1f - rise) * SLIDE.toPx()
                            transformOrigin = TransformOrigin(1f, if (above) 1f else 0f)
                        }
                        .shadow(12.dp, panelShape)
                        .background(pal.panel, panelShape)
                        .border(1.dp, pal.line, panelShape)
                        .padding(8.dp)
                        .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEachIndexed { i, label ->
                val source = remember { MutableInteractionSource() }
                val press = rememberPress(source, tween(240)).value
                val check by animateFloatAsState(if (i == checked) 1f else 0f, if (still) snap() else tween(SWITCH_MS), label = "cardCheck")
                // Pressed cards lift toward the gold tint; the current one takes all of it
                val base = lerp(pal.panel, pal.ink, 0.04f)
                val fill = lerp(base, lerp(base, pal.secondary, 0.08f), max(check, 0.45f * press))
                val edge = lerp(pal.line, pal.secondary.copy(alpha = 0.6f), press)
                val text by animateColorAsState(if (i == checked) pal.ink else pal.muted, tween(SWITCH_MS), label = "cardText")
                val shape = RoundedCornerShape(12.dp)
                Box(
                        Modifier.fillMaxWidth()
                                .pressScale(source, 0.97f)
                                .height(46.dp)
                                .clip(shape)
                                .background(fill)
                                .border(1.dp, edge, shape)
                                .semantics { this.selected = i == checked }
                                .clickable(source, null, role = Role.RadioButton) { pick(i) }
                                .padding(horizontal = 16.dp)
                ) {
                    Text(label, Modifier.align(Alignment.CenterStart), color = text, fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (check > 0.01f) {
                        // Out past the card's padding, onto its left edge
                        Box(Modifier.align(Alignment.CenterStart).offset(x = -16.dp).size(3.dp, 22.dp * check).background(pal.secondary, GoldBarShape))
                    }
                }
            }
        }
    }
}
