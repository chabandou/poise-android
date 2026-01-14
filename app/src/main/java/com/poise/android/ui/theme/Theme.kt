package com.poise.android.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

// Dark theme is the only theme for Poise branding
// PoiseColorScheme is defined in Color.kt

@Composable
fun PoiseAndroidTheme(
        darkTheme: Boolean = true, // Force dark theme for Poise aesthetic
        dynamicColor: Boolean = false, // Disable dynamic color for consistent branding
        content: @Composable () -> Unit
) {
        val colorScheme = PoiseColorScheme

        MaterialTheme(colorScheme = colorScheme, content = content)
}
