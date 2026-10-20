package com.example.kolmafiaandroid.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Item / familiar / effect icons straight from KoL's image server, cached in memory and on disk
 * (cacheDir/kolimg) so each icon is downloaded once.
 */
object KolImages {
    private const val BASE = "https://s3.amazonaws.com/images.kingdomofloathing.com/"
    private val memory = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val missing = java.util.Collections.synchronizedSet(HashSet<String>())
    private var missingSince = 0L

    /** [img] is the path KoLmafia knows, e.g. "club.gif" or "itemimages/x.gif"; bare names live in itemimages/. */
    fun load(view: ImageView, img: String?) {
        view.tag = img
        view.setImageDrawable(null)
        if (img.isNullOrEmpty() || img == "debug.gif") return
        memory.get(img)?.let { view.setImageBitmap(it); return }
        if (System.currentTimeMillis() - missingSince > 60_000) { missing.clear(); missingSince = System.currentTimeMillis() } // retry failures after a minute
        if (img in missing) { showMissing(view); return }
        val ctx = view.context.applicationContext
        pool.execute {
            val bmp = fetch(ctx, img)
            main.post {
                if (bmp == null) { missing.add(img); if (view.tag == img) showMissing(view); return@post }
                if (view.tag == img) view.setImageBitmap(bmp)
            }
        }
    }

    /** A cached image if it is already in memory or on disk (never touches the network). */
    fun peek(ctx: Context, img: String): Bitmap? {
        memory.get(img)?.let { return it }
        return try {
            val name = MessageDigest.getInstance("MD5").digest(img.toByteArray()).joinToString("") { "%02x".format(it) }
            val f = File(File(ctx.cacheDir, "kolimg"), name)
            if (!f.exists() || f.length() == 0L) null else BitmapFactory.decodeFile(f.absolutePath)?.also { memory.put(img, it) }
        } catch (_: Throwable) { null }
    }

    /** Downloads [img] in the background; [onDone] runs on the main thread if it arrived. */
    fun prefetch(ctx: Context, img: String, onDone: () -> Unit) {
        if (img in missing) return
        val app = ctx.applicationContext
        pool.execute {
            val bmp = fetch(app, img)
            main.post { if (bmp != null) onDone() else missing.add(img) }
        }
    }

    /** Grey circle with a red X: shown whenever an icon cannot be fetched or decoded. */
    private fun showMissing(view: ImageView) {
        try { view.setImageResource(com.example.kolmafiaandroid.R.drawable.img_missing) } catch (_: Throwable) {}
    }

    private fun fetch(ctx: Context, img: String): Bitmap? {
        try {
            val dir = File(ctx.cacheDir, "kolimg").apply { mkdirs() }
            val name = MessageDigest.getInstance("MD5").digest(img.toByteArray()).joinToString("") { "%02x".format(it) }
            val f = File(dir, name)
            if (!f.exists() || f.length() == 0L) {
                val path = if (img.startsWith("http")) img else BASE + (if (img.contains("/")) "" else "itemimages/") + img
                val c = URL(path).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                if (c.responseCode != 200) return null
                val tmp = File(dir, "$name.part")
                c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(f)
            }
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
            memory.put(img, bmp)
            return bmp
        } catch (_: Throwable) { // network, disk, decode or out-of-memory: never take the app down for an icon
            return null
        }
    }
}
