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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.askQuantity
import com.example.kolmafiaandroid.ui.card
import com.example.kolmafiaandroid.ui.clickable
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import com.example.kolmafiaandroid.ui.tile
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Meat manager: shows meat in hand, closet, and storage with move buttons.
 */
class MeatFragment : DialogFragment() {

    private lateinit var handValueView: TextView
    private lateinit var closetValueView: TextView
    private lateinit var storageValueView: TextView
    private var handMeat = 0L
    private var closetMeat = 0L
    private var storageMeat = 0L

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
        view.addView(label(ctx, "Meat Manager", 20f, 0xFF4CAF50.toInt(), true).apply { setPadding(0, 0, 0, dp(ctx, 16)) })

        // Three tiles
        val handTile = tile(ctx, "In Hand", "0", null) as LinearLayout
        handValueView = handTile.getChildAt(1) as TextView
        handTile.addView(makeButton(ctx, "Put in closet…") { putInCloset() })
        view.addView(handTile)

        val closetTile = tile(ctx, "In Closet", "0", null) as LinearLayout
        closetValueView = closetTile.getChildAt(1) as TextView
        closetTile.addView(makeButton(ctx, "Take from closet…") { takeFromCloset() })
        closetTile.addView(makeButton(ctx, "Put in closet…") { putInCloset() })
        view.addView(closetTile)

        val storageTile = tile(ctx, "In Storage", "0", null) as LinearLayout
        storageValueView = storageTile.getChildAt(1) as TextView
        storageTile.addView(makeButton(ctx, "Pull from storage…") { pullFromStorage() })
        view.addView(storageTile)

        refreshData()
        return view
    }

    private fun makeButton(ctx: android.content.Context, text: String, onClick: () -> Unit): Button {
        return Button(ctx).apply {
            this.text = text
            setTextColor(KC.onAccent)
            setBackgroundColor(0xFF4CAF50.toInt())
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
            setOnClickListener { onClick() }
        }
    }

    private fun refreshData() {
        // Get meat in hand from snapshot
        KolEngineHost.snapshot { json ->
            try {
                val o = JSONObject(json)
                handMeat = o.optLong("meat", 0L)
            } catch (e: Exception) {
                handMeat = 0L
            }
            activity?.runOnUiThread { updateTiles() }
        }

        // Get closet and storage meat from items()
        KolEngineHost.items { json ->
            try {
                val o = JSONObject(json)
                closetMeat = o.optLong("closetMeat", 0L)
                storageMeat = o.optLong("storageMeat", 0L)
            } catch (e: Exception) {
                closetMeat = 0L
                storageMeat = 0L
            }
            activity?.runOnUiThread { updateTiles() }
        }
    }

    private fun updateTiles() {
        val nf = java.text.NumberFormat.getIntegerInstance()
        handValueView.text = nf.format(handMeat)
        closetValueView.text = nf.format(closetMeat)
        storageValueView.text = nf.format(storageMeat)
    }

    private fun putInCloset() {
        askQuantity("Put in closet", "How much meat to put in closet?", Int.MAX_VALUE, "Put") { amount ->
            val amt = amount.toLong()
            confirmAndRun(
                "Put ${formatNumber(amt)} meat in closet?",
                "closet put $amt meat"
            )
        }
    }

    private fun takeFromCloset() {
        if (closetMeat == 0L) {
            Toast.makeText(requireContext(), "No meat in closet", Toast.LENGTH_SHORT).show()
            return
        }
        askQuantity("Take from closet", "How much meat to take from closet?", Int.MAX_VALUE, "Take") { amount ->
            val amt = amount.toLong()
            if (amt > closetMeat) {
                Toast.makeText(requireContext(), "Not enough meat in closet", Toast.LENGTH_SHORT).show()
                return@askQuantity
            }
            confirmAndRun(
                "Take ${formatNumber(amt)} meat from closet?",
                "closet take $amt meat"
            )
        }
    }

    private fun pullFromStorage() {
        if (storageMeat == 0L) {
            Toast.makeText(requireContext(), "No meat in storage", Toast.LENGTH_SHORT).show()
            return
        }
        askQuantity("Pull from storage", "How much meat to pull from storage?", Int.MAX_VALUE, "Pull") { amount ->
            val amt = amount.toLong()
            if (amt > storageMeat) {
                Toast.makeText(requireContext(), "Not enough meat in storage", Toast.LENGTH_SHORT).show()
                return@askQuantity
            }
            confirmAndRun(
                "Pull ${formatNumber(amt)} meat from storage?",
                "pull $amt meat"
            )
        }
    }

    private fun confirmAndRun(message: String, command: String) {
        com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(requireContext())
            .setTitle("Confirm")
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ ->
                runCommandWithErrorToast(command)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runCommandWithErrorToast(command: String) {
        // Run command and show toast if error appears in logs
        KolEngineHost.execute(command) { refreshData() }
        // For now, just show a toast after a delay if there's an error in the console
        lifecycleScope.launch {
            kotlinx.coroutines.delay(1500)
            // In a real implementation we'd watch KolEngineHost.lines for errors
            // For now we just refresh data
        }
    }

    private fun formatNumber(n: Long): String {
        return java.text.NumberFormat.getIntegerInstance().format(n)
    }

    private fun dp(context: android.content.Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        fun show(fragmentManager: androidx.fragment.app.FragmentManager) {
            MeatFragment().show(fragmentManager, "meat")
        }
    }
}