package com.poise.android.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

// Always dark, in one of the desktop app's palettes
@Composable
fun PoiseAndroidTheme(palette: Palette, content: @Composable () -> Unit) {
    val colorScheme =
            darkColorScheme(
                    primary = palette.accent,
                    background = palette.bg,
                    surface = palette.panel,
                    onBackground = palette.ink,
                    onSurface = palette.ink
            )
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

/**
 * Runs [change] (a theme switch) under a snapshot of the screen as it was, which then fades out,
 * so the new colours dissolve in. The app recomposes once, not on every frame of a glide, and
 * the fade is only a layer's alpha, which the GPU animates. The snapshot is copied from the
 * window's last frame (PixelCopy) rather than drawn again in software.
 */
fun View.crossfade(durationMs: Long, change: () -> Unit) {
    val root = rootView as? ViewGroup
    val window = context.activity()?.window
    if (root == null || window == null || root.width == 0) return change()
    val shot = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
    PixelCopy.request(window, shot, { result ->
        if (result != PixelCopy.SUCCESS || !root.isAttachedToWindow) {
            shot.recycle()
            return@request change()
        }
        val cover = ImageView(context).apply { setImageBitmap(shot) }
        root.addView(cover, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        change()
        cover.animate().alpha(0f).setDuration(durationMs).withLayer().withEndAction {
            root.removeView(cover)
            shot.recycle()
        }
    }, Handler(Looper.getMainLooper()))
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
