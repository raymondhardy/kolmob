package com.example.kolmafiaandroid.ui

import android.app.Dialog
import android.os.Bundle
import android.view.Window
import androidx.fragment.app.DialogFragment
import com.example.kolmafiaandroid.R

/**
 * Base for the app's full-screen pages (Mall, Meat, Logs, Chat, …): the shared window style and no system title.
 * Everything else about a page (its title row, scrolling body, buttons) is built from the pieces in UiKit.
 */
abstract class FullScreenDialog : DialogFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).also { it.requestWindowFeature(Window.FEATURE_NO_TITLE) }
}
