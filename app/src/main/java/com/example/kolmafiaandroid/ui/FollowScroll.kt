package com.example.kolmafiaandroid.ui

import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView

/**
 * Calls [stop] only when the user deliberately drags the output up to read older lines: the touch must start inside this
 * view, travel clearly further than a tap or a wobble, go in the scroll-up direction, and still be inside the view.
 * Taps, touches that start elsewhere, drags down towards the newest line and releases outside never stop following.
 */
fun ScrollView.stopFollowingOnDragUp(stop: () -> Unit) {
    val slop = ViewConfiguration.get(context).scaledTouchSlop * 3
    var armed = false
    var startY = 0f
    setOnTouchListener { _, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { armed = true; startY = ev.y }
            MotionEvent.ACTION_MOVE ->
                if (armed && ev.y - startY > slop && ev.x >= 0 && ev.x <= width && ev.y >= 0 && ev.y <= height && canScrollVertically(-1)) {
                    armed = false
                    stop()
                }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> armed = false
        }
        false
    }
}
