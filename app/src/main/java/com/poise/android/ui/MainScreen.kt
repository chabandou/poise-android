package com.poise.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.poise.android.audio.ProcessingStats
import com.poise.android.ui.theme.*

// Custom Beveled Shape (Cut top-right, bottom-left)
// polygon(0 0, 92% 0, 100% 15%, 100% 100%, 8% 100%, 0 85%)
val BeveledShape = GenericShape { size, _ ->
        val w = size.width
        val h = size.height
        moveTo(0f, 0f)
        lineTo(w * 0.92f, 0f)
        lineTo(w, h * 0.15f)
        lineTo(w, h)
        lineTo(w * 0.08f, h)
        lineTo(0f, h * 0.85f)
        close()
}

// Gradient Text Modifier
fun Modifier.gradientText(brush: Brush): Modifier =
        this.graphicsLayer(alpha = 0.99f) // Required for SrcIn blend mode
                .drawWithCache {
                        onDrawWithContent {
                                drawContent()
                                drawRect(brush, blendMode = BlendMode.SrcIn)
                        }
                }

@Composable
fun MainScreen(
        isProcessing: Boolean,
        stats: ProcessingStats?,
        outputVolume: Float,
        onVolumeChange: (Float) -> Unit,
        onToggleProcessing: (Boolean) -> Unit
) {
        // Animations for colors
        val ambientColor by
                animateColorAsState(
                        targetValue =
                                if (isProcessing) PoiseLime.copy(alpha = 0.1f)
                                else Color(0xFF3B82F6).copy(alpha = 0.1f),
                        animationSpec = tween(1000)
                )
        val headerColor by
                animateColorAsState(
                        targetValue = if (isProcessing) Color(0xFFECFDF5) else Color.White,
                        animationSpec = tween(500)
                )
        val subHeaderColor by
                animateColorAsState(
                        targetValue = if (isProcessing) PoiseLime else PoiseCyan,
                        animationSpec = tween(500)
                )

        var showModal by remember { mutableStateOf(false) }

        Box(modifier = Modifier.fillMaxSize().background(PoiseDarkBackground)) {
                // 1. Grid Background
                Canvas(modifier = Modifier.fillMaxSize()) {
                        val step = 30.dp.toPx()
                        val w = size.width
                        val h = size.height

                        // Draw vertical lines
                        for (x in 0..itemCount(w, step)) {
                                drawLine(
                                        color = PoiseGridLine,
                                        start = Offset(x * step, 0f),
                                        end = Offset(x * step, h),
                                        strokeWidth = 1f
                                )
                        }
                        // Draw horizontal lines
                        for (y in 0..itemCount(h, step)) {
                                drawLine(
                                        color = PoiseGridLine,
                                        start = Offset(0f, y * step),
                                        end = Offset(w, y * step),
                                        strokeWidth = 1f
                                )
                        }
                }

                // 2. Ambient Glow (Top Center)
                Box(
                        modifier =
                                Modifier.align(Alignment.TopCenter)
                                        .fillMaxWidth()
                                        .height(300.dp)
                                        .offset(y = (-50).dp)
                                        .background(
                                                brush =
                                                        Brush.verticalGradient(
                                                                colors =
                                                                        listOf(
                                                                                ambientColor,
                                                                                Color.Transparent
                                                                        )
                                                        )
                                        )
                )

                // Main Content
                // Main Content
                Column(
                        modifier =
                                Modifier.fillMaxSize()
                                        .statusBarsPadding()
                                        .navigationBarsPadding()
                                        .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                ) {

                        // 3. Header
                        Text(
                                text = "POISE",
                                fontSize = 58.sp,
                                lineHeight = 60.sp,
                                fontWeight = FontWeight.Black,
                                fontStyle = FontStyle.Italic,
                                color = headerColor,
                                modifier = Modifier
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                                Divider(
                                        color = subHeaderColor.copy(alpha = 0.3f),
                                        modifier = Modifier.width(32.dp).height(1.dp)
                                )
                                Text(
                                        text = "VOICE ISOLATOR",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 4.sp,
                                        color = subHeaderColor,
                                        modifier = Modifier.padding(horizontal = 12.dp)
                                )
                                Divider(
                                        color = subHeaderColor.copy(alpha = 0.3f),
                                        modifier = Modifier.width(32.dp).height(1.dp)
                                )
                        }

                        // Top Spacer - Bias upwards (0.1f) vs Bottom (0.9f)
                        Spacer(modifier = Modifier.weight(0.1f))

                        // 4. Main Button
                        MainButton(
                                isProcessing = isProcessing,
                                onToggle = { onToggleProcessing(!isProcessing) }
                        )

                        // Compact Spacing
                        Spacer(modifier = Modifier.height(0.dp))

                        // 5. Volume Slider
                        AudioVolumeSlider(
                                volume = outputVolume,
                                onVolumeChange = onVolumeChange,
                                accentColor = subHeaderColor
                        )

                        // Stabilized Card Section
                        // Wrapped in 0-height box so the variable card height doesn't affect
                        // the weighted spacing above (keeping Button/Slider position constant)
                        Box(modifier = Modifier.fillMaxWidth().height(0.dp)) {
                                Column(
                                        modifier =
                                                Modifier.wrapContentHeight(
                                                        align = Alignment.Top,
                                                        unbounded = true
                                                )
                                ) {
                                        // Compact Spacing (preserved)
                                        Spacer(modifier = Modifier.height(12.dp))

                                        // 6. Cards (Setup vs Active)
                                        Box(
                                                modifier =
                                                        Modifier.fillMaxWidth()
                                                                .zIndex(1f), // Natural height
                                                contentAlignment = Alignment.BottomCenter
                                        ) {
                                                if (isProcessing) {
                                                        LiveMetricsCard(
                                                                stats,
                                                                isVisible = isProcessing
                                                        )
                                                } else {
                                                        SetupGuideCard(
                                                                isVisible = !isProcessing,
                                                                onClick = { showModal = true }
                                                        )
                                                }
                                        }
                                }
                        }

                        // Bottom Spacer - Larger weight pushes content up
                        Spacer(modifier = Modifier.weight(0.9f))
                }

                // Modal overlay - must be OUTSIDE the Column to overlay entire screen
                if (showModal) {
                        SetupModal(onDismiss = { showModal = false })
                }
        }
}

