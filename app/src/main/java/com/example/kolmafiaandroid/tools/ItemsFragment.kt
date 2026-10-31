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
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.askQuantity
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import com.example.kolmafiaandroid.ui.pick
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Item manager: Inventory, Closet, Storage, Display case.
 */
class ItemsFragment : DialogFragment() {

    private data class ItemEntry(val name: String, val count: Int, val img: String = "", val tier: com.example.kolmafiaandroid.ui.ItemTier.Tier? = null, val free: Boolean = false)

    private enum class Tab { INVENTORY, CLOSET, STORAGE, DISPLAY }

    private var currentTab = Tab.INVENTORY
    private lateinit var listView: ListView
    private lateinit var searchEdit: EditText
    private lateinit var filterBar: com.example.kolmafiaandroid.ui.ItemFilterBar
    private lateinit var headerText: TextView
    private var inventoryItems: List<ItemEntry> = emptyList()
    private var closetItems: List<ItemEntry> = emptyList()
    private var storageItems: List<ItemEntry> = emptyList()
    private var displayItems: List<ItemEntry> = emptyList()
    private var closetMeat = 0L
    private var storageMeat = 0L
    private var filteredItems: List<ItemEntry> = emptyList()

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
        // landscape has no room for the controls above the list: they get their own scrolling pane on the left
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val controls = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // Header with meat info
        headerText = label(ctx, "", 13f, KC.muted)
        controls.addView(headerText)

        // Tab buttons
        val tabs = listOf(Tab.INVENTORY, Tab.CLOSET, Tab.STORAGE, Tab.DISPLAY)
        controls.addView(com.example.kolmafiaandroid.ui.textTabs(ctx, listOf("Inventory", "Closet", "Storage", "Display"), tabs.indexOf(currentTab)) { switchTab(tabs[it]) })

        // Crafting and shopping live with the items they use
        controls.addView(com.example.kolmafiaandroid.ui.toolLinks(ctx, requireActivity().supportFragmentManager, "items",
            listOf("Craft items" to { CraftFragment.show(requireActivity().supportFragmentManager, "items") })))

        // Search box
        searchEdit = EditText(ctx).apply {
            hint = "Search items..."
            setTextColor(KC.text)
            setHintTextColor(KC.hint)
        }
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterList(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        controls.addView(searchEdit)
        filterBar = com.example.kolmafiaandroid.ui.ItemFilterBar(ctx, "items") { updateList() }
        controls.addView(filterBar)

        // List
        listView = ListView(ctx)
        controls.addView(TextView(ctx).apply {
            textSize = 11f
            setPadding(com.example.kolmafiaandroid.ui.dp(ctx, 12), 0, com.example.kolmafiaandroid.ui.dp(ctx, 12), com.example.kolmafiaandroid.ui.dp(ctx, 4))
            val sp = android.text.SpannableStringBuilder()
            com.example.kolmafiaandroid.ui.ItemTier.Tier.values().forEach { t ->
                if (sp.isNotEmpty()) sp.append("  ")
                val st = sp.length
                sp.append("● ${t.label}")
                sp.setSpan(android.text.style.ForegroundColorSpan(t.color()), st, sp.length, 0)
            }
            text = sp
        })
        if (land) {
            view.orientation = LinearLayout.HORIZONTAL
            view.addView(android.widget.ScrollView(ctx).apply { addView(controls) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 5f).apply { marginEnd = dp(ctx, 12) })
            view.addView(listView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 5f))
        } else {
            view.addView(controls)
            view.addView(listView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { weight = 1f })
        }

