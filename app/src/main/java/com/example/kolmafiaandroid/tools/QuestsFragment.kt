package com.example.kolmafiaandroid.tools

import com.example.kolmafiaandroid.ui.KC

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.MainActivity
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.card
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import com.example.kolmafiaandroid.ui.row
import org.json.JSONObject

/**
 * Quests: what is in progress (with the current step from KoLmafia's own quest data),
 * plus quest helpers that only appear while they are useful (hedge maze, volcano maze).
 */
class QuestsFragment : DialogFragment() {
    private lateinit var body: LinearLayout
    private var showFinished = false
    private var last: JSONObject? = null
    private var locationNames = listOf<String>()
    private var zoneNames = listOf<String>()
    private var zoneOf = mapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).also { it.requestWindowFeature(Window.FEATURE_NO_TITLE) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
        }
        if (showsDialog) body.addView(label(ctx, "Quests", 20f, 0xFF4CAF50.toInt(), true).apply { setPadding(dp(ctx, 4), 0, 0, dp(ctx, 12)) })
        body.addView(label(ctx, "Loading…", 14f, KC.muted))
        KolEngineHost.locations { json ->
            val arr = try { org.json.JSONArray(json) } catch (_: Exception) { org.json.JSONArray() }
            // retired copies of areas (e.g. the "(Dehydrated)" desert) are left out so each place is listed once
            val all = (0 until arr.length()).map { arr.getJSONObject(it) }
            val baseNames = all.map { it.optString("name") }.toSet()
            val objs = all.filter { o ->
                val z = o.optString("zone")
                val n = o.optString("name")
                val base = n.replace(Regex("\\s*\\(.*\\)$"), "")
                !(z.contains("obsolete", true) || z.contains("retired", true) || z.contains("removed", true)) &&
                    !(base != n && base in baseNames)
            }
            locationNames = objs.map { it.optString("name") }.filter { it.length > 3 }
            zoneNames = objs.map { it.optString("zone") }.filter { it.length > 3 }.distinct()
            zoneOf = objs.associate { it.optString("name") to it.optString("zone") }
        }
        load(true)
        return ScrollView(ctx).apply { addView(body) }
    }

    /** Reads the quest data; with [fromKol] it first re-reads KoL's quest log so quests started or advanced since the last look show up. */
    private fun load(fromKol: Boolean) {
        val read = {
            KolEngineHost.quests { json ->
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    last = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
                    render()
                }
            }
        }
        if (fromKol) {
            read() // show what is known right away, then update
            KolEngineHost.execute("refresh quests") { read() }
        } else read()
    }

    override fun onResume() {
        super.onResume()
        if (last != null) load(false) // the game may have moved quests along while this screen was away
    }

    private fun render() {
        val ctx = context ?: return
        val o = last ?: return
        body.removeAllViews()
        if (showsDialog) body.addView(label(ctx, "Quests", 20f, 0xFF4CAF50.toInt(), true).apply { setPadding(dp(ctx, 4), 0, 0, dp(ctx, 12)) })

        fun openGame(path: String) {
            (activity as? MainActivity)?.openGamePage(path)
        }

        val arr = o.optJSONArray("quests")
        val active = mutableListOf<JSONObject>()
        val done = mutableListOf<JSONObject>()
        if (arr != null) for (i in 0 until arr.length()) {
            val q = arr.getJSONObject(i)
            if (q.optString("status") == "finished") done += q else active += q
        }

        val inProgress = card(ctx, body, "In progress · ${active.size}")
        if (active.isEmpty()) inProgress.addView(label(ctx, "Nothing in progress.", 14f, KC.muted))
        active.forEach { inProgress.addView(questBlock(it)) }

        val finished = card(ctx, body, "Finished · ${done.size}")
        finished.addView(row(ctx, if (showFinished) "Hide finished quests" else "Show finished quests", null, {
            showFinished = !showFinished
            render()
        }))
        if (showFinished) done.forEach { finished.addView(questBlock(it)) }

        val more = card(ctx, body)
        more.addView(row(ctx, "Refresh from KoL", "re-reads your quest log", { load(true) }))
        more.addView(row(ctx, "Open KoL's full quest log", null, { openGame("questlog.php") }))
    }

    private fun questBlock(q: JSONObject): View {
        val ctx = requireContext()
        return com.example.kolmafiaandroid.ui.clickable(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 10))
            addView(label(ctx, q.optString("title"), 15f, KC.text, true))
            val t = q.optString("text")
            if (t.isNotEmpty() && !t.startsWith("(")) addView(label(ctx, t, 13f, KC.muted))
        }) { questDetail(q) }
    }

    /** Locations (exact, or without "The "/trailing "(…)") or zones mentioned in the quest's current step; each is a search term for the adventure picker. */
    private fun placesIn(text: String): List<String> {
        val low = text.lowercase()
        fun variants(n: String): List<String> {
            val noParen = n.replace(Regex("\\s*\\(.*\\)$"), "")
            return listOf(n, noParen, noParen.removePrefix("The ").removePrefix("the ")).filter { it.length > 4 }
        }
        val hits = locationNames.filter { n -> variants(n).any { low.contains(it.lowercase()) } }.sortedByDescending { it.length }
        val zones = zoneNames.filter { low.contains(it.lowercase()) && hits.none { h -> h.equals(it, true) } }
        return (hits + zones).distinct().take(4)
    }

    /** KoL's own page for an area, from the zone name KoLmafia gives; the main page, which lists every area, when the area is not one we know. */
    private fun mapPageFor(zone: String): String {
        val z = zone.lowercase()
        return when {
            "desert beach" in z || z == "beach" -> "place.php?whichplace=desertbeach"
            "nearby plains" in z || z == "plains" -> "place.php?whichplace=plains"
            "distant woods" in z || z == "woods" -> "place.php?whichplace=woods"
            "big mountains" in z || z == "mountain" -> "place.php?whichplace=mountains"
            "seaside town" in z || "seaside casino" in z || z == "town" -> "place.php?whichplace=town"
            "spookyraven (first floor)" in z -> "place.php?whichplace=manor1"
            "spookyraven (second floor)" in z -> "place.php?whichplace=manor2"
            "spookyraven (third floor)" in z -> "place.php?whichplace=manor3"
            else -> "main.php"
        }
    }

    private fun questDetail(q: JSONObject) {
        val ctx = requireContext()
        val text = q.optString("text")
        val places = placesIn(text)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), 0) }
        box.addView(label(ctx, text.ifEmpty { "No details." }, 15f, KC.soft))
        val dlg = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle(q.optString("title")).setView(box)
        places.forEach { name ->
            box.addView(label(ctx, "Open the map: $name  ›", 15f, 0xFF4CAF50.toInt(), true).apply {
                setPadding(0, dp(ctx, 14), 0, dp(ctx, 4))
            })
        }
        val dialog = dlg.setNegativeButton("Close", null).setNeutralButton("Quest log") { _, _ ->
            (activity as? MainActivity)?.openGamePage("questlog.php")
        }.create()
        // tap handlers for the location lines (children after the text)
        for (i in 0 until places.size) com.example.kolmafiaandroid.ui.clickable(box.getChildAt(i + 1)) {
            dialog.dismiss()
            // open the area in the relay browser (nothing is adventured); the Adventure tab is for choosing and starting a fight
            (activity as? MainActivity)?.openGamePage(mapPageFor(zoneOf[places[i]] ?: places[i]))
        }
        if (places.isEmpty()) box.addView(label(ctx, "No known location in this step — use the quest log for details.", 12f, KC.hint).apply { setPadding(0, dp(ctx, 12), 0, 0) })
        dialog.show()
    }

    companion object {
        fun show(fm: FragmentManager) = QuestsFragment().show(fm, "quests")
    }
}
