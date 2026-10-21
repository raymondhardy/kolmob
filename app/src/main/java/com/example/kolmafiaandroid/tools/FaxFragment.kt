package com.example.kolmafiaandroid.tools

import com.example.kolmafiaandroid.ui.KC

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
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
import org.json.JSONObject

/** The clan VIP fax machine: request a monster from a faxbot, or send/receive the photocopy. */
class FaxFragment : DialogFragment() {
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
        root.addView(label(ctx, "Fax machine", 20f, 0xFF4CAF50.toInt(), true))
        status = label(ctx, "Needs a clan VIP lounge with a fax machine.", 13f, KC.muted).apply { setPadding(0, dp(ctx, 4), 0, dp(ctx, 12)) }
        root.addView(status)
        action(root, "Request a monster from a faxbot", "Pick from the faxbot lists, then fetch it") { pickMonster() }
        action(root, "Receive the fax", "Collect whatever is in the machine") { runFax("ok = receive_fax();", "Got the fax — it's in your inventory.", showMonster = true, failMsg = "Nothing to receive, or the machine couldn't hand it over.") }
        action(root, "Send your photocopy", "Put your photocopied monster back in the machine") { runFax("send_fax(); ok = available_amount(\$item[photocopied monster]) == 0;", "Photocopy sent back to the machine.", "Couldn't send: do you have a photocopied monster?") }
        return root
    }

    private fun action(root: LinearLayout, title: String, sub: String, onClick: () -> Unit) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 4), dp(ctx, 12), dp(ctx, 4), dp(ctx, 12)) }
        row.addView(label(ctx, title, 16f, KC.text))
        row.addView(label(ctx, sub, 12f, KC.muted))
        clickable(row, onClick)
        root.addView(row)
    }

    private fun run(cmd: String) {
        status.text = "Working…"
        KolEngineHost.execute(cmd) { activity?.runOnUiThread { if (isAdded) status.text = "Done. Check the log for the result." } }
    }

    /** Runs one ASH fax call and reports what really happened (success only when KoLmafia says it worked). */
    private fun runFax(expr: String, okMsg: String, failMsg: String = "", showMonster: Boolean = false) {
        status.text = "Working…  (a faxbot can take up to a minute to answer)"; status.setTextColor(KC.muted)
        KolEngineHost.setPref("kolmobFaxResult", "")
        // The bot answers through KoL chat: chat has to be running or its reply is never seen
        KolEngineHost.chatStart { _ -> sendFax(expr, okMsg, failMsg, showMonster) }
    }

    private fun sendFax(expr: String, okMsg: String, failMsg: String, showMonster: Boolean) {
        KolEngineHost.execute("ash boolean ok = false; $expr set_property(\"kolmobFaxResult\", ok ? \"ok\" : \"fail\");") {
            val ok = KolEngineHost.getPref("kolmobFaxResult") == "ok"
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val seen = if (ok && showMonster) KolEngineHost.getPref("photocopyMonster") else ""
                status.text = if (ok && seen.isNotEmpty()) "Got a photocopied $seen — it's in your inventory." else if (ok) okMsg else failMsg
                status.setTextColor(if (ok) 0xFF4CAF50.toInt() else 0xFFE57373.toInt())
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

    /** Faxbots come and go: let the player pick which one to ask, or let KoLmafia try each online bot in turn. */
    private fun chooseBot(monster: String, command: String, id: Int, bots: List<String>) {
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences("kolmob", android.content.Context.MODE_PRIVATE)
        val last = prefs.getString("fax_bot", "") ?: ""
        val labels = (listOf("Any bot that answers (tries each in turn)") + bots.map { it + (if (it == last) "  (last used)" else "") }).toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Ask which faxbot for $monster?")
            .setItems(labels) { _, which ->
                if (which == 0 || id <= 0) {
                    runFax("ok = faxbot(to_monster($id));", "Got the $monster fax — it's in your inventory.", "No faxbot delivered $monster. Try another bot.")
                } else {
                    val bot = bots[which - 1]
                    prefs.edit().putString("fax_bot", bot).apply()
                    runFax("ok = faxbot(to_monster($id), \"$bot\");", "Got the $monster fax from $bot — it's in your inventory.", "$bot didn't deliver $monster. Try another bot.")
                }
                Toast.makeText(ctx, "Requesting $monster…", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        fun show(fm: FragmentManager) = FaxFragment().show(fm, "fax")
    }
}
