package com.example.kolmafiaandroid.tools

import com.example.kolmafiaandroid.ui.KC

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.clickable
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Moods screen using KoLmafia's CLI mood commands.
 */
class MoodFragment : DialogFragment() {

    private lateinit var currentMoodText: TextView
    private lateinit var outputText: TextView

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
        view.addView(label(ctx, "Moods", 20f, 0xFF4CAF50.toInt(), true).apply { setPadding(0, 0, 0, dp(ctx, 16)) })

        // Current mood row
        val currentMoodRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        currentMoodRow.addView(label(ctx, "Current mood:", 14f, KC.muted, true))
        currentMoodText = TextView(ctx).apply {
            text = "Loading..."
            textSize = 14f
            setTextColor(KC.text)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        currentMoodRow.addView(currentMoodText)
        view.addView(currentMoodRow)

        // Change mood button
        val changeMoodBtn = Button(ctx).apply {
            text = "Change mood…"
            setTextColor(KC.onAccent)
            setBackgroundColor(0xFF1E88E5.toInt())
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            setOnClickListener { showMoodPicker() }
        }
        view.addView(changeMoodBtn)

        // Buttons
        view.addView(label(ctx, "Mood Actions", 14f, KC.muted, true).apply { setPadding(0, dp(ctx, 12), 0, dp(ctx, 4)) })

        val btnContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        addButton(ctx, btnContainer, "List moods", "mood listall", false)
        addButton(ctx, btnContainer, "Current triggers", "trigger list", false)
        addButton(ctx, btnContainer, "Fill from my effects", "mood autofill", true)
        addButton(ctx, btnContainer, "Run mood now", "mood execute", false)
        addButton(ctx, btnContainer, "Clear triggers", "mood clear", true)

        // Save as mood button (special)
        val saveBtn = Button(ctx).apply {
            text = "Save current effects as mood…"
            setTextColor(KC.onAccent)
            setBackgroundColor(0xFFE53935.toInt())
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
            setOnClickListener { showSaveMoodDialog() }
        }
        btnContainer.addView(saveBtn)

        view.addView(btnContainer)

        // Output area
        view.addView(label(ctx, "Output", 14f, KC.muted, true).apply { setPadding(0, dp(ctx, 12), 0, dp(ctx, 4)) })
        outputText = TextView(ctx).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextColor(KC.soft)
            setTextIsSelectable(true)
            movementMethod = android.text.method.ScrollingMovementMethod()
        }
        val scrollView = android.widget.ScrollView(ctx).apply {
            addView(outputText)
            setBackgroundResource(R.drawable.bg_console_output)
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
        }
        view.addView(scrollView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { weight = 1f })

        loadCurrentMood()
        return view
    }

    private fun addButton(ctx: android.content.Context, container: LinearLayout, label: String, cmd: String, needsConfirm: Boolean) {
        val btn = Button(ctx).apply {
            text = label
            setTextColor(KC.text)
            setBackgroundColor(if (needsConfirm) 0xFFE53935.toInt() else 0xFF4CAF50.toInt())
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
            setOnClickListener {
                runCommand(cmd, needsConfirm)
            }
        }
        container.addView(btn)
    }

    private fun loadCurrentMood() {
        KolEngineHost.adventureState { json ->
            try {
                val obj = JSONObject(json)
                val mood = obj.optString("mood", "none")
                activity?.runOnUiThread {
                    currentMoodText.text = mood
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    currentMoodText.text = "error"
                }
            }
        }
    }

    private fun showMoodPicker() {
        runCommand("mood listall")
    }

    private fun runCommand(cmd: String, needsConfirm: Boolean = false) {
        if (needsConfirm) {
            com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(requireContext())
                .setTitle("Confirm")
                .setMessage("Run: $cmd ?")
                .setPositiveButton("Run") { _, _ ->
                    executeCommand(cmd)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            executeCommand(cmd)
        }
    }

    private fun executeCommand(cmd: String) {
        outputText.text = "Running: $cmd\n"
        var lastOutputTime = System.currentTimeMillis()
        var isCollecting = true

        val job = lifecycleScope.launch {
            KolEngineHost.liveLines().collect { line ->
                if (!isCollecting) return@collect
                lastOutputTime = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    outputText.append("$line\n")
                }
            }
        }

        KolEngineHost.execute(cmd) {
            lifecycleScope.launch {
                while (isCollecting) {
                    kotlinx.coroutines.delay(200)
                    val now = System.currentTimeMillis()
                    if (now - lastOutputTime > 2000) {
                        isCollecting = false
                        job.cancel()
                        break
                    }
                }
            }
        }
    }

    private fun showSaveMoodDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Mood name"
            setTextColor(KC.text)
            setHintTextColor(KC.hint)
        }
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
            addView(input)
        }

        com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(requireContext())
            .setTitle("Save current effects as mood")
            .setMessage("Enter a name for this mood:")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), "Name required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Run set currentMood=<name> then save as mood
                outputText.text = "Setting current mood to: $name\n"
                executeCommand("set currentMood=$name") {
                    executeCommand("save as mood")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeCommand(cmd: String, onComplete: (() -> Unit)? = null) {
        outputText.text = "Running: $cmd\n"
        var lastOutputTime = System.currentTimeMillis()
        var isCollecting = true

        val job = lifecycleScope.launch {
            KolEngineHost.liveLines().collect { line ->
                if (!isCollecting) return@collect
                lastOutputTime = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    outputText.append("$line\n")
                }
            }
        }

        KolEngineHost.execute(cmd) {
            lifecycleScope.launch {
                while (isCollecting) {
                    kotlinx.coroutines.delay(200)
                    val now = System.currentTimeMillis()
                    if (now - lastOutputTime > 2000) {
                        isCollecting = false
                        job.cancel()
                        break
                    }
                }
                onComplete?.invoke()
            }
        }
    }

    private fun dp(context: android.content.Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        fun show(fragmentManager: androidx.fragment.app.FragmentManager) {
            MoodFragment().show(fragmentManager, "moods")
        }
    }
}