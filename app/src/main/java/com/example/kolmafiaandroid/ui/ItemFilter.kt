package com.example.kolmafiaandroid.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.sourceforge.kolmafia.KoLConstants.ConsumptionType
import net.sourceforge.kolmafia.persistence.ConsumablesDatabase
import net.sourceforge.kolmafia.persistence.ItemDatabase
import net.sourceforge.kolmafia.persistence.MallPriceDatabase

/** What kind of thing an item is, whether it gives stats, and what it is worth: all looked up from KoLmafia's databases. */
object ItemInfo {
    private val kinds = HashMap<String, String>()
    private val stats = HashMap<String, Boolean>()
    private val values = HashMap<String, Long>()

    private fun id(name: String): Int = try { ItemDatabase.getItemId(name, 1, false) } catch (_: Throwable) { -1 }

    /** One of FOOD, BOOZE, SPLEEN, EQUIPMENT, USABLE, OTHER. */
    @Synchronized fun kind(name: String): String = kinds.getOrPut(name) {
        val i = id(name)
        if (i <= 0) "OTHER" else when (try { ItemDatabase.getConsumptionType(i) } catch (_: Throwable) { ConsumptionType.NONE }) {
            ConsumptionType.EAT -> "FOOD"
            ConsumptionType.DRINK -> "BOOZE"
            ConsumptionType.SPLEEN -> "SPLEEN"
            ConsumptionType.HAT, ConsumptionType.WEAPON, ConsumptionType.OFFHAND, ConsumptionType.CONTAINER, ConsumptionType.SHIRT,
            ConsumptionType.PANTS, ConsumptionType.ACCESSORY, ConsumptionType.FAMILIAR_EQUIPMENT -> "EQUIPMENT"
            ConsumptionType.USE, ConsumptionType.USE_MULTIPLE, ConsumptionType.USE_INFINITE, ConsumptionType.USE_MESSAGE_DISPLAY,
            ConsumptionType.FOOD_HELPER, ConsumptionType.DRINK_HELPER, ConsumptionType.ZAP -> "USABLE"
            else -> "OTHER"
        }
    }

    /** True for food, booze and spleen items that give Muscle, Mysticality or Moxie when consumed. */
    @Synchronized fun givesStats(name: String): Boolean = stats.getOrPut(name) {
        try {
            listOf(ConsumablesDatabase.getMuscleRange(name), ConsumablesDatabase.getMysticalityRange(name), ConsumablesDatabase.getMoxieRange(name))
                .any { r -> r != null && r.isNotBlank() && r.trim() != "0" && r.trim() != "0-0" }
        } catch (_: Throwable) { false }
    }

    /** The better of the mall price and the autosell price (0 when unknown). */
    @Synchronized fun value(name: String): Long = values.getOrPut(name) {
        val i = id(name)
        if (i <= 0) 0L else try { maxOf(MallPriceDatabase.getPrice(i), ItemDatabase.getPriceById(i).toLong()) } catch (_: Throwable) { 0L }
    }
}

/**
 * The row that sits next to an item search box: a Filter button (check boxes: Consumable, Food, Booze, Spleen, Gives stats,
 * Equipment, Usable, Other; "match all" or "match any") and a Sort button (name, value, count; up or down).
 * Call [apply] with the full list to get the filtered, sorted list; [onChange] fires when either button changes something.
 */
