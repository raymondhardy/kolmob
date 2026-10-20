package com.example.kolmafiaandroid.ui

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * ASH syntax highlighter for the script editor.
 * Applies colors to keywords, comments, strings, and numbers.
 */
class AshSyntaxHighlighter {

    private val keywords = setOf(
        "if", "else", "while", "for", "foreach", "from", "to", "upto", "downto",
        "repeat", "until", "switch", "case", "default", "return", "break", "continue",
        "import", "record", "typedef", "static", "void", "int", "float", "string", "boolean",
        "item", "monster", "location", "effect", "skill", "familiar", "class", "stat",
        "phylum", "element", "slot", "bounty", "coinmaster", "thrall", "servant", "vykea",
        "path", "buffer", "matcher"
    )

    private val keywordPattern: Pattern = Pattern.compile("\\b(" + keywords.joinToString("|") + ")\\b")
    private val commentPattern: Pattern = Pattern.compile("//.*|/\\*[\\s\\S]*?\\*/")
    private val stringPattern: Pattern = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'")
    private val numberPattern: Pattern = Pattern.compile("\\b\\d+(\\.\\d+)?\\b")

    private val keywordColor = ForegroundColorSpan(KC.pick(0xFF64B5F6.toInt(), 0xFF1565C0.toInt()))  // Light blue
    private val commentColor = ForegroundColorSpan(KC.pick(0xFF9E9E9E.toInt(), 0xFF6B6B6B.toInt()))  // Gray
    private val stringColor = ForegroundColorSpan(KC.pick(0xFFE57373.toInt(), 0xFFC62828.toInt()))   // Light red
    private val numberColor = ForegroundColorSpan(KC.pick(0xFFFFB74D.toInt(), 0xFFE65100.toInt()))   // Orange

    // Our own span types, so we only ever remove what we added (never the user's selection/IME spans).
    private class HlColor(color: Int) : ForegroundColorSpan(color)
    private class HlBold : StyleSpan(Typeface.BOLD)

    private var busy = false

    /**
     * Colors [text] in place. Only spans are added/removed: changing the characters themselves would fire the
     * editor's TextWatcher again (infinite recursion) and would move the cursor.
     */
    fun highlight(text: Editable) {
        if (busy) return
        busy = true
        try {
            for (s in text.getSpans(0, text.length, HlColor::class.java)) text.removeSpan(s)
            for (s in text.getSpans(0, text.length, HlBold::class.java)) text.removeSpan(s)
            if (text.length > 300_000) return // very large files stay plain so typing stays smooth

            // later passes win where ranges overlap: comments > strings > numbers > keywords
            paint(text, keywordPattern, KC.pick(0xFF64B5F6.toInt(), 0xFF1565C0.toInt()), bold = true)
            paint(text, numberPattern, KC.pick(0xFFFFB74D.toInt(), 0xFFE65100.toInt()))
            paint(text, stringPattern, KC.pick(0xFFE57373.toInt(), 0xFFC62828.toInt()))
            paint(text, commentPattern, KC.pick(0xFF9E9E9E.toInt(), 0xFF6B6B6B.toInt()))
        } finally {
            busy = false
        }
    }

    private fun paint(text: Editable, pattern: Pattern, color: Int, bold: Boolean = false) {
        val matcher = pattern.matcher(text)
        while (matcher.find()) {
            text.setSpan(HlColor(color), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) text.setSpan(HlBold(), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}

class DebouncedSyntaxHighlighter(
    private val highlighter: AshSyntaxHighlighter,
    private val delayMs: Long = 300
) : TextWatcher {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRunnable: Runnable? = null

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

    override fun afterTextChanged(s: Editable?) {
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = Runnable { s?.let { highlighter.highlight(it) } }
        pendingRunnable?.let { handler.postDelayed(it, delayMs) }
    }
}