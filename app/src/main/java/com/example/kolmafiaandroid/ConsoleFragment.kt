package com.example.kolmafiaandroid

import com.example.kolmafiaandroid.ui.KC

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.text.Html
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.kolmafiaandroid.fragments.ScriptsFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Console for KoLmafia CLI and ASH: the real engine's output plus a command line. */
class ConsoleFragment : Fragment() {
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var copyBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var autoScrollCheck: CheckBox
    private lateinit var historyPrevBtn: ImageButton
    private lateinit var historyNextBtn: ImageButton

    private val history = mutableListOf<String>()
    private var historyIndex = -1
    private var prefs: SharedPreferences? = null
    private var autoScroll = true

    private var pendingBlank = false
    private var argsView: TextView? = null
    private var argParams: List<Pair<String, String>> = emptyList()
    private var argValues: List<String> = emptyList()

    /** Reads main(...) from the selected script so the arguments row can say what it expects. */
    private fun refreshArgs() {
        val v = argsView ?: return
        val f = scriptFiles.getOrNull(scriptPicker?.selectedItemPosition ?: -1)
        if (f == null) { argParams = emptyList(); v.text = ""; return }
        val src = try { f.readText() } catch (e: Exception) { "" }
        val m = Regex("""\bmain\s*\(([^)]*)\)""").find(src)
        argParams = m?.groupValues?.get(1)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.map { p -> val parts = p.split(Regex("\\s+")); (parts.firstOrNull() ?: "") to (parts.getOrNull(1) ?: "") } ?: emptyList()
        val saved = prefs?.getString("args_" + f.name, "") ?: ""
        val vals = if (saved.isEmpty()) emptyList() else saved.split("\u001f")
        argValues = argParams.indices.map { vals.getOrNull(it) ?: "" }
        v.text = if (argParams.isEmpty()) "Arguments: none (main has no parameters)"
        else "Arguments: " + argParams.joinToString(", ") { "${it.first} ${it.second}".trim() } +
            (if (argValues.any { it.isNotBlank() }) "  =  " + argValues.joinToString(" ") else "") + "   (tap to set)"
    }

