package com.example.kolmafiaandroid.tools

import android.content.Context
import androidx.fragment.app.Fragment
import com.example.kolmafiaandroid.KolEngineHost
import org.json.JSONObject

/** Things you can do with any item name, wherever it shows up (inventory, mail, ...). */
object ItemActions {
    fun openWiki(ctx: Context, name: String) {
        val page = java.net.URLEncoder.encode(name.trim().replace(' ', '_'), "UTF-8").replace("+", "_")
        try {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://wiki.kingdomofloathing.com/$page")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            android.widget.Toast.makeText(ctx, "No browser to open the wiki", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Cheapest real mall offers for exactly this item (the search also returns look-alike names). */
    fun checkMallPrice(f: Fragment, name: String) {
        val ctx = f.requireContext()
        android.widget.Toast.makeText(ctx, "Checking the mall…", android.widget.Toast.LENGTH_SHORT).show()
        KolEngineHost.mallSearch(name, 30) { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val arr = o.optJSONArray("results")
            val want = name.trim().lowercase()
            val offers = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }.filter {
                android.text.Html.fromHtml(it.optString("name"), 0).toString().trim().lowercase() == want && it.optString("cost").isEmpty() && it.optLong("price") > 0
            }.sortedBy { it.optLong("price") }
            f.activity?.runOnUiThread {
                if (!f.isAdded) return@runOnUiThread
                val nf = java.text.NumberFormat.getIntegerInstance()
                val msg = when {
                    o.has("error") -> "The mall search failed. Are you logged in?"
                    offers.isEmpty() -> "Nobody is selling $name in the mall right now."
                    else -> {
                        val shown = offers.take(5).joinToString("\n") { "${nf.format(it.optLong("price"))} meat  ·  ${nf.format(it.optInt("quantity"))} in stock" + (if (it.optString("shop").isNotEmpty()) "  ·  ${it.optString("shop")}" else "") }
                        "Cheapest: ${nf.format(offers[0].optLong("price"))} meat\n\n$shown" + if (offers.size > 5) "\n… and ${offers.size - 5} more offers" else ""
                    }
                }
                com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(ctx).setTitle(name).setMessage(msg).setPositiveButton("Close", null).show()
            }
        }
    }
}
