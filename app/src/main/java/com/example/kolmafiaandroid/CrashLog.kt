package com.example.kolmafiaandroid

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.io.File

/**
 * When turned on in Settings, keeps the stack trace of the last crash on the phone and offers it for copying at the
 * next start. Off by default.
 */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun enabled(ctx: Context) = ctx.getSharedPreferences("kolmob", Context.MODE_PRIVATE).getBoolean("crash_log", false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("kolmob", Context.MODE_PRIVATE).edit().putBoolean("crash_log", on).apply()
        if (!on) file(ctx).delete()
    }

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val version = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (_: Exception) { "?" }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                if (enabled(app)) {
                    val text = "Kolmob $version, Android ${android.os.Build.VERSION.RELEASE}\n" +
                        "Thread: ${thread.name}\n" + android.util.Log.getStackTraceString(error)
                    file(app).writeText(text)
                }
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, error)
        }
    }

    fun showIfAny(activity: Activity) {
        val f = file(activity)
        val text = try { if (f.exists()) f.readText() else null } catch (_: Throwable) { null } ?: return
        f.delete()
        com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(activity)
            .setTitle("Kolmob crashed last time")
            .setMessage(text.take(4000))
            .setPositiveButton("Copy") { _, _ ->
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Kolmob crash", text))
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
