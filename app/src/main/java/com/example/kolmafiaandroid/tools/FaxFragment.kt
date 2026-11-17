package com.example.kolmafiaandroid.tools

import com.example.kolmafiaandroid.ui.screenTitle
import com.example.kolmafiaandroid.ui.FullScreenDialog
import com.example.kolmafiaandroid.ui.KC

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.PickRow
import com.example.kolmafiaandroid.ui.clickable
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import com.example.kolmafiaandroid.ui.pickList
import androidx.lifecycle.lifecycleScope
import com.example.kolmafiaandroid.ui.BackgroundTask
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The clan VIP fax machine: request a monster from a faxbot, or send/receive the photocopy. */
class FaxFragment : FullScreenDialog() {
    private lateinit var status: TextView
    private lateinit var busyBox: LinearLayout
    private lateinit var busyText: TextView
    private lateinit var busyLine: TextView
    private val actions = ArrayList<View>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 8)) }
        root.addView(screenTitle(ctx, "Fax machine"))
        status = label(ctx, "Needs a clan VIP lounge with a fax machine.", 13f, KC.muted).apply { setPadding(0, dp(ctx, 4), 0, dp(ctx, 12)) }
        root.addView(status)
        busyBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(ctx, 10).toFloat(); setStroke(dp(ctx, 1), KC.accent) }
            visibility = View.GONE
        }
        busyBox.addView(ProgressBar(ctx), LinearLayout.LayoutParams(dp(ctx, 32), dp(ctx, 32)).apply { marginEnd = dp(ctx, 12) })
        val busyCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        busyText = label(ctx, "", 15f, KC.text, true)
        busyLine = label(ctx, "", 12f, KC.muted).apply { maxLines = 2 }
        busyCol.addView(busyText); busyCol.addView(busyLine)
        busyBox.addView(busyCol, LinearLayout.LayoutParams(0, -2, 1f))
        // the request keeps going: a spinner tab on the screen edge brings this screen back
        busyBox.addView(label(ctx, "Hide", 14f, KC.accent, true).apply {
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 4), dp(ctx, 8))
            setOnClickListener { dismiss() }
        })
        root.addView(busyBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(ctx, 12) })
        action(root, "Request a monster from a faxbot", "Pick from the faxbot lists, then fetch it") { pickMonster() }
        action(root, "Receive the fax", "Collect whatever is in the machine") { runFax("ok = receive_fax();", "Got the fax — it's in your inventory.", showMonster = true, failMsg = "Nothing to receive, or the machine couldn't hand it over.", what = "Receiving the fax") }
        action(root, "Send your photocopy", "Put your photocopied monster back in the machine") { runFax("send_fax(); ok = available_amount(\$item[photocopied monster]) == 0;", "Photocopy sent back to the machine.", "Couldn't send: do you have a photocopied monster?", what = "Sending your photocopy") }
        watchTask()
        return root
    }

    override fun onStart() { super.onStart(); BackgroundTask.screenOpen.value = true }
    override fun onStop() { BackgroundTask.screenOpen.value = false; super.onStop() }

    /** Shows the running request, if any: what it is, how long it has taken, and KoLmafia's latest line. */
    private fun watchTask() {
        viewLifecycleOwner.lifecycleScope.launch {
            launch {
                BackgroundTask.current.collect { t ->
                    busyBox.visibility = if (t == null) View.GONE else View.VISIBLE
                    for (a in actions) { a.isEnabled = t == null; a.alpha = if (t == null) 1f else 0.4f }
                    if (t != null) status.text = ""
                }
            }
            launch {
                lastResult.collect { r -> if (r != null) { status.text = r.first; status.setTextColor(if (r.second) KC.accent else KC.bad) } }
            }
            launch {
                BackgroundTask.lastLine.collect { busyLine.text = it.ifEmpty { "KoLmafia waits up to a minute for the faxbot, then checks the fax machine." } }
            }
            while (true) {
                BackgroundTask.current.value?.let { busyText.text = "${it.title}  ·  ${(System.currentTimeMillis() - it.started) / 1000}s" }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun action(root: LinearLayout, title: String, sub: String, onClick: () -> Unit) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 4), dp(ctx, 12), dp(ctx, 4), dp(ctx, 12)) }
        row.addView(label(ctx, title, 16f, KC.text))
        row.addView(label(ctx, sub, 12f, KC.muted))
        clickable(row, onClick)
        actions.add(row)
        root.addView(row)
    }

    private fun run(cmd: String) {
        status.text = "Working…"
        KolEngineHost.execute(cmd) { activity?.runOnUiThread { if (isAdded) status.text = "Done. Check the log for the result." } }
    }

    /** Runs one ASH fax call and reports what really happened (success only when KoLmafia says it worked). */
    private fun runFax(expr: String, okMsg: String, failMsg: String = "", showMonster: Boolean = false, what: String = "Working with the fax machine") {
        if (BackgroundTask.current.value != null) return
        lastResult.value = null
        BackgroundTask.start(what) { act -> if (act.supportFragmentManager.findFragmentByTag("fax") == null) show(act.supportFragmentManager) }
        KolEngineHost.setPref("kolmobFaxResult", "")
        // No chat needed: when the bot's chat reply isn't seen, KoLmafia checks the fax machine after the timeout and
        // keeps the fax only if it is the requested monster (upstream kolmafia#3831). Starting chat here would read
        // held private messages without showing them anywhere (why upstream rejected #3818).
        sendFax(expr, okMsg, failMsg, showMonster)
    }

    private fun sendFax(expr: String, okMsg: String, failMsg: String, showMonster: Boolean) {
        val app = requireContext().applicationContext
        KolEngineHost.execute("ash boolean ok = false; $expr set_property(\"kolmobFaxResult\", ok ? \"ok\" : \"fail\");") {
            val ok = KolEngineHost.getPref("kolmobFaxResult") == "ok"
            val seen = if (ok && showMonster) KolEngineHost.getPref("photocopyMonster") else ""
            // on a failure, KoLmafia's last line says why (for one, a photocopy you chose not to dump)
            val why = BackgroundTask.lastLine.value.takeIf { !ok && it.isNotEmpty() }?.let { "\nKoLmafia: $it" } ?: ""
            val msg = if (ok && seen.isNotEmpty()) "Got a photocopied $seen — it's in your inventory." else if (ok) okMsg else failMsg + why
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                BackgroundTask.finish()
                lastResult.value = msg to ok
                if (!BackgroundTask.screenOpen.value && msg.isNotEmpty()) {
                    // the fax screen was closed while it worked: still say how it went
                    Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun pickMonster() {
        status.text = "Loading faxbot lists…"
        KolEngineHost.faxMonsters { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val arr = o.optJSONArray("monsters")
            val list = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (list.isEmpty()) { status.text = "Couldn't load the faxbot lists."; return@runOnUiThread }
                status.text = "${list.size} faxable monsters."
                val rows = list.sortedWith(compareBy({ it.optString("category") }, { it.getString("name").lowercase() })).map {
                    PickRow(android.text.Html.fromHtml(it.getString("name"), 0).toString(), "", group = it.optString("category"), tag = it.getString("command"))
                }
                val byCommand = list.associateBy { it.getString("command") }
                pickList("Request a fax", rows) { r ->
                    val m = byCommand[r.tag as String] ?: return@pickList
                    val bots = m.optJSONArray("bots")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                    chooseBot(r.title, m.getString("command"), m.optInt("id"), bots)
                }
            }
        }
    }

    /** Faxbots come and go, so each one is listed with whether KoL says it is online right now; you choose which to ask. */
    private fun chooseBot(monster: String, command: String, id: Int, bots: List<String>) {
        val ctx = requireContext()
        if (bots.isEmpty()) { status.text = "No faxbot carries $monster."; status.setTextColor(KC.bad); return }
        status.text = "Checking which faxbots are online…"; status.setTextColor(KC.muted)
        KolEngineHost.setPref("kolmobBotOnline", "")
        val names = bots.joinToString(",") { it.replace("\"", "") }
        KolEngineHost.execute("ash string r = \"\"; foreach i,n in split_string(\"$names\", \",\") { r += n + \":\" + (is_online(n) ? \"1\" : \"0\") + \",\"; } set_property(\"kolmobBotOnline\", r);") {
            val online = KolEngineHost.getPref("kolmobBotOnline").split(",").filter { it.contains(":") }
                .associate { it.substringBefore(":").lowercase() to (it.substringAfter(":") == "1") }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                status.text = ""
                showBotChoice(ctx, monster, id, bots, online)
            }
        }
    }

    private fun showBotChoice(ctx: android.content.Context, monster: String, id: Int, bots: List<String>, online: Map<String, Boolean>) {
        val prefs = ctx.getSharedPreferences("kolmob", android.content.Context.MODE_PRIVATE)
        val last = prefs.getString("fax_bot", "") ?: ""
        val sorted = bots.sortedWith(compareByDescending<String> { online[it.lowercase()] == true }.thenBy { it.lowercase() })
        val labels = sorted.map {
            val on = online[it.lowercase()]
            it + (if (on == true) "  ·  online" else if (on == false) "  ·  offline (probably won't answer)" else "") + (if (it == last) "  ·  last used" else "")
        }.toTypedArray()
        com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(ctx)
            .setTitle("Ask which faxbot for $monster?")
            .setItems(labels) { _, which ->
                val bot = sorted[which]
                prefs.edit().putString("fax_bot", bot).apply()
                if (online[bot.lowercase()] == false) {
                    status.text = "$bot is offline right now, so it can't deliver $monster. Try again when it is back, or pick another bot."
                    status.setTextColor(KC.bad)
                } else {
                    runFax("ok = faxbot(to_monster($id), \"${bot.replace("\"", "")}\");", "Got the $monster fax from $bot: it's in your inventory.", "$bot didn't deliver $monster. Try again in a minute, or pick another bot.", what = "Asking $bot for $monster")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        /** How the last request went, shown when the screen is opened again. */
        private val lastResult = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, Boolean>?>(null)

        fun show(fm: FragmentManager) = FaxFragment().show(fm, "fax")
    }
}
