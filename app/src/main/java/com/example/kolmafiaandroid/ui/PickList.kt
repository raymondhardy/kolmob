package com.example.kolmafiaandroid.ui

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog

/** One selectable row in [pickList]. Rows with the same [group] sit under one header. */
class PickRow(val title: String, val subtitle: String = "", val group: String = "", val locked: Boolean = false, val tag: Any? = null, val img: String = "")

/**
 * Mobile-friendly picker: a tall bottom sheet with a search box, rows grouped under small headers,
 * short two-line rows instead of long strings, and (optionally) locked rows hidden until asked for.
 */
fun Fragment.pickList(
    title: String,
    rows: List<PickRow>,
    initialQuery: String = "",
    lockedLabel: String = "Show locked",
    searchable: Boolean = true,
    onLongPick: ((PickRow) -> Unit)? = null,
    onPick: (PickRow) -> Unit,
) {
    val ctx = requireContext()
    val green = 0xFF4CAF50.toInt()
    var showLocked = false
    var query = initialQuery.trim().lowercase()
    val hasLocked = rows.any { it.locked }

    // flat list of header strings and PickRows
    var items: List<Any> = emptyList()
    val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(i: Int) = if (items[i] is String) 0 else 1
        override fun isEnabled(i: Int) = items[i] !is String
        override fun getView(i: Int, v: View?, parent: ViewGroup): View {
            val it = items[i]
            if (it is String) {
                return (v as? TextView ?: TextView(ctx)).apply {
                    text = it.uppercase()
                    textSize = 12f
                    letterSpacing = 0.1f
                    setTextColor(green)
                    setPadding(dp(ctx, 20), dp(ctx, 16), dp(ctx, 20), dp(ctx, 4))
                }
            }
            val r = it as PickRow
            val outer = (v as? LinearLayout) ?: LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            outer.removeAllViews()
            outer.setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 10))
            if (r.img.isNotEmpty()) {
                val iv = android.widget.ImageView(ctx)
                KolImages.load(iv, r.img)
                outer.addView(iv, LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)).apply { marginEnd = dp(ctx, 14) })
            }
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(label(ctx, (if (r.locked) "🔒 " else "") + r.title, 16f, if (r.locked) KC.hint else KC.text))
            if (r.subtitle.isNotEmpty()) col.addView(label(ctx, r.subtitle, 12f, KC.muted))
            outer.addView(col)
            return outer
        }
    }
    fun rebuild() {
        val shown = rows.filter {
            (showLocked || !it.locked) &&
                (query.isEmpty() || it.title.lowercase().contains(query) || it.group.lowercase().contains(query) || it.subtitle.lowercase().contains(query))
        }
        val out = mutableListOf<Any>()
        var g: String? = null
        for (r in shown) {
            if (r.group.isNotEmpty() && r.group != g) { out.add(r.group); g = r.group }
            out.add(r)
        }
        items = out
        adapter.notifyDataSetChanged()
    }

    val sheet = BottomSheetDialog(ctx)
    val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(ctx, 12), 0, 0) }
    box.addView(label(ctx, title, 18f, KC.text, true).apply { setPadding(dp(ctx, 20), 0, dp(ctx, 20), dp(ctx, 8)) })
    val search = EditText(ctx).apply {
        hint = "Search"
        maxLines = 1
        inputType = InputType.TYPE_CLASS_TEXT
        setText(initialQuery)
    }
    if (searchable) box.addView(search, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(ctx, 16); marginEnd = dp(ctx, 16) })
    if (hasLocked) {
        val sw = android.widget.CheckBox(ctx).apply {
            text = lockedLabel
            setTextColor(KC.muted)
            setOnCheckedChangeListener { _, on -> showLocked = on; rebuild() }
        }
        box.addView(sw, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(ctx, 16); gravity = Gravity.START })
    }
    val list = ListView(ctx).apply { this.adapter = adapter; divider = null }
    box.addView(list, LinearLayout.LayoutParams(-1, if (rows.size <= 6) -2 else dp(ctx, 440)))
    list.setOnItemClickListener { _, _, pos, _ ->
        (items[pos] as? PickRow)?.let { sheet.dismiss(); onPick(it) }
    }
    if (onLongPick != null) list.setOnItemLongClickListener { _, _, pos, _ ->
        (items[pos] as? PickRow)?.let { onLongPick(it); true } ?: false
    }
    search.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { query = s.toString().trim().lowercase(); rebuild() }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    })
    rebuild()
    sheet.setContentView(box)
    sheet.show()
    // the sheet ends where the bottom nav starts, so the nav stays usable
    var host: android.content.Context = ctx
    while (host is android.content.ContextWrapper && host !is com.example.kolmafiaandroid.MainActivity) host = host.baseContext
    (host as? com.example.kolmafiaandroid.MainActivity)?.fitDialogAboveNav(sheet)
}
