package com.example.kolmafiaandroid.tools

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.KC
import com.example.kolmafiaandroid.ui.clickable
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject

/** A saved list of KoLmafia commands, run in order with one tap (and optionally at every login). */
data class Macro(val name: String, val lines: List<String>, val schedule: String = "manual") {
    val atLogin get() = schedule != "manual"
}

/** When a macro runs by itself. Hours-based ones only tick while the app is open and logged in. */
val MACRO_SCHEDULES = listOf(
    "manual" to "Only when I tap it",
    "login" to "Every time I log in",
    "daily" to "Once a day, after rollover",
    "h1" to "Every hour (while the app is open)",
    "h2" to "Every 2 hours (while the app is open)",
    "h4" to "Every 4 hours (while the app is open)",
    "h8" to "Every 8 hours (while the app is open)",
)

object MacroStore {
    private const val KEY = "macros_json"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("kolmob", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Macro> = try {
        val a = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val l = o.getJSONArray("lines")
            Macro(o.getString("name"), (0 until l.length()).map { i -> l.getString(i) }, o.optString("schedule").ifEmpty { if (o.optBoolean("login")) "login" else "manual" })
        }
    } catch (_: Exception) { emptyList() }

    fun save(ctx: Context, list: List<Macro>) {
        val a = JSONArray()
        for (m in list) a.put(JSONObject().put("name", m.name).put("lines", JSONArray(m.lines)).put("schedule", m.schedule))
        prefs(ctx).edit().putString(KEY, a.toString()).apply()
    }

    /** Runs each line in turn; the next only starts when the last has finished. */
    fun run(m: Macro, done: () -> Unit = {}) {
        val todo = m.lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
        fun next(i: Int) {
            if (i >= todo.size) { done(); return }
            KolEngineHost.execute(todo[i]) { next(i + 1) }
        }
        next(0)
    }

    private var lastLoginRun = ""
    private var running = false

    private fun lastKey(m: Macro) = "macro_last_${m.name}"

    /** The KoL day a moment belongs to; the day turns over at rollover, about 3:30 in the morning Eastern. */
    private fun kolDay(ms: Long): String {
        val z = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.of("UTC")).minusMinutes(210)
        return z.toLocalDate().toString()
    }

    /** True when [m] is due now: its schedule says so and it has not already run in that window. */
    fun due(ctx: Context, m: Macro, atLogin: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        val last = prefs(ctx).getLong(lastKey(m), 0L)
        return when (m.schedule) {
            "login" -> atLogin
            "daily" -> last == 0L || kolDay(last) != kolDay(now)
            "h1", "h2", "h4", "h8" -> now - last >= m.schedule.drop(1).toLong() * 3_600_000L
            else -> false
        }
    }

    /** Runs every due macro in turn. [atLogin] is true on the first check after a login. */
    fun runDue(ctx: Context, atLogin: Boolean) {
        if (running || !KolEngineHost.loggedIn) return
        val now = System.currentTimeMillis()
        val mine = load(ctx).filter { due(ctx, it, atLogin, now) }
        if (mine.isEmpty()) return
        running = true
        fun next(i: Int) {
            if (i >= mine.size) { running = false; return }
            prefs(ctx).edit().putLong(lastKey(mine[i]), System.currentTimeMillis()).apply()
            run(mine[i]) { next(i + 1) }
        }
        next(0)
    }

    /** Called when a character logs in, and then about once a minute while the app is open. */
    fun runLoginMacros(ctx: Context) {
        val who = KolEngineHost.currentUser
        if (who.isEmpty()) return
        val first = who != lastLoginRun
        lastLoginRun = who
        runDue(ctx, first)
    }
}

