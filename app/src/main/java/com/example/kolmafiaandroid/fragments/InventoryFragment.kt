package com.example.kolmafiaandroid.fragments

import com.example.kolmafiaandroid.ui.KC

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.kolmafiaandroid.KolEngineHost
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Inventory: every item the logged-in character carries, with search. */
class InventoryFragment : Fragment() {
    private var all: List<Pair<String, Int>> = emptyList()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var header: TextView
    private lateinit var search: EditText

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), 0) }
        header = TextView(ctx).apply { textSize = 18f; setTextColor(KC.text) }
        search = EditText(ctx).apply {
            hint = "Search items"
            setHintTextColor(KC.hint)
            setTextColor(KC.text)
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply { setTextColor(KC.soft) }
        }
        val list = ListView(ctx).apply { this.adapter = this@InventoryFragment.adapter }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        root.addView(header)
        root.addView(search)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch { KolEngineHost.loginState.collect { load() } }
    }

    override fun onResume() {
        super.onResume()
        if (view != null) load()
    }

    private fun load() {
        KolEngineHost.snapshot { json ->
            val o = JSONObject(json)
            val inv = o.optJSONArray("inventory")
            val items = (0 until (inv?.length() ?: 0)).map { inv!!.getJSONObject(it).let { j -> j.getString("name") to j.getInt("count") } }
                .sortedBy { it.first.lowercase() }
            activity?.runOnUiThread {
                if (!isAdded || view == null) return@runOnUiThread
                all = items
                header.text = if (o.optBoolean("loggedIn")) "Inventory: ${items.size} kinds, ${items.sumOf { it.second }} items" else "Not logged in"
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        adapter.clear()
        adapter.addAll(all.filter { q.isEmpty() || it.first.lowercase().contains(q) }.map { "${it.first}  ×${it.second}" })
    }
}
