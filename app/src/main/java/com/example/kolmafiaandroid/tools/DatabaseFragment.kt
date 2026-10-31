package com.example.kolmafiaandroid.tools

import com.example.kolmafiaandroid.ui.KC

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.chip
import com.example.kolmafiaandroid.ui.clickable
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Database browser for items, monsters, skills, and effects.
 */
class DatabaseFragment : DialogFragment() {

    private lateinit var searchEdit: EditText
    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private lateinit var chipContainer: LinearLayout
    private lateinit var filterBar: com.example.kolmafiaandroid.ui.ItemFilterBar

    private var currentKind = "item"
    private var searchResults: MutableList<DbEntry> = mutableListOf()

    private data class DbEntry(val id: Int, val name: String, val summary: String)

    private val kinds = listOf(
        "item" to "Items",
        "monster" to "Monsters",
        "skill" to "Skills",
        "effect" to "Effects"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(DialogFragment.STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
        }

        // Title
        view.addView(label(ctx, "Database", 20f, 0xFF4CAF50.toInt(), true).apply { setPadding(0, 0, 0, dp(ctx, 16)) })

        // Kind chips
        chipContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for ((kind, label) in kinds) {
            val chip = ctx.chip(label) { setKind(kind) }
            chipContainer.addView(chip)
        }
        view.addView(chipContainer)

        // Search box
        searchEdit = EditText(ctx).apply {
            hint = "Search (min 2 chars)..."
            setTextColor(KC.text)
            setHintTextColor(KC.hint)
        }
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { debouncedSearch(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        view.addView(searchEdit)
        filterBar = com.example.kolmafiaandroid.ui.ItemFilterBar(ctx, "database") { if (isAdded) updateList() }
        view.addView(filterBar)

        // List
        listView = ListView(ctx)
        view.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { weight = 1f })

        // Empty state
        emptyText = TextView(ctx).apply {
            text = "Select a category and type to search..."
            textSize = 16f
            setTextColor(KC.hint)
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
        }
        view.addView(emptyText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { weight = 1f })

        // Set initial kind
        setKind(currentKind)
        return view
    }

    private fun setKind(kind: String) {
        currentKind = kind
        // Update chip selection
        for (i in 0 until chipContainer.childCount) {
            val child = chipContainer.getChildAt(i) as LinearLayout
            val chipTag = child.tag as String
            val isSelected = chipTag == kind
            child.setBackgroundColor(if (isSelected) 0xFF4CAF50.toInt() else KC.faint)
        }
        searchEdit.text?.clear()
        searchResults.clear()
        updateList()
    }

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private fun debouncedSearch(query: String) {
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        searchRunnable = Runnable { performSearch(query) }
        searchRunnable?.let { searchHandler.postDelayed(it, 300) }
    }

    private fun performSearch(query: String) {
        val q = query.trim().lowercase()
        if (q.length < 2) {
            searchResults.clear()
            updateList()
            return
        }
        KolEngineHost.lookup(currentKind, q) { json ->
            activity?.runOnUiThread {
                parseResults(json)
            }
        }
    }

    private fun parseResults(json: String) {
        try {
            val arr = JSONArray(json)
            searchResults = mutableListOf()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                searchResults.add(DbEntry(
                    id = obj.getInt("id"),
                    name = obj.getString("name"),
                    summary = obj.getString("summary")
                ))
            }
        } catch (e: Exception) {
            searchResults.clear()
        }
        updateList()
    }

    private fun updateList() {
        val ctx = requireContext()
        // the stat / food / equipment filter only means something for items
        filterBar.visibility = if (currentKind == "item") View.VISIBLE else View.GONE
        val results = if (currentKind == "item") filterBar.apply(searchResults, { it.name }, { 0 }) else searchResults
        if (results.isEmpty()) {
            listView.visibility = View.GONE
            emptyText.visibility = View.VISIBLE
            emptyText.text = if (searchEdit.text.toString().trim().length >= 2) {
                "No results found"
            } else {
                "Type at least 2 characters to search"
            }
        } else {
            listView.visibility = View.VISIBLE
            emptyText.visibility = View.GONE

            val labels = results.map { "${it.name}\n${it.summary}" }
            val adapter = android.widget.ArrayAdapter<String>(
                ctx,
                android.R.layout.simple_list_item_1,
                labels.toMutableList()
            )
            listView.adapter = adapter
            listView.setOnItemClickListener { _, _, position, _ ->
                if (position < results.size) {
                    showDescribe(results[position])
                }
            }
        }
    }

    /** A quick fight simulator: the monster's stats against your character as you stand right now. */
    private fun fightOdds(monster: String) {
        val m = "to_monster(${com.example.kolmafiaandroid.ui.AshProbe.q(monster)})"
        val dmg = "expected_damage($m)"
        val expr = listOf(
            "\"HP \" + monster_hp($m) + \"  |  attack \" + monster_attack($m) + \"  |  defense \" + monster_defense($m) + \"  |  initiative \" + monster_initiative($m)",
            "\"\\n\\nIt hits you for about \" + $dmg + \" a round.\"",
            "\"\\nYou have \" + my_hp() + \" of \" + my_maxhp() + \" HP, so about \" + ($dmg > 0 ? to_string(my_hp() / $dmg) : \"many\") + \" rounds before you drop.\"",
            "\"\\n\\nYour muscle \" + my_buffedstat(\$stat[muscle]) + \", mysticality \" + my_buffedstat(\$stat[mysticality]) + \", moxie \" + my_buffedstat(\$stat[moxie])",
        ).joinToString(" + ")
        com.example.kolmafiaandroid.ui.AshProbe.eval(expr) { text ->
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(requireContext())
                    .setTitle("Fight odds: $monster")
                    .setMessage(text.ifBlank { "Could not work that out. Are you logged in?" })
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    private fun showDescribe(entry: DbEntry) {
        KolEngineHost.describe(currentKind, entry.id) { text ->
            activity?.runOnUiThread {
                showDescribeDialog(entry.name, text)
            }
        }
    }

    private fun showDescribeDialog(title: String, content: String) {
        val ctx = requireContext()
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
        }

        val textView = TextView(ctx).apply {
            text = content
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setTextColor(KC.soft)
            setTextIsSelectable(true)
            movementMethod = android.text.method.ScrollingMovementMethod()
        }

        val scrollView = android.widget.ScrollView(ctx).apply {
            addView(textView)
            setBackgroundResource(R.drawable.bg_console_output)
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
        }

        view.addView(scrollView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { weight = 1f })

        com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(ctx)
            .setTitle(title)
            .setView(view)
            .setNegativeButton("Close", null)
            .apply {
                if (currentKind == "monster") setNeutralButton("Fight odds") { _, _ -> fightOdds(title) }
            }
            .create()
            .show()
    }

    private fun dp(context: android.content.Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        fun show(fragmentManager: androidx.fragment.app.FragmentManager) {
            DatabaseFragment().show(fragmentManager, "database")
        }
    }
}