/** Macros (saved command lists) and KoLmafia aliases, in one place. */
class MacrosFragment : DialogFragment() {
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 8)) }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(label(ctx, "Macros and aliases", 20f, 0xFF4CAF50.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(label(ctx, "Close", 15f, 0xFF4CAF50.toInt()).apply { setPadding(dp(ctx, 12), dp(ctx, 8), 0, dp(ctx, 8)); setOnClickListener { dismiss() } })
        root.addView(head)
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        render()
        return root
    }

    private fun section(text: String) = label(requireContext(), text.uppercase(), 12f, 0xFF4CAF50.toInt()).apply {
        setPadding(0, dp(requireContext(), 18), 0, dp(requireContext(), 4)); letterSpacing = 0.1f
    }

    private fun row(title: String, sub: String, click: () -> Unit, long: (() -> Unit)? = null): View {
        val ctx = requireContext()
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 4), dp(ctx, 12), dp(ctx, 4), dp(ctx, 12)) }
        r.addView(label(ctx, title, 16f, KC.text))
        if (sub.isNotEmpty()) r.addView(label(ctx, sub, 12f, KC.muted))
        clickable(r, click)
        if (long != null) r.setOnLongClickListener { long(); true }
        return r
    }

    private fun render() {
        val ctx = requireContext()
        body.removeAllViews()
        body.addView(section("Macros"))
        val macros = MacroStore.load(ctx)
        if (macros.isEmpty()) body.addView(label(ctx, "A macro is a list of KoLmafia commands that run one after another. Make one for your daily routine.", 13f, KC.muted).apply { setPadding(0, 0, 0, dp(ctx, 6)) })
        for (m in macros) {
            val sub = "${m.lines.count { it.isNotBlank() }} commands" + if (m.atLogin) " · " + (MACRO_SCHEDULES.firstOrNull { it.first == m.schedule }?.second ?: "") else ""
            body.addView(row(m.name, "$sub · tap to run, hold to edit", {
                Toast.makeText(ctx, "Running ${m.name}…", Toast.LENGTH_SHORT).show()
                MacroStore.run(m) { activity?.runOnUiThread { if (isAdded) Toast.makeText(ctx, "${m.name} finished", Toast.LENGTH_SHORT).show() } }
            }, { editMacro(m) }))
        }
        body.addView(row("New macro", "Name it and list the commands, one per line", { editMacro(null) }).also { (it as LinearLayout).getChildAt(0).let { t -> (t as TextView).setTextColor(0xFF4CAF50.toInt()) } })

        body.addView(section("Aliases"))
        body.addView(label(ctx, "An alias is a short word that stands for a longer command, usable in the Console. Stored by KoLmafia.", 13f, KC.muted).apply { setPadding(0, 0, 0, dp(ctx, 6)) })
        body.addView(row("Show my aliases", "Prints them in the Console", { KolEngineHost.execute("alias"); Toast.makeText(ctx, "Listed in the Console", Toast.LENGTH_SHORT).show() }))
        body.addView(row("Add an alias", "For example: bf  =>  adventure 1 Barf Mountain", { addAlias() }))
        body.addView(row("Remove an alias", "By name", { removeAlias() }))
    }

    private fun editMacro(existing: Macro?) {
        val ctx = requireContext()
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), 0) }
        val name = EditText(ctx).apply { hint = "Name"; maxLines = 1; setText(existing?.name ?: "") }
        val cmds = EditText(ctx).apply {
            hint = "One command per line, e.g.\nuse 3 hell ramen\ncast 2 Ode to Booze"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 5; gravity = android.view.Gravity.TOP
            setText(existing?.lines?.joinToString("\n") ?: "")
        }
        val when_ = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, MACRO_SCHEDULES.map { it.second })
            setSelection(MACRO_SCHEDULES.indexOfFirst { it.first == (existing?.schedule ?: "manual") }.coerceAtLeast(0))
        }
        col.addView(name); col.addView(cmds)
        col.addView(TextView(ctx).apply { text = "When to run"; textSize = 12f; setTextColor(KC.muted); setPadding(0, dp(ctx, 10), 0, 0) })
        col.addView(when_)
        val b = com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(ctx).setTitle(if (existing == null) "New macro" else "Edit macro").setView(ScrollView(ctx).apply { addView(col) })
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) { Toast.makeText(ctx, "Give the macro a name", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val m = Macro(n, cmds.text.toString().lines(), MACRO_SCHEDULES[when_.selectedItemPosition].first)
                val all = MacroStore.load(ctx).filter { it.name != existing?.name && it.name != n } + m
                MacroStore.save(ctx, all.sortedBy { it.name.lowercase() }); render()
            }.setNegativeButton("Cancel", null)
        if (existing != null) b.setNeutralButton("Delete") { _, _ -> MacroStore.save(ctx, MacroStore.load(ctx).filter { it.name != existing.name }); render() }
        b.show()
    }

    private fun addAlias() {
        val ctx = requireContext()
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), 0) }
        val name = EditText(ctx).apply { hint = "Alias (one word)"; maxLines = 1 }
        val cmd = EditText(ctx).apply { hint = "Command it stands for"; maxLines = 1 }
        col.addView(name); col.addView(cmd)
        com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(ctx).setTitle("Add alias").setView(col).setPositiveButton("Add") { _, _ ->
            val n = name.text.toString().trim().replace(" ", ""); val c = cmd.text.toString().trim()
            if (n.isNotEmpty() && c.isNotEmpty()) { KolEngineHost.execute("alias $n => $c"); Toast.makeText(ctx, "Alias $n added", Toast.LENGTH_SHORT).show() }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun removeAlias() {
        val ctx = requireContext()
        val name = EditText(ctx).apply { hint = "Alias name"; maxLines = 1 }
        val col = LinearLayout(ctx).apply { setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), 0); addView(name, LinearLayout.LayoutParams(-1, -2)) }
        com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(ctx).setTitle("Remove alias").setView(col).setPositiveButton("Remove") { _, _ ->
            val n = name.text.toString().trim()
            if (n.isNotEmpty()) { KolEngineHost.execute("unalias $n"); Toast.makeText(ctx, "Alias $n removed", Toast.LENGTH_SHORT).show() }
        }.setNegativeButton("Cancel", null).show()
    }

    companion object {
        fun show(fm: FragmentManager) = MacrosFragment().show(fm, "macros")
    }
}
