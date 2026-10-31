package com.example.kolmafiaandroid.tools

import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.Html
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.kolmafiaandroid.KolEngineHost
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs a KoLmafia command or ASH script and shows its output live right where you tapped, so you do not have to
 * switch to the Console tab. Stop aborts the script; Hide leaves it running (the Console tab keeps the full log).
 */
class ScriptRunSheet : DialogFragment() {
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var stop: Button
    private val text = android.text.SpannableStringBuilder()

    /** Every line received (level, text), so changing the log level can redraw what was hidden. */
    private val entries = ArrayDeque<Pair<Int, CharSequence>>()
    private var minLevel = 1
    private var hidden = 0
    private var statusBase = "Running…"
    private var running = true
    private var follow = true
    private lateinit var followBtn: Button

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val title = requireArguments().getString("title") ?: "Script"
        val command = requireArguments().getString("command") ?: ""
        val dp = ctx.resources.displayMetrics.density
        val pad = (14 * dp).toInt()

        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(TextView(ctx).apply { this.text = title; textSize = 18f; setTypeface(null, Typeface.BOLD) })
        status = TextView(ctx).apply { this.text = "Running…"; textSize = 13f; setPadding(0, (2 * dp).toInt(), 0, (8 * dp).toInt()) }
        root.addView(status)

        val prefs = ctx.getSharedPreferences("kolmob", android.content.Context.MODE_PRIVATE)
        minLevel = prefs.getInt(com.example.kolmafiaandroid.ui.LogLevels.PREF, 1).coerceIn(0, 3)
        val levelRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        levelRow.addView(TextView(ctx).apply { this.text = "Log level"; textSize = 13f })
        val levelSpinner = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(
                ctx, android.R.layout.simple_spinner_dropdown_item,
                com.example.kolmafiaandroid.ui.LogLevels.labels,
            )
            setSelection(minLevel)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    if (pos == minLevel) return
                    minLevel = pos
                    prefs.edit().putInt(com.example.kolmafiaandroid.ui.LogLevels.PREF, pos).apply()
                    rebuild()
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
        levelRow.addView(levelSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (8 * dp).toInt() })
        followBtn = Button(ctx).apply {
            text = "Scroll: on"
            textSize = 12f
            setOnClickListener {
                follow = !follow
                text = if (follow) "Scroll: on" else "Scroll: off"
                if (follow) scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        }
        levelRow.addView(followBtn)
        root.addView(levelRow)
        log = TextView(ctx).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        scroll = ScrollView(ctx).apply { addView(log) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (8 * dp).toInt(), 0, 0) }
        fun btn(label: String, onClick: () -> Unit) = Button(ctx).apply {
            this.text = label; setOnClickListener { onClick() }
        }.also { row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        stop = btn("Stop") {
            KolEngineHost.abort()
            statusBase = "Stopping…"; refreshStatus()
        }
        val pauseBtn = btn("Pause") { KolEngineHost.setPaused(!KolEngineHost.paused.value) }
        btn("Copy") {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("script output", text.toString()))
            Toast.makeText(ctx, "Output copied", Toast.LENGTH_SHORT).show()
        }
        scroll.setOnTouchListener { _, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_MOVE && follow) { follow = false; followBtn.text = "Scroll: off" }
            false
        }
        btn("Hide") { dismissAllowingStateLoss() }
        root.addView(row)

        val dlg = Dialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar_MinWidth)
        dlg.setContentView(root)
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (ctx.resources.displayMetrics.heightPixels * 0.85).toInt())

        if (savedInstanceState == null) {
            lifecycleScope.launch {
                KolEngineHost.paused.collect { p ->
                    pauseBtn.text = if (p) "Resume" else "Pause"
                    if (running) { statusBase = if (p) "Paused: waiting before the next request" else "Running…"; refreshStatus() }
                }
            }
            // subscribe before starting so no line is missed
            lifecycleScope.launch {
                KolEngineHost.liveLines().collect { line ->
                    val spanned = Html.fromHtml(line, Html.FROM_HTML_MODE_COMPACT)
                    val level = com.example.kolmafiaandroid.ui.LogLevels.of(spanned)
                    entries.addLast(level to spanned)
                    if (entries.size > 4000) entries.removeFirst()
                    if (level >= minLevel) {
                        text.append(spanned).append("\n")
                        if (text.length > 80_000) text.delete(0, text.length - 60_000)
                        log.text = text
                        if (follow) scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
                    } else {
                        hidden++
                        refreshStatus()
                    }
                }
            }
            val act: FragmentActivity? = activity
            KolEngineHost.execute(command) {
                act?.runOnUiThread {
                    running = false
                    if (isAdded) { statusBase = "Finished"; refreshStatus(); stop.isEnabled = false }
                }
            }
        } else {
            statusBase = "Output continues in the Console tab"; refreshStatus()
            stop.isEnabled = false
        }
        return dlg
    }

    private fun refreshStatus() {
        status.text = statusBase + if (hidden > 0) " · $hidden hidden" else ""
    }

    private fun rebuild() {
        text.clear()
        hidden = 0
        for ((level, line) in entries) {
            if (level >= minLevel) text.append(line).append("\n") else hidden++
        }
        if (text.length > 80_000) text.delete(0, text.length - 60_000)
        log.text = text
        refreshStatus()
        if (follow) scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    companion object {
        fun run(activity: FragmentActivity, title: String, command: String) {
            ScriptRunSheet().apply { arguments = Bundle().apply { putString("title", title); putString("command", command) } }
                .show(activity.supportFragmentManager, "scriptrun")
        }

        fun pick(fm: androidx.fragment.app.FragmentManager) {
            fm.fragments.firstNotNullOfOrNull { it.activity }?.let { pick(it) }
        }

        /** Pick one of your ASH scripts (newest first), then run it with live output. */
        fun pick(activity: FragmentActivity) {
            val dir = File(activity.filesDir, "kolmafia/scripts")
            val files = (dir.listFiles { f -> f.name.lowercase().let { it.endsWith(".ash") || it.endsWith(".txt") } } ?: emptyArray())
                .sortedByDescending { it.lastModified() }
            if (files.isEmpty()) {
                Toast.makeText(activity, "No scripts yet. Add one in the Script manager.", Toast.LENGTH_SHORT).show()
                return
            }
            val names = files.map { it.name }
            val dp = activity.resources.displayMetrics.density
            val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), 0) }
            val spinner = android.widget.Spinner(activity).apply {
                adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, names)
            }
            col.addView(spinner)
            val args = android.widget.EditText(activity).apply { hint = "Arguments (optional)"; setSingleLine() }
            col.addView(args)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity).setTitle("Run a script").setView(col)
                .setPositiveButton("Run") { _, _ ->
                    val name = names[spinner.selectedItemPosition]
                    val extra = args.text.toString().trim()
                    run(activity, name, if (extra.isEmpty()) "call $name" else "call $name $extra")
                }
                .setNegativeButton("Cancel", null).show()
        }
    }
}
