package com.example.kolmafiaandroid.tools

import com.example.kolmafiaandroid.ui.KC

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
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

/** Crafting: everything KoLmafia says you can make right now; tap one to make it (runs `create`). */
class CraftFragment : DialogFragment() {
    private class Recipe(val name: String, val img: String, val possible: Int, val method: String, val ingredients: String)

    private var all = listOf<Recipe>()
    private var filter = ""
    /** "food" = cooking and cocktail crafting; "items" = everything else (smithing, jewelry, meat paste...); "all" = no split. */
    private val mode: String get() = arguments?.getString("mode") ?: "all"
    private fun inMode(m: String): Boolean {
        if (m == "CLIPART") return false // the Summon Clip Art skill's results are not crafting and would bury the real recipes
        val foody = m in setOf("COOK", "COOK_FANCY", "MIX", "MIX_FANCY", "STILL")
        return when (mode) { "food" -> foody; "items" -> !foody; else -> true }
    }
    private var filterBar: com.example.kolmafiaandroid.ui.ItemFilterBar? = null
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
        root.addView(label(ctx, when (mode) { "food" -> "Craft food & drinks"; "items" -> "Craft items"; else -> "Craft" }, 20f, 0xFF4CAF50.toInt(), true))
        val search = EditText(ctx).apply {
            hint = "Filter what you can make"
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { filter = s.toString().trim().lowercase(); render() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        root.addView(search)
        root.addView(com.example.kolmafiaandroid.ui.ItemFilterBar(ctx, "craft") { render() }.also { filterBar = it })
        status = label(ctx, "Checking what you can make…", 13f, KC.muted).apply { setPadding(0, dp(ctx, 4), 0, dp(ctx, 8)) }
        root.addView(status)
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(android.widget.ScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        load()
        return root
    }

    private fun load() {
        KolEngineHost.creatables { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            val arr = o.optJSONArray("items")
            val list = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                Recipe(android.text.Html.fromHtml(it.optString("name"), 0).toString(), it.optString("img"), it.optInt("possible"), it.optString("method"), it.optString("ingredients"))
            }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                all = list.filter { inMode(it.method) }.sortedWith(compareBy({ prettyMethod(it.method) }, { it.name.lowercase() }))
                status.text = if (o.has("error")) "Could not load recipes. Are you logged in?" else "${all.size} things you can make · tap one"
                render()
            }
        }
    }

    private fun prettyMethod(m: String) = when (m) {
        "COMBINE" -> "Meat paste"
        "COOK", "COOK_FANCY" -> "Cooking"
        "MIX", "MIX_FANCY" -> "Cocktail crafting"
        "SMITH", "SSMITH", "SMITH_ARMOR", "SMITH_WEAPON" -> "Smithing"
        "JEWELRY" -> "Jewelry"
        "STILL" -> "Still"
        "" -> "Other"
        else -> m.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun render() {
        val ctx = context ?: return
        body.removeAllViews()
        var group = ""
        val searched = all.filter { filter.isEmpty() || it.name.lowercase().contains(filter) }
        // the bar sorts and filters; the stable sort by method keeps that order inside each crafting group
        val shown = filterBar?.apply(searched, { it.name }, { it.possible })?.sortedBy { prettyMethod(it.method) } ?: searched
        for (r in shown) {
            val g = prettyMethod(r.method)
            if (g != group) {
                group = g
                body.addView(label(ctx, g.uppercase(), 12f, 0xFF4CAF50.toInt(), true).apply { letterSpacing = 0.1f; setPadding(dp(ctx, 4), dp(ctx, 16), 0, dp(ctx, 4)) })
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 4), dp(ctx, 8), dp(ctx, 4), dp(ctx, 8))
            }
            val icon = ImageView(ctx)
            KolImages.load(icon, r.img)
            row.addView(icon, LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)).apply { marginEnd = dp(ctx, 12) })
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(label(ctx, r.name, 16f, KC.text))
            if (r.ingredients.isNotEmpty()) col.addView(label(ctx, r.ingredients, 12f, KC.muted))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label(ctx, "×${r.possible}", 14f, KC.muted))
            com.example.kolmafiaandroid.ui.clickable(row) { make(r) }
            body.addView(row)
        }
    }

    private fun make(r: Recipe) {
        askQuantity("Make ${r.name}", r.ingredients, r.possible, "Make") { n ->
            status.text = "Making $n × ${r.name}…"
            KolEngineHost.execute("create $n ${r.name}") {
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    Toast.makeText(requireContext(), "Done", Toast.LENGTH_SHORT).show()
                    load()
                }
            }
        }
    }

    companion object {
        fun show(fm: FragmentManager, mode: String = "all") = CraftFragment().apply { arguments = Bundle().apply { putString("mode", mode) } }.show(fm, "craft")
    }
}
