package com.example.kolmafiaandroid.tools

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.KC
import com.example.kolmafiaandroid.ui.KolImages
import com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder
import com.example.kolmafiaandroid.ui.askQuantity
import com.example.kolmafiaandroid.ui.clickable
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import com.example.kolmafiaandroid.ui.pick
import com.example.kolmafiaandroid.ui.sectionLabel
import org.json.JSONObject

/**
 * Tapping a skill: a card in the middle of the screen that says what it does (KoL's own description), the effect it gives and that effect's bonuses,
 * how many turns of it you have, and buttons to cast, cast several, extend, cast on someone else, keep it up with your
 * mood, or shrug the effect off.
 */
object SkillSheet {
    /** [skill] is a row from the skills list (name, id, img, mpCost, perCast, buff); [act] runs a KoLmafia command. */
    fun show(fragment: Fragment, skill: JSONObject, mp: Long, act: (String) -> Unit) {
        val ctx = fragment.requireContext()
        val name = skill.getString("name")
        val cost = skill.optLong("mpCost")
        val per = skill.optInt("perCast")
        val buff = skill.optBoolean("buff")
        val affordable = if (cost > 0) (mp / cost).toInt() else 99

        val sheet = Dialog(ctx).apply { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE) }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 20)) }

        // name, icon and the basics
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(ctx).also { KolImages.load(it, skill.optString("img")) }, LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 48)).apply { marginEnd = dp(ctx, 14) })
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label(ctx, name, 19f, KC.text, true))
        val basics = listOfNotNull(
            if (buff) "Buff" else null,
            if (cost > 0) "$cost MP" else "No MP",
            if (per > 0) "$per turns a cast" else null,
        ).joinToString("  ·  ")
        titles.addView(label(ctx, basics, 13f, KC.muted))
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(head)

        val status = label(ctx, "", 14f, KC.accent, true).apply { setPadding(0, dp(ctx, 12), 0, 0); visibility = View.GONE }
        col.addView(status)

        col.addView(sectionLabel(ctx, "What it does", 14))
        val desc = label(ctx, "Loading KoL's description…", 14f, KC.soft)
        col.addView(desc)
        val effectBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(effectBox)

        col.addView(sectionLabel(ctx, "Do", 16))
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(actions)

        fun run(cmd: String) { sheet.dismiss(); act(cmd) }

        /** The buttons, once we know whether the effect is active. */
        fun buildActions(effect: String?, turns: Int) {
            actions.removeAllViews()
            val buttons = mutableListOf<Triple<Int, String, (() -> Unit)?>>()
            val canCast = cost <= mp
            buttons += Triple(R.drawable.web_bolt, "Cast", if (canCast) ({ run("cast 1 $name") }) else null)
            buttons += Triple(R.drawable.ic_add, "Cast ×…", if (canCast) ({
                sheet.dismiss()
                fragment.askQuantity("Cast $name", "Costs $cost MP a cast; you can afford $affordable now." + (if (per > 0) "\nEach cast gives $per turns." else ""), affordable.coerceAtLeast(1), "Cast") { n -> act("cast $n $name") }
            }) else null)
            // anything that gives turns of an effect can be extended and kept up; only buffs can go to another player
            if (effect != null && per > 0) {
                buttons += Triple(R.drawable.web_reload, "Extend", { extend(fragment, sheet, name, effect, turns, per, cost, mp, act) })
                buttons += Triple(R.drawable.ic_s_play, "Keep up", {
                    run("trigger lose_effect, $effect, cast 1 $name")
                    Toast.makeText(ctx, "Your mood now keeps $effect up", Toast.LENGTH_SHORT).show()
                })
            }
            if (buff) buttons += Triple(R.drawable.web_person, "On someone", if (canCast) ({ castOnSomeone(ctx, sheet, name, act) }) else null)
            if (effect != null && turns > 0) buttons += Triple(R.drawable.ic_close, "Shrug", {
                ScrollSafeDialogBuilder(ctx).setTitle("Shrug off $effect?")
                    .setMessage("You lose the $turns turns you have left.")
                    .setPositiveButton("Shrug") { _, _ -> run("uneffect $effect") }
                    .setNegativeButton("Cancel", null).show()
            })
            for (rowItems in buttons.chunked(3)) {
                val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                rowItems.forEachIndexed { i, (icon, text, onTap) ->
                    r.addView(actionButton(ctx, icon, text, onTap), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i < 2) marginEnd = dp(ctx, 8) })
                }
                repeat(3 - rowItems.size) { k -> r.addView(View(ctx), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (rowItems.size + k < 2) marginEnd = dp(ctx, 8) }) }
                actions.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) })
            }
            if (!canCast) actions.addView(label(ctx, "You need $cost MP to cast it; you have $mp.", 12f, KC.bad))
        }
        buildActions(null, 0)

        // a card floating in the middle of the screen, with the page dimmed behind it
        sheet.setContentView(ScrollView(ctx).apply {
            addView(col)
            background = GradientDrawable().apply { cornerRadius = dp(ctx, 20).toFloat(); setColor(KC.surface); setStroke(dp(ctx, 1), KC.faint) }
            clipToOutline = true
        })
        sheet.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(ctx.resources.displayMetrics.widthPixels - 2 * dp(ctx, 16), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        sheet.show()

        KolEngineHost.skillInfo(skill.optInt("id")) { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            fragment.activity?.runOnUiThread {
                if (!sheet.isShowing) return@runOnUiThread
                desc.text = cleanDescription(o.optString("desc"), o.optJSONObject("effect") != null)
                    .ifEmpty { "KoL didn't send a description for this skill." }
                val left = o.optLong("castsLeft", -1)
                val e = o.optJSONObject("effect")
                val lines = mutableListOf<String>()
                if (e != null) {
                    val turns = e.optInt("turns")
                    lines += if (turns > 0) "You have ${e.optString("name")}: $turns turns" else "${e.optString("name")} isn't active"
                }
                if (left >= 0) lines += "$left cast${if (left == 1L) "" else "s"} left today"
                if (lines.isNotEmpty()) { status.text = lines.joinToString("\n"); status.visibility = View.VISIBLE }
                if (e != null) {
                    effectBox.addView(sectionLabel(ctx, "Gives", 14))
                    val er = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    er.addView(ImageView(ctx).also { KolImages.load(it, e.optString("img")) }, LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30)).apply { marginEnd = dp(ctx, 10) })
                    er.addView(label(ctx, e.optString("name"), 15f, KC.text, true))
                    effectBox.addView(er)
                    val mods = e.optJSONArray("mods")
                    for (i in 0 until (mods?.length() ?: 0)) {
                        effectBox.addView(label(ctx, "•  " + readableModifier(mods!!.getString(i)), 14f, KC.soft).apply { setPadding(dp(ctx, 4), dp(ctx, 3), 0, 0) })
                    }
                    buildActions(e.optString("name"), e.optInt("turns"))
                }
            }
        }
    }

    /** KoL ends the description with "Gives Effect: X (N Adventures)"; the Gives section shows that already. */
    private fun cleanDescription(text: String, hasEffect: Boolean): String {
        if (!hasEffect) return text
        return text.lines().filterNot { it.trim().startsWith("Gives Effect", ignoreCase = true) || Regex("""^\(\d+ Adventures?\)$""").matches(it.trim()) }
            .joinToString("\n").trim()
    }

    /** "Mysticality Percent: +50" reads "Mysticality +50%"; "Item Drop: +10" reads "Item Drop +10". */
    private fun readableModifier(m: String): String {
        val name = m.substringBefore(':').trim()
        val value = m.substringAfter(':', "").trim()
        if (value.isEmpty()) return m
        return if (name.endsWith(" Percent")) "${name.removeSuffix(" Percent")} $value%" else "$name $value"
    }

    private fun actionButton(ctx: Context, icon: Int, text: String, onTap: (() -> Unit)?): View {
        val b = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 6), dp(ctx, 12), dp(ctx, 6), dp(ctx, 10))
            background = GradientDrawable().apply { cornerRadius = dp(ctx, 12).toFloat(); setStroke(dp(ctx, 1), KC.faint) }
        }
        b.addView(ImageView(ctx).apply {
            setImageDrawable(ContextCompat.getDrawable(ctx, icon))
            imageTintList = ColorStateList.valueOf(KC.accent)
        }, LinearLayout.LayoutParams(dp(ctx, 26), dp(ctx, 26)))
        b.addView(TextView(ctx).apply { this.text = text; textSize = 13f; setTextColor(KC.text); gravity = Gravity.CENTER; setPadding(0, dp(ctx, 6), 0, 0) })
        if (onTap != null) clickable(b, onTap) else b.alpha = 0.35f
        return b
    }

    /** Extend to a round number of turns: how many casts and how much MP each target takes. */
    private fun extend(fragment: Fragment, sheet: Dialog, skill: String, effect: String, turns: Int, per: Int, cost: Long, mp: Long, act: (String) -> Unit) {
        val options = mutableListOf<Pair<String, Int>>()
        for (target in listOf(50, 100, 200, 500)) {
            if (turns < target) {
                val n = (target - turns + per - 1) / per
                val mpNote = if (cost > 0) ", ${n * cost} MP" + (if (n * cost > mp) ": more than you have" else "") else ""
                options += "To $target turns ($n cast${if (n == 1) "" else "s"}$mpNote)" to n
            }
        }
        options += "+$per turns (1 cast)" to 1
        sheet.dismiss()
        fragment.pick("Extend $effect  (${turns} turns now)", options.map { it.first }, searchable = false) { i -> act("cast ${options[i].second} $skill") }
    }

    private fun castOnSomeone(ctx: Context, sheet: Dialog, skill: String, act: (String) -> Unit) {
        val input = EditText(ctx).apply { hint = "Player name"; maxLines = 1 }
        val box = LinearLayout(ctx).apply { setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), 0); addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        ScrollSafeDialogBuilder(ctx).setTitle("Cast $skill on whom?").setView(box)
            .setPositiveButton("Cast") { _, _ ->
                val who = input.text.toString().trim()
                if (who.isNotEmpty()) { sheet.dismiss(); act("cast 1 $skill on $who") }
            }
            .setNegativeButton("Cancel", null).show()
    }
}
