package com.example.kolmafiaandroid.fragments

import com.example.kolmafiaandroid.ui.KC

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Dialog fragment for managing ASH script files in the scripts directory.
 */
class ScriptsFragment : DialogFragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var adapter: ScriptAdapter
    private val scriptsDir by lazy { File(requireContext().filesDir, "kolmafia/scripts") }

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
        val view = inflater.inflate(R.layout.fragment_scripts, container, false)
        recyclerView = view.findViewById(R.id.scripts_recycler)
        emptyText = view.findViewById(R.id.empty_text)
        val fab = view.findViewById<FloatingActionButton>(R.id.fab_new_script)
        val toolbar = view.findViewById<MaterialToolbar>(R.id.scripts_toolbar)

        toolbar.setNavigationOnClickListener { closeOrBack() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_import -> {
                    showImportDialog()
                    true
                }
                R.id.action_install -> {
                    showInstallerDialog()
                    true
                }
                else -> false
            }
        }

        fab.setOnClickListener { showNewScriptDialog() }

        adapter = ScriptAdapter(requireContext()) { file, action ->
            when (action) {
                ScriptAction.RUN -> runScript(file)
                ScriptAction.EDIT -> openEditor(file)
                ScriptAction.DELETE -> confirmDelete(file)
            }
        }

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        refreshList()

        when (arguments?.getString("action")) {
            "new" -> view.post { showNewScriptDialog() }
            "install" -> view.post { showInstallerDialog() }
            "import" -> view.post { showImportDialog() }
        }

        return view
    }

    /** As a dialog: close it. Embedded in the scripting hub: go back to the console tab. */
    private fun closeOrBack() {
        if (showsDialog) dismiss() else (parentFragment as? com.example.kolmafiaandroid.ConsoleFragment)?.showConsoleTab()
    }

    private fun refreshList() {
        if (!scriptsDir.exists()) {
            scriptsDir.mkdirs()
        }
        val files = scriptsDir.listFiles { _, name ->
            name.lowercase(Locale.ROOT).endsWith(".ash") || name.lowercase(Locale.ROOT).endsWith(".txt")
        } ?: emptyArray()
        // relay scripts (installed to the relay folder, e.g. the Guide) are listed too; they open in the Game tab
        val relayFiles = File(requireContext().filesDir, "kolmafia/relay").listFiles { _, name ->
            name.startsWith("relay_") && name.lowercase(Locale.ROOT).endsWith(".ash")
        } ?: emptyArray()
        val all = (files.toList().sortedByDescending { it.lastModified() }) + relayFiles.sortedBy { it.name }
        adapter.submitList(all)
        emptyText.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun runScript(file: File) {
        if (file.parentFile?.name == "relay") {
            (activity as? com.example.kolmafiaandroid.MainActivity)?.openGamePage(file.name)
            return
        }
        // live output in a window right here instead of jumping to the Console tab
        com.example.kolmafiaandroid.tools.ScriptRunSheet.run(requireActivity(), file.name, "call ${file.name}")
    }

    private fun openEditor(file: File) {
        val editorFragment = ScriptEditorFragment.newInstance(file)
        editorFragment.show(parentFragmentManager, "script_editor")
    }

    private fun confirmDelete(file: File) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.scripts_delete)
            .setMessage(requireContext().getString(R.string.scripts_confirm_delete) + "\n\n${file.name}")
            .setPositiveButton(R.string.scripts_delete) { _, _ ->
                if (file.delete()) {
                    refreshList()
                    Toast.makeText(requireContext(), R.string.scripts_deleted, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), requireContext().getString(R.string.scripts_error, "Failed to delete"), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.scripts_cancel, null)
            .show()
    }

    private fun showNewScriptDialog() {
        val input = EditText(requireContext()).apply {
            hint = requireContext().getString(R.string.scripts_file_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(KC.text)
            setHintTextColor(KC.hint)
        }
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
            addView(input)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.scripts_new)
            .setView(layout)
            .setPositiveButton(R.string.scripts_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.scripts_invalid_name, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (name.contains("/") || name.contains("\\")) {
                    Toast.makeText(requireContext(), R.string.scripts_invalid_name, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val fileName = if (name.endsWith(".ash", ignoreCase = true) || name.endsWith(".txt", ignoreCase = true)) {
                    name
                } else {
                    name + ".ash"
                }
                val file = File(scriptsDir, fileName)
                if (file.exists()) {
                    Toast.makeText(requireContext(), R.string.scripts_file_exists, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                createScriptFile(file)
            }
            .setNegativeButton(R.string.scripts_cancel, null)
            .show()
    }

    private fun createScriptFile(file: File) {
        val template = "void main() {\n  print(\"Hello from Kolmob\");\n}\n"
        try {
            FileOutputStream(file).use { it.write(template.toByteArray()) }
            refreshList()
            openEditor(file)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), requireContext().getString(R.string.scripts_error, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showImportDialog() {
        val input = EditText(requireContext()).apply {
            hint = requireContext().getString(R.string.scripts_import_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(KC.text)
            setHintTextColor(KC.hint)
        }
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
            addView(input)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.scripts_import_url)
            .setView(layout)
            .setPositiveButton(R.string.scripts_import) { _, _ ->
                val urlString = input.text.toString().trim()
                if (!urlString.startsWith("https://")) {
                    Toast.makeText(requireContext(), R.string.scripts_invalid_url, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                downloadScript(urlString)
            }
            .setNegativeButton(R.string.scripts_cancel, null)
            .show()
    }

    private fun downloadScript(urlString: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("HTTP ${connection.responseCode}")
                }

                val inputStream = connection.inputStream
                val fileName = urlString.substringAfterLast("/").takeIf { it.endsWith(".ash") || it.endsWith(".txt") }
                    ?: throw Exception("URL must end with .ash or .txt")
                val file = File(scriptsDir, fileName)
                FileOutputStream(file).use { output ->
                    inputStream.copyTo(output)
                }
                inputStream.close()

                withContext(Dispatchers.Main) {
                    refreshList()
                    Toast.makeText(requireContext(), R.string.scripts_import_success, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), requireContext().getString(R.string.scripts_import_error) + ": ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private enum class ScriptAction { RUN, EDIT, DELETE }

    private class ScriptAdapter(
        private val context: Context,
        private val onAction: (File, ScriptAction) -> Unit
    ) : RecyclerView.Adapter<ScriptAdapter.ViewHolder>() {

        private var files: List<File> = emptyList()

        fun submitList(newFiles: List<File>) {
            files = newFiles
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.script_name)
            val info: TextView = view.findViewById(R.id.script_info)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.item_script, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = files[position]
            holder.name.text = file.name
            val size = file.length()
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(file.lastModified())
            holder.info.text = (if (file.parentFile?.name == "relay") "relay script, opens in the Game tab • " else "") + "${formatSize(size)} • $date"

            holder.itemView.setOnClickListener {
                showActionSheet(file)
            }
        }

        override fun getItemCount(): Int = files.size

        private fun showActionSheet(file: File) {
            val options = arrayOf<CharSequence>(
                context.getString(R.string.scripts_run),
                context.getString(R.string.scripts_edit),
                context.getString(R.string.scripts_delete)
            )
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle(file.name)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> onAction(file, ScriptAction.RUN)
                        1 -> onAction(file, ScriptAction.EDIT)
                        2 -> onAction(file, ScriptAction.DELETE)
                    }
                }
                .show()
        }

        private fun formatSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
                else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
            }
        }
    }

    // ===== Script Installer =====

    private var scopeUserCheck: android.widget.CheckBox? = null

    private var installerDialog: Dialog? = null

    override fun onDestroyView() {
        installerDialog?.dismiss() // leaving the manager (e.g. tapping the bottom bar) closes the installer too
        installerDialog = null
        super.onDestroyView()
    }

    private fun showInstallerDialog() {
        val dialog = Dialog(requireContext(), R.style.FullScreenDialogStyle)
        installerDialog = dialog
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_installer)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val toolbar = dialog.findViewById<MaterialToolbar>(R.id.installer_toolbar)
        toolbar.setNavigationOnClickListener { dialog.dismiss() }
        toolbar.title = getString(R.string.installer_title)

        val repoUrlInput = dialog.findViewById<TextInputEditText>(R.id.installer_repo_url)
        val repoUrlLayout = dialog.findViewById<TextInputLayout>(R.id.installer_repo_url_layout)
        val installBtn = dialog.findViewById<MaterialButton>(R.id.installer_install_btn)
        val progressBar = dialog.findViewById<ProgressBar>(R.id.installer_progress)
        val popularRecycler = dialog.findViewById<RecyclerView>(R.id.installer_popular_recycler)
        val installedRecycler = dialog.findViewById<RecyclerView>(R.id.installer_installed_recycler)
        val installedEmpty = dialog.findViewById<TextView>(R.id.installer_installed_empty)
        val updateAllBtn = dialog.findViewById<MaterialButton>(R.id.installer_update_all_btn)

        // Scope: everyone (global) or only the account that is logged in now
        val scopeBox = com.google.android.material.checkbox.MaterialCheckBox(requireContext()).apply {
            val who = KolEngineHost.currentUser.ifEmpty { "this account" }
            text = "Only for $who (other accounts will not get these scripts)"
            setTextColor(KC.soft)
        }
        (repoUrlLayout.parent as? ViewGroup)?.let { it.addView(scopeBox, it.indexOfChild(repoUrlLayout) + 1) }
        scopeUserCheck = scopeBox

        // Popular projects
        val popularProjects = listOf(
            PopularProject("autoscend", "https://github.com/Loathing-Associates-Scripting-Society/autoscend", getString(R.string.installer_popular_autoscend_desc), true),
            PopularProject("zlib", "https://svn.code.sf.net/p/zlib/code", getString(R.string.installer_popular_zlib_desc), false)
        )
        val popularAdapter = PopularProjectAdapter(requireContext(), popularProjects) { project ->
            installFromUrl(project.url, project.isGit, dialog, progressBar, installBtn, popularRecycler, installedRecycler, installedEmpty, repoUrlInput)
        }
        popularRecycler.layoutManager = LinearLayoutManager(requireContext())
        popularRecycler.adapter = popularAdapter

        // Installed projects
        val installedAdapter = InstalledProjectAdapter(requireContext()) { project, action ->
            when (action) {
                InstalledAction.UPDATE -> updateProject(project, dialog, progressBar, installBtn, popularRecycler, installedRecycler, installedEmpty)
                InstalledAction.DELETE -> confirmDeleteProject(project, dialog, progressBar, installBtn, popularRecycler, installedRecycler, installedEmpty)
                InstalledAction.SCOPE -> changeScope(project, installedRecycler, installedEmpty)
            }
        }
        installedRecycler.layoutManager = LinearLayoutManager(requireContext())
        installedRecycler.adapter = installedAdapter

        fun refreshInstalled() {
            val projects = scanInstalledProjects()
            installedAdapter.submitList(projects)
            installedEmpty.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
        }
        refreshInstalled()

        // Install button handler
        installBtn.setOnClickListener {
            val url = repoUrlInput.text.toString().trim()
            if (url.isEmpty()) {
                repoUrlLayout.error = getString(R.string.installer_error_empty_url)
                return@setOnClickListener
            }
            repoUrlLayout.error = null

            val isGit = url.contains("github.com")
            val isSvn = url.contains("svn.code.sf.net") || url.contains("svn.")
            if (!isGit && !isSvn) {
                repoUrlLayout.error = getString(R.string.installer_error_unsupported)
                return@setOnClickListener
            }

            installFromUrl(url, isGit, dialog, progressBar, installBtn, popularRecycler, installedRecycler, installedEmpty, repoUrlInput)
        }

        // Update all button handler
        updateAllBtn.setOnClickListener {
            updateAllProjects(dialog, progressBar, installBtn, popularRecycler, installedRecycler, installedEmpty, ::refreshInstalled, updateAllBtn)
        }

        dialog.show()
        (activity as? com.example.kolmafiaandroid.MainActivity)?.fitDialogAboveNav(dialog) // keep the bottom bar reachable
    }

    private fun installFromUrl(
        url: String,
        isGit: Boolean,
        dialog: Dialog,
        progressBar: ProgressBar,
        installBtn: MaterialButton,
        popularRecycler: RecyclerView,
        installedRecycler: RecyclerView,
        installedEmpty: TextView,
        repoUrlInput: TextInputEditText
    ) {
        val scope = if (scopeUserCheck?.isChecked == true) "user" else "global"
        setInstallingState(true, progressBar, installBtn, popularRecycler, installedRecycler)

        KolEngineHost.installProject(url, scope) {
            requireActivity().runOnUiThread {
                setInstallingState(false, progressBar, installBtn, popularRecycler, installedRecycler)
                val installedAdapter = installedRecycler.adapter as? InstalledProjectAdapter
                installedAdapter?.let { adapter ->
                    val projects = scanInstalledProjects()
                    adapter.submitList(projects)
                    installedEmpty.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
                }
                Toast.makeText(requireContext(), R.string.installer_success, Toast.LENGTH_SHORT).show()
                repoUrlInput.text?.clear()
            }
        }
    }

    /** Switch a project between "everyone" and "only the account that is logged in now". */
    private fun changeScope(project: InstalledProject, installedRecycler: RecyclerView, installedEmpty: TextView) {
        val who = KolEngineHost.currentUser.ifEmpty { "this account" }
        val options = arrayOf("Everyone (all accounts)", "Only $who")
        val checked = if (project.scope == "user") 1 else 0
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Who gets ${project.name}?")
            .setSingleChoiceItems(options, checked) { d, which ->
                d.dismiss()
                val id = (if (project.isGit) "git/" else "svn/") + project.name
                KolEngineHost.setProjectScope(id, if (which == 1) "user" else "global") {
                    activity?.runOnUiThread {
                        val adapter = installedRecycler.adapter as? InstalledProjectAdapter ?: return@runOnUiThread
                        val projects = scanInstalledProjects()
                        adapter.submitList(projects)
                        installedEmpty.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setInstallingState(
        installing: Boolean,
        progressBar: ProgressBar,
        installBtn: MaterialButton,
        popularRecycler: RecyclerView,
        installedRecycler: RecyclerView
    ) {
        progressBar.visibility = if (installing) View.VISIBLE else View.GONE
        installBtn.isEnabled = !installing
        installBtn.text = if (installing) getString(R.string.installer_installing) else getString(R.string.installer_install)
        popularRecycler.isEnabled = !installing
        installedRecycler.isEnabled = !installing
    }

    private fun updateProject(
        project: InstalledProject,
        dialog: Dialog,
        progressBar: ProgressBar,
        installBtn: MaterialButton,
        popularRecycler: RecyclerView,
        installedRecycler: RecyclerView,
        installedEmpty: TextView
    ) {
        val command = if (project.isGit) "git update ${project.name}" else "svn update ${project.name}"
        setUpdatingState(project.name, true, installedRecycler)
        
        KolEngineHost.execute(command) {
            requireActivity().runOnUiThread {
                setUpdatingState(project.name, false, installedRecycler)
                val installedAdapter = installedRecycler.adapter as? InstalledProjectAdapter
                installedAdapter?.let { adapter ->
                    val projects = scanInstalledProjects()
                    adapter.submitList(projects)
                    installedEmpty.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
                }
                Toast.makeText(requireContext(), getString(R.string.installer_success), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateAllProjects(
        dialog: Dialog,
        progressBar: ProgressBar,
        installBtn: MaterialButton,
        popularRecycler: RecyclerView,
        installedRecycler: RecyclerView,
        installedEmpty: TextView,
        refreshInstalled: () -> Unit,
        updateAllBtn: MaterialButton
    ) {
        val installedAdapter = installedRecycler.adapter as? InstalledProjectAdapter
        val projects = installedAdapter?.currentProjects ?: emptyList()
        if (projects.isEmpty()) {
            Toast.makeText(requireContext(), R.string.installer_installed_empty, Toast.LENGTH_SHORT).show()
            return
        }

        setUpdatingAllState(true, progressBar, installBtn, popularRecycler, installedRecycler, updateAllBtn)
        
        var completed = 0
        val total = projects.size
        
        fun processNext(index: Int) {
            if (index >= total) {
                requireActivity().runOnUiThread {
                    setUpdatingAllState(false, progressBar, installBtn, popularRecycler, installedRecycler, updateAllBtn)
                    refreshInstalled()
                    Toast.makeText(requireContext(), R.string.installer_update_all_done, Toast.LENGTH_SHORT).show()
                }
                return
            }
            
            val project = projects[index]
            val command = if (project.isGit) "git update ${project.name}" else "svn update ${project.name}"
            
            KolEngineHost.execute(command) {
                processNext(index + 1)
            }
        }
        
        processNext(0)
    }

    private fun setUpdatingState(projectName: String, updating: Boolean, installedRecycler: RecyclerView) {
        // The adapter handles showing updating state per item
        val adapter = installedRecycler.adapter as? InstalledProjectAdapter
        adapter?.setUpdating(projectName, updating)
    }

    private fun setUpdatingAllState(
        updating: Boolean,
        progressBar: ProgressBar,
        installBtn: MaterialButton,
        popularRecycler: RecyclerView,
        installedRecycler: RecyclerView,
        updateAllBtn: MaterialButton
    ) {
        progressBar.visibility = if (updating) View.VISIBLE else View.GONE
        installBtn.isEnabled = !updating
        popularRecycler.isEnabled = !updating
        installedRecycler.isEnabled = !updating
        updateAllBtn.isEnabled = !updating
        updateAllBtn.text = if (updating) getString(R.string.installer_updating_all) else getString(R.string.installer_update_all)
    }

    private fun confirmDeleteProject(
        project: InstalledProject,
        dialog: Dialog,
        progressBar: ProgressBar,
        installBtn: MaterialButton,
        popularRecycler: RecyclerView,
        installedRecycler: RecyclerView,
        installedEmpty: TextView
    ) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.installer_delete)
            .setMessage(getString(R.string.installer_confirm_delete, project.name))
            .setPositiveButton(R.string.installer_delete) { _, _ ->
                deleteProject(project, dialog, progressBar, installBtn, popularRecycler, installedRecycler, installedEmpty)
            }
            .setNegativeButton(R.string.scripts_cancel, null)
            .show()
    }

    private fun deleteProject(
        project: InstalledProject,
        dialog: Dialog,
        progressBar: ProgressBar,
        installBtn: MaterialButton,
        popularRecycler: RecyclerView,
        installedRecycler: RecyclerView,
        installedEmpty: TextView
    ) {
        val command = if (project.isGit) "git delete ${project.name}" else "svn delete ${project.name}"
        setDeletingState(project.name, true, installedRecycler)
        
        KolEngineHost.execute(command) {
            requireActivity().runOnUiThread {
                setDeletingState(project.name, false, installedRecycler)
                val installedAdapter = installedRecycler.adapter as? InstalledProjectAdapter
                installedAdapter?.let { adapter ->
                    val projects = scanInstalledProjects()
                    adapter.submitList(projects)
                    installedEmpty.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
                }
                Toast.makeText(requireContext(), R.string.installer_deleted, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setDeletingState(projectName: String, deleting: Boolean, installedRecycler: RecyclerView) {
        val adapter = installedRecycler.adapter as? InstalledProjectAdapter
        adapter?.setDeleting(projectName, deleting)
    }

    private fun scanInstalledProjects(): List<InstalledProject> {
        val projects = mutableListOf<InstalledProject>()
        val filesDir = requireContext().filesDir
        
        // Scan git projects
        val gitDir = File(filesDir, "kolmafia/git")
        if (gitDir.exists()) {
            gitDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && !dir.name.endsWith(".updating")) {
                    val metaFile = File(dir, ".kolmob-git.json")
                    var branch = "unknown"
                    var commit = "unknown"
                    if (metaFile.exists()) {
                        try {
                            val json = JSONObject(FileReader(metaFile).readText())
                            branch = json.optString("branch", "unknown")
                            commit = json.optString("commit", "unknown")
                            if (commit.length > 7) commit = commit.substring(0, 7)
                        } catch (e: Exception) {
                            // Ignore parse errors
                        }
                    }
                    projects.add(InstalledProject(dir.name, true, branch, commit, 0))
                }
            }
        }
        
        // Scan svn projects
        val svnDir = File(filesDir, "kolmafia/svn")
        if (svnDir.exists()) {
            svnDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && !dir.name.endsWith(".updating")) {
                    val metaFile = File(dir, ".kolmob-svn.json")
                    var revision = 0
                    if (metaFile.exists()) {
                        try {
                            val json = JSONObject(FileReader(metaFile).readText())
                            revision = json.optInt("revision", 0)
                        } catch (e: Exception) {
                            // Ignore parse errors
                        }
                    }
                    projects.add(InstalledProject(dir.name, false, "", "", revision))
                }
            }
        }
        
        projects.sortBy { it.name.lowercase(Locale.ROOT) }
        // per-account scopes saved by the engine (kolmob/projects.json): id -> {scope, owner}
        val scopes = try {
            val f = File(filesDir, "kolmafia/kolmob/projects.json")
            if (f.exists()) JSONObject(f.readText()) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
        return projects.map { p ->
            val e = scopes.optJSONObject((if (p.isGit) "git/" else "svn/") + p.name)
            if (e != null) p.copy(scope = e.optString("scope", "global"), owner = e.optString("owner", "")) else p
        }
    }

    private data class PopularProject(
        val name: String,
        val url: String,
        val description: String,
        val isGit: Boolean
    )

    private class PopularProjectAdapter(
        private val context: Context,
        private val projects: List<PopularProject>,
        private val onInstall: (PopularProject) -> Unit
    ) : RecyclerView.Adapter<PopularProjectAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.popular_name)
            val description: TextView = view.findViewById(R.id.popular_description)
            val source: TextView = view.findViewById(R.id.popular_source)
            val installBtn: MaterialButton = view.findViewById(R.id.popular_install_btn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_popular_project, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val project = projects[position]
            holder.name.text = project.name
            holder.description.text = project.description
            holder.source.text = if (project.isGit) context.getString(R.string.installer_source_git) else context.getString(R.string.installer_source_svn)
            holder.installBtn.setOnClickListener { onInstall(project) }
        }

        override fun getItemCount(): Int = projects.size
    }

    private data class InstalledProject(
        val name: String,
        val isGit: Boolean,
        val branch: String,
        val commit: String,
        val revision: Int,
        val scope: String = "global",
        val owner: String = ""
    )

    private enum class InstalledAction { UPDATE, DELETE, SCOPE }

    private class InstalledProjectAdapter(
        private val context: Context,
        private val onAction: (InstalledProject, InstalledAction) -> Unit
    ) : RecyclerView.Adapter<InstalledProjectAdapter.ViewHolder>() {

        private var projects: List<InstalledProject> = emptyList()
        private val updatingProjects = mutableSetOf<String>()
        private val deletingProjects = mutableSetOf<String>()

        fun submitList(newProjects: List<InstalledProject>) {
            projects = newProjects
            notifyDataSetChanged()
        }

        val currentProjects: List<InstalledProject>
            get() = projects

        fun setUpdating(projectName: String, updating: Boolean) {
            if (updating) updatingProjects.add(projectName) else updatingProjects.remove(projectName)
            notifyDataSetChanged()
        }

        fun setDeleting(projectName: String, deleting: Boolean) {
            if (deleting) deletingProjects.add(projectName) else deletingProjects.remove(projectName)
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.installed_name)
            val source: TextView = view.findViewById(R.id.installed_source)
            val details: TextView = view.findViewById(R.id.installed_details)
            val updateBtn: MaterialButton = view.findViewById(R.id.installed_update_btn)
            val deleteBtn: MaterialButton = view.findViewById(R.id.installed_delete_btn)
            val progressBar: ProgressBar = view.findViewById(R.id.installed_progress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.item_installed_project, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val project = projects[position]
            holder.name.text = project.name
            val kind = if (project.isGit) context.getString(R.string.installer_source_git) else context.getString(R.string.installer_source_svn)
            holder.source.text = kind + " · " + (if (project.scope == "user") "only ${project.owner} ▾" else "everyone ▾")
            holder.source.setOnClickListener { onAction(project, InstalledAction.SCOPE) }
            holder.source.setTextColor(context.getColor(if (project.isGit) R.color.colorPrimary else R.color.colorSecondary))
            
            if (project.isGit) {
                holder.details.text = context.getString(R.string.installer_branch, project.branch) + " • " + 
                    context.getString(R.string.installer_commit, project.commit)
            } else {
                holder.details.text = context.getString(R.string.installer_revision, project.revision.toString())
            }

            val isUpdating = updatingProjects.contains(project.name)
            val isDeleting = deletingProjects.contains(project.name)
            val isBusy = isUpdating || isDeleting

            holder.progressBar.visibility = if (isBusy) View.VISIBLE else View.GONE
            holder.updateBtn.isEnabled = !isBusy
            holder.deleteBtn.isEnabled = !isBusy
            holder.updateBtn.text = if (isUpdating) context.getString(R.string.installer_updating) else context.getString(R.string.installer_update)
            holder.deleteBtn.text = if (isDeleting) context.getString(R.string.installer_deleting) else context.getString(R.string.installer_delete)

            holder.updateBtn.setOnClickListener { onAction(project, InstalledAction.UPDATE) }
            holder.deleteBtn.setOnClickListener { onAction(project, InstalledAction.DELETE) }
        }

        override fun getItemCount(): Int = projects.size
    }
}