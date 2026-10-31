package com.example.kolmafiaandroid.tools

import com.example.kolmafiaandroid.ui.KC

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.InputType
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.example.kolmafiaandroid.ChatNotifier
import com.example.kolmafiaandroid.KolEngineHost
import com.example.kolmafiaandroid.R
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

/**
 * Messenger-style chat on KoLmafia's own chat engine (ChatPoller -> ChatManager buffers -> ChatSender).
 * A conversation list (channels and whispers, newest activity first, unread counts, a close button on each)
 * opens into bubbles. Updates follow KoLmafia's backoff: every 3 seconds live, every 10 when chat is away.
 */
class ChatFragment : DialogFragment() {
    private val green = 0xFF4CAF50.toInt()
    private val muted = KC.muted
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var pollDelay = 3000L

    private var keys = listOf<String>()
    private var selected: String? = null          // null = conversation list
    private val shown = mutableMapOf<String, String>()      // key -> body HTML last rendered as bubbles
    private val lens = mutableMapOf<String, Int>()          // key -> buffer length at the last update
    private val msgs = mutableMapOf<String, Int>()          // key -> number of messages
    private val snippets = mutableMapOf<String, String>()   // key -> last line, for the list
    private val lastActive = mutableMapOf<String, Long>()

    private lateinit var title: TextView
    private lateinit var backBtn: MaterialButton
    private lateinit var newBtn: MaterialButton
    private lateinit var alertsBtn: MaterialButton
    private lateinit var doneBtn: MaterialButton
    private lateinit var closeChatBtn: MaterialButton
    private lateinit var status: TextView
    private lateinit var listScroll: ScrollView
    private lateinit var listBox: LinearLayout
    private lateinit var convoScroll: ScrollView
    private lateinit var convoBox: LinearLayout
    private lateinit var composer: LinearLayout
    private lateinit var input: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun ui(block: () -> Unit) { activity?.runOnUiThread { if (isAdded && view != null) block() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog =
        super.onCreateDialog(savedInstanceState).also {
            it.window?.setWindowAnimations(R.style.ChatPanelAnim)
            it.setCanceledOnTouchOutside(false) // the lower panel leaves the game playable above it
            panelDialog = it
        }

    override fun onStart() {
        super.onStart()
        ChatNotifier.chatScreenOpen = true
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog?.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP && selected != null) { closeConvo(); true } else false
        }
    }

