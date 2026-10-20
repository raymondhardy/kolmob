package com.example.kolmafiaandroid.fragments

import com.example.kolmafiaandroid.R

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Fragment displayed for the Shop tab.
 */
class ShopFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.fragment_shop, container, false)

        view.findViewById<TextView>(R.id.shop_items).text = getString(R.string.not_logged_in)

        return view
    }
}