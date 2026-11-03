package com.example.kolmafiaandroid.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.kolmafiaandroid.R

fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

fun label(context: Context, text: String, size: Float = 15f, color: Int = KC.soft, bold: Boolean = false): TextView =
    TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

/** Fragment shorthands, so screens do not each carry their own copies. */
fun Fragment.dp(v: Int): Int = dp(requireContext(), v)
fun Fragment.label(text: String, size: Float = 15f, color: Int = KC.soft, bold: Boolean = false): TextView = label(requireContext(), text, size, color, bold)
fun Fragment.card(parent: LinearLayout, title: String? = null): LinearLayout = card(requireContext(), parent, title)

/** The big green title at the top of a full-screen page. */
fun screenTitle(context: Context, text: String): TextView = label(context, text, 20f, KC.accent, true)

/**
 * The icon at the start of a file-manager style row: "folder", "project", "file", "ext:ash" (a coloured badge for that
 * file type) or any other text, which is shown as a plain glyph.
 */
fun rowIcon(context: Context, kind: String, tint: Int = KC.muted): View {
    fun image(res: Int, color: Int) = android.widget.ImageView(context).apply { setImageResource(res); setColorFilter(color) }
    return when {
        kind == "folder" -> image(R.drawable.ic_s_folder, KC.accent).also { it.layoutParams = LinearLayout.LayoutParams(dp(context, 26), dp(context, 26)).apply { marginEnd = dp(context, 6) } }
        kind == "project" -> image(R.drawable.ic_s_project, tint).also { it.layoutParams = LinearLayout.LayoutParams(dp(context, 26), dp(context, 26)).apply { marginEnd = dp(context, 6) } }
        kind == "file" -> image(R.drawable.ic_s_file, tint).also { it.layoutParams = LinearLayout.LayoutParams(dp(context, 26), dp(context, 26)).apply { marginEnd = dp(context, 6) } }
        kind.startsWith("ext:") -> android.widget.ImageView(context).apply {
            setImageDrawable(ExtBadge(kind.removePrefix("ext:")))
            layoutParams = LinearLayout.LayoutParams(dp(context, 30), dp(context, 24)).apply { marginStart = dp(context, -1); marginEnd = dp(context, 5) }
        }
        else -> label(context, kind, 20f, tint).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(context, 28), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
}

/** One tappable list row: an optional icon, a title with a small detail line under it, tap and long-press. */
fun listRow(
    context: Context,
    title: String,
    detail: String = "",
    icon: View? = null,
    bold: Boolean = false,
    titleColor: Int = KC.text,
    onTap: (() -> Unit)? = null,
    onLong: (() -> Unit)? = null,
): LinearLayout {
    val r = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 12), dp(context, 11), dp(context, 12), dp(context, 11))
    }
    if (icon != null) r.addView(icon)
    val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    texts.addView(label(context, title, 16f, titleColor, bold))
    if (detail.isNotEmpty()) texts.addView(label(context, detail, 12f, KC.muted))
    r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    if (onTap != null) clickable(r, onTap)
    if (onLong != null) r.setOnLongClickListener { onLong(); true }
    return r
}

/** A small green uppercase heading that starts a group of rows. */
fun sectionLabel(context: Context, text: String, top: Int = 16): TextView =
    label(context, text.uppercase(), 12f, KC.accent, true).apply {
        letterSpacing = 0.1f
        setPadding(dp(context, 4), dp(context, top), 0, dp(context, 4))
    }

fun card(context: Context, parent: LinearLayout, title: String? = null): LinearLayout {
    val c = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.bg_card)
        setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(context, 12) }
    }
    if (title != null) c.addView(label(context, title.uppercase(), 12f, KC.accent, true).apply {
        letterSpacing = 0.1f
        setPadding(0, 0, 0, dp(context, 8))
    })
    parent.addView(c)
    return c
}

fun clickable(view: View, onClick: () -> Unit): View {
    val tv = TypedValue()
    view.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
    view.isClickable = true
    view.isFocusable = true
    view.foreground = ContextCompat.getDrawable(view.context, tv.resourceId)
    view.setOnClickListener { onClick() }
    return view
}

