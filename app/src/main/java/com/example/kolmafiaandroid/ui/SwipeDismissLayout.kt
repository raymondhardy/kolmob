package com.example.kolmafiaandroid.ui

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/** Hosts a side panel: dragging it to the right (mostly horizontally) slides it away and calls [onDismiss]. */
class SwipeDismissLayout(context: Context, private val onDismiss: () -> Unit) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false; tracker?.recycle(); tracker = VelocityTracker.obtain(); tracker?.addMovement(e) }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && dx > slop * 2 && abs(dx) > abs(dy) * 2) { dragging = true; return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { tracker?.recycle(); tracker = null }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        tracker?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging) translationX = (e.rawX - downX).coerceAtLeast(0f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    if (e.actionMasked == MotionEvent.ACTION_UP && (translationX > width * 0.3f || vx > 1200f)) {
                        animate().translationX(width.toFloat()).setDuration(120).withEndAction { onDismiss() }.start()
                    } else animate().translationX(0f).setDuration(120).start()
                }
                dragging = false
                tracker?.recycle(); tracker = null
            }
        }
        return dragging || e.actionMasked == MotionEvent.ACTION_DOWN
    }
}