// Helper to count grid steps
private fun itemCount(total: Float, step: Float): Int = (total / step).toInt() + 1

@Composable
fun MainButton(isProcessing: Boolean, onToggle: () -> Unit) {
        val infiniteTransition = rememberInfiniteTransition(label = "ripple")

        Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(190.dp) // Container matches button size
        ) {
                // Outline Ripple Effect (Active Only)
                if (isProcessing) {
                        repeat(2) { index ->
                                val scale by
                                        infiniteTransition.animateFloat(
                                                initialValue = 1f,
                                                targetValue = 1.5f,
                                                animationSpec =
                                                        infiniteRepeatable(
                                                                animation =
                                                                        tween(
                                                                                2500,
                                                                                easing =
                                                                                        LinearOutSlowInEasing
                                                                        ),
                                                                repeatMode = RepeatMode.Restart,
                                                                initialStartOffset =
                                                                        StartOffset(index * 800)
                                                        ),
                                                label = "scale"
                                        )

                                val alpha by
                                        infiniteTransition.animateFloat(
                                                initialValue = 0.5f,
                                                targetValue = 0f,
                                                animationSpec =
                                                        infiniteRepeatable(
                                                                animation =
                                                                        tween(
                                                                                2500,
                                                                                easing =
                                                                                        LinearOutSlowInEasing
                                                                        ),
                                                                repeatMode = RepeatMode.Restart,
                                                                initialStartOffset =
                                                                        StartOffset(index * 800)
                                                        ),
                                                label = "alpha"
                                        )

                                Box(
                                        modifier =
                                                Modifier.size(190.dp) // Match button size
                                                        .scale(scale)
                                                        .border(
                                                                1.dp,
                                                                if (index == 0)
                                                                        PoiseCyan.copy(
                                                                                alpha.coerceAtMost(
                                                                                        0.3f
                                                                                )
                                                                        )
                                                                else
                                                                        PoiseLime.copy(
                                                                                alpha.coerceAtMost(
                                                                                        0.2f
                                                                                )
                                                                        ),
                                                                CircleShape
                                                        )
                                )
                        }
                }

                // Button Glow
                val glowColor = if (isProcessing) PoiseLime else PoiseCyan
                Box(
                        modifier =
                                Modifier.size(140.dp)
                                        .background(
                                                brush =
                                                        Brush.radialGradient(
                                                                colors =
                                                                        listOf(
                                                                                glowColor.copy(
                                                                                        alpha = 0.2f
                                                                                ),
                                                                                Color.Transparent
                                                                        )
                                                        )
                                        )
                                        .scale(1.5f)
                )

                // The Button
                Button(
                        onClick = onToggle,
                        modifier = Modifier.size(190.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues(0.dp)
                ) {
                        Box(
                                modifier =
                                        Modifier.fillMaxSize()
                                                .background(
                                                        Brush.linearGradient(
                                                                colors =
                                                                        if (isProcessing)
                                                                                StopButtonGradient
                                                                        else StartButtonGradient,
                                                                start = Offset(0f, 0f),
                                                                end =
                                                                        Offset(
                                                                                1000f,
                                                                                1000f
                                                                        ) // Diagonal
                                                        )
                                                )
                                                .border(
                                                        1.dp,
                                                        Color.White.copy(alpha = 0.1f),
                                                        CircleShape
                                                ),
                                contentAlignment = Alignment.Center
                        ) {
                                Text(
                                        text = if (isProcessing) "STOP" else "START",
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 2.sp,
                                        color = Color.White
                                )

                                // Gloss effect (Top half)
                                Box(
                                        modifier =
                                                Modifier.fillMaxWidth()
                                                        .height(95.dp) // Half height
                                                        .align(Alignment.TopCenter)
                                                        .background(
                                                                Brush.verticalGradient(
                                                                        colors =
                                                                                listOf(
                                                                                        Color.White
                                                                                                .copy(
                                                                                                        alpha =
                                                                                                                0.1f
                                                                                                ),
                                                                                        Color.Transparent
                                                                                )
                                                                )
                                                        )
                                )
                        }
                }
        }
}

