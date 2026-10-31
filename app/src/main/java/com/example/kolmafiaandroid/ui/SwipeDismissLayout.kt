package com.example.kolmafiaandroid.ui

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Hosts a bottom panel: dragging down from its top strip (the header, [grabHeight] px tall) slides it away and calls [onDismiss].
 * Only the strip reacts, so scrolling the messages below it is never mistaken for a swipe.
 */
class SwipeDismissLayout(context: Context, private val grabHeight: Int, private val onDismiss: () -> Unit) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var armed = false
    private var dragging = false
    private var tracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; dragging = false
                armed = e.y <= grabHeight
                tracker?.recycle(); tracker = VelocityTracker.obtain(); tracker?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(e)
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (armed && !dragging && dy > slop * 2 && abs(dy) > abs(dx) * 2) { dragging = true; return true }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { tracker?.recycle(); tracker = null }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        tracker?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging) translationY = (e.rawY - downY).coerceAtLeast(0f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    tracker?.computeCurrentVelocity(1000)
                    val vy = tracker?.yVelocity ?: 0f
                    if (e.actionMasked == MotionEvent.ACTION_UP && (translationY > height * 0.25f || vy > 1200f)) {
                        animate().translationY(height.toFloat()).setDuration(120).withEndAction { translationY = 0f; onDismiss() }.start()
                    } else animate().translationY(0f).setDuration(120).start()
                }
                dragging = false
                tracker?.recycle(); tracker = null
            }
        }
        return dragging || e.actionMasked == MotionEvent.ACTION_DOWN
    }
}
