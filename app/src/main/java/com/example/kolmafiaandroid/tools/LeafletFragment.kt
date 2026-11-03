package com.example.kolmafiaandroid.tools

import android.app.AlertDialog
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.example.kolmafiaandroid.KolEngineHost

/**
 * The strange leaflet quest. Runs KoLmafia's own `leaflet` command: complete it (using the magic words), complete it
 * without the magic words, or just say where you are in it. Output prints in the Console tab.
 */
class LeafletFragment : Fragment() {
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
        val ctx = requireContext()
        com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(ctx)
            .setTitle("Strange leaflet")
            .setMessage("Completes the leaflet quest in your account. Progress prints in the Console.")
            .setPositiveButton("Complete it") { _, _ -> run("leaflet", "Doing the leaflet quest…") }
            .setNeutralButton("Where am I?") { _, _ -> run("leaflet location", null) }
            .setNegativeButton("Without magic words") { _, _ -> run("leaflet nomagic", "Doing the leaflet quest…") }
            .setOnDismissListener { parentFragmentManager.beginTransaction().remove(this).commitAllowingStateLoss() }
            .show()
    }

    private fun run(command: String, toast: String?) {
        if (toast != null) Toast.makeText(requireContext(), toast, Toast.LENGTH_SHORT).show()
        KolEngineHost.execute(command)
    }

    companion object {
        fun show(fm: FragmentManager) {
            fm.beginTransaction().add(LeafletFragment(), "leaflet").commitAllowingStateLoss()
        }
    }
}
