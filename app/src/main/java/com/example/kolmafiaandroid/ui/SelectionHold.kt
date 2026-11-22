package com.example.kolmafiaandroid.ui

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.widget.TextView

/**
 * Keeps a live log's text still while the user is pressing on it or has text selected.
 *
 * Android ends a selection (its handles and Copy menu) whenever the text changes, so a log that gains a line every
 * second leaves the long-pressed word highlighted with no way to extend or copy it. Callers check [holding] and keep
 * new lines aside while it is true; [release] runs once the finger is up and the selection is gone.
 */
class SelectionHold(private val tv: TextView, private val release: () -> Unit) {
    private var touching = false
    private var selecting = false

    val holding: Boolean get() = touching || selecting || tv.hasSelection()

    init {
        tv.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> touching = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { touching = false; tv.post { releaseIfFree() } }
            }
            false
        }
        tv.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean { selecting = true; return true }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
            // posted: the selection collapses only after this callback returns
            override fun onDestroyActionMode(mode: ActionMode) { selecting = false; tv.post { releaseIfFree() } }
        }
    }

    private fun releaseIfFree() { if (!holding) release() }
}
