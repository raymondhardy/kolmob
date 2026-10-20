package com.example.kolmafiaandroid.tools

import com.example.kolmafiaandroid.ui.KC

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import org.json.JSONObject
import java.text.NumberFormat

/** Look up any player's public profile (the real `showplayer.php`, parsed by KoLmafia's ProfileRequest). */
class ProfileFragment : DialogFragment() {
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var input: EditText
    private val fmt = NumberFormat.getIntegerInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).also { it.requestWindowFeature(Window.FEATURE_NO_TITLE) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 8)) }
        root.addView(label(ctx, "Player lookup", 20f, 0xFF4CAF50.toInt(), true))
        input = EditText(ctx).apply {
            hint = "Player name or #id"
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, _, _ -> lookup(); true }
        }
        root.addView(input)
        status = label(ctx, "Type a name and press search.", 13f, KC.muted).apply { setPadding(0, dp(ctx, 4), 0, dp(ctx, 8)) }
        root.addView(status)
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(android.widget.ScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        arguments?.getString("name")?.let { input.setText(it); lookup() }
        return root
    }

    private fun lookup() {
        val name = input.text.toString().trim()
        if (name.isEmpty()) return
        status.text = "Looking up $name…"
        body.removeAllViews()
        KolEngineHost.profile(name) { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { JSONObject() }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (o.has("error") || o.optString("name").isEmpty()) { status.text = "Couldn't find that player."; return@runOnUiThread }
                status.text = o.optString("name") + " (#" + o.optString("id") + ")"
                val ctx = requireContext()
                fun row(k: String, v: String) {
                    if (v.isBlank() || v == "null") return
                    val r = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 4), dp(ctx, 8), dp(ctx, 4), dp(ctx, 8)) }
                    r.addView(label(ctx, k.uppercase(), 11f, 0xFF4CAF50.toInt(), true))
                    r.addView(label(ctx, v, 16f, KC.text))
                    body.addView(r)
                }
                val run = buildString {
                    append(if (o.optBoolean("hardcore")) "Hardcore" else "Softcore")
                    if (o.optBoolean("ronin")) append(" · in Ronin")
                }
                row("Class", o.optString("class") + " · level " + o.optInt("level"))
                row("Clan", o.optString("clan") + (o.optString("rank").takeIf { it.isNotBlank() && it != "null" }?.let { " ($it)" } ?: ""))
                row("This run", run)
                row("Ascensions", o.optInt("ascensions").toString())
                row("Turns played", fmt.format(o.optInt("turns")))
                row("PvP rank", o.optInt("pvp").takeIf { it > 0 }?.let { fmt.format(it) } ?: "")
                row("Created", o.optString("created"))
                row("Last login", o.optString("lastLogin"))
            }
        }
    }

    companion object {
        fun show(fm: FragmentManager, name: String? = null) =
            ProfileFragment().apply { if (name != null) arguments = Bundle().apply { putString("name", name) } }.show(fm, "profile")
    }
}
