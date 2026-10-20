package com.example.kolmafiaandroid.tools

import com.example.kolmafiaandroid.ui.KC

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.clickable
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import org.json.JSONArray
import org.json.JSONObject

/**
 * BuffBot: set a price list (meat -> buffs) and let KoLmafia answer incoming meat/kmail buff requests
 * for a number of rounds. It only runs while the app is alive, so keep the screen on or the app in front.
 */
class BuffBotFragment : DialogFragment() {
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
        root.addView(label(ctx, "BuffBot", 20f, 0xFF4CAF50.toInt(), true))
        status = label(ctx, "Loading your price list…", 13f, KC.muted).apply { setPadding(0, dp(ctx, 4), 0, dp(ctx, 8)) }
        root.addView(status)
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(android.widget.ScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(Button(ctx).apply { text = "Add buff"; setOnClickListener { addBuff() } }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(Button(ctx).apply { text = "Run"; setOnClickListener { runBot() } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar)
        load()
        return root
    }

    private fun load() {
        KolEngineHost.buffbotOffers { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val arr = o.optJSONArray("offers") ?: JSONArray()
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val ctx = context ?: return@runOnUiThread
                status.text = if (list.isEmpty()) "No buffs on offer yet. Add one: a skill, a price in meat and a number of casts." else "${list.size} price points · tap one to remove it"
                body.removeAllViews()
                for (m in list) {
                    val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 4), dp(ctx, 12), dp(ctx, 4), dp(ctx, 12)) }
                    row.addView(label(ctx, "${m.optInt("price")} meat", 16f, KC.text, true))
                    row.addView(label(ctx, m.optString("text"), 13f, KC.muted))
                    clickable(row) {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle("Remove ${m.optInt("price")} meat offer?")
                            .setPositiveButton("Remove") { _, _ -> KolEngineHost.buffbotRemove(m.optInt("price")) { activity?.runOnUiThread { if (isAdded) load() } } }
                            .setNegativeButton("Cancel", null).show()
                    }
                    body.addView(row)
                }
            }
        }
    }

    private fun addBuff() {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), 0) }
        val skill = EditText(ctx).apply { hint = "Skill (e.g. Ode to Booze)"; maxLines = 1 }
        val price = EditText(ctx).apply { hint = "Price in meat"; inputType = InputType.TYPE_CLASS_NUMBER }
        val casts = EditText(ctx).apply { hint = "Casts per request"; inputType = InputType.TYPE_CLASS_NUMBER; setText("1") }
        box.addView(skill); box.addView(price); box.addView(casts)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle("Add a buff").setView(box)
            .setPositiveButton("Add") { _, _ ->
                val p = price.text.toString().toIntOrNull() ?: 0
                val c = casts.text.toString().toIntOrNull() ?: 0
                val s = skill.text.toString().trim()
                if (s.isNotEmpty() && p > 0 && c > 0) KolEngineHost.buffbotAdd(s, p, c) { activity?.runOnUiThread { if (isAdded) load() } }
                else android.widget.Toast.makeText(ctx, "Need a skill, a price and a cast count", android.widget.Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun runBot() {
        val ctx = requireContext()
        val n = EditText(ctx).apply { hint = "Rounds (each checks your inbox)"; inputType = InputType.TYPE_CLASS_NUMBER; setText("10") }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle("Run BuffBot").setMessage("It answers buff requests with your mana and meat. Keep the app open while it runs; stop it from the Console tab or Adventure's Stop.")
            .setView(n)
            .setPositiveButton("Run") { _, _ ->
                val r = n.text.toString().toIntOrNull() ?: 0
                if (r > 0) { KolEngineHost.execute("buffbot $r"); status.text = "BuffBot running for $r rounds. Watch the Console tab." }
            }.setNegativeButton("Cancel", null).show()
    }

    companion object {
        fun show(fm: FragmentManager) = BuffBotFragment().show(fm, "buffbot")
    }
}