fun bar(
    context: Context,
    labelText: String,
    cur: Long,
    max: Long,
    color: Int,
    hint: String? = null,
    onClick: (() -> Unit)? = null
): View {
    val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(context, 4), 0, dp(context, 4))
    }
    val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    head.addView(label(context, labelText + (if (hint != null) "  · $hint" else ""), 13f, KC.muted),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    head.addView(label(context, "$cur / $max", 13f, KC.text, true))
    box.addView(head)
    box.addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        this.max = if (max > 0) max.toInt() else 1
        progress = cur.toInt().coerceIn(0, this.max)
        progressTintList = android.content.res.ColorStateList.valueOf(color)
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(KC.faint)
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 10)).apply { topMargin = dp(context, 4) })
    if (onClick != null) clickable(box, onClick)
    return box
}

fun tile(context: Context, title: String, value: String, onClick: (() -> Unit)? = null): View {
    val t = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.bg_tile)
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
    }
    t.addView(label(context, title + if (onClick != null) "  ›" else "", 11f, KC.muted))
    t.addView(label(context, value, 18f, KC.text, true))
    if (onClick != null) clickable(t, onClick)
    return t
}

fun tileRow(context: Context, vararg tiles: View): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        tiles.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (i > 0) marginStart = dp(context, 8); topMargin = dp(context, 8) })
        }
    }

fun row(
    context: Context,
    left: String,
    right: String? = null,
    onClick: (() -> Unit)? = null,
    leftColor: Int = KC.text
): View {
    val r = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(context, 8), 0, dp(context, 8))
    }
    // a long description would otherwise take the whole row and squeeze the title into a sliver that wraps letter by letter:
    // share the width instead and let the description wrap on its own lines
    val longRight = right != null && right.length > 32
    r.addView(label(context, left, 15f, leftColor), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, if (longRight) 0.42f else 1f))
    if (right != null) {
        val rv = label(context, right, 13f, KC.muted)
        if (longRight) {
            rv.gravity = Gravity.END
            r.addView(rv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f).apply { marginStart = dp(context, 8) })
        } else r.addView(rv)
    }
    if (onClick != null) {
        r.addView(label(context, "  ›", 20f, KC.muted))
        clickable(r, onClick)
    }
    return r
}

/** Pick one of [entries] from the shared bottom sheet; [selected] marks the current choice. */
fun Fragment.pick(
    title: String,
    entries: List<String>,
    searchable: Boolean = true,
    images: List<String?>? = null,
    selected: Int? = null,
    onPick: (Int) -> Unit
) = requireContext().pick(title, entries, searchable, images, selected, onPick)

fun Context.pick(
    title: String,
    entries: List<String>,
    searchable: Boolean = true,
    images: List<String?>? = null,
    selected: Int? = null,
    onPick: (Int) -> Unit
) {
    // "Name  (detail)" / "Name   ·   detail" become a title with a small second line
    val rows = entries.mapIndexed { i, e ->
        val cut = Regex("\\s{2,}(?=[(·✓])|\\s+·\\s+").find(e)
        val t = if (cut != null) e.substring(0, cut.range.first) else e
        val sub = if (cut != null) e.substring(cut.range.last + 1).trim().trimStart('·').trim() else ""
        PickRow(t, sub, tag = i, img = images?.getOrNull(i) ?: "", current = i == selected)
    }
    pickList(title, rows, searchable = searchable && entries.size > 8) { onPick(it.tag as Int) }
}

fun Fragment.askQuantity(
    title: String,
    message: String,
    max: Int,
    confirm: String,
    onOk: (Int) -> Unit
) {
    val ctx = requireContext()
    val input = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText("1")
        selectAll()
        setPadding(dp(ctx, 24), dp(ctx, 16), dp(ctx, 24), dp(ctx, 8))
    }
    com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(ctx)
        .setTitle(title)
        .setMessage(message)
        .setView(input)
        .setPositiveButton(confirm) { _, _ ->
            val n = input.text.toString().toIntOrNull() ?: 1
            if (n > 0) onOk(n)
        }
        .setNeutralButton("Max ($max)") { _, _ -> if (max > 0) onOk(max) }
        .setNegativeButton("Cancel", null)
        .show()
}

/**
 * Creates a filter chip (selectable label) for category selection.
 */
