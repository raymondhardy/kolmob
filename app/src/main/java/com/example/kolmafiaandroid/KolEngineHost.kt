package com.example.kolmafiaandroid

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import net.sourceforge.kolmafia.KolEngine

/**
 * Owns the single engine thread that runs the real KoLmafia core (CLI commands, ASH scripts,
 * login). KoLmafia is single-session and not thread-safe, so every call is serialized here.
 */
object KolEngineHost {
    // ASH recursion needs a deep stack; KoLmafia itself runs with a large one on desktop.
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "kol-engine", 64L * 1024 * 1024).apply { isDaemon = true }
    }

    /** Every line KoLmafia prints (HTML), replayed to late subscribers. */
    val lines = MutableSharedFlow<String>(replay = 1000, extraBufferCapacity = 4000)

    /** A page the Game tab should open next (set by the More sheet; consumed by KolWebFragment). */
    @Volatile
    var pendingWeb: String? = null

    /** A script is waiting for an answer (user_confirm etc.): the text is the question. Answer with [answerPrompt]. */
    val prompts = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Set when a script was started with its arguments left blank: its missing-parameter prompts are answered with empty/0/false. */
    @Volatile
    var blankParams = false

    fun answerPrompt(line: String) {
        try { KolEngine.answerPrompt(line) } catch (t: Throwable) { lines.tryEmit("PROMPT ERROR: $t") }
    }

    /** Only lines printed from now on (the plain [lines] flow first replays the last 1000). */
    fun liveLines() = lines.drop(lines.replayCache.size)

    /** Debug hook: paths (or `js:<code>`) for the Game tab's WebView. */
    val webRequests = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** True while a character is logged in; observed by the UI. */
    val loginState = MutableStateFlow(false)

    /** True once the engine has started and warmed up. */
    val readyState = MutableStateFlow(false)

    @Volatile
    var ready = false
        private set

    @Volatile
    var loggedIn = false
        private set

    /** Name of the logged-in character (empty when logged out). */
    @Volatile
    var currentUser = ""
        private set

    private var started = false

    // Read-only state queries run on their own thread so they do not queue behind a long adventure.
    private val reader = Executors.newSingleThreadExecutor { r -> Thread(r, "kol-reader").apply { isDaemon = true } }

    /** Debug only: prints the engine thread's stack every few seconds while [block] runs. */
    private fun <T> watched(label: String, block: () -> T): T {
        if (!java.lang.Boolean.getBoolean("kolmob.http.debug")) return block()
        val engine = Thread.currentThread()
        val watcher = Thread {
            try {
                while (true) {
                    Thread.sleep(4000)
                    val frames = engine.stackTrace.take(18).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                    Log.i("KolProf", "[$label] $frames")
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
        try {
            return block()
        } finally {
            watcher.interrupt()
        }
    }

    @Volatile private var appContext: Context? = null

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        val root = File(context.filesDir, "kolmafia")
        executor.execute {
            try {
                watched("start") {
                    KolEngine.start(root) { line ->
                        Log.i("KolEngine", line)
                        lines.tryEmit(line)
                    }
                }
                ready = true
                watched("warmup") { KolEngine.warmUp() }
                // after warm-up: touching KoLmafiaCLI earlier makes its class initializer run before KoLmafia's and wedges the start
                KolEngine.setPromptListener { text -> prompts.tryEmit(text) }
                readyState.value = true
                lines.tryEmit("KoLmafia core ready (data root: ${root.absolutePath})")
            } catch (t: Throwable) {
                lines.tryEmit("ENGINE FAILED TO START: $t")
            }
        }
    }

    /** Runs a KoLmafia CLI line, e.g. `ash print("hi");`, then calls [done] on the engine thread. */
    fun execute(command: String, done: () -> Unit = {}) {
        executor.execute {
            val ctx = appContext
            if (ctx != null) ScriptService.begin(ctx, command)
            try {
                watched("cmd") { KolEngine.execute(command) }
            } catch (t: Throwable) {
                lines.tryEmit("ERROR: $t")
            }
            if (ctx != null) ScriptService.end(ctx)
            loggedIn = KolEngine.isLoggedIn()
            currentUser = if (loggedIn) KolEngine.userName() else ""
            loginState.value = loggedIn
            done()
        }
    }

    fun login(username: String, password: String, done: (Boolean) -> Unit) {
        executor.execute {
            try {
                watched("login") { KolEngine.login(username, password) }
            } catch (t: Throwable) {
                lines.tryEmit("LOGIN ERROR: $t")
            }
            loggedIn = KolEngine.isLoggedIn()
            currentUser = if (loggedIn) KolEngine.userName() else ""
            loginState.value = loggedIn
            done(loggedIn)
        }
    }

    /** A JSON snapshot of the character (see KolEngine.snapshot), delivered on the engine thread. */
    fun snapshot(done: (String) -> Unit) {
        reader.execute {
            val json = try {
                KolEngine.snapshot()
            } catch (t: Throwable) {
                "{\"loggedIn\":false,\"error\":\"$t\"}"
            }
            done(json)
        }
    }

    /** Diet plan JSON for an options string (see KolEngine.dietPlan), delivered on the reader thread. */
    fun dietPlan(spec: String, done: (String) -> Unit) {
        reader.execute {
            done(try { KolEngine.dietPlan(spec) } catch (t: Throwable) { "{\"ok\":false,\"error\":\"$t\"}" })
        }
    }

    /** Starts KoLmafia's relay server (the in-game browser proxy); delivers its port or -1. */
    fun startRelay(done: (Int) -> Unit) {
        executor.execute { done(KolEngine.startRelay()) }
    }

    /** KoL's own "Desktop/Mobile Options" (compact character pane etc.) as JSON. */
    fun interfaceOptions(done: (String) -> Unit) {
        executor.execute {
            done(try { KolEngine.interfaceOptions() } catch (t: Throwable) { "{\"ok\":false}" })
        }
    }

    fun setAccountOption(action: String, value: String, done: () -> Unit = {}) {
        executor.execute {
            try { KolEngine.setAccountOption(action, value) } catch (t: Throwable) { lines.tryEmit("ERROR: $t") }
            done()
        }
    }

    /** All adventure locations (JSON array), see KolEngine.locations. */
    fun locations(done: (String) -> Unit) {
        reader.execute { done(try { KolEngine.locations() } catch (t: Throwable) { "[]" }) }
    }

    /** Turns left, last location, combat settings and combat scripts (JSON). */
    /** Mall search runs on the command executor (it makes web requests); the result is JSON. */
    fun mallSearch(query: String, max: Int, done: (String) -> Unit) {
        executor.execute { done(try { KolEngine.mallSearch(query, max) } catch (t: Throwable) { "{\"error\":\"$t\"}" }) }
    }

    fun npcShops(done: (String) -> Unit) {
        executor.execute { done(try { KolEngine.npcShops() } catch (t: Throwable) { "{\"error\":\"$t\"}" }) }
    }

    fun coinmasters(done: (String) -> Unit) {
        executor.execute { done(try { KolEngine.coinmasters() } catch (t: Throwable) { "{\"error\":\"$t\"}" }) }
    }

    fun clanInfo(done: (String) -> Unit) {
        executor.execute { done(try { KolEngine.clanInfo() } catch (t: Throwable) { "{\"error\":\"$t\"}" }) }
    }

    fun faxMonsters(done: (String) -> Unit) {
        executor.execute { done(try { KolEngine.faxMonsters() } catch (t: Throwable) { "{\"error\":\"$t\"}" }) }
    }

    fun ashNames(prefix: String, done: (List<String>) -> Unit) {
        reader.execute { done(try { KolEngine.ashNames(prefix, 12).split("\n").filter { it.isNotEmpty() } } catch (t: Throwable) { emptyList() }) }
    }

    fun checkAsh(path: String, text: String, done: (List<String>) -> Unit) {
        reader.execute { done(try { KolEngine.checkAsh(path, text).split("\n").filter { it.isNotEmpty() } } catch (t: Throwable) { emptyList() }) }
    }

    fun commandNames(prefix: String, done: (List<String>) -> Unit) {
        reader.execute { done(try { KolEngine.commandNames(prefix, 12).split("\n").filter { it.isNotEmpty() } } catch (t: Throwable) { emptyList() }) }
    }

    fun profile(name: String, done: (String) -> Unit) {
        executor.execute { done(try { KolEngine.profile(name) } catch (t: Throwable) { "{\"error\":\"$t\"}" }) }
    }

    fun modifiers(done: (String) -> Unit) {
        reader.execute { done(try { KolEngine.modifiers() } catch (t: Throwable) { "{\"error\":\"$t\"}" }) }
    }

    fun mushroom(done: (String) -> Unit) {
        executor.execute { done(try { KolEngine.mushroom() } catch (t: Throwable) { "{\"error\":\"$t\"}" }) }
    }

    fun toggleMemento(name: String, done: (String) -> Unit) {
        executor.execute { done(try { KolEngine.toggleMemento(name) } catch (t: Throwable) { t.toString() }) }
    }

    fun toggleJunk(name: String, done: (String) -> Unit) {
        executor.execute { done(try { KolEngine.toggleJunk(name) } catch (t: Throwable) { t.toString() }) }
    }

    fun isJunk(name: String, done: (Boolean) -> Unit) {
        reader.execute { done(try { KolEngine.isJunk(name) } catch (t: Throwable) { false }) }
    }

    fun isMemento(name: String, done: (Boolean) -> Unit) {
        reader.execute { done(try { KolEngine.isMemento(name) } catch (t: Throwable) { false }) }
    }

    fun gearAll(slot: String, done: (String) -> Unit) {
        reader.execute { done(try { KolEngine.gearAll(slot) } catch (t: Throwable) { "{\"items\":[]}" }) }
    }

    fun arenaInfo(done: (String) -> Unit) {
        reader.execute { done(try { KolEngine.arenaInfo() } catch (t: Throwable) { "{}" }) }
    }

    fun conditionsLeft(done: (Int) -> Unit) {
        reader.execute { done(try { KolEngine.conditionsLeft().toInt() } catch (t: Throwable) { -1 }) }
    }

    fun combatSkills(done: (String) -> Unit) {
        reader.execute { done(try { KolEngine.combatSkills() } catch (t: Throwable) { "[]" }) }
    }

    fun buffRequestBots(done: (String) -> Unit) {
        executor.execute { done(try { KolEngine.buffRequestBots() } catch (t: Throwable) { "{\"error\":\"$t\"}" }) }
    }

    fun buffRequestOffers(bot: String, done: (String) -> Unit) {
        executor.execute { done(try { KolEngine.buffRequestOffers(bot) } catch (t: Throwable) { "{\"error\":\"$t\"}" }) }
    }

    fun buffbotOffers(done: (String) -> Unit) {
        executor.execute { done(try { KolEngine.buffbotOffers() } catch (t: Throwable) { "{\"error\":\"$t\"}" }) }
    }

    fun buffbotAdd(skill: String, price: Int, casts: Int, done: (String) -> Unit) {
        executor.execute { done(try { KolEngine.buffbotAdd(skill, price, casts) } catch (t: Throwable) { t.toString() }) }
    }

    fun buffbotRemove(price: Int, done: (String) -> Unit) {
        executor.execute { done(try { KolEngine.buffbotRemove(price) } catch (t: Throwable) { t.toString() }) }
    }

    fun gains(done: (String) -> Unit) {
        reader.execute { done(try { KolEngine.gains() } catch (t: Throwable) { "{\"error\":\"$t\"}" }) }
    }

    fun inbox(done: (String) -> Unit) {
        executor.execute { done(try { KolEngine.inbox(50) } catch (t: Throwable) { "{\"error\":\"$t\"}" }) }
    }

    fun deleteMail(id: String, done: (String) -> Unit) {
        executor.execute { done(try { KolEngine.deleteMail(id) } catch (t: Throwable) { t.toString() }) }
    }

    fun saveMail(id: String, done: (String) -> Unit) {
        executor.execute { done(try { KolEngine.saveMail(id) } catch (t: Throwable) { t.toString() }) }
    }

    fun creatables(done: (String) -> Unit) {
        executor.execute { done(try { KolEngine.creatables() } catch (t: Throwable) { "{\"error\":\"$t\"}" }) }
    }

    fun storeItems(done: (String) -> Unit) {
        executor.execute { done(try { KolEngine.storeItems() } catch (t: Throwable) { "{\"error\":\"$t\"}" }) }
    }

    fun restoreConfig(hp: Boolean, done: (String) -> Unit) {
        reader.execute { done(try { KolEngine.restoreConfig(hp) } catch (t: Throwable) { "{}" }) }
    }

    fun quests(done: (String) -> Unit) {
        reader.execute { done(try { KolEngine.quests() } catch (t: Throwable) { "{}" }) }
    }

    fun adventureState(done: (String) -> Unit) {
        reader.execute { done(try { KolEngine.adventureState() } catch (t: Throwable) { "{}" }) }
    }

/** Closet, storage, display case items and meat (JSON). */
    fun items(done: (String) -> Unit) {
        reader.execute { done(try { KolEngine.items() } catch (t: Throwable) { "{\"error\":\"$t\"}" }) }
    }

    /** Preferences as JSON array: [{name,value,scope}]. Filter by name (case-insensitive). */
    fun preferences(filter: String, done: (String) -> Unit) {
        reader.execute { done(try { KolEngine.preferences(filter) } catch (t: Throwable) { "[]" }) }
    }

    /** Database lookup: returns JSON array [{id,name,summary}] for items/monsters/skills/effects matching query. */
    fun lookup(kind: String, query: String, done: (String) -> Unit) {
        reader.execute { done(try { KolEngine.lookup(kind, query) } catch (t: Throwable) { "[]" }) }
    }

    /** Database describe: returns longer multi-line text for a specific entry by ID. */
    fun describe(kind: String, id: Int, done: (String) -> Unit) {
        reader.execute { done(try { KolEngine.describe(kind, id) } catch (t: Throwable) { "Error: $t" }) }
    }

    /** Stops the running adventure/script immediately (not queued behind it). */
    fun abort() {
        try { KolEngine.abort() } catch (t: Throwable) { lines.tryEmit("ERROR: $t") }
    }

    // ---- chat (see KolEngine) ----

    fun chatStart(done: (Boolean) -> Unit) {
        executor.execute { done(try { KolEngine.chatStart() } catch (t: Throwable) { false }) }
    }

    fun chatState(done: (String) -> Unit) {
        reader.execute { done(try { KolEngine.chatState() } catch (t: Throwable) { "{}" }) }
    }

    fun autoAttackInfo(done: (String) -> Unit) {
        reader.execute { done(try { KolEngine.autoAttackInfo() } catch (t: Throwable) { "{}" }) }
    }

    fun choiceList(done: (String) -> Unit) {
        reader.execute { done(try { KolEngine.choiceList() } catch (t: Throwable) { "[]" }) }
    }

    fun chatPoll(done: (String) -> Unit) {
        reader.execute { done(try { KolEngine.chatPoll() } catch (t: Throwable) { "{}" }) }
    }

    fun chatLengths(done: (String) -> Unit) {
        reader.execute { done(try { KolEngine.chatLengths() } catch (t: Throwable) { "{}" }) }
    }

    fun chatText(key: String, from: Int, done: (String) -> Unit) {
        reader.execute { done(try { KolEngine.chatText(key, from) } catch (t: Throwable) { "{}" }) }
    }

    fun chatSend(key: String, text: String, done: () -> Unit = {}) {
        executor.execute {
            try { KolEngine.chatSend(key, text) } catch (t: Throwable) { lines.tryEmit("CHAT ERROR: $t") }
            done()
        }
    }

    /** Logs out and removes the account's own (per-user) script projects from the data root. */
    fun logout(done: () -> Unit = {}) {
        executor.execute {
            try { KolEngine.logout() } catch (t: Throwable) { lines.tryEmit("LOGOUT ERROR: $t") }
            loggedIn = KolEngine.isLoggedIn()
            currentUser = if (loggedIn) KolEngine.userName() else ""
            loginState.value = loggedIn
            done()
        }
    }

    /** Installed Git/SVN script projects with their scope, as JSON. */
    fun projects(done: (String) -> Unit) {
        reader.execute { done(try { KolEngine.projects() } catch (t: Throwable) { "[]" }) }
    }

    /** Installs a script project; scope is "global" or "user" (only the logged-in account). */
    fun installProject(url: String, scope: String, done: () -> Unit = {}) {
        executor.execute {
            try { KolEngine.installProject(url, scope) } catch (t: Throwable) { lines.tryEmit("ERROR: $t") }
            done()
        }
    }

    fun setProjectScope(id: String, scope: String, done: (Boolean) -> Unit = {}) {
        executor.execute { done(try { KolEngine.setProjectScope(id, scope) } catch (t: Throwable) { false }) }
    }

    fun setHighlights(list: String) { executor.execute { try { KolEngine.setHighlights(list) } catch (t: Throwable) { lines.tryEmit("ERROR: $t") } } }

    fun getPref(name: String): String = try { KolEngine.getPref(name) } catch (t: Throwable) { "" }

    /** Sets a KoLmafia preference right now (not queued behind a running script). */
    fun setPref(name: String, value: String) {
        try { KolEngine.setPref(name, value) } catch (t: Throwable) { lines.tryEmit("ERROR: $t") }
    }
}
