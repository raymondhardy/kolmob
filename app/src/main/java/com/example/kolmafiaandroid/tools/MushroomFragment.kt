package com.example.kolmafiaandroid.tools

import com.example.kolmafiaandroid.ui.KC

import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import org.json.JSONObject

/** Your mushroom plot: current layout and forecast, with harvest / pick-a-square. Only meaningful once you own a plot this ascension. */
class MushroomFragment : DialogFragment() {
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
        root.addView(label(ctx, "Mushroom plot", 20f, 0xFF4CAF50.toInt(), true))
        status = label(ctx, "Looking at your fields…", 13f, KC.muted).apply { setPadding(0, dp(ctx, 4), 0, dp(ctx, 8)) }
        root.addView(status)
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(android.widget.ScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        load()
        return root
    }

    private fun load() {
        KolEngineHost.mushroom { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val ctx = context ?: return@runOnUiThread
                body.removeAllViews()
                if (o.has("error") || !o.optBoolean("owns")) {
                    status.text = "You don't have a mushroom plot this ascension. Buy one at the Degrassi Knoll first."
                    return@runOnUiThread
                }
                status.text = "Squares are numbered left to right, top to bottom."
                fun block(title: String, text: String) {
                    body.addView(label(ctx, title.uppercase(), 12f, 0xFF4CAF50.toInt(), true).apply { setPadding(0, dp(ctx, 12), 0, dp(ctx, 4)) })
                    body.addView(TextView(ctx).apply { this.text = text.replace(Regex("<[^>]*>"), ""); typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(KC.text) })
                }
                block("Now", o.optString("current"))
                block("Forecast", o.optString("forecast"))
                val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(ctx, 16), 0, 0) }
                actions.addView(Button(ctx).apply { text = "Pick a square"; setOnClickListener { pickSquare() } }, LinearLayout.LayoutParams(0, -2, 1f))
                actions.addView(Button(ctx).apply { text = "Harvest all"; setOnClickListener { confirmHarvest() } }, LinearLayout.LayoutParams(0, -2, 1f))
                body.addView(actions)
            }
        }
    }

    private fun pickSquare() {
        val ctx = context ?: return
        val input = android.widget.EditText(ctx).apply { hint = "Square number"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle("Pick a square").setView(input)
            .setPositiveButton("Pick") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) KolEngineHost.execute("field pick $n") { activity?.runOnUiThread { if (isAdded) load() } }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun confirmHarvest() {
        val ctx = context ?: return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle("Harvest all?").setMessage("Picks every ripe mushroom in the plot.")
            .setPositiveButton("Harvest") { _, _ -> KolEngineHost.execute("field harvest") { activity?.runOnUiThread { if (isAdded) load() } } }
            .setNegativeButton("Cancel", null).show()
    }

    companion object {
        fun show(fm: FragmentManager) = MushroomFragment().show(fm, "mushroom")
    }
}
