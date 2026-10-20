package com.example.kolmafiaandroid.tools

import com.example.kolmafiaandroid.ui.KC

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.askQuantity
import com.example.kolmafiaandroid.ui.card
import com.example.kolmafiaandroid.ui.clickable
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import com.example.kolmafiaandroid.ui.pick
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * KMail composer: send items and/or meat to another player.
 */
class MailFragment : DialogFragment() {

    private lateinit var recipientEdit: EditText
    private lateinit var messageEdit: EditText
    private lateinit var meatEdit: EditText
    private lateinit var itemsContainer: LinearLayout
    private lateinit var sendButton: Button
    private lateinit var previewText: TextView

    private var selectedItems = mutableListOf<SelectedItem>()
    private var inventoryItems: List<ItemEntry> = emptyList()

    private data class ItemEntry(val name: String, val count: Int)
    private data class SelectedItem(val name: String, val count: Int)

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
        view.addView(label(ctx, "Send KMail", 20f, 0xFF4CAF50.toInt(), true).apply { setPadding(0, 0, 0, dp(ctx, 16)) })

        // Recipient
        view.addView(label(ctx, "Recipient", 14f, KC.muted, true))
        recipientEdit = EditText(ctx).apply {
            hint = "Player name"
            setTextColor(KC.text)
            setHintTextColor(KC.hint)
        }
        view.addView(recipientEdit)

        // Message
        view.addView(label(ctx, "Message (optional)", 14f, KC.muted, true).apply { setPadding(0, dp(ctx, 12), 0, 0) })
        messageEdit = EditText(ctx).apply {
            hint = "Message..."
            setTextColor(KC.text)
            setHintTextColor(KC.hint)
            setMinLines(4)
            gravity = android.view.Gravity.TOP
        }
        view.addView(messageEdit)

        // Meat
        view.addView(label(ctx, "Meat (optional)", 14f, KC.muted, true).apply { setPadding(0, dp(ctx, 12), 0, 0) })
        meatEdit = EditText(ctx).apply {
            hint = "Amount (e.g. 1000)"
            setTextColor(KC.text)
            setHintTextColor(KC.hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        view.addView(meatEdit)

        // Items section
        view.addView(label(ctx, "Items", 14f, KC.muted, true).apply { setPadding(0, dp(ctx, 12), 0, 0) })
        
        val addItemBtn = Button(ctx).apply {
            text = "Add item…"
            setTextColor(KC.onAccent)
            setBackgroundColor(0xFF4CAF50.toInt())
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            setOnClickListener { showItemPicker() }
        }
        view.addView(addItemBtn)

        // Container for selected items (chips)
        itemsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        view.addView(itemsContainer)

        // Preview of the command that will be sent
        previewText = TextView(ctx).apply {
            textSize = 12f
            setTextColor(KC.hint)
            setPadding(0, dp(ctx, 8), 0, 0)
        }
        view.addView(previewText)

        // Send button
        sendButton = Button(ctx).apply {
            text = "Send KMail"
            setTextColor(KC.onAccent)
            setBackgroundColor(0xFF4CAF50.toInt())
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            setOnClickListener { sendKMail() }
        }
        view.addView(sendButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(ctx, 16) })

        loadInventory()
        return view
    }

    private fun loadInventory() {
        KolEngineHost.snapshot { json ->
            try {
                val o = JSONObject(json)
                val arr = o.optJSONArray("inventory") ?: JSONArray()
                inventoryItems = (0 until arr.length()).map { arr.getJSONObject(it) }
                    .map { ItemEntry(it.getString("name"), it.getInt("count")) }
                    .sortedBy { it.name.lowercase() }
            } catch (e: Exception) {
                inventoryItems = emptyList()
            }
        }
    }

    private fun showItemPicker() {
        if (inventoryItems.isEmpty()) {
            Toast.makeText(requireContext(), "No items in inventory", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = inventoryItems.map { "${it.name} ×${it.count}" }
        pick("Add item", labels) { i ->
            val item = inventoryItems[i]
            askQuantity("Add ${item.name}", "How many?", item.count, "Add") { qty ->
                addItem(item.name, qty)
            }
        }
    }

    private fun addItem(name: String, count: Int) {
        // Check if already added
        val existingIndex = selectedItems.indexOfFirst { it.name == name }
        if (existingIndex >= 0) {
            selectedItems[existingIndex] = SelectedItem(name, selectedItems[existingIndex].count + count)
        } else {
            selectedItems.add(SelectedItem(name, count))
        }
        updateItemsDisplay()
        updatePreview()
    }

    private fun removeItem(index: Int) {
        selectedItems.removeAt(index)
        updateItemsDisplay()
        updatePreview()
    }

    private fun updateItemsDisplay() {
        val ctx = requireContext()
        itemsContainer.removeAllViews()
        for ((index, item) in selectedItems.withIndex()) {
val chip = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_tile)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 4) }
        }
            chip.addView(label(ctx, "${item.name} ×${item.count}", 14f, KC.soft))
            val removeBtn = TextView(ctx).apply {
                text = " ✕"
                textSize = 16f
                setTextColor(0xFFE53935.toInt())
                setPadding(dp(ctx, 8), 0, 0, 0)
            }
            clickable(chip) { removeItem(index) }
            chip.addView(removeBtn)
            itemsContainer.addView(chip)
        }
    }

    private fun updatePreview() {
        val meatText = meatEdit.text.toString().trim()
        val meatAmount = if (meatText.isEmpty()) 0L else meatText.toLongOrNull() ?: 0L
        
        val parts = mutableListOf<String>()
        if (meatAmount > 0) parts.add("$meatAmount meat")
        for (item in selectedItems) {
            parts.add("${item.count} ${item.name}")
        }
        
        val recipient = recipientEdit.text.toString().trim()
        if (recipient.isEmpty()) {
            previewText.text = "Enter recipient to preview command"
            return
        }
        
        val attachments = if (parts.isEmpty()) "nothing" else parts.joinToString(", ")
        previewText.text = "Will run: send $attachments to $recipient"
    }

    private fun sendKMail() {
        val recipient = recipientEdit.text.toString().trim()
        val message = messageEdit.text.toString().trim()
        val meatText = meatEdit.text.toString().trim()
        val meatAmount = if (meatText.isEmpty()) 0L else meatText.toLongOrNull() ?: 0L

        // Validate
        if (recipient.isEmpty()) {
            Toast.makeText(requireContext(), "Recipient is required", Toast.LENGTH_SHORT).show()
            return
        }
        if (message.isEmpty() && meatAmount == 0L && selectedItems.isEmpty()) {
            Toast.makeText(requireContext(), "Message, meat, or items required", Toast.LENGTH_SHORT).show()
            return
        }

        // Build command
        val parts = mutableListOf<String>()
        if (meatAmount > 0) parts.add("$meatAmount meat")
        for (item in selectedItems) {
            parts.add("${item.count} ${item.name}")
        }
        
        val attachments = parts.joinToString(", ")
        var command = "send $attachments to $recipient"
        if (message.isNotEmpty()) {
            command += " || $message"
        }

        // Show confirmation with full command preview
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirm Send")
            .setMessage("Send to $recipient?\n\n$command")
            .setPositiveButton("Send") { _, _ ->
                // We don't actually send - just show the command that would run
                Toast.makeText(requireContext(), "Command preview (not sent): $command", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatNumber(n: Long): String {
        return java.text.NumberFormat.getIntegerInstance().format(n)
    }

    private fun dp(context: android.content.Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        fun show(fragmentManager: androidx.fragment.app.FragmentManager) {
            MailFragment().show(fragmentManager, "mail")
        }
    }
}