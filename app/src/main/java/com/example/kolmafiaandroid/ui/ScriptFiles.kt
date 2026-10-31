package com.example.kolmafiaandroid.ui

import java.io.File
import java.util.Locale

/**
 * The scripts you can run from the lists. Plain ASH/text scripts directly in the scripts folder, plus JavaScript
 * scripts (.js) there and one folder down, since installed JavaScript scripts (e.g. freecandydotexe) live in their
 * own folder. ASH files inside folders are not listed: those are libraries pulled in by a main script.
 */
object ScriptFiles {
    fun list(scriptsDir: File): List<File> {
        val out = ArrayList<File>()
        val top = scriptsDir.listFiles() ?: return out
        for (f in top) {
            val n = f.name.lowercase(Locale.ROOT)
            if (f.isFile && (n.endsWith(".ash") || n.endsWith(".txt") || n.endsWith(".js"))) {
                out.add(f)
            } else if (f.isDirectory && !n.endsWith(".updating")) {
                f.listFiles { c -> c.isFile && c.name.lowercase(Locale.ROOT).endsWith(".js") }?.let { out.addAll(it) }
            }
        }
        return out.sortedByDescending { it.lastModified() }
    }

    /** Path as the script runner wants it: relative to the scripts folder, with forward slashes. */
    fun relName(scriptsDir: File, f: File): String =
        f.relativeTo(scriptsDir).path.replace(File.separatorChar, '/')
}
