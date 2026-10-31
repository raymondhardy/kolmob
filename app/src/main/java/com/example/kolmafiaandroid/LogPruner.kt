package com.example.kolmafiaandroid

import android.content.Context
import java.io.File

/** Deletes session, chat and debug logs older than the number of days the person chose in Settings (0 = keep everything; the default is 1 day, since this is a phone). */
object LogPruner {
    const val PREF = "log_keep_days"
    val choices = listOf(1 to "1 day", 2 to "2 days", 3 to "3 days", 7 to "7 days", 14 to "14 days", 30 to "30 days", 0 to "Forever")

    fun keepDays(ctx: Context): Int = ctx.getSharedPreferences("kolmob", Context.MODE_PRIVATE).getInt(PREF, 1)

    fun setKeepDays(ctx: Context, days: Int) {
        ctx.getSharedPreferences("kolmob", Context.MODE_PRIVATE).edit().putInt(PREF, days).apply()
    }

    /** Returns how many files were deleted. Safe to call from any thread. */
    fun prune(ctx: Context): Int {
        val days = keepDays(ctx)
        if (days <= 0) return 0
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        val root = File(ctx.filesDir, "kolmafia")
        var gone = 0
        fun sweep(dir: File, ok: (String) -> Boolean) {
            dir.listFiles()?.forEach { f ->
                if (f.isFile && ok(f.name) && f.lastModified() in 1 until cutoff && f.delete()) gone++
            }
        }
        try {
            sweep(File(root, "sessions")) { it.endsWith(".txt") }
            sweep(File(root, "chats")) { it.endsWith(".html") || it.endsWith(".txt") }
            sweep(root) { it.endsWith(".txt") }
        } catch (_: Exception) {}
        return gone
    }
}
