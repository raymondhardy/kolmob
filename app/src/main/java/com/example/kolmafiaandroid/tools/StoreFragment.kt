package com.example.kolmafiaandroid.tools

import com.example.kolmafiaandroid.ui.KC

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.KolImages
import com.example.kolmafiaandroid.ui.askQuantity
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import com.example.kolmafiaandroid.ui.pickList
import com.example.kolmafiaandroid.ui.PickRow
import org.json.JSONObject
import java.text.NumberFormat

/** Your mall store: listings, reprice, take back, and add from inventory (KoLmafia's `shop` command). */
class StoreFragment : DialogFragment() {
    private class Listing(val name: String, val img: String, val quantity: Int, val price: Long, val limit: Int, val lowest: Long)

    private var listings = listOf<Listing>()
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
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(ctx, "My store", 20f, 0xFF4CAF50.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(label(ctx, "+ Add item", 15f, 0xFF4CAF50.toInt(), true).apply {
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 4), dp(ctx, 8))
            com.example.kolmafiaandroid.ui.clickable(this) { addItem() }
        })
        root.addView(head)
        status = label(ctx, "Loading your store…", 13f, KC.muted).apply { setPadding(0, dp(ctx, 4), 0, dp(ctx, 8)) }
        root.addView(status)
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(android.widget.ScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        load()
        return root
    }

    private fun load() {
        KolEngineHost.storeItems { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val arr = o.optJSONArray("items")
            val list = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                Listing(android.text.Html.fromHtml(it.optString("name"), 0).toString(), it.optString("img"), it.optInt("quantity"), it.optLong("price"), it.optInt("limit"), it.optLong("lowest"))
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                listings = list.sortedBy { it.name.lowercase() }
                status.text = when {
                    o.has("error") -> "Could not load the store. Do you have one, and are you logged in?"
                    list.isEmpty() -> "Nothing for sale. Tap “+ Add item” to list something."
                    else -> "${list.size} listings · tap one to reprice or take it back"
                }
                render()
            }
        }
    }

    private fun render() {
        val ctx = context ?: return
        body.removeAllViews()
        for (l in listings) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(ctx, 4), dp(ctx, 8), dp(ctx, 4), dp(ctx, 8)) }
            val icon = ImageView(ctx)
            KolImages.load(icon, l.img)
            row.addView(icon, LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)).apply { marginEnd = dp(ctx, 12) })
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(label(ctx, l.name, 16f, KC.text))
            val sub = mutableListOf("×${fmt.format(l.quantity)}")
            if (l.lowest > 0) sub += "cheapest in mall ${fmt.format(l.lowest)}"
            if (l.limit > 0) sub += "limit ${l.limit}/day"
            col.addView(label(ctx, sub.joinToString(" · "), 12f, KC.muted))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label(ctx, fmt.format(l.price), 15f, 0xFF4CAF50.toInt()))
            com.example.kolmafiaandroid.ui.clickable(row) { manage(l) }
            body.addView(row)
        }
    }

    private fun run(cmd: String) {
        status.text = "Working…"
        KolEngineHost.execute(cmd) { activity?.runOnUiThread { if (isAdded) load() } }
    }

    private fun manage(l: Listing) {
        val ctx = requireContext()
        com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(ctx)
            .setTitle(l.name)
            .setItems(arrayOf("Change price (now ${fmt.format(l.price)})", "Take back…")) { _, which ->
                if (which == 0) askQuantity("Price for ${l.name}", "Meat each. Cheapest in the mall right now: ${fmt.format(l.lowest)}.", 999_999_999, "Set price") { p ->
                    run("shop reprice ${l.name} @ $p")
                } else askQuantity("Take back ${l.name}", "How many?", l.quantity, "Take back") { n ->
                    run("shop take $n ${l.name}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addItem() {
        KolEngineHost.snapshot { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val arr = o.optJSONArray("inventory")
            val items = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val rows = items.sortedBy { it.getString("name").lowercase() }.map {
                    PickRow(it.getString("name"), "×${it.getInt("count")} in inventory", tag = it.getString("name") to it.getInt("count"), img = it.optString("img"))
                }
                pickList("List an item for sale", rows) { r ->
                    val pair = r.tag as Pair<*, *>
                    val name = pair.first as String
                    val count = pair.second as Int
                    askQuantity("How many $name?", "You have $count.", count, "Next") { n ->
                        askQuantity("Price for $name", "Meat each.", 999_999_999, "List it") { p ->
                            run("shop put $n $name @ $p")
                        }
                    }
                }
            }
        }
    }

    companion object {
        fun show(fm: FragmentManager) = StoreFragment().show(fm, "store")
    }
}
