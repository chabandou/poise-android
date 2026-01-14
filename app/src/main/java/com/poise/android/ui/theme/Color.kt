package com.poise.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// Premium Dark Theme Colors
val PoiseDarkBackground = Color(0xFF0A0A0C)
val PoiseGridLine = Color(0xFFFFFFFF).copy(alpha = 0.05f)

// Text
val PoiseTextPrimary = Color.White
val PoiseTextSecondary = Color(0xFF94A3B8) // slate-400

// Brand Colors
val PoiseCyan = Color(0xFF22D3EE) // cyan-400
val PoiseLime = Color(0xFFB6FF00) // #b6ff00 (Lime)
val PoiseEmerald = Color(0xFF10B981) // emerald-500
val PoiseOrange = Color(0xFFFB923C) // orange-400

// Gradients
val StartButtonGradient =
        listOf(
                Color(0xFF22D3EE), // cyan-400
                Color(0xFF0891B2) // cyan-600
        )

val StopButtonGradient =
        listOf(
                Color(0xFFB6FF00), // lime
                Color(0xFF10B981) // emerald-500
        )

val MetricValueGradient =
        listOf(
                Color(0xFFFB923C), // orange-400
                Color(0xFFEA580C) // orange-600
        )

// UI Elements
val SetupCardBg = Color(0xFF171717).copy(alpha = 0.8f) // neutral-900/80
val MetricsCardBg = Color(0xFF0F172A).copy(alpha = 0.9f) // slate-900/90

val PoiseColorScheme =
        darkColorScheme(
                primary = PoiseCyan,
                background = PoiseDarkBackground,
                surface = PoiseDarkBackground,
                onPrimary = Color.Black,
                onBackground = PoiseTextPrimary,
                onSurface = PoiseTextPrimary
        )