        refreshData()
        return view
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab
        searchEdit.text?.clear()
        updateList()
    }

    private fun formatNumber(n: Long): String {
        return java.text.NumberFormat.getIntegerInstance().format(n)
    }

    private fun refreshData() {
        // Load inventory from snapshot
        KolEngineHost.snapshot { json ->
            try {
                val o = JSONObject(json)
                val arr = o.optJSONArray("inventory") ?: JSONArray()
                inventoryItems = (0 until arr.length()).map { arr.getJSONObject(it) }
                    .map { ItemEntry(android.text.Html.fromHtml(it.getString("name"), 0).toString(), it.getInt("count"), it.optString("img")).let { e -> e.copy(tier = com.example.kolmafiaandroid.ui.ItemTier.of(e.name)) } }
                    .sortedBy { it.name.lowercase() }
            } catch (e: Exception) {
                inventoryItems = emptyList()
            }
            activity?.runOnUiThread { updateList() }
        }

        // Load closet, storage, display from items()
        KolEngineHost.items { json ->
            try {
                val o = JSONObject(json)
                closetMeat = o.optLong("closetMeat")
                storageMeat = o.optLong("storageMeat")

                val closetArr = o.optJSONArray("closet") ?: JSONArray()
                closetItems = (0 until closetArr.length()).map { closetArr.getJSONObject(it) }
                    .map { ItemEntry(android.text.Html.fromHtml(it.getString("name"), 0).toString(), it.getInt("count"), it.optString("img")).let { e -> e.copy(tier = com.example.kolmafiaandroid.ui.ItemTier.of(e.name)) } }
                    .sortedBy { it.name.lowercase() }

                val storageArr = o.optJSONArray("storage") ?: JSONArray()
                storageItems = (0 until storageArr.length()).map { storageArr.getJSONObject(it) }
                    .map { ItemEntry(android.text.Html.fromHtml(it.getString("name"), 0).toString(), it.getInt("count"), it.optString("img"), free = it.optBoolean("free")).let { e -> e.copy(tier = com.example.kolmafiaandroid.ui.ItemTier.of(e.name)) } }
                    .sortedBy { it.name.lowercase() }

                val displayArr = o.optJSONArray("display") ?: JSONArray()
                displayItems = (0 until displayArr.length()).map { displayArr.getJSONObject(it) }
                    .map { ItemEntry(android.text.Html.fromHtml(it.getString("name"), 0).toString(), it.getInt("count"), it.optString("img")).let { e -> e.copy(tier = com.example.kolmafiaandroid.ui.ItemTier.of(e.name)) } }
                    .sortedBy { it.name.lowercase() }
            } catch (e: Exception) {
                closetItems = emptyList()
                storageItems = emptyList()
                displayItems = emptyList()
            }
            activity?.runOnUiThread { updateHeader(); updateList() }
        }
    }

    private fun updateHeader() {
        when (currentTab) {
            Tab.CLOSET -> headerText.text = "Closet meat: ${formatNumber(closetMeat)}"
            Tab.STORAGE -> headerText.text = "Storage meat: ${formatNumber(storageMeat)}"
            else -> headerText.text = ""
        }
    }

    private fun getCurrentItems(): List<ItemEntry> {
        return when (currentTab) {
            Tab.INVENTORY -> inventoryItems
            Tab.CLOSET -> closetItems
            Tab.STORAGE -> storageItems
            Tab.DISPLAY -> displayItems
        }
    }

    private fun updateList() {
        val items = getCurrentItems()
        val query = searchEdit.text.toString().trim().lowercase()
        val searched = if (query.isEmpty()) items else items.filter { it.name.lowercase().contains(query) }
        filteredItems = filterBar.apply(searched, { it.name }, { it.count })

        val ctx = requireContext()
        val shownItems = filteredItems
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = shownItems.size
            override fun getItem(i: Int) = shownItems[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, parent: ViewGroup): View {
                val row = (v as? LinearLayout) ?: LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(com.example.kolmafiaandroid.ui.dp(ctx, 12), com.example.kolmafiaandroid.ui.dp(ctx, 8), com.example.kolmafiaandroid.ui.dp(ctx, 12), com.example.kolmafiaandroid.ui.dp(ctx, 8))
                    addView(android.widget.ImageView(ctx), LinearLayout.LayoutParams(com.example.kolmafiaandroid.ui.dp(ctx, 36), com.example.kolmafiaandroid.ui.dp(ctx, 36)).apply { marginEnd = com.example.kolmafiaandroid.ui.dp(ctx, 12) })
                    addView(TextView(ctx).apply { textSize = 16f; setTextColor(KC.text) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(ctx).apply { textSize = 14f; setTextColor(KC.muted) })
                }
                val it = shownItems[i]
                com.example.kolmafiaandroid.ui.KolImages.load(row.getChildAt(0) as android.widget.ImageView, it.img)
                (row.getChildAt(1) as TextView).apply { text = if (it.free && currentTab == Tab.STORAGE) it.name + "  · free pull" else it.name; setTextColor(it.tier?.color() ?: KC.text) }
                (row.getChildAt(2) as TextView).text = "×${it.count}"
                return row
            }
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            if (position < filteredItems.size) {
                showActions(filteredItems[position])
            }
        }
    }

    private fun filterList(query: String) {
        updateList()
    }

    private fun confirmAutosell(item: ItemEntry) {
        askQuantity("Autosell ${item.name}", "Sell how many? (cannot be undone)", item.count, "Sell") { n ->
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Confirm autosell")
                .setMessage("Really autosell $n ${item.name}? This cannot be undone.")
                .setPositiveButton("Yes, autosell") { _, _ ->
                    KolEngineHost.execute("sell $n ${item.name}") { refreshData() }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun makeUseAction(item: ItemEntry): () -> Unit = {
        askQuantity("Use ${item.name}", "How many?", item.count, "Use") { n ->
            KolEngineHost.execute("use $n ${item.name}") { refreshData() }
        }
    }

    private fun makeClosetPutAction(item: ItemEntry): () -> Unit = {
        askQuantity("Put in closet", "How many?", item.count, "Put") { n ->
            KolEngineHost.execute("closet put $n ${item.name}") { refreshData() }
        }
    }

    private fun makeDisplayPutAction(item: ItemEntry): () -> Unit = {
        askQuantity("Put in display case", "How many?", item.count, "Put") { n ->
            KolEngineHost.execute("display put $n ${item.name}") { refreshData() }
        }
    }

    private fun makeClosetTakeAction(item: ItemEntry): () -> Unit = {
        askQuantity("Take from closet", "How many?", item.count, "Take") { n ->
            KolEngineHost.execute("closet take $n ${item.name}") { refreshData() }
        }
    }

    private fun makeStoragePullAction(item: ItemEntry): () -> Unit = {
        askQuantity("Pull from storage", "How many?", item.count, "Pull") { n ->
            KolEngineHost.execute("pull $n ${item.name}") { refreshData() }
        }
    }

    private fun makeDisplayTakeAction(item: ItemEntry): () -> Unit = {
        askQuantity("Take from display case", "How many?", item.count, "Take") { n ->
            KolEngineHost.execute("display take $n ${item.name}") { refreshData() }
        }
    }

    private fun openWiki(item: ItemEntry) = ItemActions.openWiki(requireContext(), item.name)

    private fun checkMallPrice(item: ItemEntry) = ItemActions.checkMallPrice(this, item.name)

    private fun showActions(item: ItemEntry) {
        if (currentTab == Tab.INVENTORY) {
            KolEngineHost.isMemento(item.name) { kept ->
                KolEngineHost.isJunk(item.name) { junk ->
                    activity?.runOnUiThread { if (isAdded) showActions(item, kept, junk) }
                }
            }
        } else showActions(item, false, false)
    }

    private fun showActions(item: ItemEntry, kept: Boolean, junk: Boolean) {
        val options = mutableListOf<Pair<String, () -> Unit>>()

        when (currentTab) {
            Tab.INVENTORY -> {
                options.add("Use…" to makeUseAction(item))
                options.add("Put in closet…" to makeClosetPutAction(item))
                options.add("Put in display case…" to makeDisplayPutAction(item))
                options.add("Autosell…" to { confirmAutosell(item) })
                options.add((if (junk) "Remove from junk list" else "Mark as junk (for cleanup)") to {
                    KolEngineHost.toggleJunk(item.name) { r ->
                        activity?.runOnUiThread { if (isAdded) android.widget.Toast.makeText(requireContext(), if (r == "on") "${item.name} marked as junk" else if (r == "off") "${item.name} is no longer junk" else r, android.widget.Toast.LENGTH_SHORT).show() }
                    }
                })
                options.add((if (kept) "Stop keeping (allow selling/trading)" else "Keep (protect from selling/trading)") to {
                    KolEngineHost.toggleMemento(item.name) { r ->
                        activity?.runOnUiThread { if (isAdded) android.widget.Toast.makeText(requireContext(), if (r == "on") "${item.name} is now protected" else if (r == "off") "${item.name} is no longer protected" else r, android.widget.Toast.LENGTH_SHORT).show() }
                    }
                })
            }
            Tab.CLOSET -> {
                options.add("Take out…" to makeClosetTakeAction(item))
            }
            Tab.STORAGE -> {
                options.add("Pull…" to makeStoragePullAction(item))
            }
            Tab.DISPLAY -> {
                options.add("Take out…" to makeDisplayTakeAction(item))
            }
        }

        options.add("Check price in the mall" to { checkMallPrice(item) })
        options.add("Open in the KoL wiki" to { openWiki(item) })

        val labels = options.map { it.first }
        pick("${item.name} ×${item.count}", labels, searchable = false) { i ->
            options[i].second()
        }
    }

    companion object {
        fun show(fragmentManager: androidx.fragment.app.FragmentManager) {
            ItemsFragment().show(fragmentManager, "items")
        }
    }
}