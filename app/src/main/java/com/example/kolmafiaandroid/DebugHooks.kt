package com.example.kolmafiaandroid

/**
 * Seams that test builds can fill in. Release builds leave them empty. Test-only code that sets them lives in
 * `app/src/debug` (debug and fast builds) as `DebugOnly.install()`, which [KolApp] calls when the adb hooks are on.
 */
object DebugHooks {
    /** Replaces the Adventure tab's `adventure <turns> <location>` command when set. */
    @Volatile var adventureCommand: ((turns: Int, location: String) -> String?)? = null
}
