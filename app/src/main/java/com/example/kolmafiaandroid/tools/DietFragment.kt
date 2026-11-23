package com.example.kolmafiaandroid.tools

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import net.sourceforge.kolmafia.utilities.StringUtilities
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.KolEngineHost
import org.json.JSONObject

/**
 * Diet planner. Picks what to eat, drink and chew for the best adventures per meat (a knapsack per organ,
 * with Ode to Booze and milk of magnesium as helpers). Sources (owned, NPC shops, mall, crafting), organs and
 * a blacklist are all switchable, so e.g. an oxygen run can turn food off. Nothing is eaten until you tap Run.
 */
class DietFragment : Fragment() {
    private var shownOnce = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) shownOnce = true
    }

    private val prefs get() = requireContext().getSharedPreferences("kolmob", Context.MODE_PRIVATE)

    override fun onStart() {
        super.onStart()
        // the popup is shown once per tap: coming back to the app must not bring it up again
        if (shownOnce) return
        shownOnce = true
        showOptions()
    }

    private fun showOptions() {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        fun label(text: String) = TextView(ctx).apply { this.text = text; textSize = 13f; setPadding(0, (10 * dp).toInt(), 0, 0) }
        fun check(text: String, key: String, def: Boolean) =
            CheckBox(ctx).apply { this.text = text; isChecked = prefs.getBoolean("diet_$key", def) }.also { col.addView(it) }

        col.addView(label("Meat you value one adventure at"))
        val mpaDefault = prefs.getString("diet_mpa", null) ?: KolEngineHost.getPref("valueOfAdventure").ifBlank { "500" }
        val mpa = EditText(ctx).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(mpaDefault) }
        col.addView(mpa)

        col.addView(label("Adventures to keep in reserve (for crafting, and as the point to eat or drink when low)"))
        val reserve = EditText(ctx).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(prefs.getString("diet_reserve", "3")) }
        col.addView(reserve)

        col.addView(label("Where food and drink may come from"))
        val owned = check("Items I already have", "owned", true)
        val npc = check("Buy from NPC shops", "npc", false)
        val mall = check("Shop the mall (not available in Hardcore or Ronin)", "mall", false)
        val craft = check("Craft from what I have", "craft", false)

        col.addView(label("Which organs to fill"))
        val food = check("Food", "food", true)
        val booze = check("Booze", "booze", true)
        val spleen = check("Spleen", "spleen", true)
        val expanders = check("Use pills and teas that add room for the day: distention pill, cuppa Voraci tea, sweet tooth", "expanders", true)
        val cleaners = check("Use cleaners: pills, teas, spice melange, mojo filters and the like that empty part of an organ", "cleaners", true)
        val night = check("Overdrink: one big drink last for extra adventures (you cannot adventure again until rollover; the liver resets)", "night", true)
        val sim = check("Preview as if it were a fresh day (empty organs)", "sim", false)

        col.addView(label("Never use these, not even as a crafting ingredient (comma separated item names)"))
        val black = EditText(ctx).apply {
            hint = "e.g. Ye Olde Meade, stolen sushi"
            setText(KolEngineHost.getPref("kolmobDietBlacklist").split("|").filter { it.isNotBlank() }.joinToString(", "))
        }
        col.addView(black)

        com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(ctx)
            .setTitle("Diet planner")
            .setView(ScrollView(ctx).apply { addView(col) })
            .setPositiveButton("Plan") { _, _ ->
                val names = black.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                KolEngineHost.setPref("kolmobDietBlacklist", names.joinToString("|"))
                prefs.edit().putString("diet_mpa", mpa.text.toString()).putString("diet_reserve", reserve.text.toString())
                    .putBoolean("diet_owned", owned.isChecked).putBoolean("diet_npc", npc.isChecked)
                    .putBoolean("diet_mall", mall.isChecked).putBoolean("diet_craft", craft.isChecked)
                    .putBoolean("diet_food", food.isChecked).putBoolean("diet_booze", booze.isChecked)
                    .putBoolean("diet_spleen", spleen.isChecked).putBoolean("diet_night", night.isChecked).putBoolean("diet_cleaners", cleaners.isChecked).putBoolean("diet_expanders", expanders.isChecked)
                    .putBoolean("diet_sim", sim.isChecked).apply()
                fun b(v: Boolean) = if (v) "1" else "0"
                val spec = "mpa=${mpa.text.toString().toLongOrNull() ?: 0};reserve=${reserve.text.toString().toIntOrNull() ?: 3};owned=${b(owned.isChecked)};npc=${b(npc.isChecked)};" +
                    "mall=${b(mall.isChecked)};craft=${b(craft.isChecked)};food=${b(food.isChecked)};booze=${b(booze.isChecked)};" +
                    "spleen=${b(spleen.isChecked)};night=${b(night.isChecked)};cleaners=${b(cleaners.isChecked)};expanders=${b(expanders.isChecked)};sim=${b(sim.isChecked)}"
                Toast.makeText(ctx, "Planning…", Toast.LENGTH_SHORT).show()
                lastSpec = spec
                KolEngineHost.dietPlan(spec) { json -> activity?.runOnUiThread { showPlan(json) } }
            }
            .setNegativeButton("Cancel", null)
            .show().also { (activity as? com.example.kolmafiaandroid.MainActivity)?.fitDialogAboveNav(it) }
    }

    private fun showPlan(json: String) {
        val ctx = context ?: return
        val o = try { JSONObject(json) } catch (e: Exception) { null }
        if (o == null || !o.optBoolean("ok")) {
            com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(ctx).setTitle("Diet planner").setMessage("Could not plan: ${o?.optString("error") ?: json}")
                .setPositiveButton("OK", null).show()
            return
        }
        val items = o.optJSONArray("items")
        val sb = StringBuilder()
        sb.append("Room left: food ${o.optInt("foodSpace")}, booze ${o.optInt("boozeSpace")}, spleen ${o.optInt("spleenSpace")}\n")
        if (o.optBoolean("simulated")) sb.append("(Preview only: pretending the day just started.)\n")
        if (o.optBoolean("mallBlocked")) sb.append("Mall shopping is not allowed on this character right now, so it was skipped.\n")
        if (items == null || items.length() == 0) {
            sb.append("\nNothing worth eating or drinking with these settings.")
        } else {
            sb.append("\nAbout ${o.optDouble("totalAdventures")} adventures for ${o.optLong("totalCost")} meat.\n\n")
            if (o.optBoolean("useMilk")) sb.append("• Milk of magnesium first (+${o.optDouble("milkGain")} adventures)\n")
            if (o.optBoolean("useOde")) sb.append("• Ode to Booze will be cast right before the booze (+${o.optDouble("odeGain")} adventures)\n")
            for (i in 0 until items!!.length()) {
                val it = items.getJSONObject(i)
                val src = it.optString("source")
                val tag = if (src == "owned") "" else " [$src]"
                sb.append("• ${it.optString("organ")}: ${it.optInt("count")} × ${it.optString("name")}$tag " +
                    "(${it.optDouble("adventuresEach")} adv each)\n")
            }
        }
        val notes = o.optJSONArray("notes")
        if (notes != null && notes.length() > 0) {
            sb.append("\nHelpers:\n")
            for (i in 0 until notes.length()) sb.append("• ${notes.getString(i)}\n")
        }
        val night = o.optJSONObject("nightcap")
        sb.append("\nYou have ${o.optInt("adventuresNow")} adventures; keep ${o.optInt("reserve")} in reserve. Drink once you are down to about that.")
        sb.append("\nEat next / Drink next do one item and re-plan. Hold the booze until adventures are nearly gone so better drops can replace it.")
        if (night != null) sb.append("\nOverdrink last: ${night.optString("name")} (${night.optDouble("adventures")} adv, ${night.optInt("overshoot")} past the liver limit, ${night.optDouble("netGain")} adv more than stopping at the limit). Do this when you are done adventuring for the day.")
        val tips = o.optJSONArray("tips")
        if (tips != null) for (i in 0 until tips.length()) sb.append("\n" + tips.getString(i))

        // Steps in order; each holds one unit with what goes with it (buy/create, helpers), or a setup step (pill, Ode, cleaner).
        data class Step(val group: String, val kind: String, val label: String, val commands: List<String>)
        val sa = o.optJSONArray("steps")
        val steps = (0 until (sa?.length() ?: 0)).map { i ->
            val j = sa!!.getJSONObject(i)
            val c = j.optJSONArray("commands")
            Step(j.optString("group"), j.optString("kind"), j.optString("label"), (0 until (c?.length() ?: 0)).map { c!!.getString(it) })
        }
        val live = !o.optBoolean("simulated")
        val dp = ctx.resources.displayMetrics.density
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), 0) }
        // KoLmafia item names keep HTML entities (bu&ntilde;uelos Jaliscos): show them decoded
        col.addView(TextView(ctx).apply { text = StringUtilities.getEntityDecode(sb.toString()); textSize = 14f })
        lateinit var dlg: androidx.appcompat.app.AlertDialog
        fun btn(text: String, run: List<String>) {
            col.addView(android.widget.Button(ctx).apply {
                this.text = StringUtilities.getEntityDecode(text)
                setOnClickListener {
                    dlg.dismiss()
                    Toast.makeText(ctx, "Running. See the Console tab.", Toast.LENGTH_SHORT).show()
                    // KoLmafia's drink asks "Are you sure you want to overdrink?" itself when a drink goes past the limit
                    runAll(run, 0) { lastSpec?.let { s -> KolEngineHost.dietPlan(s) { j -> activity?.runOnUiThread { showPlan(j) } } } }
                }
            })
        }
        // "Next": the setup steps of that group up to its first item, and that item; the plan is made again afterwards.
        fun next(group: String, kind: String): Pair<String, List<String>>? {
            val mine = steps.filter { it.group == group }
            val idx = mine.indexOfFirst { it.kind == kind }
            if (idx < 0) return null
            return mine[idx].label to mine.subList(0, idx + 1).flatMap { it.commands }
        }
        if (live) {
            next("food", "eat")?.let { btn("Eat next: ${it.first}", it.second) }
            next("spleen", "chew")?.let { btn("Chew next: ${it.first}", it.second) }
            next("booze", "drink")?.let { btn("Drink next: ${it.first}", it.second) }
            val foodSpleen = steps.filter { it.group == "food" || it.group == "spleen" }.flatMap { it.commands }
            if (foodSpleen.isNotEmpty()) btn("Run all food & spleen", foodSpleen)
            val boozeAll = steps.filter { it.group == "booze" }.flatMap { it.commands }
            if (boozeAll.isNotEmpty()) btn("Drink all booze (up to the liver limit)", boozeAll)
            steps.firstOrNull { it.group == "night" }?.let { btn("Overdrink: ${it.label} (ends the day)", it.commands) }
        }
        col.addView(android.widget.Button(ctx).apply { text = "Change options"; setOnClickListener { dlg.dismiss(); showOptions() } })
        dlg = com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(ctx).setTitle("Diet plan").setView(ScrollView(ctx).apply { addView(col) })
            .setNegativeButton("Close", null).create()
        dlg.show()
        (activity as? com.example.kolmafiaandroid.MainActivity)?.fitDialogAboveNav(dlg)
    }

    private var lastSpec: String? = null

    /** Runs the commands in order and stops at the first one that fails, so nothing is eaten or drunk after a failed step. */
    private fun runAll(cmds: List<String>, i: Int, then: (() -> Unit)? = null) {
        if (i >= cmds.size) { then?.invoke(); return }
        KolEngineHost.execute(cmds[i]) {
            if (KolEngineHost.lastOk) { runAll(cmds, i + 1, then); return@execute }
            val left = cmds.size - i - 1
            activity?.runOnUiThread {
                context?.let { Toast.makeText(it, "Stopped: \"${cmds[i]}\" failed" + (if (left > 0) "; $left step(s) not run" else "") + ". See the Console tab.", Toast.LENGTH_LONG).show() }
            }
            then?.invoke()
        }
    }

    companion object {
        fun show(fm: FragmentManager) {
            fm.beginTransaction().add(DietFragment(), "diet").commitAllowingStateLoss()
        }
    }
}