@Composable
fun SetupGuideCard(isVisible: Boolean, onClick: () -> Unit) {
        if (!isVisible) return
        Card(
                modifier = Modifier.fillMaxWidth().height(96.dp).clickable(onClick = onClick),
                shape = BeveledShape,
                colors = CardDefaults.cardColors(containerColor = SetupCardBg)
        ) {
                Row(
                        modifier =
                                Modifier.fillMaxSize()
                                        .border(
                                                width = 0.dp,
                                                color = Color.Transparent,
                                                shape = BeveledShape
                                        ) // Placeholder for shape
                                        .drawBehind {
                                                // Left Border
                                                drawRect(
                                                        color = PoiseCyan,
                                                        topLeft = Offset(0f, 0f),
                                                        size =
                                                                androidx.compose.ui.geometry.Size(
                                                                        4.dp.toPx(),
                                                                        size.height
                                                                )
                                                )
                                        }
                                        .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                ) {
                        Box(
                                modifier =
                                        Modifier.size(44.dp)
                                                .background(
                                                        PoiseCyan.copy(alpha = 0.1f),
                                                        RoundedCornerShape(4.dp)
                                                )
                                                .border(
                                                        1.dp,
                                                        PoiseCyan.copy(alpha = 0.2f),
                                                        RoundedCornerShape(4.dp)
                                                ),
                                contentAlignment = Alignment.Center
                        ) {
                                Text("⚙️", fontSize = 20.sp) // Fallback icon
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                                Text(
                                        text = "SETUP GUIDE",
                                        color = Color.White,
                                        fontWeight = FontWeight.Black,
                                        fontStyle = FontStyle.Italic,
                                        fontSize = 16.sp
                                )
                                Text(
                                        text = "Configure audio routing",
                                        color = PoiseTextSecondary,
                                        fontSize = 12.sp
                                )
                        }
                        Box(
                                modifier =
                                        Modifier.size(32.dp)
                                                .background(
                                                        Color.White.copy(alpha = 0.05f),
                                                        CircleShape
                                                ),
                                contentAlignment = Alignment.Center
                        ) { Text("→", color = PoiseTextSecondary) }
                }
        }
}

