package com.example.kolmafiaandroid.tools

import android.app.AlertDialog
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.ui.PickRow
import com.example.kolmafiaandroid.ui.pickList

/**
 * Sweet Synthesis: choose the effect you want, preview what KoLmafia would use, then do it.
 * Runs KoLmafia's own `synthesize` command, which picks the candies (and may buy them).
 */
class SynthesizeFragment : Fragment() {
    private var shownOnce = false
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) shownOnce = true
    }

    override fun onStart() {
        super.onStart()
        // the popup is shown once per tap: coming back to the app must not bring it up again
        if (shownOnce) return
        shownOnce = true
        val effects = listOf(
            "Hot" to "Hot Res +9", "Cold" to "Cold Res +9", "Pungent" to "Stench Res +9", "Scary" to "Spooky Res +9",
            "Greasy" to "Sleaze Res +9", "Strong" to "Mus +300%", "Smart" to "Mys +300%", "Cool" to "Mox +300%",
            "Hardy" to "Max HP +300%", "Energy" to "Max MP +300%", "Greed" to "Meat +300%", "Collection" to "Item +150%",
            "Movement" to "Mus Exp +50%", "Learning" to "Mys Exp +50%", "Style" to "Mox Exp +50%",
        )
        pickList("Synthesize", effects.map { PickRow("Synthesis: ${it.first}", it.second, tag = it.first) }) { r ->
            val name = "Synthesis: ${r.tag}"
            com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(requireContext())
                .setTitle(name)
                .setMessage("${r.subtitle}\n\nKoLmafia picks the candies for you and may buy them. Preview shows what it would use in the Console tab.")
                .setPositiveButton("Synthesize") { _, _ ->
                    KolEngineHost.execute("synthesize $name") {
                        activity?.runOnUiThread { Toast.makeText(requireContext(), "Done. See the Console tab for the result.", Toast.LENGTH_SHORT).show() }
                    }
                }
                .setNeutralButton("Preview") { _, _ ->
                    KolEngineHost.execute("synthesize? $name")
                    Toast.makeText(requireContext(), "Preview sent. See the Console tab.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    companion object {
        fun show(fm: FragmentManager) {
            fm.beginTransaction().add(SynthesizeFragment(), "synthesize").commitAllowingStateLoss()
        }
    }
}
