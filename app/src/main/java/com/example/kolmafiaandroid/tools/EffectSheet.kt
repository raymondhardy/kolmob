package com.example.kolmafiaandroid.tools

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.KC
import com.example.kolmafiaandroid.ui.KolImages
import com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder
import com.example.kolmafiaandroid.ui.askQuantity
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import com.example.kolmafiaandroid.ui.pick
import com.example.kolmafiaandroid.ui.sectionLabel
import org.json.JSONObject

/**
 * Tapping an active effect that isn't from one of your own skills: the same floating card as a skill, with KoL's
 * description of the effect, its bonuses, where more of it comes from (and how many of that item you have), and
 * buttons to extend it, keep it up with your mood, or remove it. Effects from your skills open [SkillSheet] instead.
 */
object EffectSheet {
    /** [effect] is a row from the active effects list (name, turns, img, action); [act] runs a KoLmafia command. */
    fun show(fragment: Fragment, effect: JSONObject, act: (String) -> Unit) {
        val ctx = fragment.requireContext()
        val name = effect.getString("name")
        val turns = effect.optInt("turns")

        val scroll = ScrollView(ctx)
        val card = SkillSheet.floatingCard(ctx, scroll)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 20)) }

        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(ctx).also { KolImages.load(it, effect.optString("img")) }, LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 48)).apply { marginEnd = dp(ctx, 14) })
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label(ctx, name, 19f, KC.text, true))
        val sub = label(ctx, "$turns turn${if (turns == 1) "" else "s"} left", 13f, KC.muted)
        titles.addView(sub)
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(head)

        col.addView(sectionLabel(ctx, "What it does", 14))
        val desc = label(ctx, "Loading KoL's description…", 14f, KC.soft)
        col.addView(desc)
        val details = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(details)
        col.addView(sectionLabel(ctx, "Do", 16))
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(actions)

        scroll.addView(col)
        card.show()

        fun run(cmd: String) { card.dismiss(); act(cmd) }

        KolEngineHost.effectInfo(name) { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            fragment.activity?.runOnUiThread {
                if (!card.isShowing) return@runOnUiThread
                desc.text = o.optString("desc").ifEmpty { "KoL didn't send a description for this effect." }
                if (o.optString("quality") == "bad") { sub.text = "${sub.text}  ·  a bad effect"; sub.setTextColor(KC.bad) }

                val mods = o.optJSONArray("mods")
                if (mods != null && mods.length() > 0) {
                    details.addView(sectionLabel(ctx, "Bonuses", 14))
                    for (i in 0 until mods.length()) {
                        details.addView(label(ctx, "•  " + SkillSheet.readableModifier(mods.getString(i)), 14f, KC.soft).apply { setPadding(dp(ctx, 4), dp(ctx, 3), 0, 0) })
                    }
                }

                val action = o.optString("action")
                val item = o.optString("item")
                val have = o.optInt("have")
                val perUse = o.optInt("perUse")
                details.addView(sectionLabel(ctx, "Comes from", 14))
                val sources = o.optJSONArray("sources")
                val others = o.optInt("otherSources")
                if (sources != null && sources.length() > 0) {
                    for (i in 0 until sources.length()) {
                        val src = sources.getJSONObject(i)
                        val each = src.optInt("perUse")
                        details.addView(label(ctx, "•  ${src.optString("item")}  ·  you have ${src.optInt("have")}" + (if (each > 0) "  ·  $each turns each" else ""), 14f, KC.soft).apply { setPadding(dp(ctx, 4), dp(ctx, 3), 0, 0) })
                    }
                    if (others > 0) details.addView(label(ctx, "…and $others other item${if (others == 1) "" else "s"} you don't have", 13f, KC.muted).apply { setPadding(dp(ctx, 4), dp(ctx, 3), 0, 0) })
                } else details.addView(label(ctx, when {
                    others > 0 -> "$others different items give it; you have none of them."
                    action.isNotEmpty() -> action
                    o.optString("note").isNotEmpty() -> o.optString("note")
                    else -> "KoLmafia doesn't know a way to get more of this."
                }, 14f, KC.soft))

                // "use 1 X" with a different count; actions without a count (e.g. a telescope) only run once
                val counted = Regex("""^(\w+)\s+\d+\s+(.+)$""").find(action)
                fun times(n: Int) = if (counted != null) "${counted.groupValues[1]} $n ${counted.groupValues[2]}" else action
                val buttons = mutableListOf<Triple<Int, String, (() -> Unit)?>>()
                if (action.isNotEmpty()) {
                    if (item.isNotEmpty()) {
                        // more turns from an item: say it uses the item up, and ask first
                        fun useItems(n: Int) {
                            ScrollSafeDialogBuilder(ctx).setTitle("Use $n × $item?")
                                .setMessage("You have $have." + (if (perUse > 0) " That's ${n * perUse} more turns of $name." else "") + (if (n > have) "\nKoLmafia will try to get the rest." else ""))
                                .setPositiveButton("Use") { _, _ -> run(times(n)) }
                                .setNegativeButton("Cancel", null).show()
                        }
                        buttons += Triple(R.drawable.web_reload, "Use 1", { useItems(1) })
                        if (counted != null) buttons += Triple(R.drawable.ic_add, "Use ×…", {
                            card.dismiss()
                            fragment.askQuantity("Use $item", "You have $have." + (if (perUse > 0) " Each gives $perUse turns of $name." else ""), have.coerceAtLeast(1), "Use") { n -> act(times(n)) }
                        })
                        if (counted != null && perUse > 0) buttons += Triple(R.drawable.ic_s_add, "Use to…", {
                            card.dismiss()
                            val options = listOf(50, 100, 200, 500).filter { it > turns }.map { target ->
                                val n = (target - turns + perUse - 1) / perUse
                                "To $target turns ($n × $item" + (if (n > have) ": you have $have" else "") + ")" to n
                            }
                            if (options.isEmpty()) Toast.makeText(ctx, "Already over 500 turns", Toast.LENGTH_SHORT).show()
                            else fragment.pick("Extend $name  ($turns turns now)", options.map { it.first }, searchable = false) { i -> act(times(options[i].second)) }
                        })
                    } else {
                        buttons += Triple(R.drawable.web_reload, "Extend", { run(times(1)) })
                        if (counted != null) buttons += Triple(R.drawable.ic_add, "Extend ×…", {
                            card.dismiss()
                            fragment.askQuantity("Extend $name", "How many times? KoLmafia runs: $action", 99, "Go") { n -> act(times(n)) }
                        })
                    }
                    buttons += Triple(R.drawable.ic_s_play, "Keep up", {
                        run("trigger lose_effect, $name, ${o.optString("keepUp").ifEmpty { action }}")
                        Toast.makeText(ctx, "Your mood now keeps $name up", Toast.LENGTH_SHORT).show()
                    })
                }
                val shrug = o.optBoolean("shruggable")
                if (turns > 0 && (shrug || o.optBoolean("removable"))) buttons += SkillSheet.removeButton(ctx, name, turns, shrug) { run("uneffect $name") }
                if (buttons.isEmpty()) actions.addView(label(ctx, "Nothing to do with this one: it can't be extended or removed.", 13f, KC.muted))
                else actions.addView(SkillSheet.actionGrid(ctx, buttons))
            }
        }
    }
}
