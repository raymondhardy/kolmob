package com.example.kolmafiaandroid.fragments

import com.example.kolmafiaandroid.ui.KC

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.pickList
import com.example.kolmafiaandroid.ui.askQuantity
import com.example.kolmafiaandroid.ui.bar
import com.example.kolmafiaandroid.ui.card
import com.example.kolmafiaandroid.ui.clickable
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import com.example.kolmafiaandroid.ui.pick
import com.example.kolmafiaandroid.ui.row
import com.example.kolmafiaandroid.ui.tile
import com.example.kolmafiaandroid.ui.tileRow
import java.text.NumberFormat
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Dashboard: the logged-in character's live state, read from the real KoLmafia session. */
class DashboardFragment : Fragment() {
    private val green = 0xFF4CAF50.toInt()
    private val muted = KC.muted
    private var skillTurns = mapOf<String, Int>() // buff skill name -> turns per cast
    private var skillMp = mapOf<String, Long>()

    // Layout: a compact character header that is always visible, then sub-tabs that group the rest.
    private lateinit var headerBox: LinearLayout
    private lateinit var tabs: View
    private lateinit var bodyHost: android.widget.FrameLayout
    private lateinit var body: LinearLayout
    private var bodyScroll: ScrollView? = null
    private val itemsHostId = View.generateViewId()
    private var snapshot: JSONObject? = null
    private var tabIndex = 0
    private val tabNames = listOf("Overview", "Gear", "Skills", "Appetite", "Items")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        val wide = ctx.resources.configuration.screenWidthDp >= 600
        headerBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), 0) }
        tabs = com.example.kolmafiaandroid.ui.iconTabs(ctx, listOf(
            R.drawable.tab_overview to "Overview",
            R.drawable.tab_gear to "Gear",
            R.drawable.web_bolt to "Skills & buffs",
            R.drawable.tab_appetite to "Appetite",
            R.drawable.nav_inventory to "Items",
        ), tabIndex) { tabIndex = it; renderBody() }
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 12)) }
        bodyHost = android.widget.FrameLayout(ctx)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        if (!wide) {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(headerBox)
                addView(tabs)
                addView(bodyHost, LinearLayout.LayoutParams(match, 0, 1f))
            }
        }
        // tablets / landscape: the character header on the left, the tabs on the right
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ScrollView(ctx).apply { addView(headerBox) }, LinearLayout.LayoutParams(0, match, 1f))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(tabs)
                addView(bodyHost, LinearLayout.LayoutParams(match, 0, 1f))
            }, LinearLayout.LayoutParams(0, match, 2f))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch { KolEngineHost.loginState.collect { refresh() } }
        // a script, the diet planner or any other command may have eaten, chewed or used something: redraw when it says so
        viewLifecycleOwner.lifecycleScope.launch { KolEngineHost.stateChanged.collect { if (isResumed) refresh() } }
    }

    override fun onResume() {
        super.onResume()
        if (view != null) refresh()
    }

    private fun refresh() {
        KolEngineHost.snapshot { json ->
            activity?.runOnUiThread { if (isAdded && view != null) render(JSONObject(json)) }
        }
    }

    /** Like [act], but tells you how it went: the last thing KoLmafia printed while the command ran. */
    private fun actReport(command: String, done: String) {
        val seen = mutableListOf<String>()
        val job = viewLifecycleOwner.lifecycleScope.launch {
            KolEngineHost.lines.collect { l -> android.text.Html.fromHtml(l, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim().takeIf { it.isNotEmpty() }?.let { seen.add(it) } }
        }
        android.widget.Toast.makeText(requireContext(), "Working…", android.widget.Toast.LENGTH_SHORT).show()
        KolEngineHost.execute(command) {
            activity?.runOnUiThread {
                job.cancel()
                if (!isAdded) return@runOnUiThread
                refresh()
                val last = seen.lastOrNull()
                android.widget.Toast.makeText(requireContext(), if (last != null) "$done\n$last" else done, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Resting costs an adventure unless you have free rests left, so say so and ask first. */
    private fun restDialog() {
        KolEngineHost.restInfo { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val ctx = requireContext()
                if (!o.optBoolean("camp", true)) {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle("No campground")
                        .setMessage("KoLmafia says you don't have a campground to rest at right now.").setPositiveButton("OK", null).show()
                    return@runOnUiThread
                }
                val free = o.optInt("free")
                val full = o.optLong("hp") >= o.optLong("maxHp") && o.optLong("mp") >= o.optLong("maxMp")
                val msg = "HP ${o.optLong("hp")}/${o.optLong("maxHp")}, MP ${o.optLong("mp")}/${o.optLong("maxMp")}.\n\n" +
                    (if (free > 0) "You have $free free rest${if (free == 1) "" else "s"} left today, so this should cost no adventure."
                     else "You have no free rests left, so resting will spend 1 of your ${o.optInt("adv")} adventures.") +
                    (if (full) "\n\nYou are already at full HP and MP." else "")
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle("Rest at your campsite?").setMessage(msg)
                    .setPositiveButton("Rest") { _, _ -> actReport("rest", "Rested") }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    /** Run a KoLmafia command, then redraw with the new state. */
    /** Remembers what you wear now (for Undo), then lets KoLmafia's maximizer put on the best gear you own for the goal. */
    private fun equipBest(name: String, expr: String) {
        KolEngineHost.execute("checkpoint") {
            activity?.runOnUiThread { if (isAdded) actReport("maximize $expr", "Now wearing the best for: $name") }
        }
    }

    private fun act(command: String) {
        KolEngineHost.execute(command) { activity?.runOnUiThread { if (isAdded) refresh() } }
    }

    // ---- rendering ----

    private fun render(o: JSONObject) {
        snapshot = o
        val skills = (o.optJSONArray("skills") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        skillTurns = skills.filter { it.optInt("perCast") > 0 }.associate { it.getString("name") to it.getInt("perCast") }
        skillMp = skills.associate { it.getString("name") to it.optLong("mpCost") }
        renderHeader(o)
        if (tabIndex != ITEMS_TAB && tabIndex != QUESTS_TAB) renderBody() // the Items/Quests tabs hosts its own fragment: leave it alone on refresh
    }

    /** Name, level, HP/MP and the three numbers you check most often: always on screen. */
    private fun renderHeader(o: JSONObject) {
        val ctx = requireContext()
        headerBox.removeAllViews()
        if (!o.optBoolean("loggedIn")) return
        val nf = NumberFormat.getIntegerInstance()
        val head = card(ctx, headerBox)
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(label(ctx, o.optString("name"), 22f, KC.text, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(label(ctx, "L${o.optInt("level")} ${o.optString("class")}", 13f, green))
        head.addView(top)
        head.addView(label(ctx, o.optString("path"), 12f, muted))
        head.addView(bar(ctx, "HP", o.optLong("hp"), o.optLong("maxHp"), 0xFFE53935.toInt(), "tap to restore") { showRestore(true, o) }.apply { setPadding(0, dp(ctx, 8), 0, dp(ctx, 2)) })
        head.addView(bar(ctx, "MP", o.optLong("mp"), o.optLong("maxMp"), 0xFF1E88E5.toInt(), "tap to restore") { showRestore(false, o) })
        head.addView(label(ctx, "${nf.format(o.optLong("meat"))} meat   ·   ${o.optString("adventures")} adventures   ·   ${nf.format(o.optLong("turnsPlayed"))} turns", 13f).apply { setPadding(0, dp(ctx, 8), 0, 0) })
    }

    private fun renderBody() {
        val ctx = requireContext()
        val o = snapshot
        // leaving the Items tab: remove its fragment
        childFragmentManager.findFragmentByTag("items")?.let { if (tabIndex != ITEMS_TAB) childFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss() }
        childFragmentManager.findFragmentByTag("quests")?.let { if (tabIndex != QUESTS_TAB) childFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss() }
        bodyHost.removeAllViews()
        if (o == null || !o.optBoolean("loggedIn")) {
            bodyHost.addView(label(ctx, "Not logged in", 18f, bold = true).apply { setPadding(dp(ctx, 16), dp(ctx, 16), 0, 0) })
            return
        }
        if (tabIndex == QUESTS_TAB) {
            val host = android.widget.FrameLayout(ctx).apply { id = itemsHostId }
            bodyHost.addView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            childFragmentManager.beginTransaction().replace(itemsHostId, com.example.kolmafiaandroid.tools.QuestsFragment(), "quests").commitAllowingStateLoss()
            return
        }
        if (tabIndex == ITEMS_TAB) {
            // inventory, closet, storage and display case live here (the same screen as Tools > Items)
            val host = android.widget.FrameLayout(ctx).apply { id = itemsHostId }
            bodyHost.addView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            childFragmentManager.beginTransaction().replace(itemsHostId, com.example.kolmafiaandroid.tools.ItemsFragment(), "items").commitAllowingStateLoss()
            return
        }
        body.removeAllViews()
        (body.parent as? ViewGroup)?.removeView(body)
        val scroll = ScrollView(ctx).apply { addView(body) }
        bodyScroll = scroll
        bodyHost.addView(scroll, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        when (tabIndex) {
            0 -> overviewTab(o)
            1 -> gearTab(o)
            2 -> skillsTab(o)
            3 -> appetiteTab(o)
        }
    }

    private fun familiarList(o: JSONObject) = (o.optJSONArray("familiars") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    /** What you do most days: the day's routine and a couple of facts. */
    private fun overviewTab(o: JSONObject) {
        val ctx = requireContext()
        val daily = card(ctx, body, "Today")
        daily.addView(row(ctx, "Run breakfast", "KoLmafia's daily free actions", {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle("Run breakfast?")
                .setMessage("Runs KoLmafia's breakfast: the daily free actions you have turned on in its settings (this can use items and spend turns of free effects).")
                .setPositiveButton("Run") { _, _ -> actReport("breakfast", "Breakfast finished") }.setNegativeButton("Cancel", null).show()
        }, green))
        daily.addView(row(ctx, "Rest at your campsite", "restores HP and MP; asks first", { restDialog() }, green))
        daily.addView(row(ctx, "Refresh everything from KoL", null, { act("refresh all") }, green))
        val facts = card(ctx, body, "Right now")
        val familiars = familiarList(o)
        facts.addView(tileRow(ctx,
            tile(ctx, "FAMILIAR", o.optString("familiar").ifEmpty { "none" }, if (familiars.isNotEmpty()) { { chooseFamiliar(familiars) } } else null),
            tile(ctx, "LAST ADVENTURE", o.optString("lastAdventure").ifEmpty { "-" })
        ))
        body.addView(com.example.kolmafiaandroid.ui.toolLinks(ctx, requireActivity().supportFragmentManager, "overview"))
    }

    /** Equipment, outfits and your familiar: everything you wear or bring along. */
    private fun gearTab(o: JSONObject) {
        val ctx = requireContext()
        val eqCard = card(ctx, body, "Equipment")
        val eq = o.optJSONArray("equipment") ?: JSONArray()
        for (i in 0 until eq.length()) eqCard.addView(equipmentRow(eq.getJSONObject(i)))
        val best = card(ctx, body, "Best gear for…")
        best.addView(label(ctx, "Tap a goal to wear the best gear you own for it (ties go to the stronger item).", 12f, muted).apply { setPadding(0, 0, 0, dp(ctx, 6)) })
        for ((name, expr) in com.example.kolmafiaandroid.tools.GearGoals.list) {
            best.addView(row(ctx, name, "", { equipBest(name, expr) }, green))
        }
        best.addView(row(ctx, "Something else…", "Type your own goal", {
            val input = android.widget.EditText(ctx).apply { hint = "e.g. item drop, -combat"; maxLines = 1 }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle("Best gear for")
                .setView(input)
                .setPositiveButton("Wear it") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { equipBest(it, it) } }
                .setNegativeButton("Cancel", null).show()
        }, green))
        best.addView(row(ctx, "Undo the last change", "Back to what you wore before", { act("outfit checkpoint") }, green))
        val more = card(ctx, body, "Outfits and familiar")
        // KoLmafia lists a pseudo-outfit " - No Change - "; it is not wearable
        val outfits = (o.optJSONArray("outfits") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) } }.filter { !it.trim().startsWith("-") }
        if (outfits.isNotEmpty()) {
            more.addView(row(ctx, "Wear an outfit", "${outfits.size} saved", { pick("Outfits", outfits.sorted()) { i -> act("outfit ${outfits.sorted()[i]}") } }, green))
        }
        more.addView(row(ctx, "Manage saved outfits", "Rename or delete, in KoL's outfit page", { (activity as? com.example.kolmafiaandroid.MainActivity)?.openGamePage("account_manageoutfits.php") }, green))
        more.addView(row(ctx, "Save current gear as an outfit", "Name it and wear it again later", {
            val input = android.widget.EditText(ctx).apply { hint = "Outfit name"; maxLines = 1 }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle("Save outfit")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) act("outfit save $name")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }, green))
        val familiars = familiarList(o)
        if (familiars.isNotEmpty()) {
            more.addView(row(ctx, "Familiar: " + o.optString("familiar").ifEmpty { "none" }, "${familiars.size} owned", { chooseFamiliar(familiars) }, green))
        }
        body.addView(com.example.kolmafiaandroid.ui.toolLinks(ctx, requireActivity().supportFragmentManager, "gear"))
    }

    /** Castable skills, buffs first. */
    private fun skillsTab(o: JSONObject) {
        val ctx = requireContext()
        buffsSection(o)
        val skills = (o.optJSONArray("skills") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        if (skills.isEmpty()) { body.addView(label(ctx, "No castable skills.", 15f, muted)); body.addView(com.example.kolmafiaandroid.ui.toolLinks(ctx, requireActivity().supportFragmentManager, "skills")); return }
        val mp = o.optLong("mp")
        fun add(card: LinearLayout, list: List<JSONObject>) {
            for (s in list.sortedBy { it.getString("name") }) {
                val name = s.getString("name")
                val cost = s.optLong("mpCost")
                val per = s.optInt("perCast")
                val v = row(ctx, name, (if (per > 0) "$per turns · " else "") + "$cost MP", { castDialog(name, cost, mp) })
                val sid = s.optInt("id")
                if (sid > 0) v.setOnLongClickListener {
                    KolEngineHost.describe("skill", sid) { text ->
                        activity?.runOnUiThread { if (isAdded) com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle(name).setMessage(text).setPositiveButton("Close", null).show() }
                    }
                    true
                }
                card.addView(v)
            }
        }
        val special = skills.filter { it.optString("kind").isNotEmpty() }
        for (kind in listOf("Songs", "Expressions", "Walks")) {
            val list = special.filter { it.optString("kind") == kind }
            if (list.isNotEmpty()) add(card(ctx, body, kind), list)
        }
        val buffs = skills.filter { it.optBoolean("buff") && it.optString("kind").isEmpty() }
        val others = skills.filter { !it.optBoolean("buff") && it.optString("kind").isEmpty() }
        if (buffs.isNotEmpty()) add(card(ctx, body, "Buffs you can cast"), buffs)
        if (others.isNotEmpty()) add(card(ctx, body, "Other skills"), others)
        val passives = (o.optJSONArray("passives") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) } }.sorted()
        if (passives.isNotEmpty()) {
            card(ctx, body, "Always on").addView(row(ctx, "Passive skills", "${passives.size} · tap to browse", { pick("Passive skills", passives) { } }))
        }
        body.addView(com.example.kolmafiaandroid.ui.toolLinks(ctx, requireActivity().supportFragmentManager, "skills"))
    }

    /** Your active effects (with icons) and ways to add more; the top of the Skills & buffs tab. */
    private fun buffsSection(o: JSONObject) {
        val ctx = requireContext()
        val fx = (o.optJSONArray("effects") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val fm = requireActivity().supportFragmentManager
        if (fx.isEmpty()) {
            body.addView(label(ctx, "No active effects.", 15f, muted))
        } else {
            val fxCard = card(ctx, body, "Active effects  ·  tap one to extend or remove it")
            for (e in fx) {
                val extendable = e.optString("action").isNotEmpty()
                val line = row(ctx, e.getString("name"), "${e.getInt("turns")} turns", { effectDialog(e, o.optLong("mp")) }, if (extendable) KC.text else muted)
                val box = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
                val icon = android.widget.ImageView(ctx)
                com.example.kolmafiaandroid.ui.KolImages.load(icon, e.optString("img"))
                box.addView(icon, LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30)).apply { marginEnd = dp(ctx, 10) })
                box.addView(line, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                fxCard.addView(box)
            }
        }
        val add = card(ctx, body, "Add a buff  ·  your own buff skills are listed below")
        add.addView(row(ctx, "Find buffs for a goal", "Skills to cast and items to use", { com.example.kolmafiaandroid.tools.BoostFragment.show(fm) }, green))
        add.addView(row(ctx, "Ask a buffbot", "Buy buffs from a public bot", { com.example.kolmafiaandroid.tools.BuffRequestFragment.show(fm) }, green))
        add.addView(row(ctx, "Moods", "Buffs kept up automatically", { com.example.kolmafiaandroid.tools.MoodFragment.show(fm) }, green))
    }

    /** Stomach, liver and spleen with pickers for what to eat, drink and chew. */
    private fun appetiteTab(o: JSONObject) {
        val ctx = requireContext()
        val organs = o.optJSONObject("organs") ?: return
        val app = card(ctx, body, "Appetite")
        app.addView(bar(ctx, "Stomach", organs.optLong("fullness"), organs.optLong("fullnessLimit"), 0xFFFFB300.toInt(), "tap to eat") {
            chooseConsumable("Eat", "eat", o.optJSONArray("food"), organs.optInt("fullness"), organs.optInt("fullnessLimit"), "fullness")
        })
        app.addView(bar(ctx, "Liver", organs.optLong("drunk"), organs.optLong("drunkLimit"), 0xFF8E24AA.toInt(), "tap to drink") {
            chooseConsumable("Drink", "drink", o.optJSONArray("booze"), organs.optInt("drunk"), organs.optInt("drunkLimit"), "drunkenness")
        })
        app.addView(bar(ctx, "Spleen", organs.optLong("spleen"), organs.optLong("spleenLimit"), 0xFF00897B.toInt(), "tap to chew") {
            chooseConsumable("Chew", "chew", o.optJSONArray("spleenItems"), organs.optInt("spleen"), organs.optInt("spleenLimit"), "spleen")
        })
        val fm = requireActivity().supportFragmentManager
        val plan = card(ctx, body, "Plan and make")
        fun link(title: String, sub: String, go: () -> Unit) {
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(ctx, 8), 0, dp(ctx, 8)) }
            r.addView(label(ctx, title, 16f, KC.text)); r.addView(label(ctx, sub, 12f, muted))
            clickable(r) { go() }
            plan.addView(r)
        }
        link("Diet planner", "Best food, drink and spleen for the turns you want") { com.example.kolmafiaandroid.tools.DietFragment.show(fm) }
        link("Craft food and drinks", "Cook and mix what you can make from what you have") { com.example.kolmafiaandroid.tools.CraftFragment.show(fm, "food") }
        link("Sweet Synthesis", "Pick an effect, preview, do it") { com.example.kolmafiaandroid.tools.SynthesizeFragment.show(fm) }
    }

    private companion object {
        const val ITEMS_TAB = 4
        const val QUESTS_TAB = 5
    }

    private fun equipmentRow(slot: JSONObject): View {
        val ctx = requireContext()
        val current = slot.optString("item")
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
        }
        val icon = android.widget.ImageView(ctx).apply {
            setBackgroundColor(if (current.isEmpty()) 0x14FFFFFF else 0x00000000)
        }
        com.example.kolmafiaandroid.ui.KolImages.load(icon, slot.optString("img"))
        r.addView(icon, LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)).apply { marginEnd = dp(ctx, 12) })
        val text = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label(ctx, slot.getString("label"), 12f, muted))
        text.addView(label(ctx, current.ifEmpty { "Empty" }, 15f, if (current.isEmpty()) KC.hint else KC.text))
        r.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(label(ctx, "›", 20f, muted))
        clickable(r) { chooseItem(slot) }
        return r
    }

    /** Equipment picker: what can go in this slot; picking runs KoLmafia's equip/unequip. */
    private fun chooseItem(slot: JSONObject) {
        val cli = slot.getString("slot")
        val current = slot.optString("item")
        val opts = slot.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
        val rows = mutableListOf<com.example.kolmafiaandroid.ui.PickRow>()
        if (current.isNotEmpty()) rows += com.example.kolmafiaandroid.ui.PickRow("Take it off", "Currently: $current", tag = "")
        opts.filter { it.getString("name") != current }.sortedBy { it.getString("name").lowercase() }.forEach {
            val bits = mutableListOf<String>()
            if (it.optInt("count") > 1) bits += "×${it.optInt("count")}"
            if (it.optInt("power") > 0) bits += "power ${it.optInt("power")}"
            val req = it.optString("req")
            if (req.isNotEmpty() && !req.equals("none", true) && !req.startsWith("Mus: 0")) bits += req
            rows += com.example.kolmafiaandroid.ui.PickRow(it.getString("name"), bits.joinToString(" · "), tag = it.getString("name"), img = it.optString("img"))
        }
        rows += com.example.kolmafiaandroid.ui.PickRow("Show everything I own…", "Includes closet, storage and what you can't wear yet", tag = "\u0000all")
        if (cli == "familiar") rows += com.example.kolmafiaandroid.ui.PickRow("Open KoL's familiar page", "For familiars whose items aren't listed here", tag = "\u0000page")
        pickList(slot.getString("label"), rows) { r ->
            val n = r.tag as String
            if (n == "\u0000all") { showAllGear(slot.getString("label"), cli); return@pickList }
            if (n == "\u0000page") { (activity as? com.example.kolmafiaandroid.MainActivity)?.openGamePage("familiar.php"); return@pickList }
            act(if (n.isEmpty()) "unequip $cli" else "equip $cli $n")
        }
    }

    /** Every item that fits the slot, from inventory, closet and storage; the ones you can't wear yet are marked. */
    private fun showAllGear(label: String, cli: String) {
        KolEngineHost.gearAll(cli) { json ->
            val a = try { JSONObject(json).optJSONArray("items") ?: JSONArray() } catch (_: Exception) { JSONArray() }
            val items = (0 until a.length()).map { a.getJSONObject(it) }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (items.isEmpty()) { android.widget.Toast.makeText(requireContext(), "Nothing you own fits $label", android.widget.Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                val rows = items.sortedWith(compareBy({ !it.optBoolean("can") }, { it.getString("name").lowercase() })).map {
                    val bits = mutableListOf<String>()
                    bits += it.getString("where")
                    if (it.optInt("count") > 1) bits += "×${it.optInt("count")}"
                    if (it.optInt("power") > 0) bits += "power ${it.optInt("power")}"
                    val req = it.optString("req")
                    if (req.isNotEmpty() && !req.equals("none", true) && !req.startsWith("Mus: 0")) bits += "needs $req"
                    com.example.kolmafiaandroid.ui.PickRow(it.getString("name"), bits.joinToString(" · "), locked = !it.optBoolean("can"), tag = it, img = it.optString("img"))
                }
                pickList("$label: everything you own", rows, "", "Show items KoLmafia says won't fit") { r ->
                    val it = r.tag as JSONObject
                    val name = it.getString("name")
                    val pre = when (it.getString("where")) { "closet" -> "closet take 1 $name; "; "storage" -> "pull 1 $name; "; else -> "" }
                    if (cli == "familiar" && !it.optBoolean("can")) {
                        // KoLmafia's own check refuses this item for the familiar, but KoL may still accept it: ask KoL directly
                        act(pre + "ash visit_url(\"inv_equip.php?action=equip&whichitem=${it.optInt("id")}&ajax=1\"); refresh equipment")
                    } else act(pre + "equip $cli $name")
                }
            }
        }
    }

    private fun chooseFamiliar(familiars: List<JSONObject>) {
        val prefs = requireContext().getSharedPreferences("kolmob", Context.MODE_PRIVATE)
        val favs = (prefs.getString("fam_favs", "") ?: "").split("|").filter { it.isNotEmpty() }.toSet()
        // two sections: favorites first, then everything else; each by level (highest first), then A-Z
        val sorted = familiars.sortedWith(compareByDescending<JSONObject> { it.optInt("weight") }.thenBy { it.getString("race").lowercase() })
        fun rowFor(f: JSONObject, group: String): com.example.kolmafiaandroid.ui.PickRow {
            val name = f.optString("name")
            return com.example.kolmafiaandroid.ui.PickRow(
                f.getString("race") + (if (f.optBoolean("current")) "  ✓" else ""),
                (if (name.isNotEmpty()) "$name · " else "") + "${f.optInt("weight")} lb",
                group = group, tag = f.getString("race"), img = f.optString("img"))
        }
        val rows = mutableListOf(com.example.kolmafiaandroid.ui.PickRow("Sorted by level, then name",
            "Long-press a familiar to add or remove it from Favorites", tag = "\u0000sort"))
        sorted.filter { it.getString("race") in favs }.forEach { rows += rowFor(it, "★ Favorites") }
        sorted.filter { it.getString("race") !in favs }.forEach { rows += rowFor(it, "All other familiars") }
        pickList("Choose a familiar", rows, onLongPick = { r ->
            val race = r.tag as? String ?: return@pickList
            if (race == "\u0000sort") return@pickList
            val now = (prefs.getString("fam_favs", "") ?: "").split("|").filter { it.isNotEmpty() }
            val next = if (race in now) now - race else now + race
            prefs.edit().putString("fam_favs", next.joinToString("|")).apply()
            android.widget.Toast.makeText(requireContext(), if (race in now) "Removed $race from favorites" else "Added $race to Favorites", android.widget.Toast.LENGTH_SHORT).show()
        }) { r ->
            val race = r.tag as String
            if (race == "\u0000sort") chooseFamiliar(familiars) else act("familiar $race")
        }
    }

    /** Eat / drink / chew picker for what is in the inventory, then "how many". */
    private fun chooseConsumable(verb: String, cmd: String, items: JSONArray?, used: Int, limit: Int, unit: String) {
        val list = (0 until (items?.length() ?: 0)).map { items!!.getJSONObject(it) }.sortedBy { it.getString("name").lowercase() }
        if (list.isEmpty()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle(verb).setMessage("Nothing in your inventory to $cmd.").setPositiveButton("OK", null).show()
            return
        }
        val labels = list.map {
            val adv = it.optDouble("adv", 0.0)
            val q = it.optString("quality").lowercase().takeIf { s -> s.isNotEmpty() && s != "none" && s != "null" }
            "${it.getString("name")} ×${it.getInt("count")}   (${it.getInt("size")} $unit" +
                (if (adv > 0) ", ~${"%.1f".format(adv)} adv, ${"%.1f".format(adv / it.getInt("size").coerceAtLeast(1))}/$unit" else "") +
                (if (q != null) ", $q" else "") + ")"
        }
        pick("$verb  ($used / $limit $unit used)", labels, images = list.map { it.optString("img") }) { i ->
            val it = list[i]
            val size = it.getInt("size").coerceAtLeast(1)
            val room = ((limit - used) / size).coerceAtLeast(0)
            val max = minOf(it.getInt("count"), if (room > 0) room else it.getInt("count"))
            val note = if (room == 0) "\nYou are full for this one; KoLmafia may refuse." else "\nFits ${room}× in what is left."
            askQuantity(verb + " " + it.getString("name"), "You have ${it.getInt("count")}. Each is $size $unit.$note", max, verb) { n ->
                act("$cmd $n ${it.getString("name")}")
            }
        }
    }

    private fun castDialog(name: String, cost: Long, mp: Long) {
        val affordable = if (cost > 0) (mp / cost).toInt() else 99
        val per = skillTurns[name]
        val extra = if (per != null) "\nEach cast gives $per turns." else ""
        askQuantity("Cast $name", "Costs $cost MP per cast; you can afford $affordable now.$extra", affordable.coerceAtLeast(1), "Cast") { n ->
            if (per == null) act("cast $n $name") else chooseBuffTarget(name, n)
        }
    }

    /** Buff skills can be cast on yourself or on another player (`cast N skill on player`). */
    private fun chooseBuffTarget(name: String, n: Int) {
        val ctx = requireContext()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Cast $name")
            .setItems(arrayOf("On myself", "On another player…")) { _, which ->
                if (which == 0) { act("cast $n $name"); return@setItems }
                val input = android.widget.EditText(ctx).apply { hint = "Player name"; maxLines = 1 }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    .setTitle("Cast on whom?")
                    .setView(input)
                    .setPositiveButton("Cast") { _, _ ->
                        val who = input.text.toString().trim()
                        if (who.isNotEmpty()) act("cast $n $name on $who")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .show()
    }

    /** Active-effect actions: extend (recast / reuse the source) or remove. */
    private fun effectDialog(e: JSONObject, mp: Long) {
        val name = e.getString("name")
        val turns = e.getInt("turns")
        val action = e.optString("action")
        val m = Regex("^(cast|use)\\s+\\d+\\s+(.+)$").find(action)
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (m != null) {
            val verb = m.groupValues[1]
            val source = m.groupValues[2]
            val per = skillTurns[source] ?: 0
            val cost = skillMp[source] ?: 0
            fun run(n: Int) = act("$verb $n $source")
            fun turnsText(n: Int) = if (per > 0) " (+${n * per} turns)" else ""
            options += "Extend: 1 ${if (verb == "cast") "cast" else "use"} of $source${turnsText(1)}" to { run(1) }
            options += "Extend: 5×${turnsText(5)}" to { run(5) }
            options += "Extend: 10×${turnsText(10)}" to { run(10) }
            if (per > 0) {
                for (target in listOf(100, 200, 500)) {
                    if (turns < target) {
                        val n = (target - turns + per - 1) / per
                        val mpNote = if (cost > 0) ", ${n * cost} MP" + (if (n * cost > mp) " - more than you have" else "") else ""
                        options += "Extend to $target turns ($n casts$mpNote)" to { run(n) }
                    }
                }
            }
            options += "Extend: custom amount…" to {
                askQuantity("Extend $name", "How many times to $verb $source?", if (cost > 0) (mp / cost).toInt().coerceAtLeast(1) else 99, "Go") { run(it) }
            }
        } else if (action.isNotEmpty()) {
            options += "Extend: $action" to { act(action) }
        }
        options += "Remove this effect" to { act("uneffect $name") }
        pick("$name  ($turns turns)", options.map { it.first }, searchable = false) { options[it].second() }
    }

    /** HP/MP restore picker: auto-restore, rest, and every item/skill KoLmafia knows heals this stat. */
    private fun showRestore(hp: Boolean, o: JSONObject) {
        val stat = if (hp) "HP" else "MP"
        val cur = if (hp) o.optLong("hp") else o.optLong("mp")
        val max = if (hp) o.optLong("maxHp") else o.optLong("maxMp")
        class Entry(val text: String, val command: String)
        val entries = mutableListOf(Entry("Auto-restore to full", "restore ${stat.lowercase()}"), Entry("Rest at your campsite", "rest"))
        val arr = o.optJSONArray(if (hp) "restoreHp" else "restoreMp") ?: JSONArray()
        for (e in (0 until arr.length()).map { arr.getJSONObject(it) }.sortedByDescending { it.optLong("avg") }) {
            if (e.getString("kind") == "item") {
                entries.add(Entry("${e.getString("name")} ×${e.getInt("count")}   (+${e.optLong("avg")} $stat)", "use 1 ${e.getString("name")}"))
            } else {
                entries.add(Entry("${e.getString("name")}   (+${e.optLong("avg")} $stat, ${e.optLong("mpCost")} MP)", "cast 1 ${e.getString("name")}"))
            }
        }
        pick("Restore $stat  ($cur / $max)", entries.map { it.text }, searchable = false) { i ->
            if (i == 0) autoRestore(hp) else act(entries[i].command)
        }
    }

    /**
     * KoLmafia's own restore first (items, skills, free rests you enabled). If that left you still hurt
     * (its default list has no free rest), fall back to resting at the campsite.
     */
    private fun autoRestore(hp: Boolean) {
        val stat = if (hp) "hp" else "mp"
        KolEngineHost.execute("restore $stat") {
            KolEngineHost.snapshot { json ->
                val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
                val cur = if (hp) o.optLong("hp") else o.optLong("mp")
                val max = if (hp) o.optLong("maxHp") else o.optLong("maxMp")
                if (max > 0 && cur < max) {
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        android.widget.Toast.makeText(requireContext(), "Still at $cur / $max, resting at your campsite…", android.widget.Toast.LENGTH_SHORT).show()
                        act("rest")
                    }
                } else activity?.runOnUiThread { if (isAdded) render(o) }
            }
        }
    }
}
