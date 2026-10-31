package com.example.kolmafiaandroid

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo

/** Starts the real KoLmafia core in the background as soon as the process starts. */
class KolApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val hooks = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) ||
            (packageManager.getApplicationInfo(packageName, android.content.pm.PackageManager.GET_META_DATA)
                .metaData?.get("kolmob.hooks")?.toString() == "true")
        if (hooks) System.setProperty("kolmob.http.debug", "true")
        com.example.kolmafiaandroid.ui.KC.apply(this)
        Thread { LogPruner.prune(this) }.start()
        KolEngineHost.start(this)
        ChatNotifier.init(this)
        // Debug builds only: `adb shell am broadcast -a com.example.kolmafiaandroid.RUN --es cmd '<command>'`
        // runs a KoLmafia CLI line; output goes to the console and to logcat (tag KolEngine).
        if (hooks) {
            registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    intent.getStringExtra("cmd")?.let { KolEngineHost.execute(it) }
                    // `--es fakeui 1` (debug only) hides the login screen so offline-capable screens can be inspected.
                    if (intent.hasExtra("fakeui")) { KolEngineHost.devForceUi = true; KolEngineHost.loginState.value = true }
                    // `--es probe '<ash string expression>'` (debug only) logs the answer under tag KolProbe.
                    intent.getStringExtra("probe")?.let { e -> com.example.kolmafiaandroid.ui.AshProbe.eval(e) { android.util.Log.i("KolProbe", "answer=" + it.replace("\n", " / ")) } }
                    // `--es theme dark|light|system` (debug only) switches the app theme.
                    intent.getStringExtra("theme")?.let { t ->
                        getSharedPreferences("kolmob", Context.MODE_PRIVATE).edit().putString("theme_mode", t).apply()
                        com.example.kolmafiaandroid.ui.KC.apply(this@KolApp)
                    }
                    // `--es snapshot 1` (debug only) logs the UI snapshot JSON.
                    if (intent.hasExtra("snapshot")) KolEngineHost.snapshot { json ->
                        json.chunked(900).forEachIndexed { i, c -> android.util.Log.i("KolSnap", "$i: $c") }
                    }
                    // `--es web <path>` / `--es web js:<code>` (debug only) drives the Game tab's WebView.
                    intent.getStringExtra("web")?.let { KolEngineHost.webRequests.tryEmit(it) }
                    // `--es chat start|state|text:<key>` (debug only) exercises the chat engine; results in logcat tag KolChat.
                    intent.getStringExtra("chat")?.let { op ->
                        when {
                            op == "start" -> KolEngineHost.chatStart { android.util.Log.i("KolChat", "running=$it") }
                            op.startsWith("send:") -> op.removePrefix("send:").split("|", limit = 2).let { KolEngineHost.chatSend(it[0], it[1]) }
                            op == "state" -> KolEngineHost.chatState { android.util.Log.i("KolChat", "state=$it") }
                            op.startsWith("text:") -> KolEngineHost.chatText(op.removePrefix("text:"), 0) { android.util.Log.i("KolChat", "text=" + it.take(1500)) }
                        }
                    }
                    // `--es checktext "<ash source, \\n for newlines>"` (debug only) logs the editor's parser diagnostics (tag KolCheck).
                    intent.getStringExtra("checktext")?.let { t ->
                        KolEngineHost.checkAsh("/data/t.ash", t.replace("\\n", "\n")) { r -> android.util.Log.i("KolCheck", "result=" + r.joinToString(" | ")) }
                    }
                    // `--es notify <key>|<line>` (debug only) posts a sample chat notification.
                    intent.getStringExtra("notify")?.let { v -> v.split("|", limit = 2).let { ChatNotifier.debugNotify(it[0], it.getOrElse(1) { "test" }) } }
                    // `--es pref name=value` (debug only) sets a preference immediately, even mid-script.
                    intent.getStringExtra("pref")?.let { v -> v.split("=", limit = 2).let { if (it.size == 2) KolEngineHost.setPref(it[0], it[1]) } }
                    // `--es http <url>` (debug only) times a plain GET through the HttpClient shim.
                    intent.getStringExtra("http")?.let { url ->
                        Thread {
                            val t0 = System.currentTimeMillis()
                            try {
                                val c = com.kolmob.http.HttpClient.newBuilder().build()
                                val r = c.send(
                                    com.kolmob.http.HttpRequest.newBuilder(java.net.URI.create(url)).build(),
                                    com.kolmob.http.HttpResponse.BodyHandlers.ofString()
                                )
                                android.util.Log.i("KolEngine", "HTTP ${r.statusCode()} ${r.body().length} bytes in ${System.currentTimeMillis() - t0} ms")
                            } catch (t: Throwable) {
                                android.util.Log.i("KolEngine", "HTTP FAILED after ${System.currentTimeMillis() - t0} ms: $t")
                            }
                        }.start()
                    }
                    // `--es copy from>to` (debug only) copies a file inside KoLmafia's root folder.
                    intent.getStringExtra("diet")?.let { a ->
                        android.util.Log.i("KolDiet", net.sourceforge.kolmafia.KolEngine.dietPlan(a))
                    }
                    intent.getStringExtra("copy")?.let {
                        val (f, t) = it.split(">", limit = 2)
                        android.util.Log.i("KolEngine", net.sourceforge.kolmafia.KolEngine.copyRootFile(f, t))
                    }
                    // `--ez threads true` (debug only) logs every thread's stack, to see where a command is stuck.
                    if (intent.getBooleanExtra("threads", false)) {
                        Thread.getAllStackTraces().forEach { (th, st) ->
                            if (st.any { it.className.contains("kolmafia") && !it.className.contains("kolmafiaandroid.KolApp") }) {
                                android.util.Log.i("KolEngine", "THREAD ${th.name}: " + st.take(14).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" })
                            }
                        }
                    }
                    // `--es login user:password` (debug only) exercises the real login path.
                    intent.getStringExtra("login")?.let {
                        val (u, p) = it.split(":", limit = 2)
                        KolEngineHost.login(u, p) { ok -> android.util.Log.i("KolEngine", "LOGIN RESULT loggedIn=$ok") }
                    }
                }
            }, IntentFilter("com.example.kolmafiaandroid.RUN"), Context.RECEIVER_EXPORTED)
        }
    }
}
