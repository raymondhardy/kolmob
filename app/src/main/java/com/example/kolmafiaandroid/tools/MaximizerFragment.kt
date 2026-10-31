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
import com.example.kolmafiaandroid.ui.chip
import com.example.kolmafiaandroid.ui.clickable
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Maximizer screen wrapping KoLmafia's `maximize` command.
 */
class MaximizerFragment : DialogFragment() {

    private lateinit var expressionEdit: EditText
    private lateinit var outputText: TextView
    private lateinit var previewBtn: Button
    private lateinit var equipBtn: Button
    private lateinit var undoBtn: Button

    private val presets = listOf(
        "Item drop" to "item drop",
        "Meat drop" to "meat drop",
        "Mainstat" to "mainstat",
        "Combat rate (+)" to "combat rate",
        "Non-combat (-)" to "-combat",
        "Initiative" to "init",
        "Max HP" to "hp",
        "ML (+)" to "ml",
        "ML (-)" to "-ml",
        "Muscle" to "muscle",
        "Mysticality" to "mysticality",
        "Moxie" to "moxie",
        "Stat gains (main stat exp)" to "mainstat exp",
        "Muscle exp" to "muscle exp",
        "Mysticality exp" to "mysticality exp",
        "Moxie exp" to "moxie exp",
        "Weapon damage" to "weapon damage",
        "Spell damage" to "spell damage",
        "Damage absorption" to "damage absorption",
        "Familiar weight" to "familiar weight",
        "HP regen" to "hp regen",
        "MP regen" to "mp regen",
        "Max MP" to "mp"
    )

    private var isCollecting = false
    private var lastOutputTime = 0L

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
        view.addView(label(ctx, "Maximizer", 20f, 0xFF4CAF50.toInt(), true).apply { setPadding(0, 0, 0, dp(ctx, 16)) })

        // Preset chips
        val chipContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for ((label, expr) in presets) {
            val chip = ctx.chip(label) { appendToExpression(expr) }
            chipContainer.addView(chip)
        }
        view.addView(chipContainer)

        // Expression field
        view.addView(label(ctx, "Expression", 14f, KC.muted, true).apply { setPadding(0, dp(ctx, 12), 0, dp(ctx, 4)) })
        expressionEdit = EditText(ctx).apply {
            hint = "e.g. item drop, -combat"
            setTextColor(KC.text)
            setHintTextColor(KC.hint)
            setText("item drop")
        }
        view.addView(expressionEdit)

        // Buttons row
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 3f
        }
        previewBtn = Button(ctx).apply {
            text = "Preview"
            setTextColor(KC.onAccent)
            setBackgroundColor(0xFF1E88E5.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { runPreview() }
        }
        equipBtn = Button(ctx).apply {
            text = "Equip"
            setTextColor(KC.onAccent)
            setBackgroundColor(0xFF4CAF50.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { runEquip() }
        }
        undoBtn = Button(ctx).apply {
            text = "Undo"
            setTextColor(KC.onAccent)
            setBackgroundColor(0xFFE53935.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { runUndo() }
        }
        btnRow.addView(previewBtn)
        btnRow.addView(equipBtn)
        btnRow.addView(undoBtn)
        view.addView(btnRow)

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

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isCollecting = false
    }

    private fun appendToExpression(expr: String) {
        val current = expressionEdit.text.toString().trim()
        val newExpr = if (current.isEmpty()) expr else "$current, $expr"
        expressionEdit.setText(newExpr)
        expressionEdit.setSelection(newExpr.length)
    }

    private fun runPreview() {
        val expr = expressionEdit.text.toString().trim()
        if (expr.isEmpty()) {
            Toast.makeText(requireContext(), "Enter an expression", Toast.LENGTH_SHORT).show()
            return
        }
        outputText.text = "Running preview...\n"
        val command = "maximize? $expr"
        runCommand(command)
    }

    private fun runEquip() {
        val expr = expressionEdit.text.toString().trim()
        if (expr.isEmpty()) {
            Toast.makeText(requireContext(), "Enter an expression", Toast.LENGTH_SHORT).show()
            return
        }
        com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(requireContext())
            .setTitle("Confirm Equip")
            .setMessage("This will change your equipment to maximize: $expr\n\nAre you sure?")
            .setPositiveButton("Equip") { _, _ ->
                runEquipConfirmed()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runEquipConfirmed() {
        val expr = expressionEdit.text.toString().trim()
        outputText.text = "Checkpointing...\n"
        runCommand("checkpoint") {
            // After checkpoint, run maximize
            val maximizeCmd = "maximize $expr"
            outputText.append("Running: $maximizeCmd\n")
            runCommand(maximizeCmd)
        }
    }

    private fun runUndo() {
        runCommand("outfit checkpoint")
    }

    private fun runCommand(command: String, onComplete: (() -> Unit)? = null) {
        isCollecting = true
        var lastOutputTime = System.currentTimeMillis()

        val job = lifecycleScope.launch {
            KolEngineHost.liveLines().collect { line ->
                if (!isCollecting) return@collect
                lastOutputTime = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    outputText.append("$line\n")
                }
            }
        }

        KolEngineHost.execute(command) {
            // Wait for output to settle
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
            MaximizerFragment().show(fragmentManager, "maximizer")
        }
    }
}