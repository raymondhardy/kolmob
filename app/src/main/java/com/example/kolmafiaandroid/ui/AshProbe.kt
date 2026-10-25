package com.example.kolmafiaandroid.ui

import com.example.kolmafiaandroid.KolEngineHost

/**
 * Asks the engine a one-off question in ash and hands back the answer as text.
 * [expr] is any ash expression that gives a string, e.g. `"HP " + my_hp()`. Calls [done] off the main thread.
 * The answer travels as a marked print line that [KolEngineHost] keeps out of the Console.
 */
object AshProbe {
    /** Quotes a value for use inside an ash string literal. */
    fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    fun eval(expr: String, done: (String) -> Unit) {
        KolEngineHost.probeResult = null
        KolEngineHost.execute("ash print(\"@@P@@\" + replace_string(($expr), \"\\n\", \"~~NL~~\"));") {
            val v = KolEngineHost.probeResult
            KolEngineHost.probeResult = null
            done(v?.replace("~~NL~~", "\n") ?: "")
        }
    }
}
