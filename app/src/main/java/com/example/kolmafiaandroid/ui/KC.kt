package com.example.kolmafiaandroid.ui

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/**
 * The app theme: "system" (follow the phone), "dark" or "light". Resource colors switch through values-night;
 * colors built in code use these so everything flips together. Call [refresh] before building views.
 */
object KC {
    @JvmStatic var dark = true

    fun mode(ctx: Context): String = ctx.getSharedPreferences("kolmob", Context.MODE_PRIVATE).getString("theme_mode", "system") ?: "system"

    /** Tells AppCompat which night mode to use and works out [dark] for colors built in code. */
    fun apply(ctx: Context) {
        AppCompatDelegate.setDefaultNightMode(when (mode(ctx)) {
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
        refresh(ctx)
    }

    fun refresh(ctx: Context) {
        dark = when (mode(ctx)) {
            "dark" -> true
            "light" -> false
            else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    /** A color for dark mode and a different one for light mode. */
    fun pick(darkColor: Int, lightColor: Int) = if (dark) darkColor else lightColor

    /** Text and icons on the page background. */
    val text get() = if (dark) 0xFFFFFFFF.toInt() else 0xFF1B1B1B.toInt()
    val soft get() = if (dark) 0xFFE0E0E0.toInt() else 0xFF2E2E2E.toInt()
    val muted get() = if (dark) 0xB3FFFFFF.toInt() else 0xB31B1B1B.toInt()
    val hint get() = if (dark) 0x88FFFFFF.toInt() else 0x881B1B1B.toInt()
    val faint get() = if (dark) 0x33FFFFFF.toInt() else 0x331B1B1B.toInt()

    /** The app's green, for headings, links and selected states, and its translucent fills and outlines. */
    val accent get() = if (dark) 0xFF4CAF50.toInt() else 0xFF2E7D32.toInt()
    val accentSoft get() = if (dark) 0x334CAF50 else 0x262E7D32
    val accentLine get() = if (dark) 0x664CAF50 else 0x662E7D32
    /** Good / bad / caution / link colors that stay readable on both backgrounds (for text and icons). */
    val good get() = accent
    val bad get() = if (dark) 0xFFEF5350.toInt() else 0xFFC62828.toInt()
    val warn get() = if (dark) 0xFFFFB74D.toInt() else 0xFFE65100.toInt()
    val link get() = if (dark) 0xFF42A5F5.toInt() else 0xFF1565C0.toInt()

    /** Fills that carry white text or sit behind bars; the same in both themes. */
    const val danger = 0xFFE53935.toInt()
    const val info = 0xFF1E88E5.toInt()
    /** The green title bar of full-screen pages. */
    const val bar = 0xFF2E7D32.toInt()

    /** Surfaces: the page behind everything, raised tiles and bubbles, and small chips and pills. */
    val page get() = if (dark) 0xFF121212.toInt() else 0xFFF5F5F5.toInt()
    val surface get() = if (dark) 0xFF2A2A2A.toInt() else 0xFFFFFFFF.toInt()
    val chip get() = if (dark) 0xFF202020.toInt() else 0xFFE6E6E6.toInt()
    val dim get() = if (dark) 0xFFB0B0B0.toInt() else 0xFF666666.toInt()

    /** Text on a colored button (green, orange, red): white in both themes. */
    const val onAccent = 0xFFFFFFFF.toInt()
}
