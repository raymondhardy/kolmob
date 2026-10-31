package com.example.kolmafiaandroid

import com.example.kolmafiaandroid.ui.KC
import com.example.kolmafiaandroid.ui.ConsoleThemes

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
    private lateinit var autoScrollBtn: Button
    private lateinit var historyPrevBtn: ImageButton
    private lateinit var historyNextBtn: ImageButton

    private val history = mutableListOf<String>()
    private var historyIndex = -1
    private var prefs: SharedPreferences? = null
    private var autoScroll = true

    /** Every line received (as HTML), so changing the log level can bring back lines that were hidden. */
    private val entries = ArrayDeque<Pair<Int, String>>()
    private var minLevel = 1
    private var hiddenCount = 0
    private var trimmed = 0
    private var levelNote: TextView? = null
    private var pauseButton: ImageButton? = null
    private var pausedNow = false

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
        argParams = if (f.name.lowercase().endsWith(".js")) {
            listOf("string" to "arguments") // a JavaScript script gets everything typed after its name as one string
        } else m?.groupValues?.get(1)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.map { p -> val parts = p.split(Regex("\\s+")); (parts.firstOrNull() ?: "") to (parts.getOrNull(1) ?: "") } ?: emptyList()
        val saved = prefs?.getString("args_" + rel(f), "") ?: ""
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
        com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(ctx).setTitle("Arguments for ${rel(f)}").setView(col)
            .setPositiveButton("Save") { _, _ ->
                argValues = boxes.map { it.text.toString() }
                prefs?.edit()?.putString("args_" + rel(f), argValues.joinToString("\u001f"))?.apply()
                refreshArgs()
            }
            .setNegativeButton("Cancel", null).create().also { d ->
                d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                d.show()
            }
    }

    private var scriptPicker: android.widget.Spinner? = null
    private var scriptFiles: List<java.io.File> = emptyList()

    /** Name as shown and as `call` wants it: relative to the scripts folder (JavaScript scripts live in their own folder). */
    private fun rel(f: java.io.File): String =
        com.example.kolmafiaandroid.ui.ScriptFiles.relName(java.io.File(requireContext().filesDir, "kolmafia/scripts"), f)

    private fun reloadScripts() {
        val ctx = context ?: return
        val picker = scriptPicker ?: return
        val dir = java.io.File(ctx.filesDir, "kolmafia/scripts")
        val keep = scriptFiles.getOrNull(picker.selectedItemPosition)?.let { rel(it) } ?: prefs?.getString("last_script", null)
        scriptFiles = com.example.kolmafiaandroid.ui.ScriptFiles.list(dir).sortedBy { rel(it).lowercase() }
        picker.adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, scriptFiles.map { rel(it) })
        scriptFiles.indexOfFirst { rel(it) == keep }.takeIf { it >= 0 }?.let { picker.setSelection(it) }
        picker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                scriptFiles.getOrNull(pos)?.let { prefs?.edit()?.putString("last_script", rel(it))?.apply() }
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
        entries.clear(); hiddenCount = 0; trimmed = 0

        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt()) }
        // landscape: script controls on the left, the log (output, buttons, command line) on the right
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val controls = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val log = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (land) {
            root.orientation = LinearLayout.HORIZONTAL
            root.addView(ScrollView(ctx).apply { addView(controls) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 4f).apply { marginEnd = (8 * dp).toInt() })
            root.addView(log, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 6f))
        } else {
            root.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(log, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

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
                input.setText(if (a.isEmpty()) "call ${rel(f)}" else "call ${rel(f)} $a")
                pendingBlank = a.isEmpty()
                submit()
            }
        }, side)
        topRow.addView(iconBtn(R.drawable.ic_s_stop, "Stop script") { KolEngineHost.abort() }, side)
        pauseButton = iconBtn(R.drawable.ic_s_pause, "Pause or resume the script (it waits before its next request)") {
            KolEngineHost.setPaused(!KolEngineHost.paused.value)
        }
        topRow.addView(pauseButton, side)
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
        controls.addView(topRow)
        controls.addView(argsRow)
        controls.addView(btnRow)

        // Log level: lines a script tags [DEBUG] stay hidden unless you pick Debug.
        minLevel = (prefs?.getInt(com.example.kolmafiaandroid.ui.LogLevels.PREF, 1) ?: 1).coerceIn(0, 3)
        val levelRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), (4 * dp).toInt())
        }
        levelRow.addView(TextView(ctx).apply { text = "Log level"; textSize = 13f; setTextColor(KC.muted) })
        val levelSpinner = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, com.example.kolmafiaandroid.ui.LogLevels.labels)
            setSelection(minLevel)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (pos == minLevel || !::output.isInitialized) return
                    minLevel = pos
                    prefs?.edit()?.putInt(com.example.kolmafiaandroid.ui.LogLevels.PREF, pos)?.apply()
                    rebuildOutput()
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
        levelRow.addView(levelSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (8 * dp).toInt() })
        levelNote = TextView(ctx).apply { textSize = 12f; setTextColor(KC.muted) }
        levelRow.addView(levelNote)
        controls.addView(levelRow)
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
        log.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        applyTheme()

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
        autoScrollBtn = Button(ctx).apply {
            setTextColor(KC.onAccent)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.colorSecondary)))
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            setOnClickListener { setAutoScroll(!autoScroll) }
        }
        setAutoScroll(true)
        // dragging the output up to read it pauses following; Follow jumps back to the newest line
        scroll.setOnTouchListener { _, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_MOVE && autoScroll) setAutoScroll(false)
            false
        }
        toolbarRow.addView(copyBtn)
        toolbarRow.addView(clearBtn)
        toolbarRow.addView(Button(ctx).apply {
            text = "Colors"
            setTextColor(KC.onAccent)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.colorSecondary)))
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            setOnClickListener { showThemePicker() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * dp).toInt() })
        toolbarRow.addView(autoScrollBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (8 * dp).toInt() })
        log.addView(toolbarRow)

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
        log.addView(suggest)
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
        log.addView(inputRow)

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

    private fun theme(): ConsoleThemes.Theme = ConsoleThemes.byName(prefs?.getString("console_theme", null))

    /** Output colours: the chosen terminal profile's background and text, with KoLmafia's own colours re-mapped to it. */
    private fun applyTheme() {
        val t = theme()
        val dp = resources.displayMetrics.density
        output.setTextColor(t.fg)
        scroll.background = android.graphics.drawable.GradientDrawable().apply { setColor(t.bg); cornerRadius = 8 * dp }
        output.editableText?.let { ConsoleThemes.retheme(it, t) }
    }

    /** A list of the profiles, each drawn in its own colours, so the choice is a look and not a name. */
    private fun showThemePicker() {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt()) }
        val current = theme().name
        var dialog: androidx.appcompat.app.AlertDialog? = null
        for (th in ConsoleThemes.all) {
            val sample = android.text.SpannableStringBuilder()
            fun add(text: String, color: Int) {
                val start = sample.length
                sample.append(text)
                sample.setSpan(android.text.style.ForegroundColorSpan(color), start, sample.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val acc = th.accents ?: intArrayOf(0xFFFF6B6B.toInt(), 0xFFFFA726.toInt(), 0xFFFFEE58.toInt(), 0xFF66BB6A.toInt(), 0xFF26C6DA.toInt(), 0xFF42A5F5.toInt(), 0xFFAB47BC.toInt())
            add((if (th.name == current) "✓ " else "") + th.name + "\n", th.fg)
            add("error ", acc[0]); add("warn ", acc[2]); add("ok ", acc[3]); add("info ", acc[5]); add("item ", acc[6]); add("dim", th.dim)
            list.addView(TextView(ctx).apply {
                text = sample
                typeface = Typeface.MONOSPACE
                textSize = 13f
                setBackgroundColor(th.bg)
                setTextColor(th.fg)
                setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
                setOnClickListener {
                    prefs?.edit()?.putString("console_theme", th.name)?.apply()
                    applyTheme()
                    dialog?.dismiss()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (6 * dp).toInt() })
        }
        dialog = com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(ctx)
            .setTitle("Terminal colours")
            .setView(ScrollView(ctx).apply { addView(list) })
            .setNegativeButton("Close", null)
            .show()
    }

    /** Called by the embedded script manager (for its back arrow, and after it starts a script). */
    fun showConsoleTab() {
        selectHubTab(0)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            KolEngineHost.paused.collect { p ->
                pausedNow = p
                val ctx = context ?: return@collect
                pauseButton?.setColorFilter(if (p) ctx.getColor(R.color.colorPrimary) else KC.text)
                updateLevelNote()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            KolEngineHost.lines.collect { line ->
                withContext(Dispatchers.Main) { addLine(line) }
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
        com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(requireContext())
            .setTitle("Command History")
            .setItems(labels) { _, which ->
                val cmd = history[history.size - 1 - which]
                input.setText(cmd)
                input.setSelection(cmd.length)
            }
            .show()
    }

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        KolEngineHost.blankParams = pendingBlank
        pendingBlank = false
        // A pasted block of several commands (one per line) must run as separate commands, not as one command
        // whose last word is the rest of the paste. Script blocks (ash/js with braces) stay in one piece.
        val first = text.substringBefore(' ').lowercase()
        val isScriptBlock = (first == "ash" || first == "js") && (text.contains('{') || text.contains('}'))
        val cmds = if (isScriptBlock) listOf(text) else text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (cmd in cmds) {
            addToHistory(cmd)
            addLine(android.text.TextUtils.htmlEncode("> $cmd"))
            KolEngineHost.execute(cmd)
        }
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

    /** Adds one received line (HTML) to the history and, if its level is shown, to the screen. */
    private fun addLine(html: String) {
        val spanned = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
        val level = com.example.kolmafiaandroid.ui.LogLevels.of(spanned)
        entries.addLast(level to html)
        if (entries.size > 5000) {
            entries.removeFirst()
            // drop old lines from the screen in batches rather than on every line
            if (++trimmed >= 1000) { rebuildOutput(); return }
        }
        if (level >= minLevel) {
            output.append(ConsoleThemes.themed(spanned, theme()))
            output.append("\n")
            if (autoScroll) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        } else {
            hiddenCount++
            updateLevelNote()
        }
    }

    /** Redraws the screen from the history for the chosen log level. */
    private fun rebuildOutput() {
        if (!::output.isInitialized) return
        val t = theme()
        val sb = android.text.SpannableStringBuilder()
        hiddenCount = 0
        trimmed = 0
        for ((level, html) in entries) {
            if (level >= minLevel) {
                sb.append(ConsoleThemes.themed(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT), t)).append("\n")
            } else hiddenCount++
        }
        output.setText(sb, TextView.BufferType.EDITABLE)
        updateLevelNote()
        if (autoScroll) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** On: the output follows new lines. Off: it stays where you are reading. */
    private fun setAutoScroll(on: Boolean) {
        autoScroll = on
        autoScrollBtn.text = if (on) "Scroll: on" else "Scroll: off"
        if (on && ::scroll.isInitialized) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updateLevelNote() {
        levelNote?.text = listOfNotNull(if (pausedNow) "Paused (tap pause to resume)" else null, if (hiddenCount > 0) "$hiddenCount hidden" else null).joinToString(" · ")
    }

    private fun clearOutput() {
        entries.clear()
        hiddenCount = 0
        trimmed = 0
        updateLevelNote()
        output.setText("")
        Toast.makeText(requireContext(), "Output cleared", Toast.LENGTH_SHORT).show()
    }

    private fun openScripts() {
        selectHubTab(1)
    }
}