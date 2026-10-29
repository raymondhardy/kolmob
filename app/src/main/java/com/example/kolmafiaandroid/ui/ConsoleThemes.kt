package com.example.kolmafiaandroid.ui

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan

/**
 * Colour profiles for the script runner's output, named after common terminal profiles. KoLmafia colours some lines itself
 * (errors red, headings blue ...); a profile re-maps those to its own red / orange / yellow / green / cyan / blue / magenta so
 * they stay readable on its background. "Default" leaves them as KoLmafia sent them.
 */
object ConsoleThemes {
    class Theme(val name: String, val bg: Int, val fg: Int, val dim: Int, val accents: IntArray?)

    private fun t(name: String, bg: Long, fg: Long, dim: Long, vararg a: Long) =
        Theme(name, bg.toInt() or 0xFF000000.toInt(), fg.toInt() or 0xFF000000.toInt(), dim.toInt() or 0xFF000000.toInt(),
            if (a.isEmpty()) null else IntArray(a.size) { a[it].toInt() or 0xFF000000.toInt() })

    // accents: red, orange, yellow, green, cyan, blue, magenta
    val all: List<Theme> = listOf(
        t("Default", 0x121212, 0xE0E0E0, 0x9E9E9E),
        t("Classic green", 0x000000, 0x33FF33, 0x1A9A1A, 0xFF5555, 0xFFB000, 0xFFFF55, 0x33FF33, 0x55FFFF, 0x5599FF, 0xFF55FF),
        t("Amber", 0x1A0F00, 0xFFB000, 0x9A6A00, 0xFF6040, 0xFF9020, 0xFFD060, 0xC8D040, 0xFFE0A0, 0xE0A040, 0xFF8060),
        t("Solarized Dark", 0x002B36, 0x839496, 0x586E75, 0xDC322F, 0xCB4B16, 0xB58900, 0x859900, 0x2AA198, 0x268BD2, 0xD33682),
        t("Solarized Light", 0xFDF6E3, 0x657B83, 0x93A1A1, 0xDC322F, 0xCB4B16, 0xB58900, 0x859900, 0x2AA198, 0x268BD2, 0xD33682),
        t("Dracula", 0x282A36, 0xF8F8F2, 0x6272A4, 0xFF5555, 0xFFB86C, 0xF1FA8C, 0x50FA7B, 0x8BE9FD, 0xBD93F9, 0xFF79C6),
        t("Monokai", 0x272822, 0xF8F8F2, 0x75715E, 0xF92672, 0xFD971F, 0xE6DB74, 0xA6E22E, 0x66D9EF, 0xAE81FF, 0xF92672),
        t("Nord", 0x2E3440, 0xD8DEE9, 0x616E88, 0xBF616A, 0xD08770, 0xEBCB8B, 0xA3BE8C, 0x88C0D0, 0x81A1C1, 0xB48EAD),
        t("Gruvbox Dark", 0x282828, 0xEBDBB2, 0x928374, 0xFB4934, 0xFE8019, 0xFABD2F, 0xB8BB26, 0x8EC07C, 0x83A598, 0xD3869B),
        t("One Dark", 0x282C34, 0xABB2BF, 0x5C6370, 0xE06C75, 0xD19A66, 0xE5C07B, 0x98C379, 0x56B6C2, 0x61AFEF, 0xC678DD),
        t("Tango", 0x2E3436, 0xD3D7CF, 0x888A85, 0xEF2929, 0xFCAF3E, 0xFCE94F, 0x8AE234, 0x34E2E2, 0x729FCF, 0xAD7FA8),
        t("Campbell (Windows)", 0x0C0C0C, 0xCCCCCC, 0x767676, 0xE74856, 0xC19C00, 0xF9F1A5, 0x16C60C, 0x61D6D6, 0x3B78FF, 0xB4009E),
        t("Graphite", 0x1E1E1E, 0xD4D4D4, 0x808080, 0xF44747, 0xCE9178, 0xDCDCAA, 0xB5CEA8, 0x4EC9B0, 0x569CD6, 0xC586C0),
        t("Light", 0xFFFFFF, 0x1B1B1B, 0x666666, 0xC62828, 0xE65100, 0x9A7D00, 0x2E7D32, 0x00838F, 0x1565C0, 0x8E24AA),
    )

    fun byName(name: String?): Theme = all.firstOrNull { it.name == name } ?: all[0]

    /** The profile colour that stands in for [orig]: by hue for colours, fg / dim for white-ish and grey-black-ish text. */
    fun map(theme: Theme, orig: Int): Int {
        val acc = theme.accents ?: return orig
        val hsv = FloatArray(3)
        Color.colorToHSV(orig, hsv)
        if (hsv[1] < 0.2f || hsv[2] < 0.15f) return if (hsv[2] > 0.6f) theme.fg else theme.dim
        val h = hsv[0]
        val i = when {
            h < 15f || h >= 345f -> 0
            h < 40f -> 1
            h < 70f -> 2
            h < 165f -> 3
            h < 200f -> 4
            h < 260f -> 5
            else -> 6
        }
        return acc[i]
    }

    /** Remembers the colour KoLmafia asked for, so switching profile later can map it again from the original. */
    class ThemedSpan(val orig: Int, color: Int) : ForegroundColorSpan(color)

    /** A copy of [text] whose colours are mapped through [theme]. */
    fun themed(text: CharSequence, theme: Theme): CharSequence {
        val sb = SpannableStringBuilder(text)
        retheme(sb, theme)
        return sb
    }

    /** Re-maps every colour span in [sp] (also text that is already on screen) to [theme]. */
    fun retheme(sp: Spannable, theme: Theme) {
        for (s in sp.getSpans(0, sp.length, ForegroundColorSpan::class.java)) {
            val start = sp.getSpanStart(s)
            val end = sp.getSpanEnd(s)
            val flags = sp.getSpanFlags(s)
            val orig = if (s is ThemedSpan) s.orig else s.foregroundColor
            sp.removeSpan(s)
            sp.setSpan(ThemedSpan(orig, map(theme, orig)), start, end, flags)
        }
    }
}