class ItemFilterBar(ctx: Context, private val key: String, private val onChange: () -> Unit) : LinearLayout(ctx) {
    private val prefs = ctx.getSharedPreferences("kolmob", Context.MODE_PRIVATE)
    private val options = listOf(
        "CONSUMABLE" to "Consumable (food, booze, spleen)", "FOOD" to "Food", "BOOZE" to "Booze", "SPLEEN" to "Spleen",
        "STAT" to "Gives stats", "EQUIPMENT" to "Equipment", "USABLE" to "Usable", "OTHER" to "Other",
    )
    private val sorts = listOf("Name A to Z", "Name Z to A", "Value high to low", "Value low to high", "Count high to low", "Count low to high")
    private var checked: MutableSet<String> = (prefs.getString("itemfilter_$key", "") ?: "").split(",").filter { it.isNotEmpty() }.toMutableSet()
    private var matchAll = prefs.getBoolean("itemfilter_all_$key", false)
    private var sort = prefs.getInt("itemsort_$key", 0).coerceIn(0, sorts.size - 1)
    private val filterBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
    private val sortBtn = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (b in listOf(filterBtn, sortBtn)) {
            b.isAllCaps = false
            b.textSize = 13f
            addView(b, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(ctx, 6) })
        }
        filterBtn.setOnClickListener { chooseFilter() }
        sortBtn.setOnClickListener { chooseSort() }
        refreshLabels()
    }

    private fun refreshLabels() {
        filterBtn.text = if (checked.isEmpty()) "Filter" else "Filter (${checked.size})"
        sortBtn.text = "Sort: " + sorts[sort]
    }

    private fun chooseFilter() {
        val ctx = context
        val col = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), 0) }
        val boxes = options.map { (k, label) -> CheckBox(ctx).apply { text = label; isChecked = k in checked; setTextColor(KC.text) } }
        boxes.forEach { col.addView(it) }
        val all = CheckBox(ctx).apply { text = "Match all checked (otherwise any one)"; isChecked = matchAll; setTextColor(KC.soft) }
        col.addView(all)
        col.addView(TextView(ctx).apply { text = "Nothing checked shows everything."; textSize = 12f; setTextColor(KC.muted); setPadding(0, dp(ctx, 6), 0, 0) })
        MaterialAlertDialogBuilder(ctx).setTitle("Filter items")
            .setView(ScrollView(ctx).apply { addView(col) })
            .setPositiveButton("Apply") { _, _ ->
                checked = options.filterIndexed { i, _ -> boxes[i].isChecked }.map { it.first }.toMutableSet()
                matchAll = all.isChecked
                prefs.edit().putString("itemfilter_$key", checked.joinToString(",")).putBoolean("itemfilter_all_$key", matchAll).apply()
                refreshLabels(); onChange()
            }
            .setNeutralButton("Clear") { _, _ ->
                checked.clear(); matchAll = false
                prefs.edit().putString("itemfilter_$key", "").putBoolean("itemfilter_all_$key", false).apply()
                refreshLabels(); onChange()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun chooseSort() {
        MaterialAlertDialogBuilder(context).setTitle("Sort items")
            .setSingleChoiceItems(sorts.toTypedArray(), sort) { d, i ->
                sort = i
                prefs.edit().putInt("itemsort_$key", i).apply()
                d.dismiss(); refreshLabels(); onChange()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun matches(name: String, k: String): Boolean = when (k) {
        "CONSUMABLE" -> ItemInfo.kind(name).let { it == "FOOD" || it == "BOOZE" || it == "SPLEEN" }
        "STAT" -> ItemInfo.givesStats(name)
        else -> ItemInfo.kind(name) == k
    }

    /** [name] and [count] read an entry's item name and quantity; entries with no count can pass { 0 }. */
    fun <T> apply(items: List<T>, name: (T) -> String, count: (T) -> Int): List<T> {
        val kept = if (checked.isEmpty()) items else items.filter { e ->
            val n = name(e)
            if (matchAll) checked.all { matches(n, it) } else checked.any { matches(n, it) }
        }
        return when (sort) {
            0 -> kept.sortedBy { name(it).lowercase() }
            1 -> kept.sortedByDescending { name(it).lowercase() }
            2 -> kept.sortedWith(compareByDescending<T> { ItemInfo.value(name(it)) }.thenBy { name(it).lowercase() })
            3 -> kept.sortedWith(compareBy<T> { ItemInfo.value(name(it)) }.thenBy { name(it).lowercase() })
            4 -> kept.sortedWith(compareByDescending<T> { count(it) }.thenBy { name(it).lowercase() })
            else -> kept.sortedWith(compareBy<T> { count(it) }.thenBy { name(it).lowercase() })
        }
    }
}
