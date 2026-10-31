package com.example.kolmafiaandroid.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.webkit.WebView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The app's alert-dialog builder. A custom view put in an alert dialog is not scrollable on its own, so in a short
 * (landscape) window everything below the fold is cut off. This wraps such a view in a ScrollView; views that already
 * scroll (ScrollView, lists, web views) are passed through untouched.
 */
class ScrollSafeDialogBuilder(context: Context) : MaterialAlertDialogBuilder(context) {
    override fun setView(view: View?): MaterialAlertDialogBuilder {
        val v = view ?: return super.setView(view)
        val scrolls = v is ScrollView || v is HorizontalScrollView || v is AbsListView || v is WebView ||
            v is androidx.core.widget.NestedScrollView || v is androidx.recyclerview.widget.RecyclerView
        if (scrolls) return super.setView(v)
        // keep the view's own padding: the ScrollView only adds scrolling
        val sv = ScrollView(context).apply { isFillViewport = false; addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        return super.setView(sv)
    }
}
