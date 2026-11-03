package com.example.kolmafiaandroid.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import java.util.Locale

/** A small coloured file-type badge (the extension in a rounded tile): ASH, JS, TXT, JSON… */
class ExtBadge(ext: String) : Drawable() {
    private val label = ext.uppercase(Locale.ROOT).take(4)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    init {
        val (bg, fg) = when (ext.lowercase(Locale.ROOT)) {
            "ash" -> 0xFF2E7D32.toInt() to 0xFFFFFFFF.toInt()
            "js" -> 0xFFF7DF1E.toInt() to 0xFF1A1A1A.toInt()
            "ts" -> 0xFF3178C6.toInt() to 0xFFFFFFFF.toInt()
            "ccs", "mood" -> 0xFFEF6C00.toInt() to 0xFFFFFFFF.toInt()
            "json", "xml", "yml", "yaml" -> 0xFF8E24AA.toInt() to 0xFFFFFFFF.toInt()
            "md", "txt" -> 0xFF607D8B.toInt() to 0xFFFFFFFF.toInt()
            "html", "htm", "css" -> 0xFFE64A19.toInt() to 0xFFFFFFFF.toInt()
            else -> 0xFF546E7A.toInt() to 0xFFFFFFFF.toInt()
        }
        fill.color = bg
        text.color = fg
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        rect.set(b)
        val r = b.height() * 0.22f
        canvas.drawRoundRect(rect, r, r, fill)
        // fit the label inside the tile
        var size = b.height() * 0.5f
        text.textSize = size
        val maxW = b.width() * 0.82f
        val w = text.measureText(label)
        if (w > maxW) { size *= maxW / w; text.textSize = size }
        val y = b.exactCenterY() - (text.descent() + text.ascent()) / 2f
        canvas.drawText(label, b.exactCenterX(), y, text)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; text.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = -1
    override fun getIntrinsicHeight() = -1
}
