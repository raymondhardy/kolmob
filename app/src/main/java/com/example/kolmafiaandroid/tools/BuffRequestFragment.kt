package com.example.kolmafiaandroid.tools

import android.app.AlertDialog
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.ui.PickRow
import com.example.kolmafiaandroid.ui.pickList
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ask a public buffbot for buffs: pick a bot from KoLmafia's list, pick what it sells, confirm, and the meat is sent.
 * The bot then casts on you. Real meat leaves your account, so there is always a confirmation.
 */
class BuffRequestFragment : Fragment() {
    override fun onStart() {
        super.onStart()
        Toast.makeText(requireContext(), "Fetching buffbot prices…", Toast.LENGTH_SHORT).show()
        KolEngineHost.buffRequestBots { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val arr = o.optJSONArray("bots") ?: JSONArray()
            val bots = (0 until arr.length()).map { arr.getString(it) }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (bots.isEmpty()) Toast.makeText(requireContext(), "KoLmafia's bot list is empty right now. You can still enter a bot yourself.", Toast.LENGTH_LONG).show()
                val rows = bots.map { PickRow(it, tag = it) } + PickRow("Other bot (type its name)…", "You pick the meat amount; use the bot's published price", tag = "")
                pickList("Choose a buffbot", rows) { r ->
                    val b = r.tag as String
                    if (b.isEmpty()) manualBot() else chooseOffer(b)
                }
            }
        }
    }

    /** Fallback for bots missing from KoLmafia's price list: name, meat and message, then the usual confirmation. */
    private fun manualBot() {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val name = android.widget.EditText(ctx).apply { hint = "Bot's player name"; maxLines = 1 }
        val meat = android.widget.EditText(ctx).apply { hint = "Meat to send"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val msg = android.widget.EditText(ctx).apply { hint = "Message (optional)"; maxLines = 1 }
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0)
            addView(name); addView(meat); addView(msg)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle("Ask a buffbot").setView(box)
            .setPositiveButton("Next") { _, _ ->
                val n = name.text.toString().trim()
                val m = meat.text.toString().toIntOrNull() ?: 0
                if (n.isEmpty() || m <= 0) { Toast.makeText(ctx, "Enter a name and a meat amount.", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val note = msg.text.toString().trim()
                AlertDialog.Builder(ctx).setTitle("Send $m meat to $n?")
                    .setMessage("This sends real meat. The bot should cast on you shortly after.")
                    .setPositiveButton("Send") { _, _ ->
                        KolEngineHost.execute("send $m meat to $n" + if (note.isNotEmpty()) " || $note" else "")
                        Toast.makeText(ctx, "Sent. Watch the Console tab and your effects.", Toast.LENGTH_LONG).show()
                    }.setNegativeButton("Cancel", null).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseOffer(bot: String) {
        KolEngineHost.buffRequestOffers(bot) { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val arr = o.optJSONArray("offers") ?: JSONArray()
            val offers = (0 until arr.length()).map { arr.getJSONObject(it) }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (offers.isEmpty()) { Toast.makeText(requireContext(), "$bot has nothing on offer.", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                pickList("$bot sells", offers.map { PickRow(it.optString("text"), "${it.optInt("price")} meat" + (if (it.optBoolean("free")) " (free/charity)" else ""), tag = it) }) { r ->
                    val offer = r.tag as JSONObject
                    val price = offer.optInt("price")
                    AlertDialog.Builder(requireContext())
                        .setTitle("Send $price meat to $bot?")
                        .setMessage(offer.optString("text") + "\n\nThis sends real meat. The bot should cast on you shortly after.")
                        .setPositiveButton("Send") { _, _ ->
                            KolEngineHost.execute("send $price meat to $bot")
                            Toast.makeText(requireContext(), "Sent. Watch the Console tab and your effects.", Toast.LENGTH_LONG).show()
                        }
                        .setNegativeButton("Cancel", null).show()
                }
            }
        }
    }

    companion object {
        fun show(fm: FragmentManager) {
            fm.beginTransaction().add(BuffRequestFragment(), "buffrequest").commitAllowingStateLoss()
        }
    }
}
