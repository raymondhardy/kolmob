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

    /** Text on a colored button (green, orange, red): white in both themes. */
    const val onAccent = 0xFFFFFFFF.toInt()
}
