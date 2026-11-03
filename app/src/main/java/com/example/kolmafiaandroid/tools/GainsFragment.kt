package com.example.kolmafiaandroid.tools

import com.example.kolmafiaandroid.ui.KC

import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
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
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import org.json.JSONObject
import java.text.NumberFormat

/** What you've gained this session: meat, stats, items and effects (KoLmafia's session tally). */
class GainsFragment : DialogFragment() {
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
        root.addView(label(ctx, "Session gains", 20f, 0xFF4CAF50.toInt(), true))
        status = label(ctx, "Reading the session tally…", 13f, KC.muted).apply { setPadding(0, dp(ctx, 4), 0, dp(ctx, 8)) }
        root.addView(status)
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(android.widget.ScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        load()
        return root
    }

    private fun load() {
        KolEngineHost.gains { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val arr = o.optJSONArray("gains")
            val list = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                status.text = when {
                    o.has("error") -> "Couldn't read the session tally."
                    list.isEmpty() -> "Nothing gained yet this session."
                    else -> "${list.size} entries since you logged in"
                }
                render(list)
            }
        }
    }

    private fun render(list: List<JSONObject>) {
        val ctx = context ?: return
        renderInto(ctx, body, list)
    }

    companion object {

        private fun parse(json: String): Pair<Boolean, List<JSONObject>> {
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val arr = o.optJSONArray("gains")
            return o.has("error") to (if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) })
        }

        /** A short card: one summary line, tap to expand the full tally in place. */
        fun card(ctx: Context, parent: LinearLayout) {
            val fmt = NumberFormat.getIntegerInstance()
            val c = com.example.kolmafiaandroid.ui.card(ctx, parent)
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label(ctx, "SESSION GAINS", 12f, 0xFF4CAF50.toInt(), true).apply { letterSpacing = 0.1f })
            val summary = label(ctx, "Reading the session tally…", 14f, KC.muted).apply { setPadding(0, dp(ctx, 2), 0, 0) }
            texts.addView(summary)
            head.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            val chev = label(ctx, "▾", 18f, KC.muted)
            head.addView(chev)
            c.addView(head)
            val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            c.addView(body)
            var data: List<JSONObject> = emptyList()
            var open = false
            fun load() {
                KolEngineHost.gains { json ->
                    val (err, list) = parse(json)
                    Handler(Looper.getMainLooper()).post {
                        if (!c.isAttachedToWindow) return@post
                        data = list
                        val meat = list.filter { it.optString("kind") == "meat" }.sumOf { it.optLong("count") }
                        summary.text = when {
                            err -> "Couldn't read the session tally."
                            list.isEmpty() -> "Nothing gained yet this session."
                            else -> (if (meat != 0L) (if (meat > 0) "+" else "") + fmt.format(meat) + " meat · " else "") +
                                (if (list.size == 1) "1 entry" else "${list.size} entries")
                        }
                        if (open) renderInto(ctx, body, data)
                    }
                }
            }
            com.example.kolmafiaandroid.ui.clickable(c) {
                open = !open
                body.visibility = if (open) View.VISIBLE else View.GONE
                chev.text = if (open) "▴" else "▾"
                if (open) { renderInto(ctx, body, data); load() }
            }
            load()
        }

        fun renderInto(ctx: Context, body: LinearLayout, list: List<JSONObject>) {
            val fmt = NumberFormat.getIntegerInstance()
            body.removeAllViews()
            val order = listOf("meat" to "Meat", "stat" to "Stats", "item" to "Items", "effect" to "Effects")
            for ((kind, title) in order) {
                val rows = list.filter { it.optString("kind") == kind }.sortedBy { it.optString("name").lowercase() }
                if (rows.isEmpty()) continue
                body.addView(label(ctx, title.uppercase(), 12f, 0xFF4CAF50.toInt(), true).apply { letterSpacing = 0.1f; setPadding(dp(ctx, 4), dp(ctx, 16), 0, dp(ctx, 4)) })
                for (r in rows) {
                    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6)) }
                    if (kind == "item") {
                        val icon = ImageView(ctx)
                        KolImages.load(icon, r.optString("img"))
                        row.addView(icon, LinearLayout.LayoutParams(dp(ctx, 32), dp(ctx, 32)).apply { marginEnd = dp(ctx, 12) })
                    }
                    row.addView(label(ctx, android.text.Html.fromHtml(r.getString("name"), 0).toString(), 15f, KC.text), LinearLayout.LayoutParams(0, -2, 1f))
                    val c = r.getLong("count")
                    row.addView(label(ctx, (if (c > 0) "+" else "") + fmt.format(c), 15f, if (c >= 0) 0xFF4CAF50.toInt() else 0xFFEF5350.toInt(), true))
                    body.addView(row)
                }
            }
        }

        fun show(fm: FragmentManager) = GainsFragment().show(fm, "gains")
    }
}
