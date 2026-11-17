package com.example.kolmafiaandroid.ui

import android.app.Dialog
import java.util.Collections
import java.util.WeakHashMap

/**
 * Pick sheets and alert dialogs that are plain dialogs, not fragments, so the bottom bar can close them with the
 * full-screen ones when you change tabs; left open, they sat on top of the new tab.
 */
object OpenDialogs {
    private val open: MutableSet<Dialog> = Collections.newSetFromMap(WeakHashMap())

    fun track(d: Dialog) { open.add(d) }

    /** For a dialog that must stay up, such as a script's question that KoLmafia is waiting on. */
    fun untrack(d: Dialog) { open.remove(d) }

    fun dismissAll() {
        for (d in open.toList()) if (d.isShowing) d.dismiss()
        open.clear()
    }
}