    private fun textBtn(label: String, onClick: () -> Unit) =
        MaterialButton(requireContext(), null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = label; isAllCaps = false; minWidth = 0; minimumWidth = 0; setOnClickListener { onClick() }
            // snug padding so the title keeps room next to four buttons on a phone
            setPadding(dp(8), 0, dp(8), 0); insetLeft = 0; insetRight = 0
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(KC.pick(0xFF121212.toInt(), 0xFFF2F2F2.toInt()))
        }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), 0) }
        backBtn = textBtn("‹") { closeConvo() }.apply { textSize = 24f }
        header.addView(backBtn)
        title = TextView(ctx).apply { text = "Chat"; textSize = 20f; setTextColor(KC.text); setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(8), 0, 0, 0) }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        newBtn = textBtn("＋") { addMenu() }.apply { textSize = 20f; contentDescription = "New chat" }
        alertsBtn = textBtn("Alerts") { showAlertSettings() }
        doneBtn = textBtn("✕") { dismiss() }.apply { textSize = 20f; contentDescription = "Close chat panel" }
        closeChatBtn = textBtn("Close thread") { selected?.let { closeThread(it) } }
        panelFull = requireContext().getSharedPreferences("kolmob", android.content.Context.MODE_PRIVATE).getBoolean("chat_panel_full", false)
        var expandRef: MaterialButton? = null
        val expandBtn = textBtn(if (panelFull) "⤡" else "⤢") {
            panelFull = !panelFull
            requireContext().getSharedPreferences("kolmob", android.content.Context.MODE_PRIVATE).edit().putBoolean("chat_panel_full", panelFull).apply()
            expandRef?.text = if (panelFull) "⤡" else "⤢"
            dialog?.let { d -> (activity as? com.example.kolmafiaandroid.MainActivity)?.fitDialogAboveNav(d) }
        }.apply { textSize = 20f; contentDescription = "Full screen or lower panel" }
        expandRef = expandBtn
        for (b in listOf(newBtn, alertsBtn, closeChatBtn, expandBtn, doneBtn)) header.addView(b)
        root.addView(header)
        status = TextView(ctx).apply { textSize = 12f; setTextColor(muted); setPadding(dp(16), 0, dp(16), dp(6)); text = "Starting chat…" }
        root.addView(status)
        // conversation list
        listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(8)) }
        listScroll = ScrollView(ctx).apply { addView(listBox) }
        root.addView(listScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // conversation (bubbles)
        convoBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(6), dp(10), dp(10)) }
        convoScroll = ScrollView(ctx).apply { addView(convoBox) }
        root.addView(convoScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // composer
        composer = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(8)) }
        input = EditText(ctx).apply {
            hint = "Message"
            setHintTextColor(KC.hint)
            setTextColor(KC.text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 4
            setOnEditorActionListener { _, _, _ -> send(); true }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        composer.addView(MaterialButton(ctx).apply { text = "Send"; setOnClickListener { send() } })
        root.addView(composer)
        showMode()
        return com.example.kolmafiaandroid.ui.SwipeDismissLayout(ctx, dp(64)) {
            // swiping down: full screen first shrinks to the lower panel, then the panel closes
            if (panelFull) {
                panelFull = false
                requireContext().getSharedPreferences("kolmob", android.content.Context.MODE_PRIVATE).edit().putBoolean("chat_panel_full", false).apply()
                dialog?.let { d -> (activity as? com.example.kolmafiaandroid.MainActivity)?.fitDialogAboveNav(d) }
            } else dismiss()
        }.apply { addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
    }

    /** List view or conversation view: shows the matching header buttons and body. */
    private fun showMode() {
        val inConvo = selected != null
        backBtn.visibility = if (inConvo) View.VISIBLE else View.GONE
        closeChatBtn.visibility = if (inConvo) View.VISIBLE else View.GONE
        newBtn.visibility = if (inConvo) View.GONE else View.VISIBLE
        alertsBtn.visibility = if (inConvo) View.GONE else View.VISIBLE
        doneBtn.visibility = View.VISIBLE // the panel can always be closed
        listScroll.visibility = if (inConvo) View.GONE else View.VISIBLE
        convoScroll.visibility = if (inConvo) View.VISIBLE else View.GONE
        composer.visibility = if (inConvo) View.VISIBLE else View.GONE
        title.text = if (inConvo) label(selected!!) else "Chat"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val want = arguments?.getString("key")
        KolEngineHost.chatStart { running ->
            ui {
                if (!running) {
                    status.text = "Chat is not available. KoL requires the Altar of Literacy first."
                } else {
                    status.text = "Connecting…"
                    polling = true
                    handler.post(pollTask)
                    if (want != null) openThread(want)
                }
            }
        }
    }

    override fun onStop() {
        ChatNotifier.chatScreenOpen = false
        polling = false
        handler.removeCallbacks(pollTask)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (!polling && view != null && KolEngineHost.loggedIn) { polling = true; handler.post(pollTask) }
    }

    override fun onDestroyView() {
        if (panelDialog === dialog) panelDialog = null
        polling = false
        handler.removeCallbacks(pollTask)
        super.onDestroyView()
    }

    private val pollTask = object : Runnable {
        override fun run() {
            if (!polling || !isAdded) return
            refresh()
            handler.postDelayed(this, pollDelay)
        }
    }

    private fun updateStatus(json: String) {
        val o = try { JSONObject(json) } catch (_: Exception) { return }
        val delay = o.optInt("delay")
        if (delay <= 0) { status.text = "Connecting…"; return }
        pollDelay = maxOf(2000, delay).toLong()
        val secs = if (delay % 1000 == 0) "${delay / 1000}" else "%.1f".format(delay / 1000.0)
        if (o.optBoolean("away")) {
            status.text = "● Away mode · checking every ${secs}s. Say something to speed it back up."
            status.setTextColor(KC.pick(0xFFFFD54F.toInt(), 0xFF9A6B00.toInt()))
        } else {
            status.text = "● Live · checking every ${secs}s"
            status.setTextColor(KC.pick(0xFF81C784.toInt(), 0xFF2E7D32.toInt()))
        }
    }

    private fun refresh() {
        KolEngineHost.chatPoll { p -> ui { updateStatus(p) } }
        KolEngineHost.chatState { sj ->
            KolEngineHost.chatLengths { lj ->
                val o = try { JSONObject(sj) } catch (_: Exception) { return@chatLengths }
                val l = try { JSONObject(lj) } catch (_: Exception) { return@chatLengths }
                val list = (0 until (o.optJSONArray("channels")?.length() ?: 0)).map { o.getJSONArray("channels").getString(it) } +
                    (0 until (o.optJSONArray("buffers")?.length() ?: 0)).map { o.getJSONArray("buffers").getString(it) }
                val all = list.distinct()
                val newLens = l.optJSONObject("lens"); val newMsgs = l.optJSONObject("msgs")
                ui {
                    val now = System.currentTimeMillis()
                    keys = all
                    val changed = mutableListOf<String>()
                    for (k in all) {
                        val len = newLens?.optInt(k) ?: 0
                        msgs[k] = newMsgs?.optInt(k) ?: 0
                        if (lens[k] != len) {
                            if (lens.containsKey(k)) lastActive[k] = now
                            lens[k] = len
                            changed.add(k)
                        }
                    }
                    ChatNotifier.refreshUnread()
                    val sel = selected
                    if (sel != null) loadBuffer(sel) else {
                        renderList()
                        for (k in changed) loadSnippet(k, lens[k] ?: 0)
                    }
                }
            }
        }
    }

    /** The last line of a conversation, for the list. */
    private fun loadSnippet(key: String, len: Int) {
        KolEngineHost.chatText(key, maxOf(0, len - 900)) { json ->
            val raw = try { JSONObject(json).optString("html") } catch (_: Exception) { "" }
            val html = raw.substringAfter("<body>", raw).substringBeforeLast("</body>")
            val lines = html.split(Regex("(?i)<br\\s*/?>")).map { Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString().trim() }.filter { it.isNotEmpty() }
            ui { snippets[key] = lines.lastOrNull() ?: ""; if (selected == null) renderList() }
        }
    }

    private fun label(key: String) = if (key.startsWith("/")) "#" + key.removePrefix("/") else key.replace('_', ' ')

    private fun roundBg(color: Int, radiusDp: Int = 16, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun renderList() {
        listBox.removeAllViews()
        val ctx = requireContext()
        val visible = keys.filter { !ChatNotifier.isHidden(it) }
            .sortedWith(compareByDescending<String> { (ChatNotifier.unreadByKey[it] ?: 0) > 0 }
                .thenByDescending { lastActive[it] ?: 0L }
                .thenBy { it.startsWith("/") }.thenBy { it })
        if (visible.isEmpty()) {
            listBox.addView(TextView(ctx).apply {
                text = "No conversations open.\nTap ＋ New to join a channel or whisper a player."
                setTextColor(muted); textSize = 14f; setPadding(dp(16), dp(24), dp(16), 0)
            })
            return
        }
        for (k in visible) {
            val unread = ChatNotifier.unreadByKey[k] ?: 0
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(4), dp(10))
                background = roundBg(KC.pick(0xFF1E1E1E.toInt(), 0xFFFFFFFF.toInt()), 14)
                setOnClickListener { openThread(k) }
            }
            val avatar = TextView(ctx).apply {
                text = if (k.startsWith("/")) "#" else label(k).take(1).uppercase()
                gravity = Gravity.CENTER; textSize = 18f; setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (k.startsWith("/")) 0xFF5C6BC0.toInt() else 0xFF2E7D32.toInt()) }
            }
            row.addView(avatar, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(ctx).apply {
                text = label(k); textSize = 16f; setTextColor(KC.text); maxLines = 1
                if (unread > 0) setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            col.addView(TextView(ctx).apply {
                text = snippets[k] ?: ""; textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (unread > 0) KC.soft else muted)
            })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (unread > 0) {
                row.addView(TextView(ctx).apply {
                    text = if (unread > 99) "99+" else unread.toString()
                    textSize = 12f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    background = roundBg(green, 12)
                    setPadding(dp(8), dp(2), dp(8), dp(2)); minWidth = dp(24)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) })
            }
            row.addView(textBtn("✕") { closeThread(k) }.apply { setTextColor(muted) })
            listBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
    }

    private fun openThread(key: String) {
        ChatNotifier.unhide(requireContext(), key)
        if (!keys.contains(key)) keys = keys + key
        selected = key
        ChatNotifier.markRead(key)
        convoBox.removeAllViews()
        shown.remove(key)
        showMode()
        loadBuffer(key)
    }

    private fun closeConvo() {
        selected = null
        showMode()
        renderList()
        refresh()
    }

    /** Close a conversation: channels are left, whispers disappear until the player writes again. */
    private fun closeThread(key: String) {
        if (key.startsWith("/")) { leave(key); return }
        ChatNotifier.hide(requireContext(), key, msgs[key] ?: 0)
        if (selected == key) closeConvo() else renderList()
    }

    private fun isOwn(plain: String): Boolean {
        val me = KolEngineHost.currentUser
        val stamp = "^(\\[[^]]*]\\s*)?"
        if (Regex(stamp + "to\\s+[^:]{1,40}:", RegexOption.IGNORE_CASE).containsMatchIn(plain)) return true
        return me.isNotEmpty() && Regex(stamp + Regex.escape(me) + "(\\s*\\([^)]*\\))?:", RegexOption.IGNORE_CASE).containsMatchIn(plain)
    }

    private fun loadBuffer(key: String) {
        KolEngineHost.chatText(key, 0) { json ->
            val o = try { JSONObject(json) } catch (_: Exception) { return@chatText }
            val html = o.optString("html")
            val body = html.substringAfter("<body>", html).substringBeforeLast("</body>")
            ChatNotifier.markSeen(key, o.optInt("len"), o.optInt("msgs"))
            ui {
                if (key != selected) return@ui
                if (shown[key] == body) return@ui
                shown[key] = body
                val atBottom = !convoScroll.canScrollVertically(1) || convoBox.childCount == 0
                renderBubbles(key, body)
                if (atBottom) convoScroll.post { convoScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun renderBubbles(key: String, body: String) {
        convoBox.removeAllViews()
        val ctx = requireContext()
        val lines = body.split(Regex("(?i)<br\\s*/?>")).filter { it.isNotBlank() }.takeLast(300)
        if (lines.isEmpty()) {
            convoBox.addView(TextView(ctx).apply { text = "No messages yet in ${label(key)}."; setTextColor(muted); textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0) })
            return
        }
        val maxW = (resources.displayMetrics.widthPixels * 0.80).toInt()
        for (line in lines) {
            val sp = playerLinks(Html.fromHtml(line, Html.FROM_HTML_MODE_COMPACT, chatImages(key), null))
            val plain = sp.toString().trim()
            if (plain.isEmpty()) continue
            val own = isOwn(plain)
            val event = !own && !Regex("^(\\[[^]]*]\\s*)?[^:]{1,40}:").containsMatchIn(plain)
            val tv = TextView(ctx).apply {
                textSize = if (event) 12f else 15f
                setLinkTextColor(if (own) Color.WHITE else KC.pick(0xFF81C784.toInt(), 0xFF2E7D32.toInt()))
                movementMethod = LinkMovementMethod.getInstance()
                breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                if (own) {
                    val cs = android.text.SpannableStringBuilder(sp)
                    for (c in cs.getSpans(0, cs.length, android.text.style.ForegroundColorSpan::class.java)) cs.removeSpan(c)
                    text = cs
                    setTextColor(Color.WHITE)
                    background = roundBg(0xFF2E7D32.toInt(), 18)
                } else if (event) {
                    text = sp; setTextColor(muted); gravity = Gravity.CENTER
                } else {
                    text = sp; setTextColor(KC.soft)
                    background = roundBg(KC.pick(0xFF2A2A2A.toInt(), 0xFFFFFFFF.toInt()), 18)
                }
                if (!event) setPadding(dp(12), dp(8), dp(12), dp(8))
                this.maxWidth = if (event) resources.displayMetrics.widthPixels else maxW
            }
            convoBox.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(if (event) 6 else 4)
                gravity = if (event) Gravity.CENTER_HORIZONTAL else if (own) Gravity.END else Gravity.START
            })
        }
    }

    /** Emotes and other inline pictures in messages: drawn from the image cache, fetched in the background. */
    private fun chatImages(key: String) = Html.ImageGetter { src ->
        val ctx = requireContext()
        val bmp = com.example.kolmafiaandroid.ui.KolImages.peek(ctx, src)
        if (bmp == null) {
            com.example.kolmafiaandroid.ui.KolImages.prefetch(ctx, src) { if (isAdded) { shown.remove(key); loadBuffer(key) } }
            null
        } else {
            val h = dp(20)
            val w = (bmp.width * h.toFloat() / bmp.height).toInt().coerceAtLeast(1)
            android.graphics.drawable.BitmapDrawable(resources, bmp).apply { setBounds(0, 0, w, h) }
        }
    }

    /** Player names in chat open a small menu (whisper / profile) instead of a dead link. */
    private fun playerLinks(text: CharSequence): CharSequence {
        val sp = android.text.SpannableStringBuilder(text)
        for (u in sp.getSpans(0, sp.length, android.text.style.URLSpan::class.java)) {
            val start = sp.getSpanStart(u); val end = sp.getSpanEnd(u); val flags = sp.getSpanFlags(u)
            sp.removeSpan(u)
            if (!u.url.contains("showplayer.php")) continue
            val name = sp.subSequence(start, end).toString().trim()
            if (name.isEmpty()) continue
            // KoL colours names for a light page (often near-black); on the dark chat they would vanish
            for (c in sp.getSpans(start, end, android.text.style.ForegroundColorSpan::class.java)) sp.removeSpan(c)
            sp.setSpan(android.text.style.ForegroundColorSpan(KC.pick(0xFF8AB4F8.toInt(), 0xFF1A56C4.toInt())), start, end, flags)
            sp.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) { playerMenu(name) }
                override fun updateDrawState(ds: android.text.TextPaint) { /* keep the message's own colours */ }
            }, start, end, flags)
        }
        return sp
    }

    private fun playerMenu(name: String) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle(name)
            .setItems(arrayOf("Whisper", "View profile")) { _, i ->
                if (i == 0) {
                    val key = name.lowercase().replace(' ', '_')
                    if (!keys.contains(key)) keys = keys + key
                    openThread(key)
                } else ProfileFragment.show(parentFragmentManager, name)
            }.show()
    }

    private fun addMenu() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setItems(arrayOf("Join a channel…", "Whisper to a player…")) { _, i -> if (i == 0) joinChannel() else whisper() }
            .show()
    }

    private fun joinChannel() {
        val common = listOf("normal", "newbie", "trade", "games", "haiku", "hardcore", "clan", "Other…")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("Join a channel")
            .setItems(common.toTypedArray()) { _, i ->
                if (common[i] == "Other…") ask("Channel name") { join(it.trimStart('/')) } else join(common[i])
            }.setNegativeButton("Cancel", null).show()
    }

    private fun join(name: String) {
        if (name.isBlank()) return
        KolEngineHost.chatSend("", "/join $name") { activity?.runOnUiThread { handler.postDelayed({ refresh(); openThread("/" + name.lowercase()) }, 1500) } }
    }

    private fun whisper() = ask("Player name") { name ->
        val key = name.trim().lowercase().replace(' ', '_')
        if (key.isNotEmpty()) { if (!keys.contains(key)) { keys = keys + key }; openThread(key) }
    }

    private fun ask(title: String, onOk: (String) -> Unit) {
        val box = EditText(requireContext()).apply { inputType = InputType.TYPE_CLASS_TEXT; setPadding(dp(24), dp(16), dp(24), dp(8)) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> onOk(box.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    /** What should buzz your phone: DMs, mentions, chosen channels; off by default. */
    /** Words that get coloured in new chat messages (KoLmafia's own highlight list). */
    private fun showHighlights() {
        val ctx = requireContext()
        val pairs = KolEngineHost.getPref("highlightList").trim().split(Regex("\n+")).filter { it.isNotBlank() }.chunked(2).filter { it.size == 2 }.toMutableList()
        fun save() { KolEngineHost.setHighlights(pairs.joinToString("\n") { it[0] + "\n" + it[1] }) }
        val colours = listOf("Red" to "#FF5252", "Yellow" to "#FFD54F", "Green" to "#66BB6A", "Blue" to "#64B5F6", "Pink" to "#F48FB1")
        fun show() {
            val items = pairs.map { p -> p[0] + "   (" + (colours.firstOrNull { it.second.equals(p[1], true) }?.first ?: p[1]) + ")" }.toTypedArray()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle("Highlight words")
                .setItems(items) { _, i -> pairs.removeAt(i); save(); show() }
                .setPositiveButton("Add a word") { _, _ ->
                    ask("Word to highlight") { w ->
                        if (w.isBlank()) return@ask
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle("Colour for \"" + w.trim() + "\"")
                            .setItems(colours.map { it.first }.toTypedArray()) { _, c -> pairs.add(listOf(w.trim().lowercase(), colours[c].second)); save(); show() }.show()
                    }
                }
                .setNegativeButton("Done", null)
                .setMessage(if (pairs.isEmpty()) "No highlight words yet. Tap a word to remove it." else null)
                .show()
        }
        show()
    }

    private fun showAlertSettings() {
        val ctx = requireContext()
        val cur = com.example.kolmafiaandroid.ChatNotifier.config(ctx)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun text(t: String, size: Float = 13f, color: Int = muted) = TextView(ctx).apply { text = t; textSize = size; setTextColor(color); setPadding(0, dp(10), 0, dp(2)) }
        val master = android.widget.Switch(ctx).apply { text = "Notify me about new chat"; isChecked = cur.enabled; setTextColor(KC.text) }
        fun box(t: String, on: Boolean) = com.google.android.material.checkbox.MaterialCheckBox(ctx).apply { text = t; isChecked = on; setTextColor(KC.soft) }
        val dms = box("Whispers (direct messages)", cur.dms)
        val mentions = box("Mentions of my name in any channel", cur.mentions)
        val all = box("Every message in every channel", cur.allChannels)
        box.addView(master)
        val logSwitch = android.widget.Switch(ctx).apply { text = "Save chat logs (read them in Tools > Logs)"; setTextColor(KC.text); setPadding(0, dp(12), 0, 0) }
        logSwitch.isChecked = KolEngineHost.getPref("logChatMessages") == "true"
        box.addView(logSwitch)
        box.addView(text("Alert me for"))
        box.addView(dms); box.addView(mentions); box.addView(all)
        box.addView(text("Or only these channels"))
        val known = (keys.filter { it.startsWith("/") } + cur.channels).distinct().sorted().toMutableList()
        val channelBoxes = linkedMapOf<String, com.google.android.material.checkbox.MaterialCheckBox>()
        val channelHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun addChannel(k: String, on: Boolean) {
            if (channelBoxes.containsKey(k)) return
            channelBoxes[k] = box(k.removePrefix("/"), on).also { channelHolder.addView(it) }
        }
        known.forEach { addChannel(it, cur.channels.contains(it)) }
        box.addView(channelHolder)
        box.addView(MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "Highlight words…"
            setOnClickListener { showHighlights() }
        })
        box.addView(MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "Add a channel…"
            setOnClickListener { ask("Channel name") { n -> if (n.isNotBlank()) addChannel("/" + n.trim().trimStart('/').lowercase(), true) } }
        })
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle("Chat alerts").setView(android.widget.ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val c = com.example.kolmafiaandroid.ChatNotifier.Config(
                    master.isChecked, dms.isChecked, mentions.isChecked, all.isChecked,
                    channelBoxes.filterValues { it.isChecked }.keys.toSet()
                )
                com.example.kolmafiaandroid.ChatNotifier.save(ctx, c)
                KolEngineHost.setPref("logChatMessages", if (logSwitch.isChecked) "true" else "false")
                if (c.enabled && android.os.Build.VERSION.SDK_INT >= 33 &&
                    ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) requireActivity().requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 77)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun leave(key: String) {
        if (!key.startsWith("/")) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("Leave ${label(key)}?")
            .setPositiveButton("Leave") { _, _ ->
                KolEngineHost.chatSend("", "/leave ${key.removePrefix("/")}") {
                    ui { if (selected == key) closeConvo() ; handler.postDelayed({ refresh() }, 1200) }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun send() {
        val text = input.text.toString().trim()
        val key = selected ?: return
        if (text.isEmpty()) return
        input.setText("")
        KolEngineHost.chatSend(key, text) { ui { handler.postDelayed({ refresh() }, 900) } }
    }

    companion object {
        /** The open chat panel's window, so MainActivity can size it as a side panel instead of a full-screen page. */
        var panelDialog: android.app.Dialog? = null
        /** Part of the screen (false) or the whole width (true, the bottom bar still shows). */
        var panelFull = false
    }
}
