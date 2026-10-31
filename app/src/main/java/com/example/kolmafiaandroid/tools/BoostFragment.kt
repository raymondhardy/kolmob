package com.example.kolmafiaandroid.tools

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.KC
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import org.json.JSONObject

/**
 * Boost: pick a goal and see what you can use, cast or do (potions, buffs, one-use items) to push it higher than your
 * current gear does. This is KoLmafia's own maximizer with the equipment left out; tap a line to do it.
 */
class BoostFragment : DialogFragment() {
    private lateinit var goal: Spinner
    private lateinit var custom: EditText
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).also { it.requestWindowFeature(Window.FEATURE_NO_TITLE) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16)) }
        col.addView(label(ctx, "Boost", 20f, 0xFF4CAF50.toInt(), true))
        col.addView(label(ctx, "What you can use or cast to raise a goal, on top of the gear you are wearing. Put on the best gear first (Gear tab).", 12f, KC.muted).apply { setPadding(0, dp(ctx, 4), 0, dp(ctx, 8)) })
        val names = GearGoals.list.map { it.first } + "Custom…"
        goal = Spinner(ctx).apply { adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, names) }
        col.addView(goal)
        custom = EditText(ctx).apply { hint = "Maximizer expression, e.g. item drop, -combat"; setTextColor(KC.text); setHintTextColor(KC.hint); visibility = View.GONE; setSingleLine() }
        col.addView(custom)
        goal.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                custom.visibility = if (pos == GearGoals.list.size) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        col.addView(android.widget.Button(ctx).apply { text = "Find boosts"; setOnClickListener { find() } })
        status = label(ctx, "", 13f, KC.muted).apply { setPadding(0, dp(ctx, 8), 0, dp(ctx, 4)) }
        col.addView(status)
        results = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ScrollView(ctx).apply { addView(results) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        col.addView(android.widget.Button(ctx).apply { text = "Close"; setOnClickListener { dismiss() } })
        return col
    }

    private fun expression(): String {
        val i = goal.selectedItemPosition
        return if (i in GearGoals.list.indices) GearGoals.list[i].second else custom.text.toString().trim()
    }

    private fun find() {
        val expr = expression()
        if (expr.isEmpty()) { status.text = "Type a goal first."; return }
        if (busy) return
        busy = true
        status.text = "Looking…"
        results.removeAllViews()
        KolEngineHost.boosts(expr) { json ->
            activity?.runOnUiThread {
                busy = false
                if (!isAdded || view == null) return@runOnUiThread
                show(json)
            }
        }
    }

    private fun show(json: String) {
        val ctx = requireContext()
        val o = try { JSONObject(json) } catch (e: Exception) { null }
        results.removeAllViews()
        if (o == null || !o.optBoolean("ok")) { status.text = "Couldn't work that out: ${o?.optString("error") ?: json}"; return }
        val arr = o.optJSONArray("boosts")
        if (arr == null || arr.length() == 0) { status.text = "Nothing in your inventory or skills adds to that right now."; return }
        status.text = "Tap one to do it. Best first."
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            results.addView(TextView(ctx).apply {
                text = b.getString("text")
                textSize = 14f
                setTextColor(KC.soft)
                setPadding(dp(ctx, 8), dp(ctx, 12), dp(ctx, 8), dp(ctx, 12))
                setOnClickListener { use(b.getString("cmd")) }
            })
        }
    }

    private fun use(cmd: String) {
        if (busy) return
        busy = true
        status.text = "Doing: $cmd"
        KolEngineHost.execute(cmd) {
            activity?.runOnUiThread {
                busy = false
                if (isAdded && view != null) find()
            }
        }
    }

    companion object {
        fun show(fm: FragmentManager) { BoostFragment().show(fm, "boost") }
    }
}