    private fun showArgsDialog() {
        val ctx = context ?: return
        val f = scriptFiles.getOrNull(scriptPicker?.selectedItemPosition ?: -1) ?: return
        if (argParams.isEmpty()) {
            Toast.makeText(ctx, "This script's main() takes no arguments.", Toast.LENGTH_SHORT).show()
            return
        }
        val dp = ctx.resources.displayMetrics.density
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0) }
        col.addView(TextView(ctx).apply { text = "One box per parameter of main(). A box left empty passes nothing (empty text, 0 or false)." ; textSize = 13f })
        val boxes = argParams.mapIndexed { i, (type, name) ->
            EditText(ctx).apply {
                hint = "$type $name".trim()
                setText(argValues.getOrNull(i) ?: "")
                setSingleLine()
            }.also { col.addView(it) }
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle("Arguments for ${f.name}").setView(col)
            .setPositiveButton("Save") { _, _ ->
                argValues = boxes.map { it.text.toString() }
                prefs?.edit()?.putString("args_" + f.name, argValues.joinToString("\u001f"))?.apply()
                refreshArgs()
            }
            .setNegativeButton("Cancel", null).create().also { d ->
                d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                d.show()
            }
    }

    private var scriptPicker: android.widget.Spinner? = null
    private var scriptFiles: List<java.io.File> = emptyList()

    private fun reloadScripts() {
        val ctx = context ?: return
        val picker = scriptPicker ?: return
        val dir = java.io.File(ctx.filesDir, "kolmafia/scripts")
        val keep = scriptFiles.getOrNull(picker.selectedItemPosition)?.name ?: prefs?.getString("last_script", null)
        scriptFiles = (dir.listFiles { f -> f.name.lowercase().let { it.endsWith(".ash") || it.endsWith(".txt") } } ?: emptyArray())
            .sortedBy { it.name.lowercase() }
        picker.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, scriptFiles.map { it.name })
        scriptFiles.indexOfFirst { it.name == keep }.takeIf { it >= 0 }?.let { picker.setSelection(it) }
        picker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                scriptFiles.getOrNull(pos)?.let { prefs?.edit()?.putString("last_script", it.name)?.apply() }
                refreshArgs()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    override fun onResume() {
        super.onResume()
        reloadScripts()
    }

    private companion object {
        const val PREFS_NAME = "kolmob"
        const val HISTORY_KEY = "console_history"
        const val MAX_HISTORY = 100
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadHistory()

        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt()) }

        // Script bar: pick an installed script, run it, edit it, add or install new ones. Output shows right below.
        val picker = android.widget.Spinner(ctx)
        scriptPicker = picker
        val argsRow = TextView(ctx).apply {
            textSize = 13f
            setTextColor(KC.muted)
            setPadding((6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt())
            setOnClickListener { showArgsDialog() }
        }
        argsView = argsRow
        fun iconBtn(icon: Int, desc: String, onClick: () -> Unit) = ImageButton(ctx).apply {
            setImageResource(icon)
            setColorFilter(KC.text)
            setBackgroundResource(R.drawable.bg_icon_button)
            contentDescription = desc
            tooltipText = desc
            minimumHeight = (48 * dp).toInt()
            setOnClickListener { onClick() }
        }
        fun selectedScript(): java.io.File? = scriptFiles.getOrNull(picker.selectedItemPosition)
        val topRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topRow.addView(picker, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val side = LinearLayout.LayoutParams((56 * dp).toInt(), (48 * dp).toInt()).apply { marginStart = (6 * dp).toInt() }
        topRow.addView(iconBtn(R.drawable.ic_s_play, "Run script") {
            val f = selectedScript()
            if (f == null) Toast.makeText(ctx, "No scripts yet. Tap + or the import buttons.", Toast.LENGTH_SHORT).show()
            else {
                val a = argValues.dropLastWhile { it.isBlank() }.joinToString(" ") { it.trim() }
                input.setText(if (a.isEmpty()) "call ${f.name}" else "call ${f.name} $a")
                pendingBlank = a.isEmpty()
                submit()
            }
        }, side)
        topRow.addView(iconBtn(R.drawable.ic_s_stop, "Stop script") { KolEngineHost.abort() }, side)
        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt()) }
        fun small(icon: Int, desc: String, onClick: () -> Unit) =
            btnRow.addView(iconBtn(icon, desc, onClick), LinearLayout.LayoutParams(0, (48 * dp).toInt(), 1f).apply { marginEnd = (6 * dp).toInt() })
        fun manager(action: String?) = ScriptsFragment().apply { if (action != null) arguments = Bundle().apply { putString("action", action) } }
            .show(parentFragmentManager, "scripts")
        small(R.drawable.ic_s_edit, "Edit selected script") {
            val f = selectedScript()
            if (f != null) com.example.kolmafiaandroid.fragments.ScriptEditorFragment.newInstance(f).show(childFragmentManager, "script_editor")
        }
        small(R.drawable.ic_s_add, "New script") { manager("new") }
        small(R.drawable.ic_s_import, "Import a script from a link or file") { manager("import") }
        small(R.drawable.ic_s_cloud, "Install from GitHub or SVN") { manager("install") }
        small(R.drawable.ic_s_folder, "Manage scripts") { manager(null) }
        root.addView(topRow)
        root.addView(argsRow)
        root.addView(btnRow)
        reloadScripts()

        // Output area
        output = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(0xFFE0E0E0.toInt())
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
        }
        scroll = ScrollView(ctx).apply {
            addView(output)
            setBackgroundResource(R.drawable.bg_console_output)
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Toolbar row: Copy, Clear, Auto-scroll
        val toolbarRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
        }
        copyBtn = Button(ctx).apply {
            text = "Copy"
            setTextColor(KC.onAccent)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.colorPrimary)))
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            setOnClickListener { copyOutput() }
        }
        clearBtn = Button(ctx).apply {
            text = "Clear"
            setTextColor(KC.onAccent)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.colorSecondary)))
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            setOnClickListener { clearOutput() }
        }
        autoScrollCheck = CheckBox(ctx).apply {
            text = "Auto-scroll"
            isChecked = true
            setTextColor(KC.muted)
            setButtonDrawable(null)
            setPadding((16 * dp).toInt(), 0, 0, 0)
            setOnCheckedChangeListener { _, checked -> autoScroll = checked }
        }
        toolbarRow.addView(copyBtn)
        toolbarRow.addView(clearBtn)
        toolbarRow.addView(autoScrollCheck, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(toolbarRow)

        // Input row with history buttons
        val inputRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        historyPrevBtn = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            setColorFilter(KC.soft)
            setBackgroundColor(0x00000000)
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            contentDescription = "Previous command"
            setOnClickListener { navigateHistory(-1) }
        }
        historyNextBtn = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_media_next)
            setColorFilter(KC.soft)
            setBackgroundColor(0x00000000)
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            contentDescription = "Next command"
            setOnClickListener { navigateHistory(1) }
        }
        input = EditText(ctx).apply {
            hint = "KoLmafia command or: ash print(\"hi\");"
            setHintTextColor(KC.hint)
            setTextColor(KC.text)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        }
        val run = Button(ctx).apply {
            text = "Run"
            setTextColor(KC.onAccent)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.colorPrimary)))
            setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (12 * dp).toInt())
        }
        val scriptsBtn = Button(ctx).apply {
            text = getString(R.string.tab_scripts)
            setTextColor(KC.onAccent)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.colorSecondary)))
            setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (12 * dp).toInt())
        }
        // command-name suggestions for the first word
        val suggestRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0) }
        val suggest = android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; visibility = View.GONE; addView(suggestRow) }
        root.addView(suggest)
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                val t = e?.toString() ?: ""
                val word = t.trimStart()
                if (word.isEmpty() || word.contains(' ') || word.contains('\n')) { suggest.visibility = View.GONE; return }
                KolEngineHost.commandNames(word.lowercase()) { names ->
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        suggestRow.removeAllViews()
                        val shown = names.filter { it != word.lowercase() }
                        shown.forEach { n ->
                            suggestRow.addView(TextView(ctx).apply {
                                text = n
                                typeface = Typeface.MONOSPACE
                                textSize = 13f
                                setTextColor(KC.text)
                                setBackgroundResource(R.drawable.bg_icon_button)
                                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
                                setOnClickListener { input.setText("$n "); input.setSelection(input.text.length) }
                            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (6 * dp).toInt() })
                        }
                        suggest.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        inputRow.addView(historyPrevBtn)
        inputRow.addView(historyNextBtn)
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(run)
        root.addView(inputRow)

        // Enter key handler
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED) {
                submit()
                true
            } else false
        }

        // Long press on input for history menu
        input.setOnLongClickListener {
            showHistoryMenu()
            true
        }

        run.setOnClickListener { submit() }
        return root
    }

    // ---- scripting hub: one place for the console and for managing/editing/installing scripts ----

    private var hubTabs: View? = null

    @Suppress("UNCHECKED_CAST")
    private fun selectHubTab(i: Int) {
        (hubTabs?.tag as? (Int) -> Unit)?.invoke(i)
    }
    private var hubHost: android.widget.FrameLayout? = null
    private var consoleRoot: View? = null
    private val scriptsHostId = View.generateViewId()

    private fun scriptsHub(console: View): View {
        val ctx = requireContext()
        consoleRoot = console
        val tabs = com.example.kolmafiaandroid.ui.iconTabs(ctx, listOf(
            R.drawable.nav_console to "Console",
            R.drawable.tab_code to "Scripts",
        )) { showHubPane(it) }
        val host = android.widget.FrameLayout(ctx)
        hubTabs = tabs
        hubHost = host
        host.addView(console, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(tabs)
            addView(host, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun showHubPane(index: Int) {
        val host = hubHost ?: return
        val ctx = context ?: return
        childFragmentManager.findFragmentByTag("scripts")?.let { childFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss() }
        host.removeAllViews()
        if (index == 0) {
            consoleRoot?.let { host.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
        } else {
            val holder = android.widget.FrameLayout(ctx).apply { id = scriptsHostId }
            host.addView(holder, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            childFragmentManager.beginTransaction().replace(scriptsHostId, ScriptsFragment(), "scripts").commitAllowingStateLoss()
        }
    }

    /** Called by the embedded script manager (for its back arrow, and after it starts a script). */
    fun showConsoleTab() {
        selectHubTab(0)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            KolEngineHost.lines.collect { line ->
                withContext(Dispatchers.Main) {
                    output.append(Html.fromHtml(line, Html.FROM_HTML_MODE_COMPACT))
                    output.append("\n")
                    if (autoScroll) {
                        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            }
        }
    }

    private fun loadHistory() {
        val json = prefs?.getString(HISTORY_KEY, "[]") ?: "[]"
        try {
            val arr = org.json.JSONArray(json)
            history.clear()
            for (i in 0 until arr.length()) {
                history.add(arr.getString(i))
            }
        } catch (e: Exception) {
            history.clear()
        }
        historyIndex = history.size
    }

    private fun saveHistory() {
        val arr = org.json.JSONArray()
        for (cmd in history) arr.put(cmd)
        prefs?.edit()?.putString(HISTORY_KEY, arr.toString())?.apply()
    }

    private fun addToHistory(cmd: String) {
        if (history.isNotEmpty() && history.last() == cmd) return
        history.add(cmd)
        if (history.size > MAX_HISTORY) history.removeAt(0)
        historyIndex = history.size
        saveHistory()
    }

    private fun navigateHistory(delta: Int) {
        if (history.isEmpty()) return
        historyIndex = (historyIndex + delta).coerceIn(0, history.size)
        if (historyIndex < history.size) {
            input.setText(history[historyIndex])
            input.setSelection(input.length())
        }
    }

    private fun showHistoryMenu() {
        if (history.isEmpty()) {
            Toast.makeText(requireContext(), "No command history", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = history.reversed().toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Command History")
            .setItems(labels) { _, which ->
                val cmd = history[history.size - 1 - which]
                input.setText(cmd)
                input.setSelection(cmd.length)
            }
            .show()
    }

    private fun submit() {
        val cmd = input.text.toString().trim()
        if (cmd.isEmpty()) return
        addToHistory(cmd)
        input.setText("")
        output.append("> $cmd\n")
        KolEngineHost.blankParams = pendingBlank
        pendingBlank = false
        KolEngineHost.execute(cmd)
    }

    private fun copyOutput() {
        val text = output.text.toString()
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), "Nothing to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Console output", text))
        Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun clearOutput() {
        output.setText("")
        Toast.makeText(requireContext(), "Output cleared", Toast.LENGTH_SHORT).show()
    }

    private fun openScripts() {
        selectHubTab(1)
    }
}