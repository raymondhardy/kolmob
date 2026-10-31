package com.example.kolmafiaandroid

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.Html
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * Background chat alerts. While you are logged in it watches KoLmafia's chat buffers (the same ones the chat
 * screen shows) and posts an Android notification for new messages you asked to hear about: whispers (DMs),
 * mentions of your name, and/or chosen channels. A foreground service keeps the process alive in the background.
 */
object ChatNotifier {
    const val CHANNEL_ID = "chat"
    const val SERVICE_CHANNEL_ID = "service"
    private const val PREFS = "kolmob_notify"

    /** True while the chat screen is on top, so nothing is announced twice. */
    @Volatile
    var chatScreenOpen = false

    class Config(
        val enabled: Boolean,
        val dms: Boolean,
        val mentions: Boolean,
        val allChannels: Boolean,
        val channels: Set<String>,
    )

    private lateinit var app: Application
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var chatStarted = false
    private var suspended = false
    private var seeded = false
    private val last = mutableMapOf<String, String>()
    private val recent = mutableMapOf<String, MutableList<String>>()

    // ---- unread tracking for the badges on More / Chat and the thread list ----
    private val seenLen = mutableMapOf<String, Int>()
    private val seenMsgs = mutableMapOf<String, Int>()
    private val hiddenAt = mutableMapOf<String, Int>()   // closed threads: message count when closed
    private var unreadSeeded = false

    /** Messages you have not looked at, per thread (closed threads are left out until they get a new message). */
    @Volatile
    var unreadByKey: Map<String, Int> = emptyMap()
        private set

    /** Number of chat threads with messages you have not looked at. */
    val unreadTabs: Int get() = unreadByKey.size

    /** Number of unread messages in all threads. */
    val unreadTotal: Int get() = unreadByKey.values.sum()

    /** Called on the main thread whenever the unread counts change. */
    var onUnreadChanged: (() -> Unit)? = null

    /** The chat screen showed this buffer up to [len] characters ([msgs] messages). */
    fun markSeen(key: String, len: Int, msgs: Int = -1) {
        synchronized(seenLen) {
            seenLen[key] = len
            if (msgs >= 0) seenMsgs[key] = msgs
        }
    }

    /** Close a thread: it leaves the list until somebody writes in it again. */
    fun hide(ctx: Context, key: String, msgsNow: Int) {
        synchronized(seenLen) { hiddenAt[key] = msgsNow; seenMsgs[key] = msgsNow }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("hidden", hiddenAt.map { it.key + "\u001f" + it.value }.toSet()).apply()
        markRead(key)
    }

    /** A closed thread was opened again on purpose (e.g. you whispered that player). */
    fun unhide(ctx: Context, key: String) {
        if (!isHidden(key)) return
        synchronized(seenLen) { hiddenAt.remove(key) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("hidden", hiddenAt.map { it.key + "\u001f" + it.value }.toSet()).apply()
    }

    /** Refresh the unread counts right now (the chat screen calls this after each update). */
    fun refreshUnread() { try { pollUnread() } catch (_: Exception) { } }

    fun isHidden(key: String): Boolean = synchronized(seenLen) { hiddenAt.containsKey(key) }

    private fun loadHidden() {
        val set = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("hidden", emptySet()) ?: emptySet()
        synchronized(seenLen) { for (e in set) { val p = e.split('\u001f'); if (p.size == 2) hiddenAt[p[0]] = p[1].toIntOrNull() ?: 0 } }
    }

    private fun pollUnread() {
        if (!KolEngineHost.loggedIn) return
        KolEngineHost.chatLengths { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { return@chatLengths }
            if (!o.optBoolean("running")) return@chatLengths
            val lens = o.optJSONObject("lens") ?: return@chatLengths
            val msgs = o.optJSONObject("msgs") ?: return@chatLengths
            val now = linkedMapOf<String, Int>()
            synchronized(seenLen) {
                for (k in lens.keys()) {
                    val len = lens.optInt(k)
                    val m = msgs.optInt(k)
                    if (!seenMsgs.containsKey(k)) { seenMsgs[k] = if (unreadSeeded) 0 else m; seenLen[k] = if (unreadSeeded) 0 else len }
                    val hidden = hiddenAt[k]
                    if (hidden != null) {
                        if (m > hidden) { hiddenAt.remove(k) } else continue
                    }
                    val n = maxOf(0, m - (seenMsgs[k] ?: 0))
                    if (n > 0) now[k] = n
                }
            }
            unreadSeeded = true
            if (now != unreadByKey) {
                unreadByKey = now
                handler.post { onUnreadChanged?.invoke() }
            }
        }
    }

    fun config(ctx: Context): Config {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            enabled = p.getBoolean("enabled", false),
            dms = p.getBoolean("dms", true),
            mentions = p.getBoolean("mentions", true),
            allChannels = p.getBoolean("all", false),
            channels = p.getStringSet("channels", emptySet()) ?: emptySet(),
        )
    }

