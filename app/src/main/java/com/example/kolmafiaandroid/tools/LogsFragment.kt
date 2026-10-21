package com.example.kolmafiaandroid.tools

import com.example.kolmafiaandroid.ui.KC

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
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
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.clickable
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Log viewer for KoLmafia session logs and debug files.
 */
class LogsFragment : DialogFragment() {

    private lateinit var listView: ListView
    private lateinit var searchEdit: EditText
    private lateinit var emptyText: TextView
    private var logFiles: List<LogFile> = emptyList()
    private var filteredFiles: List<LogFile> = emptyList()

    private data class LogFile(
        val file: File,
        val name: String,
        val size: Long,
        val lastModified: Long
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
        view.addView(label(ctx, "Log Viewer", 20f, 0xFF4CAF50.toInt(), true).apply { setPadding(0, 0, 0, dp(ctx, 16)) })

        // Search box
        searchEdit = EditText(ctx).apply {
            hint = "Search log files..."
            setTextColor(KC.text)
            setHintTextColor(KC.hint)
        }
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterFiles(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        view.addView(searchEdit)

        // Clean-up
        val cleanRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun cleanBtn(text: String, onClick: () -> Unit) = com.google.android.material.button.MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            setOnClickListener { onClick() }
        }
        cleanRow.addView(cleanBtn("Delete shown…") { confirmDelete(filteredFiles, "the ${filteredFiles.size} files shown") },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(ctx, 8) })
        cleanRow.addView(cleanBtn("Keep only today…") {
            val start = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
            val old = logFiles.filter { it.lastModified < start }
            confirmDelete(old, "${old.size} files from before today")
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        view.addView(cleanRow)
        view.addView(label(ctx, "Tap a file to read it. Long-press to delete it.", 12f, KC.muted).apply { setPadding(0, dp(ctx, 6), 0, dp(ctx, 6)) })

        // List
        listView = ListView(ctx)
        view.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { weight = 1f })

        // Empty state
        emptyText = TextView(ctx).apply {
            text = "No log files found.\nRun some scripts or adventures to generate logs."
            textSize = 16f
            setTextColor(KC.hint)
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
        }
        view.addView(emptyText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { weight = 1f })

        refreshFileList()
        return view
    }

    private fun refreshFileList() {
        lifecycleScope.launch(Dispatchers.IO) {
            val filesDir = File(requireContext().filesDir, "kolmafia")
            val files = mutableListOf<LogFile>()

            // Session logs in sessions folder
            val sessionsDir = File(filesDir, "sessions")
            if (sessionsDir.exists()) {
                sessionsDir.listFiles { _, name -> name.endsWith(".txt") }?.forEach { file ->
                    files.add(LogFile(file, file.name, file.length(), file.lastModified()))
                }
            }

            // Chat logs (written when the logChatMessages preference is on)
            File(filesDir, "chats").listFiles { _, name -> name.endsWith(".html") || name.endsWith(".txt") }?.forEach { file ->
                files.add(LogFile(file, "chat: " + file.name, file.length(), file.lastModified()))
            }

            // Root log files (DEBUG.txt, etc.)
            filesDir.listFiles { _, name -> name.endsWith(".txt") }?.forEach { file ->
                files.add(LogFile(file, file.name, file.length(), file.lastModified()))
            }

            // Sort by last modified, newest first
            files.sortByDescending { it.lastModified }

            withContext(Dispatchers.Main) {
                logFiles = files
                filteredFiles = files
                updateList()
            }
        }
    }

    private fun filterFiles(query: String) {
        val q = query.trim().lowercase(Locale.ROOT)
        filteredFiles = if (q.isEmpty()) logFiles else logFiles.filter { it.name.lowercase(Locale.ROOT).contains(q) }
        updateList()
    }

    private fun updateList() {
        val ctx = requireContext()
        if (filteredFiles.isEmpty()) {
            listView.visibility = View.GONE
            emptyText.visibility = View.VISIBLE
        } else {
            listView.visibility = View.VISIBLE
            emptyText.visibility = View.GONE

            val labels = filteredFiles.map { file ->
                val nf = java.text.NumberFormat.getIntegerInstance()
                val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(file.lastModified)
                "${file.name}\n${nf.format(file.size)} bytes • $date"
            }
            val adapter = android.widget.ArrayAdapter<String>(
                ctx,
                android.R.layout.simple_list_item_1,
                labels.toMutableList()
            )
            listView.adapter = adapter
            listView.setOnItemLongClickListener { _, _, position, _ ->
                if (position < filteredFiles.size) confirmDelete(listOf(filteredFiles[position]), filteredFiles[position].name)
                true
            }
            listView.setOnItemClickListener { _, _, position, _ ->
                if (position < filteredFiles.size) {
                    openLogFile(filteredFiles[position])
                }
            }
        }
    }

