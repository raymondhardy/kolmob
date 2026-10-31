package com.example.kolmafiaandroid.tools

import com.example.kolmafiaandroid.ui.KC

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import java.io.File

/**
 * Combat script (.ccs) editor: a text editor with one-tap snippets for the common lines
 * (attack, skill, item, sections, conditions). Files live in KoLmafia's ccs/ folder.
 */
class CcsEditorFragment : DialogFragment() {
    private lateinit var file: File
    private lateinit var editor: EditText
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
        val name = arguments?.getString("name") ?: "new"
        file = File(ccsDir(requireContext()), if (name.endsWith(".ccs")) name else "$name.ccs")
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).also { it.requestWindowFeature(Window.FEATURE_NO_TITLE) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 8)) }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(ctx, "Combat script · " + file.nameWithoutExtension, 18f, 0xFF4CAF50.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(label(ctx, "Save", 16f, 0xFF4CAF50.toInt(), true).apply {
            setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
            com.example.kolmafiaandroid.ui.clickable(this) { save() }
        })
        root.addView(head)

        editor = EditText(ctx).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            gravity = Gravity.TOP or Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(if (file.exists()) file.readText() else TEMPLATE)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { dirty = true }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        dirty = false
        // snippet row: tap to insert at the cursor
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "attack" to "attack\n",
            "skill…" to "skill ",
            "use…" to "use ",
            "[ monster ]" to "[ monster name ]\n",
            "if hp" to "if hpbelow 30\n  \nendif\n",
            "abort" to "abort\n",
            "runaway" to "runaway\n",
            "consult" to "consult ",
        ).forEach { (text, ins) ->
            chips.addView(ctx.chipView(text) { insert(ins) })
        }
        root.addView(HorizontalScrollView(ctx).apply { addView(chips); isHorizontalScrollBarEnabled = false }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, 8); bottomMargin = dp(ctx, 8) })
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(label(ctx, "Lines after “[ name ]” apply to that monster; “[ default ]” is the fallback. Tap Save, then pick the script in Adventure → Combat script.", 12f, KC.muted).apply { setPadding(0, dp(ctx, 6), 0, 0) })
        return root
    }


    private fun android.content.Context.chipView(text: String, onClick: () -> Unit): View =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF4CAF50.toInt())
            setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 20f * resources.displayMetrics.density; setStroke(dp(context, 1), 0x664CAF50) }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(context, 8) }
            com.example.kolmafiaandroid.ui.clickable(this) { onClick() }
        }

    private fun insert(text: String) {
        val start = editor.selectionStart.coerceAtLeast(0)
        editor.text.insert(start, text)
    }

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            file.writeText(editor.text.toString())
            dirty = false
            Toast.makeText(requireContext(), "Saved ${file.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Could not save: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        (parentFragment as? Runnable)?.run()
    }

    companion object {
        const val TEMPLATE = "[ default ]\n  attack\n"
        fun ccsDir(ctx: android.content.Context) = File(ctx.filesDir, "kolmafia/ccs")
        fun show(fm: FragmentManager, name: String) {
            CcsEditorFragment().apply { arguments = Bundle().apply { putString("name", name) } }.show(fm, "ccs")
        }
        /** Ask for a name, then open the editor on a new script. */
        fun create(ctx: android.content.Context, fm: FragmentManager) {
            val input = EditText(ctx).apply { hint = "script name"; setSingleLine() }
            com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(ctx).setTitle("New combat script").setView(input)
                .setPositiveButton("Create") { _, _ ->
                    val n = input.text.toString().trim().replace(Regex("[^A-Za-z0-9 _-]"), "")
                    if (n.isNotEmpty()) show(fm, n)
                }.setNegativeButton("Cancel", null).show()
        }
    }
}
