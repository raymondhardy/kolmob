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
    private var running = true

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
        log = TextView(ctx).apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        scroll = ScrollView(ctx).apply { addView(log) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (8 * dp).toInt(), 0, 0) }
        fun btn(label: String, onClick: () -> Unit) = Button(ctx).apply {
            this.text = label; setOnClickListener { onClick() }
        }.also { row.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        stop = btn("Stop") {
            KolEngineHost.abort()
            status.text = "Stopping…"
        }
        btn("Copy") {
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("script output", text.toString()))
            Toast.makeText(ctx, "Output copied", Toast.LENGTH_SHORT).show()
        }
        btn("Hide") { dismissAllowingStateLoss() }
        root.addView(row)

        val dlg = Dialog(ctx, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar_MinWidth)
        dlg.setContentView(root)
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (ctx.resources.displayMetrics.heightPixels * 0.85).toInt())

        if (savedInstanceState == null) {
            // subscribe before starting so no line is missed
            lifecycleScope.launch {
                KolEngineHost.liveLines().collect { line ->
                    text.append(Html.fromHtml(line, Html.FROM_HTML_MODE_COMPACT)).append("\n")
                    if (text.length > 80_000) text.delete(0, text.length - 60_000)
                    log.text = text
                    scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
                }
            }
            val act: FragmentActivity? = activity
            KolEngineHost.execute(command) {
                act?.runOnUiThread {
                    running = false
                    if (isAdded) { status.text = "Finished"; stop.isEnabled = false }
                }
            }
        } else {
            status.text = "Output continues in the Console tab"
            stop.isEnabled = false
        }
        return dlg
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