    fun save(ctx: Context, c: Config) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", c.enabled).putBoolean("dms", c.dms).putBoolean("mentions", c.mentions)
            .putBoolean("all", c.allChannels).putStringSet("channels", c.channels).apply()
        refreshService()
    }

    fun init(application: Application) {
        app = application
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Chat messages", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Whispers, mentions and the channels you chose" })
        nm.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL_ID, "Connection", NotificationManager.IMPORTANCE_MIN)
            .apply { description = "Keeps Kolmob connected for chat alerts" })
        if (!polling) {
            polling = true
            loadHidden()
            handler.postDelayed(tick, 6000)
        }
    }

    /** Start or stop the foreground service to match the setting and the login state. */
    fun refreshService() {
        if (!::app.isInitialized) return
        val want = config(app).enabled && KolEngineHost.loggedIn
        try {
            if (want) app.startForegroundService(Intent(app, ChatService::class.java)) else app.stopService(Intent(app, ChatService::class.java))
        } catch (_: Exception) {
            // starting a service from the background can be refused; alerts then only work while the app is open
        }
        if (!want) { chatStarted = false; seeded = false; last.clear(); recent.clear() }
    }

    private val tick = object : Runnable {
        override fun run() {
            try { poll() } catch (_: Exception) { }
            try { pollUnread() } catch (_: Exception) { }
            handler.postDelayed(this, if (away) 10000L else 5000L)
        }
    }

    /** KoLmafia's chat is in away mode (nothing said for 16 minutes): it polls slower, and so do we. */
    @Volatile
    var away = false
        private set

    private fun poll() {
        val cfg = config(app)
        if (!KolEngineHost.loggedIn) return
        val auto = app.getSharedPreferences("kolmob", Context.MODE_PRIVATE).getBoolean("chat_autostart", false)
        if (!cfg.enabled && !auto) return
        if (chatScreenOpen) suspended = false          // the panel restarts the poller itself
        if (suspended) return
        if (!chatStarted) {
            chatStarted = true
            KolEngineHost.chatStart { ok -> if (!ok) chatStarted = false }
            return
        }
        // Chat gone quiet (away mode), nobody looking and no alerts wanted: stop polling KoL until the panel is opened
        if (away && !cfg.enabled && !chatScreenOpen) {
            suspended = true; chatStarted = false; away = false
            KolEngineHost.chatSuspend()
            return
        }
        KolEngineHost.chatPoll { p -> away = try { JSONObject(p).optBoolean("away") } catch (_: Exception) { false } }
        KolEngineHost.chatState { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { return@chatState }
            if (!o.optBoolean("running")) return@chatState
            val keys = LinkedHashSet<String>()
            o.optJSONArray("channels")?.let { for (i in 0 until it.length()) keys.add(it.getString(i)) }
            o.optJSONArray("buffers")?.let { for (i in 0 until it.length()) keys.add(it.getString(i)) }
            val firstPass = !seeded
            for (k in keys) KolEngineHost.chatText(k, 0) { t -> handle(k, t, firstPass, cfg) }
            seeded = true
        }
    }

    private fun bodyOf(json: String): String? = try {
        val html = JSONObject(json).optString("html")
        html.substringAfter("<body>", html).substringBeforeLast("</body>")
    } catch (_: Exception) { null }

    private fun handle(key: String, json: String, firstPass: Boolean, cfg: Config) {
        if (!cfg.enabled) return                        // chat may be running only to count unread messages
        val body = bodyOf(json) ?: return
        val prev = last[key]
        last[key] = body
        if (prev == null && firstPass) return           // history that was already there when we started watching
        if (prev == body) return
        val fresh = when {
            prev == null -> body                        // a brand-new conversation (first DM from someone)
            body.startsWith(prev) -> body.substring(prev.length)
            else -> body.takeLast(maxOf(200, body.length - prev.length)) // buffer was trimmed
        }
        val me = KolEngineHost.currentUser
        val lines = Html.fromHtml(fresh, Html.FROM_HTML_MODE_COMPACT).toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val worth = lines.filter { line ->
            if (me.isNotEmpty() && Regex("^\\[[^]]*]\\s*" + Regex.escape(me) + ":").containsMatchIn(line)) return@filter false // my own message
            if (key.startsWith("/")) {
                cfg.allChannels || cfg.channels.contains(key) || (cfg.mentions && me.isNotEmpty() && line.contains(me, ignoreCase = true))
            } else cfg.dms
        }
        if (worth.isEmpty() || chatScreenOpen) return
        notify(key, worth)
    }

    private fun notify(key: String, newLines: List<String>) {
        val history = recent.getOrPut(key) { mutableListOf() }
        history.addAll(newLines)
        while (history.size > 6) history.removeAt(0)
        val title = if (key.startsWith("/")) "Chat · ${key.removePrefix("/")}" else "Whisper · $key"
        val intent = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_chat", key)
        }
        val pi = PendingIntent.getActivity(app, key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val style = NotificationCompat.InboxStyle().also { s -> history.forEach { s.addLine(it) } }.setBigContentTitle(title)
        val n = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.web_chat)
            .setContentTitle(title)
            .setContentText(newLines.last())
            .setStyle(style)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        try {
            app.getSystemService(NotificationManager::class.java).notify(key.hashCode(), n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted
        }
    }

    /** Debug hook only (adb): post a sample notification so the look and the tap target can be checked. */
    fun debugNotify(key: String, line: String) = notify(key, listOf(line))

    /** Called when the chat screen shows a conversation, so its notification and backlog are cleared. */
    fun markRead(key: String) {
        recent.remove(key)
        if (::app.isInitialized) app.getSystemService(NotificationManager::class.java).cancel(key.hashCode())
    }
}
