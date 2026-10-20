package com.example.kolmafiaandroid.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.Layout
import android.text.TextPaint
import android.util.AttributeSet
import android.widget.TextView

/**
 * A TextView that shows line numbers in a gutter on the left side.
 * Designed to be placed next to an EditText with the same line height and padding.
 */
class LineNumberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = 14f * resources.displayMetrics.scaledDensity
        color = KC.hint
        typeface = android.graphics.Typeface.MONOSPACE
    }

    private var targetEditText: android.widget.EditText? = null
    private val rect = Rect()
    private var lineCount = 0

    fun setTargetEditText(editText: android.widget.EditText) {
        targetEditText = editText
        // Match padding and text size
        setPadding(0, editText.paddingTop, (16 * resources.displayMetrics.density).toInt(), editText.paddingBottom)
        paint.textSize = editText.textSize
        requestLayout()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val editText = targetEditText ?: return

        val layout = editText.layout ?: return
        val lineCount = layout.lineCount
        this.lineCount = lineCount

        val firstLine = layout.getLineForVertical(editText.scrollY)
        val lastLine = layout.getLineForVertical(editText.scrollY + height)

        // Calculate the width needed for line numbers
        val maxLineNum = lineCount.toString().length
        val charWidth = paint.measureText("9")
        val neededWidth = (maxLineNum * charWidth + 24 * resources.displayMetrics.density).toInt()
        if (width != neededWidth) {
            layoutParams.width = neededWidth
            requestLayout()
        }

        // Draw line numbers
        for (i in firstLine..lastLine) {
            val baseline = layout.getLineBaseline(i)
            val y = baseline + editText.paddingTop - editText.scrollY
            val lineNum = (i + 1).toString()
            canvas.drawText(lineNum, width - (12 * resources.displayMetrics.density).toInt().toFloat(), y.toFloat(), paint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val editText = targetEditText
        if (editText != null) {
            val maxLineNum = editText.lineCount.toString().length
            val charWidth = paint.measureText("9")
            val neededWidth = (maxLineNum * charWidth + 24 * resources.displayMetrics.density).toInt()
            val widthSpec = MeasureSpec.makeMeasureSpec(neededWidth, MeasureSpec.EXACTLY)
            super.onMeasure(widthSpec, heightMeasureSpec)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}