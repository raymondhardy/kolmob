package com.example.kolmafiaandroid.tools

import com.example.kolmafiaandroid.ui.KC

import android.app.Dialog
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.clickable
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import org.json.JSONObject

/** Your kmail inbox, newest first: tap a message to read it in full. */
class InboxFragment : DialogFragment() {
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
        root.addView(label(ctx, "Inbox", 20f, 0xFF4CAF50.toInt(), true))
        status = label(ctx, "Fetching your kmail…", 13f, KC.muted).apply { setPadding(0, dp(ctx, 4), 0, dp(ctx, 8)) }
        root.addView(status)
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(android.widget.ScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        KolEngineHost.inbox { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val arr = o.optJSONArray("messages")
            val list = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                status.text = when {
                    o.has("error") -> "Couldn't read your inbox. Are you logged in?"
                    list.isEmpty() -> "Your inbox is empty."
                    else -> "${list.size} messages · tap one to read it"
                }
                render(list)
            }
        }
        return root
    }

    private fun plain(html: String) = Html.fromHtml(html.replace("<br>", "\n").replace("<br/>", "\n"), Html.FROM_HTML_MODE_COMPACT).toString().trim()

    private var shown: MutableList<JSONObject> = mutableListOf()

    private fun reply(to: String) {
        val ctx = context ?: return
        val input = android.widget.EditText(ctx).apply { hint = "Message"; minLines = 3 }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Reply to $to")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    KolEngineHost.execute("send to $to || $t")
                    status.text = "Reply sent to $to"
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // KoL has no read flag of its own, so read state is kept on the phone: unread messages show a green dot
    private fun seenIds(ctx: android.content.Context): Set<String> =
        (ctx.getSharedPreferences("kolmob", android.content.Context.MODE_PRIVATE).getString("mail_seen", "") ?: "").split(",").filter { it.isNotEmpty() }.toSet()

    private fun markSeen(ctx: android.content.Context, ids: List<String>) {
        val all = (seenIds(ctx) + ids).toList().takeLast(500)
        ctx.getSharedPreferences("kolmob", android.content.Context.MODE_PRIVATE).edit().putString("mail_seen", all.joinToString(",")).apply()
    }

    private fun render(list: List<JSONObject>) {
        val ctx = context ?: return
        shown = list.toMutableList()
        body.removeAllViews()
        if (list.any { it.optString("id") !in seenIds(ctx) }) {
            body.addView(com.google.android.material.button.MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Mark all read"
                setOnClickListener { markSeen(ctx, list.map { it.optString("id") }); render(list) }
            })
        }
        for (m in list) {
            val text = plain(m.optString("html"))
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 4), dp(ctx, 12), dp(ctx, 4), dp(ctx, 12)) }
            val unread = m.optString("id") !in seenIds(ctx)
            row.addView(label(ctx, (if (unread) "● " else "") + plain(m.optString("sender")), 16f, if (unread) 0xFF4CAF50.toInt() else KC.text, unread))
            row.addView(label(ctx, text.lineSequence().firstOrNull { it.isNotBlank() }?.take(90) ?: "(no text)", 13f, KC.muted))
            clickable(row) {
                markSeen(ctx, listOf(m.optString("id")))
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    .setTitle("From " + plain(m.optString("sender")))
                    .setMessage(text.ifEmpty { "(no text)" })
                    .setPositiveButton("Reply") { _, _ -> reply(plain(m.optString("sender"))) }
                    .setNeutralButton("Save") { _, _ ->
                        KolEngineHost.saveMail(m.optString("id")) { r ->
                            activity?.runOnUiThread {
                                if (!isAdded) return@runOnUiThread
                                if (r == "ok") { shown.remove(m); status.text = "${shown.size} messages · saved"; render(shown) }
                                else status.text = "Couldn't save: $r"
                            }
                        }
                    }
                    .setNegativeButton("Delete") { _, _ ->
                        KolEngineHost.deleteMail(m.optString("id")) { r ->
                            activity?.runOnUiThread {
                                if (!isAdded) return@runOnUiThread
                                if (r == "ok") { shown.remove(m); status.text = "${shown.size} messages · deleted"; render(shown) }
                                else status.text = "Couldn't delete: $r"
                            }
                        }
                    }
                    .show()
            }
            body.addView(row)
        }
    }

    companion object {
        fun show(fm: FragmentManager) = InboxFragment().show(fm, "inbox")
    }
}
