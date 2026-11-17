package com.example.kolmafiaandroid.ui

import android.content.Context
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

    // Favourites go first in the script lists; hidden scripts (libraries, say) only show in the file manager.
    // Both are kept by path relative to the scripts folder.
    private const val FAVOURITES = "script_favourites"
    private const val HIDDEN = "script_hidden"

    private fun set(ctx: Context, key: String): Set<String> =
        ctx.getSharedPreferences("kolmob", Context.MODE_PRIVATE).getStringSet(key, emptySet()) ?: emptySet()

    private fun toggle(ctx: Context, key: String, rel: String, on: Boolean) {
        val now = set(ctx, key).toMutableSet()
        if (on) now.add(rel) else now.remove(rel)
        ctx.getSharedPreferences("kolmob", Context.MODE_PRIVATE).edit().putStringSet(key, now).apply()
    }

    fun isFavourite(ctx: Context, rel: String) = rel in set(ctx, FAVOURITES)
    fun isHidden(ctx: Context, rel: String) = rel in set(ctx, HIDDEN)
    fun setFavourite(ctx: Context, rel: String, on: Boolean) = toggle(ctx, FAVOURITES, rel, on)
    fun setHidden(ctx: Context, rel: String, on: Boolean) = toggle(ctx, HIDDEN, rel, on)

    /** The scripts for a run list: hidden ones left out, favourites first, each part in [order]. */
    fun forLists(ctx: Context, scriptsDir: File, order: Comparator<File>): List<File> {
        val favs = set(ctx, FAVOURITES)
        val hidden = set(ctx, HIDDEN)
        val shown = list(scriptsDir).filter { relName(scriptsDir, it) !in hidden }.sortedWith(order)
        return shown.filter { relName(scriptsDir, it) in favs } + shown.filter { relName(scriptsDir, it) !in favs }
    }

    /** Path as the script runner wants it: relative to the scripts folder, with forward slashes. */
    fun relName(scriptsDir: File, f: File): String =
        f.relativeTo(scriptsDir).path.replace(File.separatorChar, '/')
}