    /** Deleting a log cannot be undone, so confirm; empty lists just say so. */
    private fun confirmDelete(files: List<LogFile>, what: String) {
        val ctx = requireContext()
        if (files.isEmpty()) { Toast.makeText(ctx, "Nothing to delete", Toast.LENGTH_SHORT).show(); return }
        val bytes = files.sumOf { it.size }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Delete logs?")
            .setMessage("Delete $what (${formatSize(bytes)})? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val n = files.count { try { it.file.delete() } catch (_: Exception) { false } }
                    withContext(Dispatchers.Main) {
                        if (isAdded) { Toast.makeText(requireContext(), "Deleted $n file${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show(); refreshFileList() }
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun openLogFile(logFile: LogFile) {
        lifecycleScope.launch(Dispatchers.IO) {
            val content = if (logFile.size > 200 * 1024) {
                // Load last 200 KB
                val buffer = ByteArray((200 * 1024).toInt())
                val fis = FileInputStream(logFile.file)
                fis.skip(logFile.size - buffer.size)
                val read = fis.read(buffer)
                fis.close()
                String(buffer, 0, read)
            } else {
                logFile.file.readText()
            }

            // chat logs are saved as HTML: show them as plain text
            val shown = if (logFile.file.name.endsWith(".html"))
                android.text.Html.fromHtml(content.replace(Regex("(?is)<style.*?</style>"), "").replace(Regex("(?is)<head>.*?</head>"), "").replace("<br>", "\n").replace("<br/>", "\n"), android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
            else content
            withContext(Dispatchers.Main) {
                showLogViewer(logFile.name, shown, logFile.size > 200 * 1024, logFile.file)
            }
        }
    }

    private fun showLogViewer(title: String, content: String, isTruncated: Boolean, fullFile: File) {
        val ctx = requireContext()
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
        }

        // Title
        view.addView(label(ctx, title, 16f, 0xFF4CAF50.toInt(), true).apply { setPadding(0, 0, 0, dp(ctx, 12)) })

        // Truncated notice
        if (isTruncated) {
            val notice = label(ctx, "Showing last 200 KB (truncated)", 12f, KC.pick(0xFFFFB74D.toInt(), 0xFFE65100.toInt()))
            val loadAllBtn = android.widget.Button(ctx).apply {
                text = "Load all"
                setTextColor(KC.onAccent)
                setBackgroundColor(0xFF4CAF50.toInt())
                setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
                setOnClickListener { loadFullFile(fullFile, notice, this) }
            }
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            row.addView(notice)
            row.addView(loadAllBtn)
            view.addView(row)
        }

        // Search box
        val searchEdit = EditText(ctx).apply {
            hint = "Search in log..."
            setTextColor(KC.text)
            setHintTextColor(KC.hint)
        }

        // Log content
        val logText = TextView(ctx).apply {
            text = content
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextColor(KC.soft)
            setTextIsSelectable(true)
            movementMethod = android.text.method.ScrollingMovementMethod()
        }

        val scrollView = android.widget.ScrollView(ctx).apply {
            addView(logText)
            setBackgroundResource(R.drawable.bg_console_output)
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
        }

        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim().lowercase(Locale.ROOT)
                if (query.isEmpty()) {
                    logText.text = content
                } else {
                    // Simple highlight - just show matching lines
                    val lines = content.lines().filter { it.lowercase(Locale.ROOT).contains(query) }
                    logText.text = if (lines.isEmpty()) "No matches" else lines.joinToString("\n")
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        view.addView(searchEdit)
        view.addView(scrollView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { weight = 1f })

        // Buttons
        val buttonRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER }
        val copyBtn = android.widget.Button(ctx).apply {
            text = "Copy"
            setTextColor(KC.onAccent)
            setBackgroundColor(0xFF4CAF50.toInt())
            setPadding(dp(ctx, 24), dp(ctx, 12), dp(ctx, 24), dp(ctx, 12))
            setOnClickListener { copyToClipboard(content) }
        }
        val shareBtn = android.widget.Button(ctx).apply {
            text = "Share"
            setTextColor(KC.onAccent)
            setBackgroundColor(0xFF1E88E5.toInt())
            setPadding(dp(ctx, 24), dp(ctx, 12), dp(ctx, 24), dp(ctx, 12))
            setOnClickListener { shareContent(content) }
        }
        buttonRow.addView(copyBtn)
        buttonRow.addView(shareBtn)
        view.addView(buttonRow)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setView(android.widget.ScrollView(ctx).apply { addView(view) })
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
        (activity as? com.example.kolmafiaandroid.MainActivity)?.fitDialogAboveNav(dialog) // keep the bottom bar reachable
    }

    private fun loadFullFile(file: File, notice: TextView, loadBtn: android.widget.Button) {
        lifecycleScope.launch(Dispatchers.IO) {
            val content = file.readText()
            withContext(Dispatchers.Main) {
                notice.text = "Full file loaded (${formatSize(file.length())})"
                loadBtn.visibility = View.GONE
                // Need to update the dialog's logText - but we don't have reference
                // For simplicity, just reopen with full content
                showLogViewer(file.name, content, false, file)
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Log content", text))
        Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun shareContent(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share log via"))
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    private fun dp(context: android.content.Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        fun show(fragmentManager: androidx.fragment.app.FragmentManager) {
            LogsFragment().show(fragmentManager, "logs")
        }
    }
}