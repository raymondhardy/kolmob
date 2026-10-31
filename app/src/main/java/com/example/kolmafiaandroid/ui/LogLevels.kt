package com.example.kolmafiaandroid.ui

/** Log levels for script output. Scripts such as autoscend tag their lines with [DEBUG], [INFO], [WARNING], [ERROR]. */
object LogLevels {
    const val PREF = "log_level"
    val labels = arrayOf("Debug (everything)", "Info", "Warnings and errors", "Errors only")

    /** 0 = debug, 1 = info (also any line without a level tag), 2 = warning, 3 = error. */
    fun of(s: CharSequence): Int {
        val t = s.toString().trimStart()
        return when {
            t.startsWith("[DEBUG]") -> 0
            t.startsWith("[WARNING]") || t.startsWith("[WARN]") -> 2
            t.startsWith("[ERROR]") || t.startsWith("[CRITICAL]") -> 3
            else -> 1
        }
    }
}