@Composable
fun LiveMetricsCard(stats: ProcessingStats?, isVisible: Boolean) {
        if (!isVisible) return

        // Ping Animation
        val infiniteTransition = rememberInfiniteTransition(label = "ping")
        val pingAlpha by
                infiniteTransition.animateFloat(
                        initialValue = 0.75f,
                        targetValue = 0f,
                        animationSpec =
                                infiniteRepeatable(
                                        animation = tween(1000),
                                        repeatMode = RepeatMode.Restart
                                ),
                        label = "pingAlpha"
                )
        val pingScale by
                infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.5f,
                        animationSpec =
                                infiniteRepeatable(
                                        animation = tween(1000),
                                        repeatMode = RepeatMode.Restart
                                ),
                        label = "pingScale"
                )

        // Determine health status for metric coloring
        val rtfThreshold = 1.0f
        val latencyThreshold = 100.0 // Double to match avgTimeMs type
        val currentRtf = stats?.rtf ?: 0f
        val currentLatency = stats?.avgTimeMs ?: 0.0
        val isHealthy = currentRtf < rtfThreshold && currentLatency < latencyThreshold

        val isVadActive = stats?.isVadDetected == true

        // Dim colors if VAD is idle
        val metricColor: Color =
                if (isVadActive) {
                        if (isHealthy) PoiseLime else PoiseOrange
                } else {
                        Color.White.copy(alpha = 0.3f)
                }

        Card(
                modifier = Modifier.fillMaxWidth(), // Height determined by content
                shape = BeveledShape,
                colors = CardDefaults.cardColors(containerColor = SetupCardBg)
        ) {
                Column(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .drawBehind {
                                                // Left Border Lime
                                                drawRect(
                                                        color = PoiseLime,
                                                        topLeft = Offset(0f, 0f),
                                                        size =
                                                                androidx.compose.ui.geometry.Size(
                                                                        4.dp.toPx(),
                                                                        size.height
                                                                )
                                                )
                                        }
                                        .padding(24.dp)
                ) {
                        // Header
                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                                Text(
                                        text = "LIVE METRICS",
                                        color = Color.White.copy(alpha = 0.5f), // Slate-400 equiv
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 1.sp
                                )

                                // Active Badge
                                Row(
                                        modifier =
                                                Modifier.background(PoiseLime.copy(alpha = 0.1f))
                                                        .border(
                                                                1.dp,
                                                                PoiseLime,
                                                                TransformOrigin(0f, 0f).let {
                                                                        androidx.compose.ui.graphics
                                                                                .RectangleShape
                                                                }
                                                        ) // Simplified shape
                                                        .padding(
                                                                horizontal = 8.dp,
                                                                vertical = 4.dp
                                                        ),
                                        verticalAlignment = Alignment.CenterVertically
                                ) {
                                        Box(
                                                modifier = Modifier.size(8.dp),
                                                contentAlignment = Alignment.Center
                                        ) {
                                                Box(
                                                        modifier =
                                                                Modifier.size(8.dp)
                                                                        .scale(pingScale)
                                                                        .background(
                                                                                PoiseLime.copy(
                                                                                        alpha =
                                                                                                if (stats?.isVadDetected ==
                                                                                                                true
                                                                                                )
                                                                                                        pingAlpha
                                                                                                else
                                                                                                        0f
                                                                                ),
                                                                                CircleShape
                                                                        )
                                                )
                                                Box(
                                                        modifier =
                                                                Modifier.size(6.dp)
                                                                        .background(
                                                                                if (stats?.isVadDetected ==
                                                                                                true
                                                                                )
                                                                                        PoiseLime
                                                                                else PoiseOrange,
                                                                                CircleShape
                                                                        )
                                                )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                                text =
                                                        if (stats?.isVadDetected == true) "ACTIVE"
                                                        else "IDLE",
                                                color =
                                                        if (stats?.isVadDetected == true) PoiseLime
                                                        else PoiseOrange,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Black,
                                                fontStyle = FontStyle.Italic
                                        )
                                }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Metrics Row
                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                                // RTF
                                MetricColumn("RTF") {
                                        Text(
                                                text = stats?.let { String.format("%.2f", it.rtf) }
                                                                ?: "1.08",
                                                fontSize = 32.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = metricColor
                                        )
                                }

                                Divider(
                                        color = Color.White.copy(alpha = 0.05f),
                                        modifier = Modifier.height(40.dp).width(1.dp)
                                )

                                // LATENCY
                                MetricColumn("LATENCY (MS)") {
                                        Text(
                                                text =
                                                        stats?.let {
                                                                String.format("%.1f", it.avgTimeMs)
                                                        }
                                                                ?: "17.3",
                                                fontSize = 32.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = metricColor
                                        )
                                }

                                Divider(
                                        color = Color.White.copy(alpha = 0.05f),
                                        modifier = Modifier.height(40.dp).width(1.dp)
                                )

                                // STATUS - shows service running state
                                MetricColumn("STATUS") {
                                        val isActive = stats != null
                                        Text(
                                                text = if (isActive) "ON" else "OFF",
                                                fontSize = 32.sp,
                                                fontWeight = FontWeight.Bold,
                                                color =
                                                        if (isActive) PoiseLime
                                                        else Color.White.copy(alpha = 0.5f)
                                        )
                                }
                        }
                }
        }
}

