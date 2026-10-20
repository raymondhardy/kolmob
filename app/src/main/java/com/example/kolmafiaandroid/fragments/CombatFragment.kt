package com.example.kolmafiaandroid.fragments

import com.example.kolmafiaandroid.R

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Fragment displayed for the Combat tab.
 */
class CombatFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.fragment_combat, container, false)

        view.findViewById<TextView>(R.id.comb_level).text = getString(R.string.not_logged_in)
        view.findViewById<TextView>(R.id.comb_stats).text = ""

        return view
    }
}