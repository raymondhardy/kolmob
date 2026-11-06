package com.example.kolmafiaandroid.tools

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.ui.Dropdown
import com.example.kolmafiaandroid.ui.FullScreenDialog
import com.example.kolmafiaandroid.ui.KC
import com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder
import com.example.kolmafiaandroid.ui.clickable
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import com.example.kolmafiaandroid.ui.screenTitle
import com.example.kolmafiaandroid.ui.sectionLabel
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.json.JSONObject

/**
 * KoLmafia's Modifier Maximizer, with everything one tap away: tap a goal and it maximizes; change where gear may come
 * from, what to spend or what kinds of boosts to look at and it maximizes again; tap a suggestion to do it. Gear changes
 * happen straight away (Undo gear puts back what you wore when you opened this); anything that uses up an item or meat
 * asks once first. After every change it maximizes again, as the desktop window does.
 */
class MaximizerFragment : FullScreenDialog() {
    private lateinit var goal: Dropdown
    private lateinit var scopeGroup: MaterialButtonToggleGroup
    private lateinit var priceGroup: MaterialButtonToggleGroup
    private lateinit var maxPriceChip: Chip
    private lateinit var filterChips: ChipGroup
    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private lateinit var wearAll: MaterialButton

    /** Every filter KoLmafia knows, with what the app calls it. */
    private val filters = listOf(
        "equip" to "Gear", "cast" to "Skills", "usable" to "Items", "wish" to "Wishes",
        "booze" to "Drink", "food" to "Food", "spleen" to "Spleen", "other" to "Other",
    )
    private val scopes = listOf("On hand", "Can make", "Pull or buy")
    private val prices = listOf("Ignore", "Buyable", "All")

    private val goals: List<Pair<String, String>> = GearGoals.list
    private var goalIndex = 0
    private var customExpr = ""
    private var scope = 0
    private var priceLevel = 0
    private var maxPrice = 0
    private val filterOn = filters.associate { it.first to true }.toMutableMap()

