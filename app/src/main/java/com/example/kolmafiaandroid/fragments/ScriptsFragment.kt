package com.example.kolmafiaandroid.fragments

import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.KC
import com.example.kolmafiaandroid.ui.ScriptFiles
import com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The script manager, as a file manager: your scripts folder with its subfolders, the installed Git and SVN
 * projects pinned at the top, and everything you do with scripts (run, edit, rename, delete, create, import,
 * install and update projects) in one place.
 */
class ScriptsFragment : DialogFragment() {

    private val scriptsDir by lazy { File(requireContext().filesDir, "kolmafia/scripts") }
    private val relayDir by lazy { File(requireContext().filesDir, "kolmafia/relay") }
    private var dir: File? = null
    private var sortRecent = false
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var titleView: TextView
    private lateinit var backBtn: TextView
    private var embedded = false
    private lateinit var crumb: TextView
    private lateinit var backCallback: OnBackPressedCallback
    private val busy = mutableSetOf<String>()
    private var installing = false
    private var showProjects = false

    private data class Project(
        val name: String, val isGit: Boolean, val branch: String, val commit: String, val revision: Int,
        val scope: String = "global", val owner: String = ""
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(DialogFragment.STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        // as a dialog, Back goes up a folder (or out of Projects) before it closes the manager
        dialog.setOnKeyListener { _, code, ev ->
            if (code == android.view.KeyEvent.KEYCODE_BACK && ev.action == android.view.KeyEvent.ACTION_UP && (showProjects || (dir ?: scriptsDir) != scriptsDir)) { goUp(); true } else false
        }
        return dialog
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        scriptsDir.mkdirs()
        dir = scriptsDir
        val page = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(KC.pick(0xFF121212.toInt(), 0xFFF5F5F5.toInt())) }

        embedded = !showsDialog
        val fg = if (embedded) KC.text else 0xFFFFFFFF.toInt()
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), if (embedded) 0 else dp(8), dp(8), if (embedded) 0 else dp(8))
            if (!embedded) setBackgroundColor(0xFF2E7D32.toInt())
        }
        fun headBtn(text: String, size: Float, click: () -> Unit) = TextView(ctx).apply {
            this.text = text; textSize = size; setTextColor(fg); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(6), dp(14), dp(6)); setOnClickListener { click() }
        }
        backBtn = headBtn("‹", if (embedded) 26f else 30f) { goUp() }
        head.addView(backBtn)
        titleView = TextView(ctx).apply {
            textSize = if (embedded) 14f else 19f
            setTextColor(if (embedded) KC.muted else 0xFFFFFFFF.toInt())
            if (!embedded) typeface = Typeface.DEFAULT_BOLD
            if (embedded) setPadding(dp(8), 0, 0, 0)
        }
        head.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(headBtn("＋", 22f) { showAddMenu() })
        head.addView(headBtn("⋮", 22f) { showMoreMenu() })
        page.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (embedded) 48 else 56)))

        crumb = TextView(ctx).apply { textSize = 12f; setTextColor(KC.muted); setPadding(dp(16), dp(10), dp(16), dp(4)) }
        if (!embedded) page.addView(crumb)
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(96)) }
        scroll = ScrollView(ctx).apply { addView(list) }
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        backCallback = object : OnBackPressedCallback(false) { override fun handleOnBackPressed() { goUp() } }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        render()
        when (arguments?.getString("action")) {
            "new" -> page.post { newScriptDialog() }
            "install" -> page.post { addProjectDialog() }
            "import" -> page.post { importDialog() }
        }
        return page
    }

    override fun onStart() {
        super.onStart()
        if (showsDialog) (activity as? com.example.kolmafiaandroid.MainActivity)?.fitDialogAboveNav(requireDialog())
    }

    private fun goUp() {
        val d = dir ?: return
        if (showProjects) { showProjects = false; render() }
        else if (d != scriptsDir) { dir = d.parentFile ?: scriptsDir; render() }
        else if (showsDialog) dismiss()
        else (parentFragment as? com.example.kolmafiaandroid.ConsoleFragment)?.showConsoleTab()
    }

    // ---------------------------------------------------------------- listing

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(requireContext()).apply {
        this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun section(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(14), dp(8), dp(4)) }
        row.addView(label(title, 12f, 0xFF4CAF50.toInt(), true).apply { letterSpacing = 0.1f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null) row.addView(label(action, 13f, 0xFF4CAF50.toInt(), true).apply { setPadding(dp(8), dp(4), dp(8), dp(4)); setOnClickListener { onAction?.invoke() } })
        list.addView(row)
    }

    private fun row(glyph: String, glyphColor: Int, name: String, detail: String, boldName: Boolean, onTap: () -> Unit, onLong: (() -> Unit)? = null): LinearLayout {
        val r = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onTap() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        val ext = if (glyph.startsWith("f:")) glyph.substring(2) else ""
        val iconRes = when {
            glyph == "▸" -> R.drawable.ic_s_folder
            glyph == "◆" -> R.drawable.ic_s_project
            glyph == "◦" || (glyph.startsWith("f:") && ext.isEmpty()) -> R.drawable.ic_s_file
            else -> 0
        }
        if (ext.isNotEmpty()) {
            r.addView(android.widget.ImageView(requireContext()).apply { setImageDrawable(com.example.kolmafiaandroid.ui.ExtBadge(ext)) },
                LinearLayout.LayoutParams(dp(30), dp(24)).apply { marginStart = dp(-1); marginEnd = dp(5) })
        } else if (iconRes != 0) {
            r.addView(android.widget.ImageView(requireContext()).apply {
                setImageResource(iconRes)
                setColorFilter(glyphColor)
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(6) })
        } else {
            r.addView(label(glyph, 20f, glyphColor).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val texts = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(name, 16f, KC.text, boldName))
        texts.addView(label(detail, 12f, KC.muted))
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        list.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return r
    }

    private fun size(b: Long): String = when {
        b >= 1_048_576 -> String.format(Locale.US, "%.1f MB", b / 1_048_576.0)
        b >= 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
        else -> "$b B"
    }

    private fun render() {
        val d = dir ?: scriptsDir
        val atRoot = d == scriptsDir
        backCallback.isEnabled = !atRoot || showProjects
        val rel = d.relativeTo(scriptsDir).path.replace(File.separatorChar, '/')
        crumb.text = if (showProjects) "/Projects" else if (atRoot) "/" else "/$rel"
        // embedded under the Console | Scripts tabs there is nothing to go back to at the top level, and the path is the title
        titleView.text = if (embedded) crumb.text else if (showProjects) "Projects" else if (atRoot) "Scripts" else d.name
        backBtn.visibility = if (embedded && atRoot && !showProjects) View.GONE else View.VISIBLE
        if (embedded && backBtn.visibility == View.GONE) titleView.setPadding(dp(16), 0, 0, 0) else if (embedded) titleView.setPadding(dp(8), 0, 0, 0)
        list.removeAllViews()
        val y = scroll.scrollY

        if (showProjects) {
            val projects = scanProjects()
            section("INSTALLED FROM GIT AND SVN", if (projects.size > 1) "Update all" else null) { updateAll(projects) }
            if (installing) row("…", KC.muted, "Installing…", "This can take a minute", false, {})
            if (projects.isEmpty() && !installing) list.addView(label("Nothing installed yet. Use ＋ then Add a project.", 14f, KC.muted).apply { setPadding(dp(12), dp(16), dp(12), dp(16)) })
            for (p in projects) projectRow(p)
            return
        }
        if (atRoot) {
            val projects = scanProjects()
            if (projects.isNotEmpty() || installing) {
                val working = projects.count { it.name in busy } + (if (installing) 1 else 0)
                row("◆", 0xFF4CAF50.toInt(), "Projects",
                    (if (working > 0) "$working working · " else "") + "${projects.size} installed from Git and SVN", true,
                    { showProjects = true; render(); scroll.scrollTo(0, 0) })
            }
        }

        val hidden = Regex("^\\.git$|^\\.svn$|^\\.kolmob-.*|.*\\.updating$")
        val cmp: Comparator<File> = if (sortRecent) compareByDescending { it.lastModified() } else compareBy { it.name.lowercase(Locale.ROOT) }
        val kids = (d.listFiles() ?: emptyArray()).filter { !hidden.matches(it.name) }
        val folders = kids.filter { it.isDirectory }.sortedWith(cmp)
        val files = kids.filter { it.isFile }.sortedWith(cmp)
        if (atRoot && (installing || scanProjects().isNotEmpty())) section("FILES", if (sortRecent) "Newest first" else "A–Z") { sortRecent = !sortRecent; render() }
        if (kids.isEmpty()) list.addView(label("Empty folder", 14f, KC.muted).apply { setPadding(dp(12), dp(16), dp(12), dp(16)) })
        val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
        for (f in folders) {
            val n = f.listFiles()?.count { !hidden.matches(it.name) } ?: 0
            row("▸", 0xFF4CAF50.toInt(), f.name, if (n == 1) "1 item" else "$n items", true, { dir = f; render(); scroll.scrollTo(0, 0) }, { itemMenu(f) })
        }
        for (f in files) {
            row("f:" + f.extension, KC.muted, f.name, size(f.length()) + " · " + fmt.format(Date(f.lastModified())), false, { itemMenu(f) }, { itemMenu(f) })
        }
        if (atRoot) {
            val relay = (relayDir.listFiles() ?: emptyArray()).filter {
                it.name.startsWith("relay_") && it.name.lowercase(Locale.ROOT).endsWith(".ash") && !com.example.kolmafiaandroid.ui.GuideBundle.isBundled(it.name)
            }.sortedBy { it.name }
            if (relay.isNotEmpty()) {
                section("RELAY SCRIPTS")
                for (f in relay) row("◦", KC.muted, f.name, "Opens in the Game tab", false, {
                    (activity as? com.example.kolmafiaandroid.MainActivity)?.openGamePage(f.name)
                })
            }
        }
        scroll.post { scroll.scrollTo(0, y) }
    }

    // ---------------------------------------------------------------- files and folders

    private fun runnable(f: File) = f.isFile && f.extension.lowercase(Locale.ROOT) in setOf("ash", "js", "txt")
    private fun editable(f: File) = f.isFile && f.length() <= 1_000_000L &&
        (f.extension.lowercase(Locale.ROOT) in setOf("ash", "js", "txt", "json", "md", "ccs", "xml", "css", "html", "htm", "csv", "properties", "yml", "yaml", "ts", "cfg", "ini", "tsv", "mood") || f.extension.isEmpty())

    private fun itemMenu(f: File) {
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        if (runnable(f)) { labels.add("Run"); acts.add { runScript(f) } }
        if (editable(f)) { labels.add("Edit"); acts.add { ScriptEditorFragment.newInstance(f).show(parentFragmentManager, "script_editor") } }
        if (f.isDirectory) { labels.add("Open"); acts.add { dir = f; render() } }
        labels.add("Rename"); acts.add { renameDialog(f) }
        labels.add("Delete"); acts.add { deleteDialog(f) }
        ScrollSafeDialogBuilder(requireContext()).setTitle(f.name)
            .setItems(labels.toTypedArray()) { _, which -> acts[which]() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun runScript(f: File) {
        val rel = ScriptFiles.relName(scriptsDir, f)
        com.example.kolmafiaandroid.tools.ScriptRunSheet.run(requireActivity(), rel, "call $rel")
    }

    private fun nameDialog(title: String, hint: String, initial: String, ok: String, onName: (String) -> Unit) {
        val input = EditText(requireContext()).apply {
            this.hint = hint; setText(initial); inputType = InputType.TYPE_CLASS_TEXT; setSingleLine()
            setTextColor(KC.text); setHintTextColor(KC.hint)
            setSelection(0, initial.substringBeforeLast('.', initial).length)
        }
        val box = FrameLayout(requireContext()).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        ScrollSafeDialogBuilder(requireContext()).setTitle(title).setView(box)
            .setPositiveButton(ok) { _, _ ->
                val n = input.text.toString().trim()
                if (n.isEmpty() || n.contains('/') || n.contains('\\') || n == "." || n == "..") {
                    Toast.makeText(requireContext(), "That name won't work", Toast.LENGTH_SHORT).show()
                } else onName(n)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun newScriptDialog() = nameDialog("New script", "name.ash", "", "Create") { n ->
        val name = if (n.contains('.')) n else "$n.ash"
        val f = File(dir ?: scriptsDir, name)
        if (f.exists()) { Toast.makeText(requireContext(), "That name is already used", Toast.LENGTH_SHORT).show(); return@nameDialog }
        try {
            FileOutputStream(f).use { it.write("void main() {\n  print(\"Hello from Kolmob\");\n}\n".toByteArray()) }
            render()
            ScriptEditorFragment.newInstance(f).show(parentFragmentManager, "script_editor")
        } catch (e: Exception) { Toast.makeText(requireContext(), "Could not create it: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun newFolderDialog() = nameDialog("New folder", "folder name", "", "Create") { n ->
        val f = File(dir ?: scriptsDir, n)
        if (f.exists()) Toast.makeText(requireContext(), "That name is already used", Toast.LENGTH_SHORT).show()
        else if (f.mkdirs()) render() else Toast.makeText(requireContext(), "Could not create the folder", Toast.LENGTH_SHORT).show()
    }

    private fun renameDialog(f: File) = nameDialog("Rename", "new name", f.name, "Rename") { n ->
        val target = File(f.parentFile, n)
        if (target.exists()) Toast.makeText(requireContext(), "That name is already used", Toast.LENGTH_SHORT).show()
        else if (f.renameTo(target)) render() else Toast.makeText(requireContext(), "Could not rename it", Toast.LENGTH_SHORT).show()
    }

    private fun deleteDialog(f: File) {
        val n = if (f.isDirectory) (f.walkTopDown().count { it.isFile }) else 0
        ScrollSafeDialogBuilder(requireContext()).setTitle("Delete ${f.name}?")
            .setMessage(if (f.isDirectory) "This folder and the $n file${if (n == 1) "" else "s"} inside it will be removed. This cannot be undone." else "This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                if (!ok) Toast.makeText(requireContext(), "Could not delete it", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------------------------------------------------------------- menus, import

    private fun showAddMenu() {
        val items = arrayOf("New script", "New folder", "Import a script from a link", "Add a project from GitHub or SVN")
        ScrollSafeDialogBuilder(requireContext()).setTitle("Add")
            .setItems(items) { _, which ->
                when (which) { 0 -> newScriptDialog(); 1 -> newFolderDialog(); 2 -> importDialog(); else -> addProjectDialog() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showMoreMenu() {
        val items = arrayOf(if (sortRecent) "Sort A–Z" else "Sort newest first", "Update all projects")
        ScrollSafeDialogBuilder(requireContext()).setTitle("Scripts")
            .setItems(items) { _, which ->
                if (which == 0) { sortRecent = !sortRecent; render() } else updateAll(scanProjects())
            }.setNegativeButton("Cancel", null).show()
    }

    private fun importDialog() {
        val input = EditText(requireContext()).apply {
            hint = "https://…/script.ash"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; setSingleLine()
            setTextColor(KC.text); setHintTextColor(KC.hint)
        }
        val box = FrameLayout(requireContext()).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        ScrollSafeDialogBuilder(requireContext()).setTitle("Import a script").setView(box)
            .setPositiveButton("Import") { _, _ ->
                val u = input.text.toString().trim()
                if (!u.startsWith("https://")) Toast.makeText(requireContext(), "Use a link that starts with https://", Toast.LENGTH_SHORT).show()
                else downloadScript(u)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun downloadScript(urlString: String) {
        val into = dir ?: scriptsDir
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val c = URL(urlString).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 30000; c.instanceFollowRedirects = true
                if (c.responseCode != HttpURLConnection.HTTP_OK) throw Exception("HTTP ${c.responseCode}")
                val fileName = urlString.substringAfterLast("/").substringBefore('?').takeIf {
                    it.endsWith(".ash") || it.endsWith(".txt") || it.endsWith(".js")
                } ?: throw Exception("the link must end with .ash, .js or .txt")
                c.inputStream.use { ins -> FileOutputStream(File(into, fileName)).use { ins.copyTo(it) } }
                withContext(Dispatchers.Main) { if (isAdded) { render(); Toast.makeText(requireContext(), "Imported $fileName", Toast.LENGTH_SHORT).show() } }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { if (isAdded) Toast.makeText(requireContext(), "Import failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    // ---------------------------------------------------------------- projects

    private fun scanProjects(): List<Project> {
        val out = ArrayList<Project>()
        val base = File(requireContext().filesDir, "kolmafia")
        File(base, "git").listFiles()?.forEach { d ->
            if (d.isDirectory && !d.name.endsWith(".updating")) {
                var branch = "unknown"; var commit = "unknown"
                try {
                    val j = JSONObject(File(d, ".kolmob-git.json").readText())
                    branch = j.optString("branch", "unknown"); commit = j.optString("commit", "unknown").take(7)
                } catch (_: Exception) {}
                out.add(Project(d.name, true, branch, commit, 0))
            }
        }
        File(base, "svn").listFiles()?.forEach { d ->
            if (d.isDirectory && !d.name.endsWith(".updating")) {
                var rev = 0
                try { rev = JSONObject(File(d, ".kolmob-svn.json").readText()).optInt("revision", 0) } catch (_: Exception) {}
                out.add(Project(d.name, false, "", "", rev))
            }
        }
        out.sortBy { it.name.lowercase(Locale.ROOT) }
        val scopes = try { File(base, "kolmob/projects.json").let { if (it.exists()) JSONObject(it.readText()) else JSONObject() } } catch (_: Exception) { JSONObject() }
        return out.map { p ->
            val e = scopes.optJSONObject((if (p.isGit) "git/" else "svn/") + p.name)
            if (e != null) p.copy(scope = e.optString("scope", "global"), owner = e.optString("owner", "")) else p
        }
    }

    private fun projectRow(p: Project) {
        val kind = if (p.isGit) "Git · ${p.branch} · ${p.commit}" else "SVN · revision ${p.revision}"
        val who = if (p.scope == "user") "only ${p.owner}" else "everyone"
        val state = if (p.name in busy) "Working…" else "$kind · $who"
        row("◆", if (p.isGit) 0xFF4CAF50.toInt() else 0xFFFFB300.toInt(), p.name, state, true, { if (p.name !in busy) projectMenu(p) }, { if (p.name !in busy) projectMenu(p) })
    }

    private fun projectMenu(p: Project) {
        val labels = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        labels.add("Update"); acts.add { runProject(p, if (p.isGit) "git update ${p.name}" else "svn update ${p.name}", "Updated ${p.name}") }
        if (p.isGit) { labels.add("Change branch"); acts.add { changeBranch(p) } }
        labels.add("Who gets it"); acts.add { changeScope(p) }
        labels.add("View source files"); acts.add {
            com.example.kolmafiaandroid.ui.FileBrowser.show(requireActivity(), File(requireContext().filesDir, "kolmafia/" + (if (p.isGit) "git/" else "svn/") + p.name), p.name, scriptsDir) { render() }
        }
        labels.add("Remove project"); acts.add {
            ScrollSafeDialogBuilder(requireContext()).setTitle("Remove ${p.name}?")
                .setMessage("Its scripts will be removed from your scripts folder too.")
                .setPositiveButton("Remove") { _, _ -> runProject(p, if (p.isGit) "git delete ${p.name}" else "svn delete ${p.name}", "Removed ${p.name}") }
                .setNegativeButton("Cancel", null).show()
        }
        ScrollSafeDialogBuilder(requireContext()).setTitle(p.name)
            .setItems(labels.toTypedArray()) { _, which -> acts[which]() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun runProject(p: Project, command: String, done: String) {
        busy.add(p.name); render()
        KolEngineHost.execute(command) {
            activity?.runOnUiThread {
                busy.remove(p.name)
                if (isAdded) { render(); Toast.makeText(requireContext(), done, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun updateAll(projects: List<Project>) {
        if (projects.isEmpty()) { Toast.makeText(requireContext(), "No projects installed", Toast.LENGTH_SHORT).show(); return }
        projects.forEach { busy.add(it.name) }
        render()
        fun next(i: Int) {
            if (i >= projects.size) {
                activity?.runOnUiThread { busy.clear(); if (isAdded) { render(); Toast.makeText(requireContext(), "All projects updated", Toast.LENGTH_SHORT).show() } }
                return
            }
            val p = projects[i]
            KolEngineHost.execute(if (p.isGit) "git update ${p.name}" else "svn update ${p.name}") { next(i + 1) }
        }
        next(0)
    }

    private fun changeScope(p: Project) {
        val who = KolEngineHost.currentUser.ifEmpty { "this account" }
        ScrollSafeDialogBuilder(requireContext()).setTitle("Who gets ${p.name}?")
            .setSingleChoiceItems(arrayOf("Everyone (all accounts)", "Only $who"), if (p.scope == "user") 1 else 0) { d, which ->
                d.dismiss()
                KolEngineHost.setProjectScope((if (p.isGit) "git/" else "svn/") + p.name, if (which == 1) "user" else "global") {
                    activity?.runOnUiThread { if (isAdded) render() }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    /** Branches of a GitHub repository (default branch first), or null when they can't be fetched. */
    private fun fetchBranches(repoUrl: String, done: (List<String>?) -> Unit) {
        val m = Regex("github\\.com/([^/]+)/([^/]+)").find(repoUrl)
        if (m == null) { done(null); return }
        val api = "https://api.github.com/repos/${m.groupValues[1]}/${m.groupValues[2]}"
        Thread {
            val result: List<String>? = try {
                fun get(u: String): String {
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.connectTimeout = 10000; c.readTimeout = 10000
                    c.setRequestProperty("Accept", "application/vnd.github+json")
                    return c.inputStream.bufferedReader().use { it.readText() }
                }
                val def = JSONObject(get(api)).optString("default_branch", "")
                val arr = JSONArray(get("$api/branches?per_page=100"))
                val names = (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }.toMutableList()
                if (def.isNotEmpty()) { names.remove(def); names.add(0, def) }
                names
            } catch (e: Exception) { null }
            activity?.runOnUiThread { done(result) }
        }.start()
    }

    /** Drop-down of branches with [selected] (or the default) preselected; falls back to typing when the list can't be fetched. */
    private fun pickBranch(title: String, repoUrl: String, selected: String?, onPick: (String) -> Unit) {
        fetchBranches(repoUrl) { names ->
            if (!isAdded) return@fetchBranches
            if (names.isNullOrEmpty()) {
                val input = EditText(requireContext()).apply { setText(selected ?: ""); hint = "branch"; setSingleLine(); setTextColor(KC.text); setHintTextColor(KC.hint) }
                val box = FrameLayout(requireContext()).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
                ScrollSafeDialogBuilder(requireContext()).setTitle(title)
                    .setMessage("Couldn't load the branch list; type one.").setView(box)
                    .setPositiveButton("OK") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onPick) }
                    .setNegativeButton("Cancel", null).show()
                return@fetchBranches
            }
            val items = if (selected != null && selected !in names) listOf(selected) + names else names
            var idx = items.indexOf(selected ?: names[0]).coerceAtLeast(0)
            val labels = items.mapIndexed { i, n -> if (selected == null && i == 0) "$n (default)" else n }.toTypedArray()
            ScrollSafeDialogBuilder(requireContext()).setTitle(title)
                .setSingleChoiceItems(labels, idx) { _, which -> idx = which }
                .setPositiveButton("OK") { _, _ -> onPick(items[idx]) }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun changeBranch(p: Project) {
        val meta = File(requireContext().filesDir, "kolmafia/git/${p.name}/.kolmob-git.json")
        val url = try { JSONObject(meta.readText()).optString("url", "") } catch (_: Exception) { "" }
        if (url.isEmpty()) { Toast.makeText(requireContext(), "Can't find this project's repository link", Toast.LENGTH_LONG).show(); return }
        pickBranch("Branch for ${p.name}", url, p.branch) { branch ->
            if (branch == p.branch) return@pickBranch
            busy.add(p.name); render()
            val wasUser = p.scope == "user"
            KolEngineHost.execute("git delete ${p.name}") {
                KolEngineHost.execute("git checkout $url $branch") {
                    val finish = {
                        activity?.runOnUiThread {
                            busy.remove(p.name)
                            if (isAdded) {
                                render()
                                val ok = scanProjects().any { it.isGit && it.branch == branch && it.name.endsWith("-$branch") }
                                Toast.makeText(requireContext(), if (ok) "Switched to $branch" else "Switch failed - see the Console log", Toast.LENGTH_LONG).show()
                            }
                        }
                        Unit
                    }
                    if (wasUser) {
                        val newId = "git/" + p.name.removeSuffix("-" + p.branch) + "-" + branch
                        KolEngineHost.setProjectScope(newId, "user") { finish() }
                    } else finish()
                }
            }
        }
    }

    private fun addProjectDialog() {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val input = EditText(ctx).apply {
            hint = "https://github.com/user/repo"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; setSingleLine()
            setTextColor(KC.text); setHintTextColor(KC.hint)
        }
        box.addView(input)
        val who = KolEngineHost.currentUser.ifEmpty { "this account" }
        val scopeBox = CheckBox(ctx).apply { text = "Only for $who (other accounts will not get these scripts)"; setTextColor(KC.soft) }
        box.addView(scopeBox)
        box.addView(label("Popular", 12f, 0xFF4CAF50.toInt(), true).apply { setPadding(0, dp(12), 0, dp(2)) })
        fun popular(name: String, url: String, desc: String) {
            box.addView(label(name, 15f, KC.text, true).apply { setPadding(0, dp(8), 0, 0); setOnClickListener { input.setText(url) } })
            box.addView(label(desc, 12f, KC.muted).apply { setOnClickListener { input.setText(url) } })
        }
        popular("autoscend", "https://github.com/loathers/autoscend", "Automates ascension runs")
        popular("zlib", "https://svn.code.sf.net/p/zlib/code", "A shared library many scripts need")
        ScrollSafeDialogBuilder(ctx).setTitle("Add a project").setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Install") { _, _ ->
                val url = input.text.toString().trim()
                val isGit = net.sourceforge.kolmafia.KolEngine.gitCheckoutArgs(url) != null
                val isSvn = url.contains("svn.")
                if (url.isEmpty() || (!isGit && !isSvn)) {
                    Toast.makeText(ctx, "That is not a Git or SVN link", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val scope = if (scopeBox.isChecked) "user" else "global"
                val args = net.sourceforge.kolmafia.KolEngine.gitCheckoutArgs(url)
                if (isGit && args != null && !args.contains(" ")) {
                    pickBranch("Which branch?", args, null) { branch -> install("$args $branch", scope) }
                } else install(url, scope)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun install(url: String, scope: String) {
        installing = true; showProjects = true; render()
        KolEngineHost.installProject(url, scope) {
            activity?.runOnUiThread {
                installing = false
                if (isAdded) { render(); Toast.makeText(requireContext(), "Installed", Toast.LENGTH_SHORT).show() }
            }
        }
    }
}
