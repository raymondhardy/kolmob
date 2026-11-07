package com.example.kolmafiaandroid.ui

import android.view.MotionEvent
import android.widget.ScrollView

/**
 * Calls [stop] as soon as the user drags the output up to read older lines, even while new lines keep arriving.
 *
 * The text inside is selectable, so it takes the finger's first touch and the ScrollView only sees the drag once it
 * takes over scrolling. So this watches the scroll position instead: while a finger is dragging the ScrollView,
 * any move towards older lines stops following. Following only ever scrolls down, and trimming old lines only
 * happens with no finger down, so neither can trigger it. Taps and drags down towards the newest line never stop it.
 */
fun ScrollView.stopFollowingOnDragUp(stop: () -> Unit) {
    var dragging = false
    setOnTouchListener { _, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> dragging = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        false
    }
    setOnScrollChangeListener { _, _, y, _, oldY ->
        if (dragging && y < oldY) stop()
    }
}