fun Context.chip(text: String, onClick: () -> Unit): LinearLayout {
    val ctx = this
    val chip = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
        setBackgroundColor(KC.faint)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(ctx, 8) }
        tag = text
    }
    val label = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(KC.text)
    }
    chip.addView(label)
    clickable(chip, onClick)
    return chip
}

/**
 * A fixed row of icon buttons that act as tabs (never scrolls, equal widths). Each is a rounded, outlined button so
 * it reads as something to press; the selected one is filled green. The icon is the label, so pick obvious ones;
 * the description is read by TalkBack and shown on long-press.
 */
fun iconTabs(context: Context, tabs: List<Pair<Int, String>>, selected: Int = 0, onSelect: (Int) -> Unit): LinearLayout {
    val green = KC.accent
    val dim = KC.dim
    val d = context.resources.displayMetrics.density
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
    }
    // one segmented bar, same surface as the cards; selected segment gets a soft green pill
    val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16 * d
            setColor(KC.chip)
            setStroke((1 * d).toInt(), KC.faint)
        }
    }
    row.addView(bar, LinearLayout.LayoutParams(-1, -2))
    val views = mutableListOf<android.widget.ImageView>()
    fun paint(current: Int) {
        views.forEachIndexed { i, v ->
            val on = i == current
            v.setColorFilter(if (on) green else dim)
            v.background = if (on) android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(KC.accentSoft)
            } else null
        }
    }
    tabs.forEachIndexed { i, (icon, desc) ->
        val v = android.widget.ImageView(context).apply {
            setImageResource(icon)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            contentDescription = desc
            tooltipText = desc
            setOnClickListener { paint(i); onSelect(i) }
        }
        views.add(v)
        bar.addView(v, LinearLayout.LayoutParams(0, (44 * d).toInt(), 1f))
    }
    paint(selected)
    // lets callers switch tab from code:  (row.tag as (Int) -> Unit)(index)
    row.tag = { i: Int -> paint(i); onSelect(i) }
    return row
}

/** Same segmented look as [iconTabs], with short text labels (for sections that have no obvious icon). */
fun textTabs(context: Context, labels: List<String>, selected: Int = 0, onSelect: (Int) -> Unit): LinearLayout {
    val green = KC.accent
    val d = context.resources.displayMetrics.density
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, (4 * d).toInt(), 0, (8 * d).toInt())
    }
    val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 16 * d
            setColor(KC.chip)
            setStroke((1 * d).toInt(), KC.faint)
        }
    }
    row.addView(bar, LinearLayout.LayoutParams(-1, -2))
    val views = mutableListOf<TextView>()
    fun paint(current: Int) = views.forEachIndexed { i, v ->
        val on = i == current
        v.setTextColor(if (on) green else KC.dim)
        v.background = if (on) android.graphics.drawable.GradientDrawable().apply { cornerRadius = 12 * d; setColor(KC.accentSoft) } else null
    }
    labels.forEachIndexed { i, text ->
        val v = TextView(context).apply {
            this.text = text
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 1
            setOnClickListener { paint(i); onSelect(i) }
        }
        views.add(v)
        bar.addView(v, LinearLayout.LayoutParams(0, (40 * d).toInt(), 1f))
    }
    paint(selected)
    row.tag = { i: Int -> paint(i); onSelect(i) }
    return row
}


/** A horizontally scrolling row of small link buttons: each opens a screen that belongs with the tab it sits in. */
fun linkRow(context: Context, links: List<Pair<String, () -> Unit>>): View {
    val d = context.resources.displayMetrics.density
    val row = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding((2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt()) }
    for ((title, go) in links) {
        val tv = android.widget.TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(KC.accent)
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply { setStroke((1 * d).toInt(), KC.accent); cornerRadius = 18 * d }
            layoutParams = android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (8 * d).toInt() }
        }
        clickable(tv) { go() }
        row.addView(tv)
    }
    return android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) }
}

/** The tools registered for a Character tab, as link buttons (see [com.example.kolmafiaandroid.tools.ToolRegistry]). */
fun toolLinks(context: Context, fm: androidx.fragment.app.FragmentManager, home: String, extra: List<Pair<String, () -> Unit>> = emptyList()): View =
    linkRow(context, extra + com.example.kolmafiaandroid.tools.ToolRegistry.inTab(home).map { t ->
        t.title to { if (t.page != null) Unit else t.open(fm) }
    })