@Composable
fun MetricColumn(label: String, content: @Composable () -> Unit) {
        Column {
                content()
                Text(
                        text = label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.5f), // Slate-500 equiv
                        letterSpacing = 1.sp
                )
        }
}

@Composable
fun SetupModal(onDismiss: () -> Unit) {
        Box(
                modifier =
                        Modifier.fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.8f)) // Dimmed backdrop
                                .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                ) {},
                contentAlignment = Alignment.Center
        ) {
                Card(
                        modifier =
                                Modifier.fillMaxWidth(0.9f).wrapContentHeight().drawWithCache {
                                        onDrawWithContent {
                                                drawContent()
                                                val w = size.width
                                                val h = size.height
                                                val stroke = 1.dp.toPx()

                                                // Top (Straight)
                                                drawLine(
                                                        PoiseCyan,
                                                        Offset(0f, 0f),
                                                        Offset(w * 0.92f, 0f),
                                                        stroke
                                                )
                                                // Right (Straight)
                                                drawLine(
                                                        PoiseCyan,
                                                        Offset(w, h * 0.15f),
                                                        Offset(w, h),
                                                        stroke
                                                )
                                                // Bottom (Straight)
                                                drawLine(
                                                        PoiseCyan,
                                                        Offset(w, h),
                                                        Offset(w * 0.08f, h),
                                                        stroke
                                                )
                                                // Left (Straight)
                                                drawLine(
                                                        PoiseCyan,
                                                        Offset(0f, h * 0.85f),
                                                        Offset(0f, 0f),
                                                        stroke
                                                )
                                        }
                                }, // Partial borders
                        shape = BeveledShape,
                        colors =
                                CardDefaults.cardColors(
                                        containerColor = Color(0xFF030508) // Deep dark background
                                )
                ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                                Text(
                                        text = "SETUP GUIDE",
                                        color = Color.White,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Black,
                                        fontStyle =
                                                FontStyle.Italic, // Kept italic based on logo, but
                                        // removed rotation if any
                                        letterSpacing = 1.sp
                                )

                                Spacer(modifier = Modifier.height(32.dp))

                                SetupStep(
                                        "1",
                                        "SEPARATE APP SOUND",
                                        "Go to Settings → Sounds → Separate App Sound. Enable it for the app you want to denoise and set the audio device to 'Phone'."
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                SetupStep(
                                        "2",
                                        "AUDIO ROUTING",
                                        "Mute your phone speaker completely. Only increase the volume on your connected headphones/earbuds."
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                SetupStep(
                                        "3",
                                        "START POISE",
                                        "Press the START button below. Poise will capture the routed audio and output clean, denoised sound to your headphones."
                                )

                                Spacer(modifier = Modifier.height(32.dp))

                                Button(
                                        onClick = onDismiss,
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        colors =
                                                ButtonDefaults.buttonColors(
                                                        containerColor = PoiseCyan
                                                ),
                                        shape = RectangleShape // Sharp edges
                                ) {
                                        Text(
                                                "GOT IT",
                                                color = Color.Black,
                                                fontWeight = FontWeight.Black,
                                                fontStyle = FontStyle.Italic,
                                                fontSize = 18.sp,
                                                letterSpacing = 1.sp
                                        )
                                }
                        }
                }
        }
}

