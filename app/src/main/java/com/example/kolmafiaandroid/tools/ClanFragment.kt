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
import com.example.kolmafiaandroid.ui.PickRow
import com.example.kolmafiaandroid.ui.askQuantity
import com.example.kolmafiaandroid.ui.clickable
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import com.example.kolmafiaandroid.ui.pickList
import org.json.JSONObject

/** Your clan's stash: browse, take items out, put items in (KoLmafia's `stash` command). */
class ClanFragment : DialogFragment() {
    private var stash = listOf<JSONObject>()
    private lateinit var title: TextView
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
        title = label(ctx, "Clan", 20f, 0xFF4CAF50.toInt(), true)
        root.addView(title)
        status = label(ctx, "Opening the clan stash…", 13f, KC.muted).apply { setPadding(0, dp(ctx, 4), 0, dp(ctx, 8)) }
        root.addView(status)
        val put = label(ctx, "+ Put an item in the stash", 15f, 0xFF4CAF50.toInt(), true).apply { setPadding(0, dp(ctx, 8), 0, dp(ctx, 12)) }
        clickable(put) { putItem() }
        root.addView(put)
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(android.widget.ScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        load()
        return root
    }

    private fun clean(s: String) = android.text.Html.fromHtml(s, 0).toString()

    private fun load() {
        KolEngineHost.clanInfo { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val arr = o.optJSONArray("stash")
            val list = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                stash = list.sortedBy { it.getString("name").lowercase() }
                val name = clean(o.optString("name"))
                if (name.isNotEmpty()) title.text = name
                status.text = when {
                    o.has("error") -> "Could not open the stash. Are you in a clan with stash access?"
                    stash.isEmpty() -> "The stash is empty."
                    else -> "${stash.size} items · tap one to take it"
                }
                render()
            }
        }
    }

    private fun render() {
        val ctx = context ?: return
        body.removeAllViews()
        for (it in stash) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(ctx, 4), dp(ctx, 8), dp(ctx, 4), dp(ctx, 8)) }
            val icon = ImageView(ctx)
            KolImages.load(icon, it.optString("img"))
            row.addView(icon, LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)).apply { marginEnd = dp(ctx, 12) })
            row.addView(label(ctx, clean(it.getString("name")), 16f, KC.text), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label(ctx, "×${it.getInt("count")}", 14f, KC.muted))
            clickable(row) { take(it) }
            body.addView(row)
        }
    }

    private fun run(cmd: String) {
        status.text = "Working…"
        KolEngineHost.execute(cmd) { activity?.runOnUiThread { if (isAdded) load() } }
    }

    private fun take(it: JSONObject) {
        val name = clean(it.getString("name"))
        askQuantity("Take $name", "The stash has ${it.getInt("count")}.", it.getInt("count"), "Take") { n -> run("stash take $n $name") }
    }

    private fun putItem() {
        KolEngineHost.snapshot { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val arr = o.optJSONArray("inventory")
            val items = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val rows = items.sortedBy { it.getString("name").lowercase() }.map {
                    PickRow(clean(it.getString("name")), "×${it.getInt("count")} in inventory", tag = clean(it.getString("name")) to it.getInt("count"), img = it.optString("img"))
                }
                pickList("Put in the stash", rows) { r ->
                    val (name, count) = r.tag as Pair<*, *>
                    askQuantity("Put $name in the stash", "You have $count.", count as Int, "Put") { n -> run("stash put $n $name") }
                }
            }
        }
    }

    companion object {
        fun show(fm: FragmentManager) = ClanFragment().show(fm, "clan")
    }
}
