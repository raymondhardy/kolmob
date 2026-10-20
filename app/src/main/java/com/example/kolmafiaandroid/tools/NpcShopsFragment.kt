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
import com.example.kolmafiaandroid.ui.askQuantity
import com.example.kolmafiaandroid.ui.clickable
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import com.example.kolmafiaandroid.ui.pickList
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat

/** NPC stores open to you right now; pick a store, pick an item, buy it (runs `buy`, which uses NPC stores only). */
class NpcShopsFragment : DialogFragment() {
    private val coin get() = arguments?.getBoolean("coin") == true
    private var shops = listOf<JSONObject>()
    private var showLocked = false
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private val fmt = NumberFormat.getIntegerInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).also { it.requestWindowFeature(Window.FEATURE_NO_TITLE) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 8)) }
        root.addView(label(ctx, if (coin) "Coinmasters" else "NPC shops", 20f, 0xFF4CAF50.toInt(), true))
        status = label(ctx, "Finding stores open to you…", 13f, KC.muted).apply { setPadding(0, dp(ctx, 4), 0, dp(ctx, 8)) }
        root.addView(status)
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(android.widget.ScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        load()
        return root
    }

    private fun load() {
        (if (coin) KolEngineHost::coinmasters else KolEngineHost::npcShops) { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val arr = o.optJSONArray("shops") ?: JSONArray()
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                shops = list
                status.text = if (o.has("error")) "Could not load stores." else (if (coin) "${shops.count { it.optBoolean("open", true) }} open stores · tap one" else "${shops.size} stores · tap one")
                render()
            }
        }
    }

    private fun clean(s: String) = android.text.Html.fromHtml(s, 0).toString()

    private fun render() {
        val ctx = context ?: return
        body.removeAllViews()
        val visible = if (coin && !showLocked) shops.filter { it.optBoolean("open", true) } else shops
        for (s in visible) {
            val n = s.getJSONArray("items").length()
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 4), dp(ctx, 12), dp(ctx, 4), dp(ctx, 12)) }
            row.addView(label(ctx, clean(s.getString("name")), 16f, KC.text))
            val open = s.optBoolean("open", true)
            val sub = if (coin) (if (open) "$n items · you have ${s.optString("tokens")}" else "Locked: " + clean(s.optString("reason")).take(80)) else "$n items"
            row.addView(label(ctx, sub, 12f, KC.muted))
            if (!open) row.alpha = 0.5f
            clickable(row) { openShop(s) }
            body.addView(row)
        }
        val hidden = shops.size - visible.size
        if (coin && (hidden > 0 || showLocked)) {
            val row = LinearLayout(ctx).apply { setPadding(dp(ctx, 4), dp(ctx, 16), dp(ctx, 4), dp(ctx, 16)) }
            row.addView(label(ctx, if (showLocked) "Hide locked stores" else "Show $hidden locked stores", 14f, 0xFF4CAF50.toInt(), true))
            clickable(row) { showLocked = !showLocked; render() }
            body.addView(row)
        }
    }

    private fun openShop(s: JSONObject) {
        val items = s.getJSONArray("items")
        val rows = (0 until items.length()).map { items.getJSONObject(it) }.let { l -> if (coin) l else l.sortedBy { it.getLong("price") } }.map {
            val lim = it.optInt("limit")
            val sub = if (coin) it.optString("cost") else fmt.format(it.getLong("price")) + " meat" + if (lim in 1..99) " · limit $lim" else ""
            PickRow(clean(it.getString("name")), sub, tag = it, img = it.optString("img"))
        }
        pickList(clean(s.getString("name")), rows) { r ->
            val it = r.tag as JSONObject
            val name = clean(it.getString("name"))
            val msg = if (coin) it.optString("cost") + " each" else fmt.format(it.getLong("price")) + " meat each"
            askQuantity("Buy $name", msg, 1000, "Buy") { n ->
                status.text = "Buying $n × $name…"
                KolEngineHost.execute(if (coin) "coinmaster buy ${s.getString("nick")} $n $name" else "buy $n $name") {
                    activity?.runOnUiThread {
                        if (isAdded) { Toast.makeText(requireContext(), "Done", Toast.LENGTH_SHORT).show(); status.text = "${shops.size} stores · tap one" }
                    }
                }
            }
        }
    }

    companion object {
        fun show(fm: FragmentManager, coin: Boolean = false) =
            NpcShopsFragment().apply { arguments = Bundle().apply { putBoolean("coin", coin) } }.show(fm, "npcshops")
    }
}
