package com.example.kolmafiaandroid.ui

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import androidx.core.view.doOnLayout
import java.util.Collections
import java.util.WeakHashMap

/**
 * Landscape is short: a screen built as "title, controls, then a list that takes the rest" leaves the list a few lines,
 * and a plain form with no scrolling is simply cut off. This runs once on a full-screen dialog's root (a vertical
 * LinearLayout) in landscape and fixes both without touching each screen:
 *  - the body (a weighted ScrollView or ListView) is squeezed -> everything above it moves INTO it, so the whole screen scrolls as one;
 *  - there is no scrolling body and the content is taller than the screen -> everything goes into one ScrollView.
 */
object LandscapeFit {
    /** In landscape the keyboard otherwise opens its own full-screen editor and hides the app; keep typing in the field itself. */
    fun noExtractUi(v: View?) {
        when (v) {
            is android.widget.EditText -> v.imeOptions = v.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            is ViewGroup -> for (i in 0 until v.childCount) noExtractUi(v.getChildAt(i))
        }
    }

    private val done: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    fun apply(root: View) {
        if (root !is LinearLayout || root.orientation != LinearLayout.VERTICAL) return
        if (!done.add(root)) return
        root.doOnLayout { run(it as LinearLayout) }
    }

    private fun weighted(v: View) = ((v.layoutParams as? LinearLayout.LayoutParams)?.weight ?: 0f) > 0f

    private fun run(root: LinearLayout) {
        val avail = root.height - root.paddingTop - root.paddingBottom
        if (avail <= 0) return
        val kids = (0 until root.childCount).map { root.getChildAt(it) }.filter { it.visibility != View.GONE }
        val body = kids.firstOrNull { (it is ScrollView || it is ListView) && weighted(it) }
        if (body == null) {
            if (kids.any { weighted(it) }) return // a weighted host (a hub's content area): its own screen is fitted separately
            val total = kids.sumOf { k ->
                val lp = k.layoutParams as? ViewGroup.MarginLayoutParams
                k.height + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
            }
            if (total > avail + 4 && kids.none { it is ScrollView && it.height >= avail * 0.9 }) wrapAll(root)
            return
        }
        val above = kids.takeWhile { it !== body }
        if (above.isEmpty() || body.height >= avail * 0.40) return
        moveHeaders(root, body, above)
    }

    private fun wrapAll(root: LinearLayout) {
        val ctx = root.context
        val kids = (0 until root.childCount).map { root.getChildAt(it) }
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.removeAllViews()
        for (k in kids) inner.addView(k, k.layoutParams)
        root.addView(ScrollView(ctx).apply { isFillViewport = true; addView(inner, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun moveHeaders(root: LinearLayout, body: View, above: List<View>) {
        val ctx = root.context
        for (h in above) root.removeView(h)
        when (body) {
            is ScrollView -> {
                // headers go in a wrapper NEXT TO the original content: screens rebuild their content container
                // (removeAllViews) on refresh, which would wipe headers placed inside it
                val content = body.getChildAt(0)
                val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                body.removeAllViews()
                for (h in above) wrapper.addView(h, h.layoutParams)
                if (content != null) wrapper.addView(content, content.layoutParams?.let { ViewGroup.LayoutParams(it.width, it.height) } ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                body.addView(wrapper, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            is ListView -> {
                val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                for (h in above) box.addView(h, h.layoutParams)
                body.addHeaderView(box, null, false)
            }
        }
    }
}
