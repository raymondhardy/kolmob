package com.example.kolmafiaandroid.ui

import android.content.Context
import java.io.File

/**
 * Ezandora's Guide, shipped inside the app (public domain, see assets/guide/LICENSE.txt).
 *
 * The bundled copy is written to the relay folder under its own private names, so the relay server can
 * serve it, but nothing a user installs or removes (relay_Guide.ash and its Guide files) can touch it.
 * It is re-written whenever it is missing or changed, and the Scripts list hides it.
 */
object GuideBundle {
    const val PAGE = "relay_KolmobGuide.ash"
    const val SCRIPT = "KolmobGuideSide.js"

    /** True for files this class owns, so lists can skip them. */
    fun isBundled(name: String) = name == PAGE || name == SCRIPT

    @Synchronized
    fun install(ctx: Context) {
        val dir = File(ctx.filesDir, "kolmafia/relay").also { it.mkdirs() }
        // the page asks for its browser-side script by name; point it at our own private copy
        val ash = ctx.assets.open("guide/guide.ash").use { it.readBytes() }
            .toString(Charsets.UTF_8).replace("GuideBrowserSide.js", SCRIPT).toByteArray(Charsets.UTF_8)
        val js = ctx.assets.open("guide/guide.js").use { it.readBytes() }
        write(File(dir, PAGE), ash)
        write(File(dir, SCRIPT), js)
    }

    private fun write(f: File, bytes: ByteArray) {
        if (f.exists() && f.length() == bytes.size.toLong() && f.readBytes().contentEquals(bytes)) return
        f.writeBytes(bytes)
    }
}
