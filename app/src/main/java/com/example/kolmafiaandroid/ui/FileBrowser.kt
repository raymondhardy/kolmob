package com.example.kolmafiaandroid.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.fragments.ScriptEditorFragment
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * A plain file and folder view: open folders, see sizes and dates, tap a text file to open it in the editor
 * (or run it, when it is a script under [scriptsDir]).
 */
object FileBrowser {
    private val TEXT_EXT = setOf(
        "ash", "js", "txt", "json", "md", "ccs", "xml", "css", "html", "htm", "csv", "properties", "yml", "yaml", "ts", "map", "cfg", "ini", "tsv", "mood", "svg"
    )
    private const val MAX_EDIT_BYTES = 1_000_000L
    private val HIDDEN = Regex("^\\.git$|^\\.svn$|^\\.kolmob-.*|.*\\.updating$")

    fun show(activity: FragmentActivity, root: File, title: String, scriptsDir: File?, onClose: (() -> Unit)? = null) {
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val dialog = Dialog(activity, R.style.FullScreenDialogStyle)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(KC.pick(0xFF121212.toInt(), 0xFFF5F5F5.toInt())) }
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(8), px(16), px(8))
            setBackgroundColor(0xFF2E7D32.toInt())
        }
        val titleView = TextView(activity).apply { textSize = 18f; setTextColor(0xFFFFFFFF.toInt()); typeface = Typeface.DEFAULT_BOLD }
        val up = TextView(activity).apply {
            text = "‹"; textSize = 30f; setTextColor(0xFFFFFFFF.toInt()); setPadding(px(14), 0, px(18), px(4))
        }
        head.addView(up)
        head.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(56)))

        val crumb = TextView(activity).apply { textSize = 12f; setTextColor(KC.muted); setPadding(px(16), px(10), px(16), px(6)) }
        page.addView(crumb)
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(px(8), 0, px(8), px(24)) }
        page.addView(ScrollView(activity).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setContentView(page)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        var dir = root

        fun size(b: Long): String = when {
            b >= 1_048_576 -> String.format(Locale.US, "%.1f MB", b / 1_048_576.0)
            b >= 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
            else -> "$b B"
        }

        lateinit var render: () -> Unit

        fun openFile(f: File) {
            val ext = f.extension.lowercase(Locale.ROOT)
            val canEdit = f.length() <= MAX_EDIT_BYTES && (ext in TEXT_EXT || ext.isEmpty())
            val runnable = scriptsDir != null && f.startsWith(scriptsDir) && ext in setOf("ash", "js", "txt")
            if (!canEdit && !runnable) {
                Toast.makeText(activity, "This file cannot be opened here (${size(f.length())})", Toast.LENGTH_SHORT).show()
                return
            }
            if (!runnable) {
                ScriptEditorFragment.newInstance(f).show(activity.supportFragmentManager, "script_editor")
                return
            }
            ScrollSafeDialogBuilder(activity).setTitle(f.name)
                .setItems(arrayOf("Run", "Open in editor")) { _, which ->
                    if (which == 0) {
                        val rel = ScriptFiles.relName(scriptsDir!!, f)
                        com.example.kolmafiaandroid.tools.ScriptRunSheet.run(activity, rel, "call $rel")
                    } else {
                        ScriptEditorFragment.newInstance(f).show(activity.supportFragmentManager, "script_editor")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        fun row(name: String, detail: String, folder: Boolean, onTap: () -> Unit) {
            val r = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(12), px(12), px(12), px(12))
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { onTap() }
            }
            r.addView(TextView(activity).apply {
                text = if (folder) "▸" else "·"
                textSize = 20f
                setTextColor(if (folder) 0xFF4CAF50.toInt() else KC.muted)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(px(28), ViewGroup.LayoutParams.WRAP_CONTENT))
            val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(activity).apply {
                text = name; textSize = 16f; setTextColor(KC.text)
                if (folder) typeface = Typeface.DEFAULT_BOLD
            })
            texts.addView(TextView(activity).apply { text = detail; textSize = 12f; setTextColor(KC.muted) })
            r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        render = {
            titleView.text = if (dir == root) title else dir.name
            up.visibility = View.VISIBLE
            val rel = dir.relativeTo(root).path.replace(File.separatorChar, '/')
            crumb.text = if (rel.isEmpty()) "/" else "/$rel"
            list.removeAllViews()
            val kids = (dir.listFiles() ?: emptyArray()).filter { !HIDDEN.matches(it.name) }
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
            if (kids.isEmpty()) {
                list.addView(TextView(activity).apply { text = "Empty folder"; textSize = 14f; setTextColor(KC.muted); setPadding(px(12), px(16), px(12), px(16)) })
            }
            val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
            for (k in kids) {
                if (k.isDirectory) {
                    val n = k.listFiles()?.count { !HIDDEN.matches(it.name) } ?: 0
                    row(k.name, if (n == 1) "1 item" else "$n items", true) { dir = k; render() }
                } else {
                    row(k.name, size(k.length()) + " · " + fmt.format(Date(k.lastModified())), false) { openFile(k) }
                }
            }
        }

        fun goBack() { if (dir == root) dialog.dismiss() else { dir = dir.parentFile ?: root; render() } }
        up.setOnClickListener { goBack() }
        dialog.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) { goBack(); true } else false
        }
        dialog.setOnDismissListener { onClose?.invoke() }
        render()
        dialog.show()
    }
}