@Composable
fun SetupStep(number: String, title: String, description: String) {
        Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                        modifier =
                                Modifier.size(24.dp)
                                        .background(PoiseCyan.copy(alpha = 0.1f), CircleShape)
                                        .border(1.dp, PoiseCyan.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                ) {
                        Text(
                                number,
                                color = PoiseCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                        )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                        Text(
                                title,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontStyle = FontStyle.Italic
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(description, color = Color.Gray, fontSize = 11.sp, lineHeight = 14.sp)
                }
        }
}

@Composable
fun AudioVolumeSlider(volume: Float, onVolumeChange: (Float) -> Unit, accentColor: Color) {
        val percentValue = (volume * 100).toInt()

        Column(modifier = Modifier.fillMaxWidth()) {
                // Header row: Labels + Percentage
                // Header row: Labels + Percentage
                Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                ) {
                        Column(
                                modifier =
                                        Modifier.drawBehind {
                                                        // Left accent bar with bevel
                                                        val w = 4.dp.toPx()
                                                        val h = size.height
                                                        val path =
                                                                androidx.compose.ui.graphics.Path()
                                                                        .apply {
                                                                                moveTo(0f, 0f)
                                                                                lineTo(w, 0f)
                                                                                lineTo(w, h)
                                                                                lineTo(
                                                                                        0f,
                                                                                        h - w
                                                                                ) // Bevel
                                                                                // bottom-left
                                                                                close()
                                                                        }
                                                        drawPath(path, color = accentColor)
                                                }
                                                .padding(start = 16.dp)
                        ) {
                                Text(
                                        text = "OUTPUT",
                                        color = Color.White.copy(alpha = 0.5f),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        letterSpacing = 1.sp
                                )
                                Text(
                                        text = "AUDIO VOLUME",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Black,
                                        fontStyle = FontStyle.Italic
                                )
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                        text = "$percentValue",
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier =
                                                Modifier.gradientText(
                                                        Brush.verticalGradient(
                                                                listOf(
                                                                        accentColor,
                                                                        accentColor.copy(
                                                                                alpha = 0.8f
                                                                        )
                                                                )
                                                        )
                                                )
                                )
                                Text(
                                        text = " %",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White.copy(alpha = 0.6f),
                                        modifier = Modifier.padding(bottom = 4.dp)
                                )
                        }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Slider
                Slider(
                        value = volume,
                        onValueChange = onVolumeChange,
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                        colors =
                                SliderDefaults.colors(
                                        thumbColor = accentColor,
                                        activeTrackColor = accentColor.copy(alpha = 0.6f),
                                        inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                                )
                )
        }
}
