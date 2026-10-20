package com.example.kolmafiaandroid.tools

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.KC
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject

/**
 * KoLmafia's choice adventure settings: for each known choice, which option to take automatically
 * ("Manual" stops and asks). Used whenever KoLmafia plays: the Adventure button and scripts.
 */
class ChoiceAdvFragment : DialogFragment() {
    private var all = listOf<JSONObject>()
    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private lateinit var onlySet: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).also { it.requestWindowFeature(Window.FEATURE_NO_TITLE) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(KC.pick(0xFF121212.toInt(), 0xFFF2F2F2.toInt()))
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), 0)
        }
        root.addView(label(ctx, "Choice adventures", 20f, 0xFF4CAF50.toInt(), true))
        root.addView(label(ctx, "What KoLmafia picks on its own when it plays for you (the Adventure button and scripts). Manual stops and asks.", 12f, KC.muted).apply { setPadding(0, dp(ctx, 4), 0, dp(ctx, 8)) })
        val browser = CheckBox(ctx).apply {
            text = "Also pick these automatically in the browser (2-second countdown, tap to cancel)"
            setTextColor(KC.soft)
            isChecked = KolEngineHost.getPref("kolmobBrowserAutoChoice") == "true"
            setOnCheckedChangeListener { _, on -> KolEngineHost.setPref("kolmobBrowserAutoChoice", if (on) "true" else "false") }
        }
        root.addView(browser)
        search = EditText(ctx).apply {
            hint = "Search by name or zone"
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(KC.text)
            setHintTextColor(KC.hint)
        }
        root.addView(search)
        onlySet = CheckBox(ctx).apply { text = "Only the ones I set"; setTextColor(KC.muted); setOnCheckedChangeListener { _, _ -> render() } }
        root.addView(onlySet)
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(ctx).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { render() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        list.addView(label(ctx, "Loading…", 14f, KC.muted))
        load()
        return root
    }

    private fun load() {
        KolEngineHost.choiceList { json ->
            val arr = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
            all = (0 until arr.length()).map { arr.getJSONObject(it) }
            activity?.runOnUiThread { if (isAdded && view != null) render() }
        }
    }

    private fun optionName(o: JSONObject): String {
        val v = o.optInt("value")
        if (v == 0) return "Manual"
        val opts = o.getJSONArray("options")
        for (i in 0 until opts.length()) if (opts.getJSONObject(i).optInt("d") == v) return opts.getJSONObject(i).optString("n")
        return "Option $v"
    }

    private fun render() {
        val ctx = context ?: return
        list.removeAllViews()
        val q = search.text.toString().trim().lowercase()
        var shown = 0
        for (o in all) {
            if (onlySet.isChecked && o.optInt("value") == 0) continue
            if (q.isNotEmpty() && !(o.optString("name").lowercase().contains(q) || o.optString("zone").lowercase().contains(q))) continue
            if (++shown > 120) break
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(ctx, 10), 0, dp(ctx, 10)) }
            row.addView(label(ctx, o.optString("name"), 15f, KC.text, true))
            val set = o.optInt("value") != 0
            row.addView(label(ctx, "${o.optString("zone")} · #${o.optInt("choice")}", 12f, KC.muted))
            row.addView(label(ctx, "→ " + optionName(o), 13f, if (set) 0xFF4CAF50.toInt() else KC.muted))
            row.setOnClickListener { pick(o) }
            list.addView(row)
        }
        if (shown == 0) list.addView(label(ctx, "Nothing matches.", 14f, KC.muted))
        else if (shown > 120) list.addView(label(ctx, "Showing the first 120. Search to narrow it down.", 12f, KC.muted))
    }

    private fun pick(o: JSONObject) {
        val opts = o.getJSONArray("options")
        val names = mutableListOf("Manual (stop and ask me)")
        val decisions = mutableListOf(0)
        for (i in 0 until opts.length()) { names.add(opts.getJSONObject(i).optString("n")); decisions.add(opts.getJSONObject(i).optInt("d")) }
        val cur = decisions.indexOf(o.optInt("value")).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext()).setTitle(o.optString("name"))
            .setSingleChoiceItems(names.toTypedArray(), cur) { d, i ->
                KolEngineHost.setPref(o.optString("setting"), decisions[i].toString())
                d.dismiss()
                load()
            }
            .setNegativeButton("Cancel", null).show()
    }

    companion object {
        fun show(fm: FragmentManager) = ChoiceAdvFragment().show(fm, "choiceadv")
    }
}