    private var boosts: List<JSONObject> = emptyList()
    private var busy = false
    private var rerun = false
    private var loading = true
    /** The gear worn when this screen opened is saved before the first change, so Undo goes back to it. */
    private var gearSaved = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val outer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 8)) }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(ScrollView(ctx).apply { addView(col) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        col.addView(screenTitle(ctx, "Maximizer"))
        col.addView(label(ctx, "Pick a goal. KoLmafia finds the best gear and boosts; tap one to do it.", 12f, KC.muted).apply { setPadding(0, dp(ctx, 4), 0, 0) })

        col.addView(sectionLabel(ctx, "Goal", 12))
        goal = Dropdown(ctx, "Maximize for", goalNames()) { pos ->
            if (pos == goals.size) askCustom() else { goalIndex = pos; customExpr = ""; goal.options = goalNames(); maximize() }
        }
        col.addView(goal)

        col.addView(sectionLabel(ctx, "Gear from", 12))
        scopeGroup = toggleGroup(scopes) { scope = it; maximize() }
        col.addView(scopeGroup)

        col.addView(sectionLabel(ctx, "Spending", 12))
        priceGroup = toggleGroup(prices) { priceLevel = it; showMaxPrice(); maximize() }
        col.addView(priceGroup)
        maxPriceChip = chip("").apply { isCheckable = false; setOnClickListener { askMaxPrice() } }
        col.addView(maxPriceChip)

        col.addView(sectionLabel(ctx, "Look at", 12))
        filterChips = ChipGroup(ctx).apply { isSingleLine = true }
        for ((key, name) in filters) {
            filterChips.addView(chip(name).apply {
                isChecked = filterOn[key] == true
                tag = key
                setOnClickListener { filterOn[key] = isChecked; maximize() }
            })
        }
        col.addView(sideways(filterChips))

        heading = label(ctx, "", 17f, KC.text, true).apply { setPadding(0, dp(ctx, 16), 0, 0) }
        col.addView(heading)
        status = label(ctx, "Loading your maximizer settings…", 13f, KC.muted).apply { setPadding(0, dp(ctx, 2), 0, dp(ctx, 8)) }
        col.addView(status)
        results = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(results)

        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(ctx, 8), 0, 0) }
        wearAll = MaterialButton(ctx).apply { text = "Wear all gear"; isAllCaps = false; letterSpacing = 0f; isEnabled = false; setOnClickListener { wearAllGear() } }
        val undo = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "Undo gear"; isAllCaps = false; letterSpacing = 0f; setOnClickListener { undoGear() } }
        bar.addView(wearAll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(ctx, 8) })
        bar.addView(undo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        outer.addView(bar)

        showMaxPrice()
        loadSettings()
        return outer
    }

    // ---- option controls ----

    private fun chip(text: String): Chip {
        val ctx = requireContext()
        val checked = intArrayOf(android.R.attr.state_checked)
        return Chip(ctx).apply {
            this.text = text
            isCheckable = true
            isCheckedIconVisible = false
            chipBackgroundColor = ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(KC.accent, KC.faint))
            setTextColor(ColorStateList(arrayOf(checked, intArrayOf()), intArrayOf(KC.onAccent, KC.text)))
            chipStrokeWidth = 0f
        }
    }

    private fun toggleGroup(labels: List<String>, onPick: (Int) -> Unit): MaterialButtonToggleGroup {
        val ctx = requireContext()
        val group = MaterialButtonToggleGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true }
        labels.forEachIndexed { i, text ->
            group.addView(MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId(); tag = i; this.text = text; isAllCaps = false; letterSpacing = 0f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        group.addOnButtonCheckedListener { g, id, isChecked ->
            if (isChecked && !loading) onPick(g.findViewById<View>(id).tag as Int)
        }
        return group
    }

    /** One row of chips you swipe sideways, so the options stay short and the results stay in view. */
    private fun sideways(group: ChipGroup) = android.widget.HorizontalScrollView(requireContext()).apply {
        isHorizontalScrollBarEnabled = false
        addView(group)
    }

    private fun checkIndex(group: MaterialButtonToggleGroup, index: Int) = group.check(group.getChildAt(index.coerceIn(0, group.childCount - 1)).id)

    /** The presets, then your own expression (or a way to type one). */
    private fun goalNames() = goals.map { it.first } + (if (customExpr.isEmpty()) "Custom…" else "Custom: $customExpr")

    private fun askCustom() {
        val ctx = requireContext()
        val input = EditText(ctx).apply { hint = "e.g. item drop, -combat, -tie"; setText(customExpr); setSingleLine() }
        val box = LinearLayout(ctx).apply { setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), 0); addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        ScrollSafeDialogBuilder(ctx)
            .setTitle("Maximize for")
            .setMessage("Anything the maximizer understands, as in KoLmafia's maximize command.")
            .setView(box)
            .setPositiveButton("Maximize") { _, _ ->
                val e = input.text.toString().trim()
                if (e.isEmpty()) restoreGoal() else { customExpr = e; goal.options = goalNames(); goal.selectedIndex = goals.size; maximize() }
            }
            .setNegativeButton("Cancel") { _, _ -> restoreGoal() }
            .setOnCancelListener { restoreGoal() }
            .show()
    }

    private fun restoreGoal() { goal.selectedIndex = if (customExpr.isNotEmpty()) goals.size else goalIndex }

    private fun showMaxPrice() {
        maxPriceChip.visibility = if (priceLevel > 0) View.VISIBLE else View.GONE
        maxPriceChip.text = if (maxPrice > 0) "Spend at most %,d meat each".format(maxPrice) else "No price limit · tap to set one"
    }

    private fun askMaxPrice() {
        val ctx = requireContext()
        val input = EditText(ctx).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "meat (0 = no limit)"; if (maxPrice > 0) setText(maxPrice.toString()) }
        val box = LinearLayout(ctx).apply { setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), 0); addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        ScrollSafeDialogBuilder(ctx)
            .setTitle("Most to spend on one thing")
            .setView(box)
            .setPositiveButton("Set") { _, _ -> maxPrice = input.text.toString().toIntOrNull() ?: 0; showMaxPrice(); maximize() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The desktop window's last choices (gear source, spending, filters); then maximizes. */
    private fun loadSettings() {
        KolEngineHost.maximizerSettings { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread
                scope = o.optInt("scope").coerceIn(0, 2)
                priceLevel = o.optInt("priceLevel").coerceIn(0, 2)
                maxPrice = o.optInt("maxPrice")
                checkIndex(scopeGroup, scope)
                checkIndex(priceGroup, priceLevel)
                showMaxPrice()
                o.optJSONArray("filters")?.let { a ->
                    val on = (0 until a.length()).map { a.getString(it) }.toSet()
                    filters.forEach { (k, _) -> filterOn[k] = k in on }
                    for (i in 0 until filterChips.childCount) (filterChips.getChildAt(i) as Chip).let { it.isChecked = filterOn[it.tag] == true }
                }
                loading = false
                maximize()
            }
        }
    }

    private fun expression(): String = customExpr.ifEmpty { goals.getOrNull(goalIndex)?.second ?: "" }

    private fun goalName(): String = customExpr.ifEmpty { goals.getOrNull(goalIndex)?.first ?: "" }

    // ---- maximizing ----

    /** Runs the maximizer with the current options. A change made while it runs is picked up when it finishes. */
    private fun maximize(done: String? = null) {
        if (loading) return
        if (busy) { rerun = true; return }
        val on = filters.map { it.first }.filter { filterOn[it] == true }
        if (on.isEmpty()) { heading.text = ""; status.text = "Turn on at least one kind of suggestion under Look at."; results.removeAllViews(); return }
        busy = true; rerun = false
        val expr = expression()
        heading.text = goalName()
        status.text = done ?: "Maximizing…"; status.setTextColor(KC.muted)
        wearAll.isEnabled = false
        results.alpha = 0.4f
        KolEngineHost.maximizer(expr, scope, maxPrice, priceLevel, on.joinToString(",")) { json ->
            activity?.runOnUiThread {
                busy = false
                if (!isAdded || view == null) return@runOnUiThread
                if (rerun) { maximize(); return@runOnUiThread }
                show(json, done)
            }
        }
    }

    private fun show(json: String, done: String?) {
        results.alpha = 1f
        results.removeAllViews()
        val o = try { JSONObject(json) } catch (_: Exception) { null }
        if (o == null || !o.optBoolean("ok")) {
            status.text = "Couldn't maximize that: ${o?.optString("error") ?: json}"; status.setTextColor(KC.bad); boosts = emptyList(); return
        }
        val arr = o.optJSONArray("boosts")
        boosts = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }
        heading.text = "%s · score %.1f%s".format(goalName(), o.optDouble("score"), if (o.optBoolean("failed")) " (can't meet it)" else "")
        val doable = boosts.filter { it.optString("cmd").isNotBlank() }
        status.text = done?.let { "✓ $it" } ?: if (doable.isEmpty()) "You're already at the best you can do for this." else "Tap one to do it."
        status.setTextColor(if (done != null) KC.accent else KC.muted)
        render()
    }

    private fun render() {
        val ctx = requireContext()
        val keep = boosts.filter { it.optBoolean("equip") && it.optString("cmd").isBlank() }
        val gear = boosts.filter { it.optBoolean("equip") && it.optString("cmd").isNotBlank() }
        val other = boosts.filter { !it.optBoolean("equip") && it.optString("cmd").isNotBlank() }
        if (gear.isNotEmpty()) { results.addView(sectionLabel(ctx, "Change gear", 4)); gear.forEach { results.addView(boostRow(it)) } }
        if (other.isNotEmpty()) { results.addView(sectionLabel(ctx, "Use, cast or do", 12)); other.forEach { results.addView(boostRow(it)) } }
        if (keep.isNotEmpty()) {
            val names = keep.joinToString(" · ") { it.optString("text").removePrefix("keep ").substringAfter(": ").trim() }
            results.addView(label(ctx, "Already the best: $names", 12f, KC.muted).apply { setPadding(dp(ctx, 4), dp(ctx, 12), dp(ctx, 4), dp(ctx, 8)) })
        }
        wearAll.isEnabled = gear.isNotEmpty()
    }

    private fun boostRow(b: JSONObject): View {
        val ctx = requireContext()
        val cmd = b.optString("cmd")
        val (badge, title, detail) = describe(b)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            background = GradientDrawable().apply { cornerRadius = dp(ctx, 10).toFloat(); setStroke(dp(ctx, 1), KC.faint) }
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(ctx, badge, 11f, KC.accent, true).apply { letterSpacing = 0.08f })
        texts.addView(label(ctx, title, 15f, KC.text))
        if (detail.isNotEmpty()) texts.addView(label(ctx, detail, 12f, KC.muted))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val gain = b.optDouble("gain")
        if (!gain.isNaN() && gain != 0.0) {
            row.addView(label(ctx, "%+.1f".format(gain), 17f, if (gain > 0) KC.accent else KC.bad, true).apply { setPadding(dp(ctx, 8), 0, 0, 0) })
        }
        clickable(row) { tapped(b, cmd, title) }
        return LinearLayout(ctx).apply {
            setPadding(0, 0, 0, dp(ctx, 8))
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** A short verb, the thing itself and any extra detail, from KoLmafia's suggestion. */
    private fun describe(b: JSONObject): Triple<String, String, String> {
        val cmd = b.optString("cmd").trim()
        val verb = cmd.substringBefore(' ').lowercase()
        var text = b.optString("text").trim()
        // the gain is shown on the right; drop it from KoLmafia's text
        text = text.replace(Regex("""\s*\([+-]?[\d.]+\)\s*$"""), "").replace(Regex(""",\s*[+-]?[\d.]+\)\s*$"""), ")")
        val item = b.optString("item")
        if (b.optBoolean("equip") && item.isNotEmpty() && verb == "equip") {
            val slot = when (val s = b.optString("slot")) {
                "acc1", "acc2", "acc3" -> "accessory"
                "familiar" -> "familiar equipment"
                "" -> ""
                else -> s
            }
            return Triple(if (slot.isEmpty()) "WEAR" else "WEAR · ${slot.uppercase()}", item, if (cmd.contains(';')) cmd else "")
        }
        val badge = when (verb) {
            "equip" -> "WEAR"; "unequip" -> "TAKE OFF"; "cast" -> "CAST"; "use" -> "USE"; "eat" -> "EAT"; "drink" -> "DRINK"
            "chew" -> "CHEW"; "buy" -> "BUY"; "familiar" -> "FAMILIAR"; "shrug", "uneffect" -> "REMOVE EFFECT"
            "genie", "wish" -> "WISH"; else -> verb.uppercase()
        }
        val rest = text.substringAfter(' ', text)
        val title = rest.substringBefore(" (").trim().ifEmpty { text }
        val detail = if (rest.contains(" (")) rest.substringAfter(" (").removeSuffix(")") else ""
        // "unequip off-hand (hot plate)": the item is what you take off, the slot is where
        if (verb == "unequip" && detail.isNotEmpty()) return Triple("TAKE OFF · ${title.uppercase()}", detail, "")
        return Triple(badge, title, detail)
    }

    // ---- doing things ----

    /** Gear swaps, familiar swaps and skill casts happen straight away; anything else (eat, drink, use, buy, ...) asks once. */
    private fun needsConfirm(cmd: String): Boolean {
        if (cmd.contains(';')) return true
        return cmd.trim().substringBefore(' ').lowercase() !in setOf("equip", "unequip", "cast", "familiar", "enthrone", "bjornify", "fold")
    }

    private fun tapped(b: JSONObject, cmd: String, title: String) {
        if (busy) return
        val i = b.getInt("i")
        if (!needsConfirm(cmd)) { apply(i.toString(), b.optBoolean("equip"), "Done: $title"); return }
        ScrollSafeDialogBuilder(requireContext())
            .setTitle("${describe(b).first.lowercase().replaceFirstChar { it.uppercase() }} $title?")
            .setMessage("KoLmafia will run:\n$cmd")
            .setPositiveButton("Do it") { _, _ -> apply(i.toString(), b.optBoolean("equip"), "Done: $title") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun wearAllGear() {
        if (busy) return
        val gear = boosts.filter { it.optBoolean("equip") && it.optString("cmd").isNotBlank() }
        val cmds = gear.map { it.optString("cmd") }
        val go = { apply("all-gear", true, "Wearing the best gear for ${goalName()}") }
        if (cmds.none { needsConfirm(it) }) { go(); return }
        ScrollSafeDialogBuilder(requireContext())
            .setTitle("Make or buy gear?")
            .setMessage("Some of this gear has to be made or bought first:\n\n" + cmds.joinToString("\n"))
            .setPositiveButton("Do it") { _, _ -> go() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun apply(indexes: String, touchesGear: Boolean, done: String) {
        if (busy) return
        busy = true; wearAll.isEnabled = false
        status.text = "Working…"; status.setTextColor(KC.muted)
        results.alpha = 0.4f
        val checkpoint = touchesGear && !gearSaved
        KolEngineHost.maximizerApply(indexes, checkpoint) { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { null }
            activity?.runOnUiThread {
                busy = false
                if (!isAdded || view == null) return@runOnUiThread
                if (checkpoint) gearSaved = true
                if (o?.optBoolean("ok") == true) maximize(done) // maximize again, as the desktop does
                else { results.alpha = 1f; status.text = "Stopped: ${o?.optString("error") ?: json}"; status.setTextColor(KC.bad); wearAll.isEnabled = true }
            }
        }
    }

    private fun undoGear() {
        if (busy) return
        if (!gearSaved) { status.text = "Nothing to undo: no gear has changed here yet."; status.setTextColor(KC.muted); return }
        busy = true
        status.text = "Putting your gear back…"; status.setTextColor(KC.muted)
        KolEngineHost.execute("outfit checkpoint") {
            activity?.runOnUiThread {
                busy = false
                if (isAdded && view != null) { gearSaved = false; maximize("Gear put back") }
            }
        }
    }

    companion object {
        fun show(fm: FragmentManager) { MaximizerFragment().show(fm, "maximizer") }
    }
}
