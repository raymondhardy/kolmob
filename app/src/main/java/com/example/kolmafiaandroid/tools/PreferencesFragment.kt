package com.example.kolmafiaandroid.tools

import com.example.kolmafiaandroid.ui.KC

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Preferences browser/editor (KoLmafia's "Options").
 */
class PreferencesFragment : DialogFragment() {

    private lateinit var searchEdit: EditText
    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private var allPrefs = mutableListOf<PrefEntry>()
    private var filteredPrefs = mutableListOf<PrefEntry>()

    private data class PrefEntry(
        val name: String,
        val value: String,
        val defaultValue: String,
        val scope: String
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

        // Title + warning
        view.addView(label(ctx, "Preferences", 20f, 0xFF4CAF50.toInt(), true).apply { setPadding(0, 0, 0, dp(ctx, 8)) })
        view.addView(label(ctx, "Changing preferences can break scripts.", 12f, KC.pick(0xFFFFB74D.toInt(), 0xFFE65100.toInt())).apply { setPadding(0, 0, 0, dp(ctx, 16)) })

        // Search box
        searchEdit = EditText(ctx).apply {
            hint = "Search preferences (e.g. battle)..."
            setTextColor(KC.text)
            setHintTextColor(KC.hint)
        }
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { searchSoon(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        view.addView(searchEdit)

        // List
        listView = ListView(ctx)
        view.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { weight = 1f })

        // Empty state
        emptyText = TextView(ctx).apply {
            text = "No preferences found."
            textSize = 16f
            setTextColor(KC.hint)
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
        }
        view.addView(emptyText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { weight = 1f })

        val initial = arguments?.getString("filter").orEmpty()
        if (initial.isNotEmpty()) searchEdit.setText(initial) else loadPreferences()
        return view
    }

    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Search on the engine side. KoLmafia has ~3,900 preferences and the engine returns at most 200 per
     * call, so filtering only the first 200 here hid almost everything (all the per-character ones).
     */
    private fun searchSoon(query: String) {
        searchHandler.removeCallbacksAndMessages(null)
        searchHandler.postDelayed({ loadPreferences(query.trim()) }, 250)
    }

    private fun loadPreferences(filter: String = "") {
        KolEngineHost.preferences(filter) { json ->
            activity?.runOnUiThread {
                if (isAdded) parsePreferences(json)
            }
        }
    }

    private fun parsePreferences(json: String) {
        try {
            val arr = JSONArray(json)
            allPrefs.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                allPrefs.add(PrefEntry(
                    name = obj.getString("name"),
                    value = obj.getString("value"),
                    defaultValue = obj.getString("default"),
                    scope = obj.getString("scope")
                ))
            }
        } catch (e: Exception) {
            allPrefs.clear()
        }
        filteredPrefs = allPrefs.toMutableList()
        updateList()
    }

    private fun filterPrefs(query: String) {
        val q = query.trim().lowercase()
        filteredPrefs = if (q.isEmpty()) allPrefs.toMutableList() else allPrefs.filter { it.name.lowercase().contains(q) }.toMutableList()
        updateList()
    }

    private fun updateList() {
        val ctx = requireContext()
        if (filteredPrefs.isEmpty()) {
            listView.visibility = View.GONE
            emptyText.visibility = View.VISIBLE
        } else {
            listView.visibility = View.VISIBLE
            emptyText.visibility = View.GONE

            val labels = filteredPrefs.map { entry ->
                val truncated = if (entry.value.length > 60) entry.value.substring(0, 60) + "…" else entry.value
                "${entry.name} = $truncated\n${entry.scope} (default: ${entry.defaultValue})"
            }
            val adapter = android.widget.ArrayAdapter<String>(
                ctx,
                android.R.layout.simple_list_item_1,
                labels.toMutableList()
            )
            listView.adapter = adapter
            listView.setOnItemClickListener { _, _, position, _ ->
                if (position < filteredPrefs.size) {
                    showEditDialog(filteredPrefs[position])
                }
            }
        }
    }

    private fun showEditDialog(entry: PrefEntry) {
        val ctx = requireContext()
        val isBoolean = entry.value.equals("true", ignoreCase = true) || entry.value.equals("false", ignoreCase = true)

        if (isBoolean) {
            // Boolean preference - use a switch
            val currentValue = entry.value.equals("true", ignoreCase = true)
            val switch = Switch(ctx).apply {
                isChecked = currentValue
                text = "Enabled"
                setTextColor(KC.text)
            }

            com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(ctx)
                .setTitle("Edit: ${entry.name}")
                .setMessage("Scope: ${entry.scope}\nDefault: ${entry.defaultValue}")
                .setView(switch)
                .setPositiveButton("Save") { _, _ ->
                    val newValue = if (switch.isChecked) "true" else "false"
                    if (newValue != entry.value) {
                        savePreference(entry.name, newValue)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            // String/numeric preference - use multiline edit text
            val input = EditText(ctx).apply {
                setText(entry.value)
                setSelection(entry.value.length)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setTextColor(KC.text)
                setHintTextColor(KC.hint)
                minLines = 3
            }
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 24), dp(ctx, 16), dp(ctx, 24), dp(ctx, 8))
                addView(input)
            }

            com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(ctx)
                .setTitle("Edit: ${entry.name}")
                .setMessage("Scope: ${entry.scope}\nDefault: ${entry.defaultValue}")
                .setView(layout)
                .setPositiveButton("Save") { _, _ ->
                    val newValue = input.text.toString()
                    if (newValue != entry.value) {
                        savePreference(entry.name, newValue)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun savePreference(name: String, value: String) {
        val escapedValue = value.replace("\"", "\\\"")
        val command = "set $name=$escapedValue"
        KolEngineHost.execute(command) {
            // Reload after a short delay to let the engine process
            lifecycleScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(500)
                withContext(Dispatchers.Main) {
                    loadPreferences()
                    Toast.makeText(requireContext(), "Saved: $name", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun dp(context: android.content.Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        fun show(fragmentManager: androidx.fragment.app.FragmentManager, filter: String = "") {
            PreferencesFragment().apply { arguments = android.os.Bundle().apply { putString("filter", filter) } }.show(fragmentManager, "preferences")
        }
    }
}