package com.example.kolmafiaandroid.tools

import com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.MainActivity
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.KC
import com.example.kolmafiaandroid.ui.card
import com.example.kolmafiaandroid.ui.clickable
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import org.json.JSONObject

/**
 * Settings, in pages: a home list, and a page each for the app, breakfast and the relay browser (the Game tab).
 * Every setting is a switch you tap, a drop-down you pick from, or a value you type, with a line saying what it does.
 * The full list of KoLmafia preferences is still one tap away.
 */
class SettingsFragment : DialogFragment() {
    private lateinit var titleView: TextView
    private lateinit var backView: TextView
    private lateinit var body: FrameLayout
    private val stack = ArrayList<String>()
    private var breakfastWhich = "Hardcore"
    private var lists: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ctx.getColor(R.color.colorBackground))
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 8), dp(ctx, 12), dp(ctx, 16), dp(ctx, 8))
        }
        backView = label(ctx, "‹", 30f, KC.text, true).apply {
            setPadding(dp(ctx, 12), 0, dp(ctx, 16), dp(ctx, 4))
            setOnClickListener { goBack() }
        }
        titleView = label(ctx, "Settings", 20f, KC.text, true)
        header.addView(backView)
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)
        body = FrameLayout(ctx)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        stack.add("home")
        render()
        return root
    }

    override fun onStart() {
        super.onStart()
        dialog?.setOnKeyListener { _, keyCode, ev ->
            if (keyCode == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) { goBack(); true } else false
        }
    }

    private fun goBack() {
        if (stack.size > 1) { stack.removeAt(stack.size - 1); render() } else dismissAllowingStateLoss()
    }

    private fun open(page: String) { stack.add(page); render() }

    private fun render() {
        val ctx = requireContext()
        val page = stack.last()
        body.removeAllViews()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 4), dp(ctx, 16), dp(ctx, 24))
        }
        body.addView(ScrollView(ctx).apply { addView(col) })
        titleView.text = when (page) {
            "app" -> "App and account"
            "breakfast" -> "Breakfast"
            "recovery" -> "HP and MP recovery"
            "relay" -> "Relay browser"
            else -> "Settings"
        }
        when (page) {
            "app" -> appPage(col)
            "breakfast" -> breakfastPage(col)
            "recovery" -> recoveryPage(col)
            "relay" -> relayPage(col)
            else -> homePage(col)
        }
    }

    // ---- pages ----

    private fun homePage(col: LinearLayout) {
        val ctx = requireContext()
        val c = card(ctx, col)
        navRow(c, "App and account", "Theme, screen, chat and logs") { open("app") }
        navRow(c, "Breakfast", "What KoLmafia does each day: skills, clan, garden, items") { open("breakfast") }
        navRow(c, "HP and MP recovery", "When and how KoLmafia restores health and mana, and mana burning") { open("recovery") }
        navRow(c, "Relay browser", "How the Game tab looks, links, warnings and helper scripts") { open("relay") }
        navRow(c, "All preferences", "Every KoLmafia preference, searchable") {
            PreferencesFragment.show(parentFragmentManager)
        }
        col.addView(label(ctx, (activity as? MainActivity)?.versionLine() ?: "", 11f, KC.muted).apply { setPadding(dp(ctx, 4), dp(ctx, 8), 0, 0) })
    }

    private fun appPage(col: LinearLayout) {
        val ctx = requireContext()
        val c = card(ctx, col)
        (activity as? MainActivity)?.buildAppSettings(c)
    }

    private fun breakfastPage(col: LinearLayout) {
        val ctx = requireContext()
        val run = card(ctx, col)
        navRow(run, "Run breakfast now", "Does everything turned on below, once") {
            ScrollSafeDialogBuilder(ctx).setTitle("Run breakfast?")
                .setMessage("Runs KoLmafia's breakfast with the settings below. It can use items and spend turns of free effects.")
                .setPositiveButton("Run") { _, _ -> (activity as? androidx.fragment.app.FragmentActivity)?.let { ScriptRunSheet.run(it, "Breakfast", "breakfast") } }
                .setNegativeButton("Cancel", null).show()
        }
        val l = lists
        if (l == null) {
            col.addView(label(ctx, "Loading…", 13f, KC.muted).apply { setPadding(dp(ctx, 4), dp(ctx, 8), 0, 0) })
            KolEngineHost.breakfastLists { json ->
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    lists = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
                    if (stack.last() == "breakfast") render()
                }
            }
            return
        }
        val which = card(ctx, col, "Which breakfast")
        val whichNames = listOf("Hardcore" to "In Ronin (hardcore)", "Softcore" to "After Ronin (softcore)")
        which.addView(label(ctx, "KoLmafia uses the first set while you are still in Ronin or hardcore, and the second once you can interact.", 12f, KC.muted).apply { setPadding(0, 0, 0, dp(ctx, 6)) })
        spinnerRow(which, "Edit the settings for", null, whichNames.map { it.second }, whichNames.indexOfFirst { it.first == breakfastWhich }.coerceAtLeast(0)) { i ->
            val pick = whichNames[i].first
            if (pick != breakfastWhich) { breakfastWhich = pick; render() }
        }
        val s = breakfastWhich
        val daily = card(ctx, col, "Daily skills and summons")
        multiRow(daily, "Skills to cast", "Free-ish daily skills cast during breakfast", "breakfast$s", strings(l, "skills"))
        if (s == "Hardcore") choiceRow(daily, "Tome summons", "Which Tome skill to use (none, all, or one)", "tomeSkills$s", listOf("none" to "None", "all" to "All") + strings(l, "tomes").map { it to it })
        choiceRow(daily, "Libram summons", "Which Libram skill to use", "libramSkills$s", listOf("none" to "None", "all" to "All") + strings(l, "librams").map { it to it })
        choiceRow(daily, "Grimoire summons", "Which Grimoire skill to use", "grimoireSkills$s", listOf("none" to "None", "all" to "All") + strings(l, "grimoires").map { it to it })
        toggleRow(daily, "Honor path restrictions", "Skip summons your current path does not allow", "pathedSummons$s")
        val camp = card(ctx, col, "Campground and clan")
        choiceRow(camp, "Garden", "What to harvest from your garden", "harvestGarden$s", listOf("none" to "Nothing", "any" to "Anything") + strings(l, "crops").map { it to it })
        toggleRow(camp, "Plant mushrooms", "Plant your mushroom plot", "autoPlant$s")
        toggleRow(camp, "Visit the clan rumpus room", "Use the free rumpus room items", "visitRumpus$s")
        toggleRow(camp, "Visit the clan VIP lounge", "Collect the daily VIP lounge items", "visitLounge$s")
        val extra = card(ctx, col, "Items and extras")
        toggleRow(extra, "Recover HP and MP at login", "Rest and restore when breakfast starts", "loginRecovery$s")
        toggleRow(extra, "Get hermit clovers", "Trade for the day's clovers", "grabClovers$s")
        toggleRow(extra, "Read the guild manual", "Read your guild's manual", "readManual$s")
        toggleRow(extra, "Use once-a-day items", "Use the small daily gifts and toys", "useCrimboToys$s")
        toggleRow(extra, "Check the Jackass Plumber", "Check the arcade's Jackass Plumber game", "checkJackass$s")
        toggleRow(extra, "Make Pocket Wishes", "Use the genie bottle's daily wishes", "makePocketWishes$s")
        toggleRow(extra, "Have a Boxing Daydream", "Take the daily boxing daydream", "haveBoxingDaydream$s")
        toggleRow(extra, "Harvest batteries", "Collect from the potted power plant", "harvestBatteries$s")
        toggleRow(extra, "Use the Book of Every Skill", "Read it for the day's skill", "useBookOfEverySkill$s")
        toggleRow(extra, "Make handheld radios", "Make the day's handheld radios", "makeHandheldRadios$s")
        val always = card(ctx, col, "Always (hardcore or not)")
        multiRow(always, "Skills to cast", "Skills that cost no MP, so they are safe to use every time", "breakfastAlways", strings(l, "always"))
    }

    private fun recoveryPage(col: LinearLayout) {
        val ctx = requireContext()
        val steps = listOf("0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0")
        fun pctRow(parent: LinearLayout, title: String, desc: String, key: String, withOff: Boolean) {
            val labels = (if (withOff) listOf("Off") else emptyList()) + steps.map { "${(it.toDouble() * 100).toInt()}%" }
            val values = (if (withOff) listOf("-0.05") else emptyList()) + steps
            val cur = KolEngineHost.getPref(key).toDoubleOrNull() ?: values.last().toDouble()
            val i = values.indices.minByOrNull { Math.abs(values[it].toDouble() - cur) } ?: 0
            spinnerRow(parent, title, desc, labels, i) { pos -> KolEngineHost.setPref(key, values[pos]) }
        }
        val hp = card(ctx, col, "Health")
        pctRow(hp, "Restore HP when it falls below", "KoLmafia heals before an adventure or fight if your HP is under this", "hpAutoRecovery", true)
        pctRow(hp, "Restore HP up to", "How full to heal once it starts", "hpAutoRecoveryTarget", false)
        val mp = card(ctx, col, "Mana")
        pctRow(mp, "Restore MP when it falls below", "KoLmafia restores mana when your MP is under this", "mpAutoRecovery", true)
        pctRow(mp, "Restore MP up to", "How full to restore once it starts", "mpAutoRecoveryTarget", false)
        val burn = card(ctx, col, "Mana burning")
        pctRow(burn, "Burn extra mana above", "Cast your buffs and spare skills while MP is over this", "manaBurningThreshold", true)
        pctRow(burn, "Start burning at", "Only start burning once MP reaches this", "manaBurningTrigger", true)
        val items = card(ctx, col, "What it may use")
        val note = label(ctx, "Loading choices…", 12f, KC.muted)
        items.addView(note)
        KolEngineHost.recoveryLists { json ->
            activity?.runOnUiThread {
                if (!isAdded || stack.last() != "recovery") return@runOnUiThread
                items.removeView(note)
                val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
                fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                multiRow(items, "HP restores", "Items, skills and places used to heal", "hpAutoRecoveryItems", list("hp"), ";")
                multiRow(items, "MP restores", "Items and skills used to restore mana", "mpAutoRecoveryItems", list("mp"), ";")
                textRow(items, "Recovery script", "A script to run instead of KoLmafia's own recovery", "recoveryScript")
                textRow(items, "Between-battle script", "A script to run before each adventure", "betweenBattleScript")
            }
        }
    }

    private fun relayPage(col: LinearLayout) {
        val ctx = requireContext()
        val look = card(ctx, col, "Game tab display (your KoL account)")
        look.addView(label(ctx, "These are KoL's own account options, so they also change the website.", 12f, KC.muted).apply { setPadding(0, 0, 0, dp(ctx, 6)) })
        val loading = label(ctx, "Loading from KoL…", 12f, KC.muted)
        look.addView(loading)
        KolEngineHost.interfaceOptions { json ->
            activity?.runOnUiThread {
                if (!isAdded || stack.last() != "relay") return@runOnUiThread
                look.removeView(loading)
                val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
                if (!o.optBoolean("ok")) { look.addView(label(ctx, "Could not read your KoL options.", 12f, KC.muted)); return@runOnUiThread }
                val menus = listOf("fancy" to "Fancy (drop-down menus)", "compact" to "Compact (one Adventure menu)", "normal" to "Normal (plain links)")
                spinnerRow(look, "Top menu style", "The navigation bar across the top of the game", menus.map { it.second }, menus.indexOfFirst { it.first == o.optString("menu") }.coerceAtLeast(0)) { pos ->
                    KolEngineHost.setAccountOption("menu", menus[pos].first)
                }
                for ((flag, title) in listOf(
                    "flag_compactchar" to "Compact character pane",
                    "flag_shortcharpane" to "Short character pane",
                    "flag_compacteffects" to "Compact effect display",
                    "flag_slimhpmpdisplay" to "Slim HP/MP/Meat/Adv display"
                )) {
                    toggleRow(look, title, null, null, o.optBoolean(flag)) { on -> KolEngineHost.setAccountOption(flag, if (on) "1" else "0") }
                }
            }
        }
        val links = card(ctx, col, "Links and helpers")
        toggleRow(links, "Use links", "Add use links next to items you can use", "relayAddsUseLinks")
        toggleRow(links, "Wiki links", "Add wiki links next to items and monsters", "relayAddsWikiLinks")
        toggleRow(links, "Restore links", "Add HP and MP restore links to the character pane", "relayAddsRestoreLinks")
        toggleRow(links, "Up-arrow links", "Add links to use effects and items again", "relayAddsUpArrowLinks")
        toggleRow(links, "Quick scripts", "Show your quick-script buttons", "relayAddsQuickScripts")
        toggleRow(links, "Custom combat links", "Add combat shortcuts to fights", "relayAddsCustomCombat")
        toggleRow(links, "Disco Bandit helper", "Show the Disco Bandit combat helper", "relayAddsDiscoHelper")
        val info = card(ctx, col, "Information and warnings")
        toggleRow(info, "Show spoilers", "Show monster and item details KoL does not tell you", "relayShowSpoilers")
        toggleRow(info, "Show warnings", "Warn before risky actions", "relayShowWarnings")
        toggleRow(info, "Warn when recovery fails", "Warn if automatic HP/MP recovery did not work", "relayWarnOnRecoverFailure")
        toggleRow(info, "Effect names as text", "Show effects as text instead of icons", "relayTextualizesEffects")
        toggleRow(info, "Hide junk in mall results", "Leave junk items out of mall searches", "relayHidesJunkMallItems")
        toggleRow(info, "Shorter zap list", "Only list items that can be zapped", "relayTrimsZapList")
        toggleRow(info, "Color chat text", "Format and color chat messages", "relayFormatsChatText")
        val auto = card(ctx, col, "Automatic upkeep")
        toggleRow(auto, "Keep effects up", "Cast your mood's effects as pages load", "relayMaintainsEffects")
        toggleRow(auto, "Keep health up", "Restore HP as pages load", "relayMaintainsHealth")
        toggleRow(auto, "Keep mana up", "Restore MP as pages load", "relayMaintainsMana")
        toggleRow(auto, "Run the before-battle script", "Run your before-battle script when a fight starts from the Game tab", "relayRunsBeforeBattleScript")
        toggleRow(auto, "Run the after-adventure script", "Run your after-adventure script after each adventure from the Game tab", "relayRunsAfterAdventureScript")
        toggleRow(auto, "Run the before-PvP script", "Run your before-PvP script before a PvP fight from the Game tab", "relayRunsBeforePVPScript")
        val app = card(ctx, col, "Appearance")
        toggleRow(app, "Replacement images", "Use KoLmafia's replacement images", "relayOverridesImages")
        toggleRow(app, "Script button first", "Put your script button ahead of the skill buttons", "relayScriptButtonFirst")
        numberRow(app, "Skill buttons", "How many skill buttons to show in fights", "relaySkillButtonCount")
    }

    // ---- rows ----

    private fun strings(o: JSONObject, name: String): List<String> {
        val a = o.optJSONArray(name) ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }

    private fun rowBase(parent: LinearLayout, title: String, desc: String?): Pair<LinearLayout, LinearLayout> {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(ctx, title, 15f, KC.text))
        if (desc != null) texts.addView(label(ctx, desc, 12f, KC.muted))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return row to texts
    }

    private fun navRow(parent: LinearLayout, title: String, desc: String, go: () -> Unit) {
        val (row, _) = rowBase(parent, title, desc)
        row.addView(label(requireContext(), "›", 24f, KC.muted))
        clickable(row, go)
    }

    /** A switch backed by a KoLmafia preference. */
    private fun toggleRow(parent: LinearLayout, title: String, desc: String?, key: String) =
        toggleRow(parent, title, desc, key, KolEngineHost.getPref(key).equals("true", ignoreCase = true)) { on -> KolEngineHost.setPref(key, if (on) "true" else "false") }

    /** A switch with its own current value and save action. Tapping anywhere on the row flips it. */
    private fun toggleRow(parent: LinearLayout, title: String, desc: String?, @Suppress("UNUSED_PARAMETER") key: String?, value: Boolean, save: (Boolean) -> Unit) {
        val (row, _) = rowBase(parent, title, desc)
        val sw = Switch(requireContext()).apply { isChecked = value; isClickable = false }
        row.addView(sw)
        clickable(row) { sw.isChecked = !sw.isChecked; save(sw.isChecked) }
    }

    private fun spinnerRow(parent: LinearLayout, title: String, desc: String?, labels: List<String>, selected: Int, picked: (Int) -> Unit) {
        val ctx = requireContext()
        val (row, texts) = rowBase(parent, title, desc)
        row.orientation = LinearLayout.VERTICAL
        row.gravity = Gravity.START
        // the text block was sized for a horizontal row (weight, zero width); stacked above the drop-down it must wrap
        texts.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val sp = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(selected)
        }
        row.addView(sp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        var first = true
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (first) { first = false; return }
                picked(pos)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /** A drop-down backed by a preference whose value is one of [options] (value to label). */
    private fun choiceRow(parent: LinearLayout, title: String, desc: String?, key: String, options: List<Pair<String, String>>) {
        val cur = KolEngineHost.getPref(key)
        val i = options.indexOfFirst { it.first.equals(cur, ignoreCase = true) }.coerceAtLeast(0)
        spinnerRow(parent, title, desc, options.map { it.second }, i) { pos -> KolEngineHost.setPref(key, options[pos].first) }
    }

    /** Pick any number of [options]; saved as a comma-separated preference. */
    private fun multiRow(parent: LinearLayout, title: String, desc: String?, key: String, options: List<String>, sep: String = ",") {
        val ctx = requireContext()
        val (row, texts) = rowBase(parent, title, desc)
        val lower = sep != "," // item lists are stored lower-case, in the order KoLmafia tries them
        fun chosen() = KolEngineHost.getPref(key).split(sep).map { it.trim() }.mapNotNull { v -> options.firstOrNull { it.equals(v, ignoreCase = true) } }
        val summary = label(ctx, "", 12f, KC.soft)
        fun refresh() {
            val c = chosen()
            summary.text = if (c.isEmpty()) "None selected" else "${c.size} selected: " + c.joinToString(", ")
        }
        refresh()
        texts.addView(summary)
        row.addView(label(ctx, "›", 24f, KC.muted))
        clickable(row) {
            val now = chosen().toSet()
            val checked = BooleanArray(options.size) { options[it] in now }
            ScrollSafeDialogBuilder(ctx).setTitle(title)
                .setMultiChoiceItems(options.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                .setPositiveButton("Save") { _, _ ->
                    KolEngineHost.setPref(key, options.filterIndexed { i, _ -> checked[i] }.joinToString(sep) { if (lower) it.lowercase() else it })
                    refresh()
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun textRow(parent: LinearLayout, title: String, desc: String?, key: String, numeric: Boolean = false) {
        val ctx = requireContext()
        val (row, _) = rowBase(parent, title, desc)
        val value = label(ctx, "", 13f, KC.soft).apply { setPadding(dp(ctx, 16), 0, 0, 0) }
        fun refresh() { val v = KolEngineHost.getPref(key); value.text = if (v.isEmpty()) "not set" else v }
        refresh()
        row.addView(value)
        clickable(row) {
            val input = EditText(ctx).apply {
                setText(KolEngineHost.getPref(key))
                if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
                setSelection(text.length)
            }
            val pad = dp(ctx, 20)
            ScrollSafeDialogBuilder(ctx).setTitle(title)
                .setView(FrameLayout(ctx).apply { setPadding(pad, dp(ctx, 8), pad, 0); addView(input) })
                .setPositiveButton("Save") { _, _ -> KolEngineHost.setPref(key, input.text.toString().trim()); refresh() }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun numberRow(parent: LinearLayout, title: String, desc: String?, key: String) = textRow(parent, title, desc, key, true)

    companion object {
        fun show(fm: FragmentManager) {
            SettingsFragment().show(fm, "settings")
        }
    }
}
