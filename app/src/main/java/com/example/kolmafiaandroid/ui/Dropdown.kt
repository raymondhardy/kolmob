package com.example.kolmafiaandroid.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.kolmafiaandroid.R

/**
 * The app's one drop-down: an outlined field showing the current choice that opens the shared picker sheet.
 * Use it instead of Spinner. [onChange] fires only when the person picks something.
 */
// A plain TextView on purpose: the app styles it itself and does not use AppCompat tinting
@SuppressLint("AppCompatCustomView")
class Dropdown(
    context: Context,
    private val title: String,
    options: List<String>,
    selected: Int = 0,
    private val onChange: (Int) -> Unit = {},
) : TextView(context) {
    var options: List<String> = options
        set(v) { field = v; selectedIndex = selectedIndex.coerceIn(0, maxOf(0, v.size - 1)); refresh() }
    var selectedIndex: Int = selected.coerceIn(0, maxOf(0, options.size - 1))
        set(v) { field = v; refresh() }

    init {
        textSize = 15f
        setTextColor(KC.text)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(context, 48)
        setPadding(dp(context, 14), dp(context, 8), dp(context, 10), dp(context, 8))
        background = GradientDrawable().apply { cornerRadius = dp(context, 8).toFloat(); setStroke(dp(context, 1), KC.faint) }
        ContextCompat.getDrawable(context, R.drawable.ic_s_chevron_down)?.mutate()?.let {
            it.setTint(KC.muted)
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, it, null)
        }
        isClickable = true
        setOnClickListener {
            if (this.options.isNotEmpty()) context.pick(title, this.options, selected = selectedIndex) { i -> selectedIndex = i; onChange(i) }
        }
        refresh()
    }

    private fun refresh() { text = options.getOrNull(selectedIndex) ?: "" }
}
