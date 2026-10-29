package com.example.kolmafiaandroid.fragments

import com.example.kolmafiaandroid.ui.KC

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.Html
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.row as uiRow
import com.example.kolmafiaandroid.ui.pickList
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Adventure screen: pick a location, a turn count and a fight action, run KoLmafia's real
 * `adventure` command (mood, restores and combat scripts all apply) and watch the live fight log.
 */
class AdventureFragment : Fragment() {
    private class Loc(val name: String, val zone: String, val ok: Boolean)

    private val green = 0xFF4CAF50.toInt()
    private val muted = KC.muted
    private var locations = listOf<Loc>()
    private var selected: String? = null
    private var running = false
    private var turnsLeft = 0
    private var state = JSONObject()

    private lateinit var locationButton: MaterialButton
    private lateinit var turnsInput: EditText
    private lateinit var goButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var againButton: MaterialButton
    private lateinit var conditionButton: MaterialButton
    private lateinit var statusLine: TextView
    private lateinit var actionButton: MaterialButton
    private lateinit var ccsButton: MaterialButton
    private lateinit var autoAttackButton: MaterialButton
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private val logBuffer = ArrayDeque<CharSequence>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(s: String, size: Float = 14f, color: Int = KC.soft, bold: Boolean = false) =
        TextView(requireContext()).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(12) }
        }
        c.addView(label(title.uppercase(), 12f, green, true).apply { letterSpacing = 0.1f; setPadding(0, 0, 0, dp(8)) })
        parent.addView(c)
        return c
    }

    private fun outlined(text: String, onClick: () -> Unit) =
        MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { onClick() }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        selected = ctx.getSharedPreferences("kolmob", Context.MODE_PRIVATE).getString("adv_location", null)
        val wide = resources.configuration.screenWidthDp >= 600 // tablets / landscape: log gets its own column
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        val rightColumn = if (wide) LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(12), dp(12), dp(12)) } else root
        // phones get sub-tabs (Go / Prep / Fights / Quests); tablets keep everything on one screen
        fun pane() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        val prepBox = if (wide) root else pane()
        val fightsBox = if (wide) root else pane()
        val logBox = if (wide) rightColumn else root   // phones: the live log sits right under the Adventure button

        // --- destination ---
        val dest = card(root, "Adventure")
        locationButton = outlined("Choose a location…") { chooseLocation() }
        dest.addView(locationButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val turnsRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        turnsRow.addView(label("Turns", 14f, muted))
        turnsInput = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("1")
            setTextColor(KC.text)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        turnsRow.addView(turnsInput, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        dest.addView(turnsRow)
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun chip(text: String, value: () -> Int) = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { turnsInput.setText("${value()}") }
        }
        for (n in listOf(1, 5, 10, 25)) {
            chips.addView(chip("$n") { n }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        }
        chips.addView(chip("All") { state.optInt("adventures", 1).coerceAtLeast(1) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dest.addView(chips)
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        goButton = MaterialButton(ctx).apply { text = "Adventure"; setOnClickListener { go() } }
        stopButton = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Stop"
            isEnabled = false
            setOnClickListener { KolEngineHost.abort(); append("<font color='#ff8a65'>Stopping…</font>") }
        }
        buttons.addView(goButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(stopButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        dest.addView(buttons)
        val extra = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        againButton = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Again ×1"
            setOnClickListener { go(1) }
        }
        conditionButton = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setOnClickListener { chooseConditions() }
        }
        extra.addView(againButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        extra.addView(conditionButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply { marginStart = dp(8) })
        dest.addView(extra)
        updateConditionButton()
        statusLine = label("", 12f, muted).apply { setPadding(0, dp(8), 0, 0) }
        dest.addView(statusLine)

        // --- combat settings ---
        // --- before you go: the prep routine from the ascension scripts (restore, buff, gear) ---
        val prep = card(prepBox, "Before you go")
        prep.addView(uiRow(ctx, "Restore HP to full", null, { KolEngineHost.execute("restore hp") }, green))
        prep.addView(uiRow(ctx, "Restore MP to full", null, { KolEngineHost.execute("restore mp") }, green))
        // ascension scripts: shown only when autoscend is installed
        if (java.io.File(ctx.filesDir, "kolmafia/scripts/autoscend.ash").exists()) {
            val asc = card(prepBox, "Ascension  ·  autoscend")
            asc.addView(uiRow(ctx, "Run autoscend", "plays your whole run", {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle("Run autoscend?")
                    .setMessage("It will spend adventures, meat and items on its own until the run is done or you stop it. A notification keeps it running in the background, with a Stop button.")
                    .setPositiveButton("Run") { _, _ -> KolEngineHost.execute("autoscend") }
                    .setNegativeButton("Cancel", null).show()
            }, green))
            asc.addView(uiRow(ctx, "Simulate", "autoscend sim: shows what it would do", { KolEngineHost.execute("autoscend sim") }, green))
            asc.addView(uiRow(ctx, "Stop", "abort whatever is running", { KolEngineHost.abort() }, 0xFFFF8A65.toInt()))
            asc.addView(uiRow(ctx, "Settings", "all auto_ preferences", {
                com.example.kolmafiaandroid.tools.PreferencesFragment.show(parentFragmentManager, "auto_")
            }, KC.text))
        }
        val auto = card(prepBox, "Auto-restore  ·  KoLmafia heals for you between turns")
        for (hp in listOf(true, false)) {
            val name = if (hp) "HP" else "MP"
            val base = if (hp) "hpAutoRecovery" else "mpAutoRecovery"
            val line = uiRow(ctx, "$name: …", null, null)
            auto.addView(line)
            KolEngineHost.restoreConfig(hp) { json ->
                val o = JSONObject(json)
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    val th = o.optDouble("threshold", -1.0)
                    val tg = o.optDouble("target", 1.0)
                    val items = o.optJSONArray("items")
                    val onCount = (0 until (items?.length() ?: 0)).count { items!!.getJSONObject(it).optBoolean("on") }
                    auto.removeView(line)
                    auto.addView(uiRow(ctx, "$name: " + (if (th < 0) "off" else "restore below ${Math.round(th * 100)}%, up to ${Math.round(tg * 100)}%") , "$onCount sources", {
                        autoRestoreDialog(hp, base, th, tg, items)
                    }, KC.text))
                }
            }
        }
        prep.addView(uiRow(ctx, "Run my mood (keeps your buffs up)", null, { KolEngineHost.execute("mood execute") }, green))
        prep.addView(uiRow(ctx, "Edit moods", "choose which buffs and triggers your mood keeps up", {
            com.example.kolmafiaandroid.tools.MoodFragment.show(parentFragmentManager)
        }, KC.text))
        prep.addView(uiRow(ctx, "Burn spare MP now", "casts your mood's buffs with MP above the reserve", { KolEngineHost.execute("burn extra mp") }, green))
        prep.addView(uiRow(ctx, "Show mood and restore status", "prints what the mood would cast and what restores are set", {
            KolEngineHost.execute("mood list; restore")
        }, KC.text))
        prep.addView(uiRow(ctx, "Scripts, mana burning and item sources", "between-turn scripts, MP burning, where missing items come from", { automationDialog() }, KC.text))
        prep.addView(uiRow(ctx, "Pick gear with the maximizer", null, {
            com.example.kolmafiaandroid.tools.MaximizerFragment().show(parentFragmentManager, "maximizer")
        }, green))
        prep.addView(label("Familiar, outfits, skills and buffs are on the Character screen.", 12f, muted).apply { setPadding(0, dp(8), 0, 0) })

        val combat = card(fightsBox, "Fights")
        actionButton = outlined("Fight action") { chooseAction() }
        ccsButton = outlined("Combat script") { chooseScript() }
        combat.addView(actionButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        combat.addView(ccsButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        // KoL's own account auto-attack: it applies when you play in the browser too
        autoAttackButton = outlined("Auto-attack") { chooseAutoAttack() }
        combat.addView(autoAttackButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        combat.addView(outlined("Choice adventures…") { com.example.kolmafiaandroid.tools.ChoiceAdvFragment.show(parentFragmentManager) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        refreshAutoAttack()

        fightBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        fightInfo = label("", 14f, KC.text, true).apply { setPadding(0, dp(12), 0, dp(4)) }
        fightBox.addView(fightInfo)
        val fightRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun fb(text: String, f: () -> Unit) = outlined(text, f).also { fightRow.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        fb("Attack") { fightDo("ash attack()") }
        fb("Skill…") { fightSkill() }
        fb("Item…") { fightItem() }
        fb("Run") { fightDo("ash runaway()") }
        fightBox.addView(fightRow)
        combat.addView(fightBox)

        // --- live log ---
        val logCard = card(logBox, "Log")
        log = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(KC.soft)
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(ctx).apply { addView(log) }
        logCard.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (wide) 560 else 260)))
        logCard.addView(MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "Clear log"
            setOnClickListener { logBuffer.clear(); log.text = "" }
        })
        fun scroller(child: View) = ScrollView(ctx).apply {
            addView(child)
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        if (!wide) {
            val host = android.widget.FrameLayout(ctx)
            val panes = listOf(root, prepBox, fightsBox, null)
            val shownPane = arrayOfNulls<View>(1)
            val questsHostId = View.generateViewId()
            fun show(i: Int) {
                shownPane[0]?.let { (it.parent as? ViewGroup)?.removeAllViews() }
                childFragmentManager.findFragmentByTag("quests")?.let { childFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss() }
                host.removeAllViews()
                if (i == 3) {
                    // the quest log is a screen of its own: hosted as a child fragment
                    host.addView(android.widget.FrameLayout(ctx).apply { id = questsHostId }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    childFragmentManager.beginTransaction().replace(questsHostId, com.example.kolmafiaandroid.tools.QuestsFragment(), "quests").commitAllowingStateLoss()
                    return
                }
                val pane = panes[i]!!
                (pane.parent as? ViewGroup)?.removeView(pane)
                val sv = scroller(pane)
                shownPane[0] = sv
                host.addView(sv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                if (i == 0) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
            val tabs = com.example.kolmafiaandroid.ui.iconTabs(ctx, listOf(
                R.drawable.tab_go to "Go",
                R.drawable.tab_prep to "Prep",
                R.drawable.tab_fights to "Fights",
                R.drawable.web_quests to "Quests",
            )) { show(it) }
            show(0)
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(tabs)
                addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        }
        root.setPadding(dp(12), dp(12), dp(6), dp(12))
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(scroller(root), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(scroller(rightColumn), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.post { view.requestFocus() }
        viewLifecycleOwner.lifecycleScope.launch {
            KolEngineHost.lines.collect { line ->
                withContext(Dispatchers.Main) {
                    append(line)
                    if (running) refreshState()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch { KolEngineHost.loginState.collect { loggedIn -> if (loggedIn) loadAll() } }
    }

    override fun onResume() {
        super.onResume()
        if (view != null) {
            val saved = requireContext().getSharedPreferences("kolmob", Context.MODE_PRIVATE).getString("adv_location", null)
            if (saved != null && saved != selected) { selected = saved; updateLocationButton() }
            val prefs = requireContext().getSharedPreferences("kolmob", Context.MODE_PRIVATE)
            prefs.getString("adv_search", null)?.let { term ->
                prefs.edit().remove("adv_search").apply()
                view?.post { chooseLocation(term) }
            }
        }
        if (view != null && KolEngineHost.loginState.value) refreshState()
    }

    private fun loadAll() {
        refreshState()
        KolEngineHost.locations { json ->
            val arr = JSONArray(json)
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }.map { Loc(it.getString("name"), it.optString("zone"), it.optBoolean("ok", true)) }
            activity?.runOnUiThread { if (isAdded) { locations = list; updateLocationButton() } }
        }
    }

    private var lastRefresh = 0L
    private fun refreshState() {
        val now = System.currentTimeMillis()
        if (now - lastRefresh < 1500) return
        lastRefresh = now
        KolEngineHost.adventureState { json ->
            activity?.runOnUiThread { if (isAdded && view != null) applyState(JSONObject(json)) }
        }
    }

    private lateinit var fightBox: LinearLayout
    private lateinit var fightInfo: TextView

    private fun fightDo(cmd: String) {
        KolEngineHost.execute(cmd) { KolEngineHost.adventureState { json -> activity?.runOnUiThread { if (isAdded) applyState(JSONObject(json)) } } }
    }

    private fun fightSkill() {
        KolEngineHost.combatSkills { json ->
            val a = try { org.json.JSONArray(json) } catch (_: Exception) { org.json.JSONArray() }
            val names = (0 until a.length()).map { a.getJSONObject(it) }.map { it.getString("name") + " (" + it.optInt("mpCost") + " MP)" to it.getString("name") }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (names.isEmpty()) { android.widget.Toast.makeText(requireContext(), "No combat skills", android.widget.Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                pickList("Use a skill", names.map { com.example.kolmafiaandroid.ui.PickRow(it.first, tag = it.second) }) { r ->
                    fightDo("ash use_skill(to_skill(\"${r.tag}\"))")
                }
            }
        }
    }

    private fun fightItem() {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply { hint = "Item name"; maxLines = 1 }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle("Throw an item").setView(input)
            .setPositiveButton("Use") { _, _ ->
                val n = input.text.toString().trim().replace("\"", "")
                if (n.isNotEmpty()) fightDo("ash throw_item(to_item(\"$n\"))")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun applyState(o: JSONObject) {
        state = o
        turnsLeft = o.optInt("adventures")
        val last = o.optString("lastAdventure")
        statusLine.text = "$turnsLeft adventures left" + (if (last.isNotEmpty()) "  ·  last: $last" else "") +
            "  ·  HP ${o.optLong("hp")}/${o.optLong("maxHp")}"
        val monster = o.optString("monster")
        if (monster.isNotEmpty()) {
            val phylum = o.optString("monsterPhylum").takeIf { it.isNotEmpty() && it != "null" && it != "NONE" }?.lowercase()
            statusLine.text = statusLine.text.toString() + "\nLast monster: $monster  ·  HP ${o.optInt("monsterHp")}  ·  Atk ${o.optInt("monsterAtk")}  ·  Def ${o.optInt("monsterDef")}" +
                (if (phylum != null) "  ·  $phylum" else "")
        }
        fightBox.visibility = if (o.optInt("round") > 0) View.VISIBLE else View.GONE
        fightInfo.text = "Fight in progress · round ${o.optInt("round")}" + (if (monster.isNotEmpty()) "\n$monster · HP ${o.optInt("monsterHp")}" else "")
        if (o.optInt("round") > 0) {
            statusLine.text = statusLine.text.toString() + "\n⚠ A fight is in progress (round ${o.optInt("round")}). Finish it below, or in the Game tab, before adventuring."
        }
        actionButton.text = "Fight action:  " + o.optString("battleAction").ifEmpty { "attack with weapon" }
        ccsButton.text = "Combat script:  " + o.optString("ccs").ifEmpty { "default" }
    }

    private fun updateLocationButton() {
        val sel = selected
        val loc = locations.firstOrNull { it.name == sel }
        locationButton.text = when {
            sel == null -> "Choose a location…"
            loc != null -> "${loc.name}   ·   ${loc.zone}"
            else -> sel
        }
        goButton.isEnabled = sel != null && !running
    }

    private fun setRunning(r: Boolean) {
        running = r
        goButton.isEnabled = !r && selected != null
        againButton.isEnabled = !r && selected != null
        stopButton.isEnabled = r
        goButton.text = if (r) "Adventuring…" else "Adventure"
    }

    private fun append(html: String) {
        val text = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().trimEnd()
        if (text.isEmpty()) return
        logBuffer.addLast(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).trimEndSpaces())
        while (logBuffer.size > 300) logBuffer.removeFirst()
        val sb = android.text.SpannableStringBuilder()
        logBuffer.forEach { sb.append(it).append('\n') }
        log.text = sb
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun CharSequence.trimEndSpaces(): CharSequence {
        var end = length
        while (end > 0 && this[end - 1].isWhitespace()) end--
        return subSequence(0, end)
    }

    private fun go(forceTurns: Int = 0) {
        val name = selected ?: return
        val turns = if (forceTurns > 0) forceTurns else turnsInput.text.toString().toIntOrNull() ?: 1
        if (turns <= 0) return
        setRunning(true)
        append("<b>▶ Adventuring $turns× at $name</b>")
        val conds = conditionList()
        val started = System.currentTimeMillis()
        val appCtx = requireContext().applicationContext
        // KoLmafia's own stop conditions: the adventure loop ends when they are all met
        val script = (listOf("conditions clear") + conds.map { "conditions add $it" } + "adventure $turns $name")
        fun runNext(i: Int) {
            if (i >= script.size) {
                // a condition that was met stops the run once; KoLmafia drops it, so we drop ours too
                if (conds.isNotEmpty()) KolEngineHost.conditionsLeft { left ->
                    if (left == 0) activity?.runOnUiThread { if (isAdded) { saveConditions(emptyList()); append("<i>Stop condition reached and cleared.</i>") } }
                }
                activity?.runOnUiThread {
                    if (isAdded) {
                        setRunning(false)
                        lastRefresh = 0
                        refreshState()
                        // KoLmafia also stops on special encounters and unset choices: if the game is waiting on you, say so
                        com.example.kolmafiaandroid.ui.AshProbe.eval(
                            "visit_url(\"main.php\").contains_text(\"name=whichchoice\") ? \"WAIT\" : (visit_url(\"main.php\").contains_text(\"fightform\") ? \"WAIT\" : \"OK\")"
                        ) { state ->
                            activity?.runOnUiThread {
                                if (!isAdded) return@runOnUiThread
                                val waiting = state.trim() == "WAIT"
                                val long = System.currentTimeMillis() - started > 20000 && !requireActivity().hasWindowFocus()
                                if (waiting) {
                                    append("<b>■ Stopped — the game needs you</b>")
                                    com.google.android.material.snackbar.Snackbar.make(requireView(), "Stopped — the game needs you", com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
                                        .setAction("Open it") { (activity as? com.example.kolmafiaandroid.MainActivity)?.openGamePage("main.php") }
                                        .show()
                                    if (long) notifyFinished(appCtx, name, "Stopped — the game needs you")
                                } else {
                                    append("<b>■ Finished</b>")
                                    // a long run that ended while you were elsewhere gets a notification
                                    if (long) notifyFinished(appCtx, name, "Adventuring finished")
                                }
                            }
                        }
                    }
                }
                return
            }
            KolEngineHost.execute(script[i]) { runNext(i + 1) }
        }
        runNext(0)
    }

    private fun notifyFinished(ctx: Context, place: String, title: String = "Adventuring finished") {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(android.app.NotificationChannel("adventure", "Adventure runs", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val open = android.app.PendingIntent.getActivity(ctx, 0,
                ctx.packageManager.getLaunchIntentForPackage(ctx.packageName), android.app.PendingIntent.FLAG_IMMUTABLE)
            nm.notify(4711, androidx.core.app.NotificationCompat.Builder(ctx, "adventure")
                .setSmallIcon(R.drawable.tab_go).setContentTitle(title)
                .setContentText(place).setContentIntent(open).setAutoCancel(true).build())
        } catch (_: Exception) {}
    }

    // ---- stop conditions (KoLmafia `conditions add ...`) ----

    private fun conditionList(): List<String> =
        (requireContext().getSharedPreferences("kolmob", Context.MODE_PRIVATE).getString("adv_conditions", "") ?: "")
            .split("|").filter { it.isNotBlank() }

    private fun saveConditions(l: List<String>) {
        requireContext().getSharedPreferences("kolmob", Context.MODE_PRIVATE).edit().putString("adv_conditions", l.joinToString("|")).apply()
        updateConditionButton()
    }

    private fun updateConditionButton() {
        val c = if (isAdded) conditionList() else emptyList()
        conditionButton.text = if (c.isEmpty()) "Stop when…" else "Stop when: " + c.joinToString(", ")
    }

    private fun chooseConditions() {
        val ctx = requireContext()
        val kinds = listOf("Get an item", "Reach a level", "Gain meat", "Choice adventures seen", "Clear all")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle("Stop adventuring when…")
            .setItems(kinds.toTypedArray()) { _, i ->
                if (i == 4) { saveConditions(emptyList()); return@setItems }
                val input = EditText(ctx).apply {
                    hint = if (i == 0) "Item name, or 2 item name" else "Number"
                    if (i != 0) inputType = InputType.TYPE_CLASS_NUMBER
                    maxLines = 1
                }
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle(kinds[i]).setView(input)
                    .setPositiveButton("Add") { _, _ ->
                        val t = input.text.toString().trim()
                        if (t.isEmpty()) return@setPositiveButton
                        val cond = when (i) {
                            0 -> if (t.first().isDigit()) t else "1 $t"
                            1 -> "level $t"
                            2 -> "$t meat"
                            else -> "$t choiceadv"
                        }
                        saveConditions(conditionList() + cond)
                    }.setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Cancel", null).show()
    }

    // ---- pickers ----

    private fun chooseLocation(initial: String = "") {
        if (locations.isEmpty()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setMessage("Loading locations… try again in a moment.").setPositiveButton("OK", null).show()
            return
        }
        val prefs = requireContext().getSharedPreferences("kolmob", Context.MODE_PRIVATE)
        val recent = (prefs.getString("adv_recent", "") ?: "").split("|").filter { it.isNotEmpty() }
        val byName = locations.associateBy { it.name }
        val favs = (prefs.getString("adv_favs", "") ?: "").split("|").filter { it.isNotEmpty() }
        val favRows = if (initial.isNotEmpty()) emptyList() else favs.mapNotNull { byName[it] }.filter { it.ok }
            .map { com.example.kolmafiaandroid.ui.PickRow("★ " + it.name, group = "Favorites", tag = it.name) }
        val recentRows = if (initial.isNotEmpty()) emptyList() else recent.mapNotNull { byName[it] }.filter { it.ok && it.name !in favs }
            .map { com.example.kolmafiaandroid.ui.PickRow(it.name, group = "Recent", tag = it.name) }
        val rows = favRows + recentRows + locations.sortedWith(compareBy({ it.zone }, { it.name }))
            .map { com.example.kolmafiaandroid.ui.PickRow(it.name, group = it.zone, locked = !it.ok, tag = it.name) }
        pickList("Where to?", rows, initial, "Show locked places", onLongPick = { r ->
            val name = r.tag as String
            val now = (prefs.getString("adv_favs", "") ?: "").split("|").filter { it.isNotEmpty() }
            val next = if (name in now) now - name else now + name
            prefs.edit().putString("adv_favs", next.joinToString("|")).apply()
            android.widget.Toast.makeText(requireContext(), if (name in now) "Removed $name from favorites" else "Starred $name (shows under Favorites next time)", android.widget.Toast.LENGTH_SHORT).show()
        }) { r ->
            selected = r.tag as String
            val updated = (listOf(selected) + recent.filter { it != selected }).take(5).joinToString("|")
            prefs.edit().putString("adv_location", selected).putString("adv_recent", updated).apply()
            updateLocationButton()
        }
    }

    /** Threshold, target and which restore sources KoLmafia may use. */
    private fun autoRestoreDialog(hp: Boolean, base: String, threshold: Double, target: Double, items: JSONArray?) {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun seek(title: String, value: Int, min: Int, onChange: (Int) -> Unit): TextView {
            val t = label("$title: $value%", 14f)
            box.addView(t)
            box.addView(android.widget.SeekBar(ctx).apply {
                max = 100 - min
                val g = android.content.res.ColorStateList.valueOf(green)
                progressTintList = g; thumbTintList = g
                progress = (value - min).coerceIn(0, 100 - min)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, u: Boolean) { t.text = "$title: ${p + min}%"; onChange(p + min) }
                    override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
                })
            })
            return t
        }
        var th = if (threshold < 0) 0 else Math.round(threshold * 100).toInt()
        var tg = Math.round(target * 100).toInt()
        seek("Restore when below (0 = off)", th, 0) { th = it }
        seek("Restore up to", tg, 1) { tg = it }
        val names = (0 until (items?.length() ?: 0)).map { items!!.getJSONObject(it) }
        val chosen = names.map { it.optBoolean("on") }.toBooleanArray()
        box.addView(label("Sources KoLmafia may use", 13f, muted).apply { setPadding(0, dp(12), 0, dp(4)) })
        val sources = android.widget.ListView(ctx)
        sources.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        sources.adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_multiple_choice, names.map { it.optString("name") })
        chosen.forEachIndexed { i, c -> sources.setItemChecked(i, c) }
        box.addView(sources, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle("Auto-restore " + if (hp) "HP" else "MP").setView(box)
            .setPositiveButton("Save") { _, _ ->
                KolEngineHost.setPref(base, if (th == 0) "-0.05" else (th / 100.0).toString())
                KolEngineHost.setPref(base + "Target", (tg / 100.0).toString())
                KolEngineHost.setPref(base + "Items", names.indices.filter { sources.isItemChecked(it) }.joinToString(";") { names[it].optString("name").lowercase() })
                view?.postDelayed({ if (isAdded) refreshState() }, 300)
                android.widget.Toast.makeText(ctx, "Saved", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** KoLmafia's between-turn hooks and mana-burning thresholds (plain preferences). */
    private fun automationDialog() {
        val ctx = requireContext()
        val pad = dp(20)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(8), pad, 0) }
        val fields = listOf(
            "betweenBattleScript" to "Script/command run before each turn",
            "afterAdventureScript" to "Script/command run after each turn",
            "recoveryScript" to "Script run when HP/MP restore fails",
            "manaBurningTrigger" to "Burn MP when above (0.0 to 1.0 of max MP)",
            "manaBurningThreshold" to "Keep this much MP in reserve (-0.05 = off)",
        )
        val inputs = fields.map { (pref, hint) ->
            box.addView(label(hint, 12f, muted).apply { setPadding(0, dp(10), 0, 0) })
            EditText(ctx).apply { setText(KolEngineHost.getPref(pref)); maxLines = 1; setTextColor(KC.text) }.also { box.addView(it) }
        }
        box.addView(label("Where KoLmafia may get missing items from", 13f, muted).apply { setPadding(0, dp(16), 0, dp(4)) })
        val sources = listOf(
            "autoSatisfyWithMall" to "The mall (buys with meat)",
            "autoSatisfyWithNPCs" to "NPC stores",
            "autoSatisfyWithCoinmasters" to "Coinmasters",
            "autoSatisfyWithCloset" to "Your closet",
            "autoSatisfyWithStorage" to "Hagnk's storage",
            "autoSatisfyWithStash" to "The clan stash",
            "removeMalignantEffects" to "Remove harmful effects when restoring",
        )
        val checks = sources.map { (pref, text) ->
            android.widget.CheckBox(ctx).apply { this.text = text; isChecked = KolEngineHost.getPref(pref) == "true"; setTextColor(KC.text) }.also { box.addView(it) }
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle("Scripts, mana burning and sources")
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                sources.forEachIndexed { i, f -> KolEngineHost.setPref(f.first, checks[i].isChecked.toString()) }
                fields.forEachIndexed { i, f -> KolEngineHost.setPref(f.first, inputs[i].text.toString().trim()) }
                android.widget.Toast.makeText(ctx, "Saved", android.widget.Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseAction() {
        val options = listOf(
            "attack with weapon" to "Attack with your weapon",
            "custom combat script" to "Use the combat script below",
            "abort" to "Stop at the first fight (look before you leap)",
            "skill" to "Cast one combat skill every round…"
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Fight action")
            .setItems(options.map { it.second }.toTypedArray()) { _, i ->
                if (options[i].first == "skill") {
                    KolEngineHost.combatSkills { json ->
                        val a = try { org.json.JSONArray(json) } catch (_: Exception) { org.json.JSONArray() }
                        val rows = (0 until a.length()).map { a.getJSONObject(it) }
                            .map { com.example.kolmafiaandroid.ui.PickRow(it.getString("name") + " (" + it.optInt("mpCost") + " MP)", tag = it.getString("name")) }
                        activity?.runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            pickList("Skill to use every round", rows) { r ->
                                KolEngineHost.execute("set battleAction=skill ${r.tag}") { activity?.runOnUiThread { lastRefresh = 0; refreshState() } }
                            }
                        }
                    }
                    return@setItems
                }
                KolEngineHost.execute("set battleAction=${options[i].first}") { activity?.runOnUiThread { lastRefresh = 0; refreshState() } }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshAutoAttack() {
        KolEngineHost.autoAttackInfo { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { return@autoAttackInfo }
            activity?.runOnUiThread { if (isAdded && view != null) autoAttackButton.text = "Auto-attack:  " + o.optString("name", "?") }
        }
    }

    private fun chooseAutoAttack() {
        KolEngineHost.autoAttackInfo { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { return@autoAttackInfo }
            val arr = o.optJSONArray("options") ?: return@autoAttackInfo
            val ids = (0 until arr.length()).map { arr.getJSONObject(it).optInt("id") }
            val names = (0 until arr.length()).map { arr.getJSONObject(it).optString("name") }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Auto-attack (also used in the browser)")
                    .setSingleChoiceItems(names.toTypedArray(), ids.indexOf(o.optInt("current"))) { d, i ->
                        d.dismiss()
                        KolEngineHost.execute("autoattack ${ids[i]}") { activity?.runOnUiThread { refreshAutoAttack() } }
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun chooseScript() {
        val files = (0 until (state.optJSONArray("ccsFiles")?.length() ?: 0)).map { state.getJSONArray("ccsFiles").getString(it) }
        val entries = listOf("default") + files.filter { it != "default" }
        val current = state.optString("ccs").ifEmpty { "default" }
        val labels = entries + listOfNotNull(if (current != "default") "✎  Edit “$current”" else null, "＋  New script…")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Combat script")
            .setItems(labels.toTypedArray()) { _, i ->
                when {
                    i < entries.size -> KolEngineHost.execute("set customCombatScript=${entries[i]}") { activity?.runOnUiThread { lastRefresh = 0; refreshState() } }
                    labels[i].startsWith("✎") -> com.example.kolmafiaandroid.tools.CcsEditorFragment.show(parentFragmentManager, current)
                    else -> com.example.kolmafiaandroid.tools.CcsEditorFragment.create(requireContext(), parentFragmentManager)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
