package com.example.kolmafiaandroid.tools

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.KolEngineHost

/**
 * Familiar training in the Cake-Shaped Arena. Runs KoLmafia's own `train` command for the familiar you have out:
 * to a base weight, to a buffed weight, or for a number of arena turns. Progress prints in the Console tab.
 */
class TrainFragment : Fragment() {
    private var shownOnce = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) shownOnce = true
    }

    override fun onStart() {
        super.onStart()
        // the popup is shown once per tap: coming back to the app must not bring it up again
        if (shownOnce) return
        shownOnce = true
        Toast.makeText(requireContext(), "Checking the arena…", Toast.LENGTH_SHORT).show()
        KolEngineHost.arenaInfo { json ->
            val o = try { org.json.JSONObject(json) } catch (_: Exception) { org.json.JSONObject() }
            activity?.runOnUiThread { if (isAdded) showDialog(o) }
        }
    }

    private fun showDialog(info: org.json.JSONObject) {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val modes = listOf("base" to "Base weight", "buff" to "Buffed weight", "turns" to "Arena turns")
        val group = RadioGroup(ctx).apply {
            modes.forEachIndexed { i, m -> addView(RadioButton(ctx).apply { id = 100 + i; text = m.second; isChecked = i == 0 }) }
        }
        val amount = EditText(ctx).apply { hint = "How many"; inputType = InputType.TYPE_CLASS_NUMBER }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(group); addView(amount)
        }
        com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(ctx)
            .setTitle("Train familiar")
            .setMessage(arenaSummary(info) + "\n\nTrains " + (KolEngineHost.getPref("_lastFamiliarTrained").ifEmpty { "the familiar you have out" }) + " using arena matches (100 meat and an adventure per match, stops after 5 losses). Progress shows in the Console tab; Stop in the Adventure tab aborts it.")
            .setView(box)
            .setPositiveButton("Train") { _, _ ->
                val n = amount.text.toString().toIntOrNull()
                if (n == null || n <= 0) {
                    Toast.makeText(ctx, "Enter a number first.", Toast.LENGTH_SHORT).show()
                } else {
                    val mode = modes[(group.checkedRadioButtonId - 100).coerceIn(0, modes.lastIndex)].first
                    KolEngineHost.execute("train $mode $n")
                    Toast.makeText(ctx, "Training started. See the Console tab.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Stop training") { _, _ -> KolEngineHost.abort() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun arenaSummary(o: org.json.JSONObject): String {
        val fam = o.optString("familiar")
        if (fam.isEmpty()) return "No familiar is out. Take one along first."
        val sb = StringBuilder("$fam" + (o.optString("name").takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "") + ", ${o.optInt("weight")} lb")
        if (!o.optBoolean("trainable")) sb.append("\nKoLmafia doesn't know how to train this familiar.")
        val opp = o.optJSONArray("opponents")
        if (opp != null && opp.length() > 0) {
            sb.append("\nOpponents today:")
            for (i in 0 until opp.length()) opp.getJSONObject(i).let { sb.append("\n  ${it.optString("name")} (${it.optString("race")}, ${it.optInt("weight")} lb)") }
        }
        return sb.toString()
    }

    companion object {
        fun show(fm: FragmentManager) {
            fm.beginTransaction().add(TrainFragment(), "train").commitAllowingStateLoss()
        }
    }
}
