package com.example.kolmafiaandroid.fragments

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.graphics.Typeface
import android.text.Spannable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.AshSyntaxHighlighter
import com.example.kolmafiaandroid.ui.LineNumberView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream

/**
 * Full-screen editor for ASH scripts with line numbers, syntax highlighting, find, and run.
 */
class ScriptEditorFragment : DialogFragment() {

    private var scriptFile: File? = null
    private var isNewFile = false
    private var hasChanges = false
    private var originalContent = ""

    private lateinit var editor: EditText
    private val highlightHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val highlightTask = Runnable { if (isAdded && ::editor.isInitialized) syntaxHighlighter.highlight(editor.text) }
    private lateinit var lineNumbers: LineNumberView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var findBar: View
    private lateinit var findInput: EditText
    private lateinit var findPrevBtn: Button
    private lateinit var findNextBtn: Button
    private lateinit var findCloseBtn: Button

    private val syntaxHighlighter = AshSyntaxHighlighter()
    private var findMatches = mutableListOf<Int>()
    private var currentFindIndex = -1
    private var findHighlightSpans = mutableListOf<android.text.style.BackgroundColorSpan>()

    companion object {
        fun newInstance(file: File): ScriptEditorFragment {
            val fragment = ScriptEditorFragment()
            val args = Bundle()
            args.putString("file_path", file.absolutePath)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(DialogFragment.STYLE_NORMAL, R.style.FullScreenDialogStyle)
        val path = arguments?.getString("file_path") ?: return
        scriptFile = File(path)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.let {
            // the shared full-screen style hides the status bar, which left the toolbar underneath it (Run was untappable)
            it.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(it, true)
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_script_editor, container, false)

        // keep clear of the status bar, navigation bar and keyboard
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { androidx.core.view.ViewCompat.requestApplyInsets(v) }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        editor = view.findViewById(R.id.script_input)
        lineNumbers = view.findViewById(R.id.line_numbers)
        toolbar = view.findViewById(R.id.editor_toolbar)
        findBar = view.findViewById(R.id.find_bar)
        findInput = view.findViewById(R.id.find_input)
        findPrevBtn = view.findViewById(R.id.find_prev)
        findNextBtn = view.findViewById(R.id.find_next)
        findCloseBtn = view.findViewById(R.id.find_close)

        // Set up line numbers
        lineNumbers.setTargetEditText(editor)

        // Apply dark theme styling
        editor.setBackgroundColor(requireContext().getColor(R.color.colorBackground))
        editor.setTextColor(requireContext().getColor(R.color.colorOnBackground))
        editor.setHintTextColor(requireContext().getColor(R.color.bottom_nav_inactive))

        // Load file content
        scriptFile?.let { file ->
            if (file.exists()) {
                val content = file.readText()
                editor.setText(content)
                originalContent = content
                toolbar.title = file.name
            } else {
                isNewFile = true
                val defaultContent = "void main() {\n  print(\"Hello from Kolmob\");\n}\n"
                editor.setText(defaultContent)
                originalContent = defaultContent
            }
        }


        // Autocomplete: tappable suggestions above the keyboard. Nothing is inserted unless you tap, so your text is never rewritten.
        val dpx = resources.displayMetrics.density
        val sugRow = android.widget.LinearLayout(requireContext()).apply { orientation = android.widget.LinearLayout.HORIZONTAL; setPadding((6 * dpx).toInt(), (4 * dpx).toInt(), (6 * dpx).toInt(), (4 * dpx).toInt()) }
        val sugBar = android.widget.HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            setBackgroundColor(requireContext().getColor(R.color.colorSurface))
            addView(sugRow)
        }
        (view as android.widget.LinearLayout).addView(sugBar)
        val ashWords = listOf("if", "else", "while", "for", "foreach", "from", "to", "upto", "downto", "repeat", "until", "switch", "case",
            "default", "return", "break", "continue", "import", "record", "typedef", "static", "void", "int", "float", "string", "boolean",
            "item", "monster", "location", "effect", "skill", "familiar", "class", "stat", "phylum", "element", "slot", "buffer", "matcher",
            "true", "false", "main", "print", "abort", "call", "try", "catch", "finally")
        var sugGen = 0
        fun currentWord(): Pair<Int, String>? {
            val t = editor.text
            val end = editor.selectionStart
            if (end <= 0 || end > t.length || editor.selectionEnd != end) return null
            var st = end
            while (st > 0 && (t[st - 1].isLetterOrDigit() || t[st - 1] == '_')) st--
            if (end - st < 2) return null
            return st to t.substring(st, end)
        }
        fun showSuggestions(word: String, start: Int, library: List<String>) {
            val seen = LinkedHashSet<String>()
            val lw = word.lowercase()
            ashWords.filter { it.startsWith(lw) && it != lw }.forEach { seen.add(it) }
            // words already used in this file
            Regex("[A-Za-z_][A-Za-z0-9_]{2,}").findAll(editor.text).map { it.value }
                .filter { it.lowercase().startsWith(lw) && it != word }.distinct().take(8).forEach { seen.add(it) }
            library.forEach { seen.add(it) }
            sugRow.removeAllViews()
            seen.take(14).forEach { name ->
                val isFn = library.any { it == name }
                val shown = name
                val bare = name.substringBefore("(")
                sugRow.addView(TextView(requireContext()).apply {
                    text = shown
                    typeface = Typeface.MONOSPACE
                    textSize = 14f
                    setTextColor(requireContext().getColor(R.color.colorOnSurface))
                    setBackgroundResource(R.drawable.bg_icon_button)
                    setPadding((12 * dpx).toInt(), (8 * dpx).toInt(), (12 * dpx).toInt(), (8 * dpx).toInt())
                    setOnClickListener {
                        val cur = currentWord()
                        val s0 = cur?.first ?: start
                        val e0 = editor.selectionStart
                        if (s0 in 0..e0 && e0 <= editor.text.length) {
                            editor.text.replace(s0, e0, if (isFn) "$bare(" else name)
                        }
                        sugBar.visibility = View.GONE
                    }
                }, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (6 * dpx).toInt() })
            }
            sugBar.visibility = if (seen.isEmpty()) View.GONE else View.VISIBLE
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val cw = currentWord()
                if (cw == null) { sugBar.visibility = View.GONE; return }
                val gen = ++sugGen
                showSuggestions(cw.second, cw.first, emptyList())
                com.example.kolmafiaandroid.KolEngineHost.ashNames(cw.second) { names ->
                    activity?.runOnUiThread {
                        if (isAdded && gen == sugGen) currentWord()?.let { showSuggestions(it.second, it.first, names) }
                    }
                }
            }
        })


        // Problem bar: KoLmafia's own parser checks the script as you type (nothing is run). Tap it to jump to the line.
        val problemBar = TextView(requireContext()).apply {
            typeface = Typeface.MONOSPACE; textSize = 12f; visibility = View.GONE
            setTextColor(0xFFFF8A80.toInt()); setBackgroundColor(requireContext().getColor(R.color.colorSurface))
            setPadding((10 * dpx).toInt(), (6 * dpx).toInt(), (10 * dpx).toInt(), (6 * dpx).toInt())
        }
        (view as android.widget.LinearLayout).addView(problemBar, (view as android.widget.LinearLayout).indexOfChild(sugBar))
        var problemLine = 0
        val checkHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var checkGen = 0
        var allProblems: List<List<String>> = emptyList()
        fun cursorLine(): Int {
            val t = editor.text
            val sel = editor.selectionStart.coerceIn(0, t.length)
            var n = 1
            for (i in 0 until sel) if (t[i] == '\n') n++
            return n
        }
        // Problems on the line you are still typing on stay hidden until you move off it.
        fun renderProblems() {
            val cl = cursorLine()
            val shown = allProblems.filter { (it[0].toIntOrNull() ?: 1) != cl }
            val first = shown.firstOrNull()
            if (first == null) { problemBar.visibility = View.GONE; return }
            problemLine = first[0].toIntOrNull() ?: 1
            val extra = if (shown.size > 1) "  (+${shown.size - 1} more)" else ""
            problemBar.text = "Line ${first[0]}: ${first[3]}$extra"
            problemBar.setTextColor(if (first[2] == "WARNING") 0xFFFFE082.toInt() else 0xFFFF8A80.toInt())
            problemBar.visibility = View.VISIBLE
        }
        val checkTask = Runnable {
            val f = scriptFile ?: return@Runnable
            val gen = ++checkGen
            com.example.kolmafiaandroid.KolEngineHost.checkAsh(f.path, editor.text.toString()) { problems ->
                activity?.runOnUiThread {
                    if (!isAdded || gen != checkGen) return@runOnUiThread
                    allProblems = problems.map { it.split("\t") }.filter { it.size >= 4 }
                    renderProblems()
                }
            }
        }
        val cursorTick = object : Runnable {
            var last = -1
            override fun run() {
                if (!isAdded) return
                val c = cursorLine()
                if (c != last) { last = c; renderProblems() }
                checkHandler.postDelayed(this, 400)
            }
        }
        checkHandler.postDelayed(cursorTick, 400)
        problemBar.setOnClickListener {
            val lines = editor.text.toString().split("\n")
            var off = 0
            for (i in 0 until (problemLine - 1).coerceIn(0, lines.size)) off += lines[i].length + 1
            editor.requestFocus(); editor.setSelection(off.coerceIn(0, editor.text.length))
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                checkHandler.removeCallbacks(checkTask)
                checkHandler.postDelayed(checkTask, 900)
            }
        })
        checkHandler.postDelayed(checkTask, 600)

        // Set up syntax highlighting with debounce
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                hasChanges = s.toString() != originalContent
                // Debounced syntax highlighting (typing stays smooth on big scripts)
                highlightHandler.removeCallbacks(highlightTask)
                highlightHandler.postDelayed(highlightTask, 350)
            }
        })

        // Toolbar listeners
        toolbar.setNavigationOnClickListener { onBackPressed() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_save -> {
                    saveScript(editor.text.toString())
                    true
                }
                R.id.action_run_args -> {
                    val input = android.widget.EditText(requireContext()).apply { hint = "arguments passed to main()"; maxLines = 1 }
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Run with arguments")
                        .setView(input)
                        .setPositiveButton("Run") { _, _ -> runScript(input.text.toString().trim()) }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
                R.id.action_open_import -> {
                    openImport()
                    true
                }
                R.id.action_run -> {
                    runScript()
                    true
                }
                R.id.action_find -> {
                    toggleFindBar()
                    true
                }
                else -> false
            }
        }

        // Find bar listeners
        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                findText(s.toString())
            }
        })

        findPrevBtn.setOnClickListener { findPrevious() }
        findNextBtn.setOnClickListener { findNext() }
        findCloseBtn.setOnClickListener { hideFindBar() }

        return view
    }

    override fun onResume() {
        super.onResume()
        // Initial syntax highlight
        syntaxHighlighter.highlight(editor.text)
    }

    private fun onBackPressed() {
        if (hasChanges) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.scripts_cancel)
                .setMessage("Discard changes?")
                .setPositiveButton(R.string.scripts_cancel) { _, _ -> dismiss() }
                .setNegativeButton(android.R.string.ok, null)
                .show()
        } else {
            dismiss()
        }
    }

    private fun saveScript(content: String, close: Boolean = true) {
        scriptFile?.let { file ->
            try {
                FileOutputStream(file).use { it.write(content.toByteArray()) }
                originalContent = content
                hasChanges = false
                if (close) {
                    Toast.makeText(requireContext(), R.string.scripts_saved, Toast.LENGTH_SHORT).show()
                    dismiss()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.scripts_error, e.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun runScript(args: String = "") {
        val content = editor.text.toString()
        saveScript(content, close = false)
        // After saving, run the script via KolEngineHost
        scriptFile?.let { file ->
            val fileName = file.name
            watchForError(fileName)
            KolEngineHost.execute(if (args.isEmpty()) "call $fileName" else "call $fileName $args")
            Toast.makeText(requireContext(), "Running: $fileName", Toast.LENGTH_SHORT).show()
        }
    }

    /** If the run reports an error location in this file, select it in the editor. */
    private fun watchForError(fileName: String) {
        val skip = KolEngineHost.lines.replayCache.size
        val job = viewLifecycleOwner.lifecycleScope.launch {
            KolEngineHost.lines.drop(skip).collect { line ->
                val chars = Regex("""char (\d+) to char (\d+)""").find(line)
                val ln = Regex("""\b([\w./-]+\.ash), line (\d+)""").find(line)
                if (ln != null && !ln.groupValues[1].endsWith(fileName.substringAfterLast('/'))) return@collect
                val text = editor.text
                if (ln != null) {
                    // "(file.ash, line N, char A to char B)": columns are within the line
                    val target = ln.groupValues[2].toInt()
                    var off = 0
                    repeat(target - 1) { val i = text.indexOf('\n', off); if (i >= 0) off = i + 1 }
                    val eol = text.indexOf('\n', off).let { if (it < 0) text.length else it }
                    val start = if (chars != null) (off + chars.groupValues[1].toInt() - 1).coerceIn(off, eol) else off
                    val end = if (chars != null) (off + chars.groupValues[2].toInt() - 1).coerceIn(start, eol) else eol
                    editor.requestFocus()
                    editor.setSelection(start, end)
                    Toast.makeText(requireContext(), "Error at line $target", Toast.LENGTH_LONG).show()
                } else if (chars != null) {
                    // "(char A to char B)": offsets within the whole script
                    val a = chars.groupValues[1].toInt().coerceIn(0, text.length)
                    val b = chars.groupValues[2].toInt().coerceIn(a, text.length)
                    editor.requestFocus()
                    editor.setSelection(a, b)
                    Toast.makeText(requireContext(), "Error at line ${text.substring(0, a).count { it == '\n' } + 1}", Toast.LENGTH_LONG).show()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch { kotlinx.coroutines.delay(20_000); job.cancel() }
    }

    private fun toggleFindBar() {
        if (findBar.visibility == View.VISIBLE) {
            hideFindBar()
        } else {
            showFindBar()
        }
    }

    /** Lists the files this script imports (resolved like KoLmafia: next to the script, then the scripts root) and opens one. */
    private fun openImport() {
        val ctx = requireContext()
        val root = File(ctx.filesDir, "kolmafia/scripts")
        val dir = scriptFile?.parentFile ?: root
        val names = Regex("""^\s*import\s+[<"']([^>"']+)[>"']""", RegexOption.MULTILINE)
            .findAll(editor.text).map { it.groupValues[1].trim() }.distinct().toList()
        if (names.isEmpty()) { Toast.makeText(ctx, "This script has no imports", Toast.LENGTH_SHORT).show(); return }
        val found = names.map { n -> n to listOf(File(dir, n), File(root, n)).firstOrNull { it.isFile } }
        MaterialAlertDialogBuilder(ctx).setTitle("Imports")
            .setItems(found.map { it.first + if (it.second == null) "  (not found)" else "" }.toTypedArray()) { _, i ->
                val f = found[i].second
                if (f == null) Toast.makeText(ctx, "Not found in scripts. Is its repository installed?", Toast.LENGTH_SHORT).show()
                else newInstance(f).show(parentFragmentManager, "script_editor_import")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showFindBar() {
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        // Show keyboard
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(findInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideFindBar() {
        findBar.visibility = View.GONE
        findInput.text.clear()
        clearFindHighlights()
    }

    private fun findText(query: String) {
        clearFindHighlights()
        if (query.isEmpty()) return

        val text = editor.text.toString()
        findMatches = mutableListOf()
        var index = text.indexOf(query, 0)
        while (index >= 0) {
            findMatches.add(index)
            index = text.indexOf(query, index + query.length)
        }

        if (findMatches.isNotEmpty()) {
            currentFindIndex = 0
            highlightCurrentMatch()
        }
    }

    private fun findNext() {
        val matches = findMatches
        if (matches.isEmpty()) return
        currentFindIndex = (currentFindIndex + 1) % matches.size
        highlightCurrentMatch()
    }

    private fun findPrevious() {
        val matches = findMatches
        if (matches.isEmpty()) return
        currentFindIndex = (currentFindIndex - 1 + matches.size) % matches.size
        highlightCurrentMatch()
    }

    private fun highlightCurrentMatch() {
        val matches = findMatches
        if (matches.isEmpty() || currentFindIndex < 0 || currentFindIndex >= matches.size) return

        val query = findInput.text.toString()
        if (query.isEmpty()) return

        val start = matches[currentFindIndex]
        val end = start + query.length

        // Remove existing find highlights
        clearFindHighlights()

        // Add highlight for current match
        val highlight = android.text.style.BackgroundColorSpan(0xFFFFEB3B.toInt()) // Yellow
        findHighlightSpans.add(highlight)
        (editor.text as Spannable).setSpan(highlight, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Scroll to the match
        val layout = editor.layout
        if (layout != null) {
            val line = layout.getLineForOffset(start)
            val y = layout.getLineTop(line)
            editor.scrollTo(0, y - editor.height / 2)
        }
    }

    private fun clearFindHighlights() {
        for (span in findHighlightSpans) {
            editor.text.removeSpan(span)
        }
        findHighlightSpans.clear()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clear spans to prevent memory leaks
        clearFindHighlights()
    }
}