package com.example.kolmafiaandroid.tools

import com.example.kolmafiaandroid.ui.KC

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import org.json.JSONObject

/** Your character's current numeric modifiers, straight from KoLmafia's modifier engine. */
class ModifiersFragment : DialogFragment() {
    private var mods = listOf<Pair<String, Double>>()
    private var filter = ""
    private var stats = listOf<String>()
    private lateinit var body: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).also { it.requestWindowFeature(Window.FEATURE_NO_TITLE) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 8)) }
        root.addView(label(ctx, "Modifiers", 20f, 0xFF4CAF50.toInt(), true))
        root.addView(EditText(ctx).apply {
            hint = "Filter (e.g. resistance, item, damage)"
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { filter = s.toString().trim().lowercase(); render() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
        status = label(ctx, "Reading your modifiers…", 13f, KC.muted).apply { setPadding(0, dp(ctx, 4), 0, dp(ctx, 8)) }
        root.addView(status)
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(android.widget.ScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        KolEngineHost.modifiers { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val sa = o.optJSONArray("stats")
            val statLines = if (sa == null) emptyList() else (0 until sa.length()).map { sa.getJSONObject(it) }.map {
                val nf = java.text.NumberFormat.getIntegerInstance()
                it.getString("name") + "|" + it.getInt("buffed") + (if (it.getInt("buffed") != it.getInt("base")) " (base ${it.getInt("base")})" else "") + "|" + nf.format(it.getLong("substats")) + " substats"
            }
            val arr = o.optJSONArray("mods")
            val list = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }.map { it.getString("name") to it.getDouble("value") }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                stats = statLines
                mods = list.sortedBy { it.first.lowercase() }
                status.text = if (o.has("error")) "Couldn't read modifiers. Are you logged in?" else "${mods.size} active modifiers"
                render()
            }
        }
        return root
    }

    private fun fmt(v: Double): String {
        val s = if (v == Math.rint(v)) v.toLong().toString() else String.format("%.1f", v)
        return if (v > 0) "+$s" else s
    }

    private fun render() {
        val ctx = context ?: return
        body.removeAllViews()
        if (filter.isEmpty()) for (line in stats) {
            val (n, v, sub) = line.split("|")
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), dp(ctx, 10)) }
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(label(ctx, n, 16f, KC.text, true))
            col.addView(label(ctx, sub, 12f, KC.muted))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label(ctx, v, 16f, 0xFF4CAF50.toInt(), true))
            body.addView(row)
        }
        for ((name, v) in mods.filter { filter.isEmpty() || it.first.lowercase().contains(filter) }) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), dp(ctx, 10)) }
            row.addView(label(ctx, name, 15f, KC.text), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label(ctx, fmt(v), 15f, if (v >= 0) 0xFF4CAF50.toInt() else 0xFFEF5350.toInt(), true))
            body.addView(row)
        }
    }

    companion object {
        fun show(fm: FragmentManager) = ModifiersFragment().show(fm, "modifiers")
    }
}
