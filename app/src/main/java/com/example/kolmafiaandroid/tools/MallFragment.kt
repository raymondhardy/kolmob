package com.example.kolmafiaandroid.tools

import com.example.kolmafiaandroid.ui.KC

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.KolImages
import com.example.kolmafiaandroid.ui.askQuantity
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import org.json.JSONObject
import java.text.NumberFormat

/** Mall: search the player mall (KoLmafia's MallSearchRequest) and buy with KoLmafia's own `buy` command. */
class MallFragment : DialogFragment() {
    private class Offer(val name: String, val img: String, val price: Long, val quantity: Int, val limit: Int, val shop: String, val cost: String = "")

    private var offers = listOf<Offer>()
    private var meat = 0L
    private lateinit var status: TextView
    private lateinit var meatView: TextView
    private lateinit var search: EditText
    private lateinit var filterBar: com.example.kolmafiaandroid.ui.ItemFilterBar
    private lateinit var list: ListView
    private var lastQuery = ""
    private val fmt = NumberFormat.getIntegerInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).also { it.requestWindowFeature(Window.FEATURE_NO_TITLE) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 8))
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(ctx, "Mall", 20f, 0xFF4CAF50.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
        meatView = label(ctx, "", 13f, KC.muted)
        head.addView(meatView)
        root.addView(head)
        search = EditText(ctx).apply {
            hint = "Search the mall (item name)"
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { runSearch(text.toString()); true } else false }
        }
        root.addView(search)
        filterBar = com.example.kolmafiaandroid.ui.ItemFilterBar(ctx, "mall") { if (isAdded) showOffers() }
        root.addView(filterBar)
        status = label(ctx, "Type an item name and search.", 13f, KC.muted).apply { setPadding(0, dp(ctx, 4), 0, dp(ctx, 8)) }
        root.addView(status)
        list = ListView(ctx).apply { divider = null }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        refreshMeat()
        return root
    }

    private fun refreshMeat() {
        KolEngineHost.snapshot { json ->
            meat = try { JSONObject(json).optLong("meat") } catch (_: Exception) { 0L }
            activity?.runOnUiThread { if (isAdded) meatView.text = "${fmt.format(meat)} meat" }
        }
    }

    private fun runSearch(q: String) {
        val query = q.trim()
        if (query.isEmpty()) return
        lastQuery = query
        status.text = "Searching for “$query”…"
        KolEngineHost.mallSearch(query, 30) { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val arr = o.optJSONArray("results")
            val found = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                Offer(android.text.Html.fromHtml(it.optString("name"), 0).toString(), it.optString("img"), it.optLong("price"), it.optInt("quantity"), it.optInt("limit"), it.optString("shop"), it.optString("cost"))
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                offers = found
                status.text = when {
                    o.has("error") -> "Search failed. Are you logged in?"
                    found.isEmpty() -> "Nothing for sale matches “$query”."
                    else -> "${found.size} offers · tap to buy, hold for price history"
                }
                showOffers()
            }
        }
    }

    private fun showOffers() {
        val ctx = requireContext()
        val shown = filterBar.apply(offers, { it.name }, { it.quantity })
        list.adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(i: Int) = shown[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, parent: ViewGroup): View {
                val row = (v as? LinearLayout) ?: LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(ctx, 4), dp(ctx, 8), dp(ctx, 4), dp(ctx, 8))
                    addView(ImageView(ctx), LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)).apply { marginEnd = dp(ctx, 12) })
                    val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                    col.addView(TextView(ctx).apply { textSize = 16f; setTextColor(KC.text) })
                    col.addView(TextView(ctx).apply { textSize = 12f; setTextColor(KC.muted) })
                    addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(TextView(ctx).apply { textSize = 15f; setTextColor(0xFF4CAF50.toInt()) })
                }
                val o = shown[i]
                KolImages.load(row.getChildAt(0) as ImageView, o.img)
                val col = row.getChildAt(1) as LinearLayout
                (col.getChildAt(0) as TextView).text = o.name
                (col.getChildAt(1) as TextView).text = listOf(o.shop, "${fmt.format(o.quantity)} in stock", if (o.limit in 1 until o.quantity) "limit ${o.limit}/day" else "").filter { it.isNotEmpty() }.joinToString(" · ")
                (row.getChildAt(2) as TextView).text = if (o.cost.isNotEmpty()) o.cost else fmt.format(o.price) + " meat"
                return row
            }
        }
        list.setOnItemClickListener { _, _, pos, _ -> buy(shown[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ -> priceHistory(shown[pos].name); true }
    }

    /** What KoLmafia has recorded about an item's mall price, and how old that record is. */
    private fun priceHistory(name: String) {
        val i = "to_item(${com.example.kolmafiaandroid.ui.AshProbe.q(name)})"
        val expr = "\"Cheapest in the mall now: \" + mall_price($i) + \" meat\\nLast recorded price: \" + historical_price($i) + \" meat\\nThat record is \" + round(historical_age($i) * 10) / 10.0 + \" days old\\nAutosell: \" + autosell_price($i) + \" meat\\nYou own: \" + item_amount($i) + \" in inventory, \" + closet_amount($i) + \" in the closet\""
        com.example.kolmafiaandroid.ui.AshProbe.eval(expr) { text ->
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("Price history: $name")
                    .setMessage(text.ifBlank { "No price information available." }).setPositiveButton("OK", null).show()
            }
        }
    }

    private fun buy(o: Offer) {
        if (o.cost.isNotEmpty()) {
            // a coinmaster such as Mr. Store: paid in its own currency, never meat
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle(o.name)
                .setMessage("Sold by ${o.shop} for ${o.cost}, not for meat.")
                .setPositiveButton("OK", null).show()
            return
        }
        val affordable = if (o.price > 0) (meat / o.price).toInt() else 0
        val cap = minOf(o.quantity, affordable, if (o.limit > 0) o.limit else Int.MAX_VALUE)
        if (cap <= 0) {
            Toast.makeText(requireContext(), if (affordable <= 0) "Not enough meat for one." else "None available.", Toast.LENGTH_SHORT).show()
            return
        }
        askQuantity("Buy ${o.name}", "${fmt.format(o.price)} meat each from ${o.shop}. You have ${fmt.format(meat)} meat.", cap, "Buy") { n ->
            // KoLmafia's own buy command: cheapest stores first, never above this price
            KolEngineHost.execute("buy $n ${o.name} @ ${o.price}") {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    Toast.makeText(requireContext(), "Purchase finished — check your inventory", Toast.LENGTH_SHORT).show()
                    refreshMeat()
                    if (lastQuery.isNotEmpty()) runSearch(lastQuery)
                }
            }
        }
    }

    companion object {
        fun show(fm: FragmentManager) = MallFragment().show(fm, "mall")
    }
}
