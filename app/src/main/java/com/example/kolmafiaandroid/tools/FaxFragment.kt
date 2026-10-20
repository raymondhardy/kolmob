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
        action(root, "Receive the fax", "Collect whatever is in the machine") { run("fax receive") }
        action(root, "Send your photocopy", "Put your photocopied monster back in the machine") { run("fax send") }
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
                pickList("Request a fax", rows) { r ->
                    run("faxbot ${r.tag as String}")
                    Toast.makeText(requireContext(), "Requesting ${r.title}…", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        fun show(fm: FragmentManager) = FaxFragment().show(fm, "fax")
    }
}
