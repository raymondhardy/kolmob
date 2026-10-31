package com.example.kolmafiaandroid

import com.example.kolmafiaandroid.ui.KC

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * The in-game browser: Kingdom of Loathing served through KoLmafia's own relay server, so the
 * engine keeps tracking everything (and relay scripts keep working) while you play.
 *
 * It always shows the full site (the game.php frameset: character pane, menu, main pane, chat); there is no phone layout.
 */
class KolWebFragment : Fragment() {
    private var web: WebView? = null
    private lateinit var box: LinearLayout
    private lateinit var message: TextView
    private lateinit var shortcuts: HorizontalScrollView
    /** Slim HP / MP / fullness bar, shown only when the character pane is hidden ("Game only"). */
    private var statusBar: LinearLayout? = null
    private val vt = HashMap<String, TextView>()
    private val vitalsTick = object : Runnable {
        override fun run() {
            if (vitalsWanted()) refreshVitals()
            statusBar?.postDelayed(this, 4000)
        }
    }
    /** Optional: on a choice page, take the option set in Choice adventures after a 2-second countdown (tap the banner to cancel). */
    /** Native replacement for a web <select>: [json] is {title, labels[], selected[], multiple}. */
    private fun showSelectDialog(json: String) {
        val ctx = context ?: return
        val o = try { org.json.JSONObject(json) } catch (_: Exception) { return }
        val la = o.optJSONArray("labels") ?: return
        val sa = o.optJSONArray("selected")
        val labels = Array(la.length()) { la.optString(it).ifBlank { "(blank)" } }
        val checked = BooleanArray(labels.size) { sa?.optBoolean(it) ?: false }
        val title = o.optString("title").ifBlank { "Choose" }
        fun apply(idx: List<Int>) {
            web?.evaluateJavascript("window.__kolSelSet&&window.__kolSelSet([${idx.joinToString(",")}])", null)
        }
        val b = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx).setTitle(title)
        if (o.optBoolean("multiple")) {
            b.setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
                .setPositiveButton("OK") { _, _ -> apply(checked.indices.filter { checked[it] }) }
        } else {
            b.setSingleChoiceItems(labels, checked.indexOfFirst { it }) { d, i -> d.dismiss(); apply(listOf(i)) }
        }
        val d = b.setNegativeButton("Cancel", null).show()
        (activity as? com.example.kolmafiaandroid.MainActivity)?.fitDialogAboveNav(d)
    }

    private fun autoChoose(view: WebView) {
        Thread {
            if (KolEngineHost.getPref("kolmobBrowserAutoChoice") != "true") return@Thread
            view.post {
                view.evaluateJavascript("(function(){var e=document.querySelector('input[name=whichchoice]');return e?e.value:'';})()") { raw ->
                    val choice = raw?.trim('"')?.toIntOrNull() ?: return@evaluateJavascript
                    Thread {
                        val opt = KolEngineHost.getPref("choiceAdventure$choice").toIntOrNull() ?: 0
                        if (opt <= 0) return@Thread
                        view.post {
                            view.evaluateJavascript(AUTO_CHOICE_JS.replace("__OPT__", opt.toString()), null)
                        }
                    }.start()
                }
            }
        }.start()
    }

    private var port = -1
    private var fileCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    private val filePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    /** The phone's Back button walks the game's page history while this tab is showing. */
    private val backCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (overlay != null) { hideGuide(); isEnabled = web?.canGoBack() == true }
            else if (web?.canGoBack() == true) web?.goBack() else isEnabled = false
        }
    }

    /** Game shortcuts as icons: description (tooltip + accessibility), KoL page, icon. */
    private data class Shortcut(val desc: String, val path: String, val icon: Int)

    private val menu = listOf(
        Shortcut("Adventure map", "place.php?whichplace=town", R.drawable.web_map),
        Shortcut("Inventory", "inventory.php", R.drawable.web_bag),
        Shortcut("Character", "charsheet.php", R.drawable.web_person),
        Shortcut("Skills", "skills.php", R.drawable.web_bolt),
        Shortcut("Familiar", "familiar.php", R.drawable.web_paw),
        Shortcut("Trophies", "trophies.php", R.drawable.web_quests),
        Shortcut("Ascension history", "\u0000ascensions", R.drawable.web_person),
        Shortcut("KoL options", "account.php", R.drawable.web_gear),
    )

    /** The places people go most, one tap away: opens the game page for it. */
    private val places = listOf(
        "Town map" to "place.php?whichplace=town",
        "Market Square" to "place.php?whichplace=town_market",
        "Seaside Town (right side)" to "place.php?whichplace=town_right",
        "Wrong side of the tracks" to "place.php?whichplace=town_wrong",
        "The Distant Woods" to "place.php?whichplace=woods",
        "The Nearby Plains" to "place.php?whichplace=plains",
        "The Big Mountains" to "place.php?whichplace=mountains",
        "The Desert Beach" to "place.php?whichplace=desertbeach",
        "Council of Loathing" to "council.php",
        "Campground" to "campground.php",
        "Guild" to "guild.php",
        "The Mall" to "mall.php",
        "Clan hall" to "clan_hall.php",
        "Hagnk's storage" to "storage.php",
        "Display case" to "managecollection.php",
        "Hedge maze" to "hedgepuzzle.php",
        "Volcano maze" to "volcanomaze.php",
    )

    private fun showGoTo() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("Go to…")
            .setItems(places.map { it.first }.toTypedArray()) { _, i -> openInGame(places[i].second) }
            .setNegativeButton("Cancel", null).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // shortcut bar (the desktop top menu, in a phone-friendly form)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt()) }
        fun icon(res: Int, desc: String, onClick: () -> Unit): ImageButton {
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            return ImageButton(ctx).apply {
                setImageResource(res)
                setColorFilter(KC.text)
                contentDescription = desc
                tooltipText = desc
                setBackgroundResource(R.drawable.bg_icon_button) // outlined tile + ripple so it reads as a button
                scaleType = android.widget.ImageView.ScaleType.CENTER
                layoutParams = LinearLayout.LayoutParams((42 * dp).toInt(), (42 * dp).toInt()).apply { marginEnd = (4 * dp).toInt() }
                setOnClickListener { onClick() }
            }
        }
        row.addView(icon(R.drawable.web_back, "Back") { if (web?.canGoBack() == true) web?.goBack() })
        row.addView(icon(R.drawable.web_forward, "Forward") { if (web?.canGoForward() == true) web?.goForward() })
        row.addView(icon(R.drawable.web_reload, "Reload") { web?.reload() })
        row.addView(View(ctx).apply {
            setBackgroundColor(KC.faint)
            layoutParams = LinearLayout.LayoutParams((1 * dp).toInt(), (26 * dp).toInt()).apply { marginStart = (6 * dp).toInt(); marginEnd = (6 * dp).toInt() }
        })
        // only the map stays on the bar (handy if something covers the bottom tabs); Inventory and Character are the bottom tabs' job,
        // everything else lives in the hamburger menu
        row.addView(icon(R.drawable.web_map, "Go to a place") { showGoTo() })
        row.addView(icon(R.drawable.web_panes, "Side panes: all / hide announcements / game only") { cyclePanes() })
        row.addView(icon(R.drawable.web_guide, "Guide: what to do next") { showGuide(false) })
        row.addView(icon(R.drawable.web_quests, "Quests: what is in progress") {
            com.example.kolmafiaandroid.tools.QuestsFragment.show(requireActivity().supportFragmentManager)
        })
        row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f)) // pushes the hamburger to the right edge
        val more = icon(R.drawable.web_menu, "More pages") { }
        more.setOnClickListener { anchor ->
            val popup = android.widget.PopupMenu(ctx, anchor)
            menu.drop(3).forEachIndexed { i, sc -> popup.menu.add(0, i, 10 + i, sc.desc).setIcon(sc.icon) }
            popup.menu.add(0, 100, 110, "Text size…").setIcon(R.drawable.web_text_size)
            val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            if (landscape) { // the bottom tabs are hidden on the Game tab in landscape: offer them here
                popup.menu.add(0, 200, 0, "Character tab").setIcon(R.drawable.nav_character)
                popup.menu.add(0, 201, 1, "Adventure tab").setIcon(R.drawable.nav_adventure)
                popup.menu.add(0, 202, 2, "Scripts tab").setIcon(R.drawable.nav_console)
                popup.menu.add(0, 203, 3, "Chat, tools & settings").setIcon(R.drawable.ic_tools)
            }
            popup.setForceShowIcon(true)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    100 -> chooseTextSize()
                    in 200..202 -> (activity as? MainActivity)?.goToTab(item.itemId - 200)
                    203 -> (activity as? MainActivity)?.openMore()
                    else -> {
                        val path = menu.drop(3)[item.itemId].path
                        if (path == "\u0000ascensions") com.example.kolmafiaandroid.tools.ToolRegistry.find("ascensions")?.open?.invoke(requireActivity().supportFragmentManager)
                        else openInGame(path)
                    }
                }
                true
            }
            popup.show()
        }
        row.addView(more)
        shortcuts = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(row) }
        root.addView(shortcuts)

        // slim vitals bar: icons (drawn to match the game's) for HP, MP, adventures, meat and the three organs, standing in for the
        // character pane when it is hidden. Tap HP or MP to top off.
        fun vitalItem(key: String, icon: Int, desc: String, onClick: (() -> Unit)? = null): LinearLayout {
            val label = TextView(ctx).apply { text = "..."; textSize = 12f; setTextColor(KC.text); maxLines = 1 }
            vt[key] = label
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                contentDescription = desc
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = (5 * dp).toInt() }
                addView(android.widget.ImageView(ctx).apply {
                    setImageResource(icon)
                    setColorFilter(KC.text)
                    layoutParams = LinearLayout.LayoutParams((16 * dp).toInt(), (16 * dp).toInt()).apply { marginEnd = (2 * dp).toInt() }
                })
                addView(label)
                if (onClick != null) {
                    setBackgroundResource(R.drawable.bg_icon_button)
                    setPadding((5 * dp).toInt(), (5 * dp).toInt(), (5 * dp).toInt(), (5 * dp).toInt())
                    setOnClickListener { onClick() }
                } else {
                    setPadding((2 * dp).toInt(), (5 * dp).toInt(), (2 * dp).toInt(), (5 * dp).toInt())
                }
            }
        }
        fun restoreTap(command: String, what: String): () -> Unit = {
            android.widget.Toast.makeText(ctx, "Restoring $what...", android.widget.Toast.LENGTH_SHORT).show()
            KolEngineHost.execute(command) { activity?.runOnUiThread { if (isAdded) refreshVitals() } }
        }
        vt.clear()
        statusBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), (3 * dp).toInt(), (8 * dp).toInt(), (3 * dp).toInt())
            addView(vitalItem("hp", R.drawable.ic_vital_hp, "Hit points. Tap to restore", restoreTap("restore hp", "HP")))
            addView(vitalItem("mp", R.drawable.ic_vital_mp, "Mana. Tap to restore", restoreTap("restore mp", "MP")))
            addView(vitalItem("adv", R.drawable.ic_vital_adv, "Adventures left"))
            addView(vitalItem("food", R.drawable.ic_vital_food, "Fullness"))
            addView(vitalItem("drink", R.drawable.ic_vital_drink, "Liver"))
            addView(vitalItem("spleen", R.drawable.ic_vital_spleen, "Spleen"))
            addView(vitalItem("meat", R.drawable.ic_vital_meat, "Meat"))
            visibility = if (panePref().getInt("web_panes", 0) == paneCols.size - 1) View.VISIBLE else View.GONE
        }
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            // landscape: the screen is wide and short, so the vitals share the toolbar row (in the free space before the menu button)
            statusBar?.setPadding((8 * dp).toInt(), 0, 0, 0)
            row.addView(statusBar, row.indexOfChild(more) - 1)
        } else {
            // swipe sideways if the line is longer than the screen
            root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(statusBar) })
        }

        val frame = FrameLayout(ctx)
        val wv = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.useWideViewPort = true // honour the viewport meta we inject in phone layout
            settings.loadWithOverviewMode = true
            settings.textZoom = ctx.getSharedPreferences("kolmob", Context.MODE_PRIVATE).getInt("web_text_zoom", 115)
            // KoL opens item/effect/skill descriptions with window.open(); without this the WebView replaces the page with them
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // KoL's drop-downs and multi-selects: Android's own popup is blank on some phones and ignores our nav bar,
            // so every <select> tap is handed to a native list dialog that sits above the navigation.
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun open(json: String) {
                    activity?.runOnUiThread { if (isAdded) showSelectDialog(json) }
                }
            }, "KolSelect")
            // Daily Reminders buttons send "use <item>" to the chat box, which never opens the page that item shows
            // (the wish box, the pantogram, a choice). The page's own click is swapped for opening the item directly.
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun id(name: String): Int = KolEngineHost.itemId(name)
            }, "KolItem")
            // the Guide overlay: the page script fetches relay_Guide.ash, boils it down to cards and hands them back here
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun done(json: String) { activity?.runOnUiThread { if (isAdded) { guideJson = json; guideError = null; renderGuide() } } }
                @android.webkit.JavascriptInterface
                fun fail(msg: String) { activity?.runOnUiThread { if (isAdded) { guideError = msg; renderGuide() } } }
            }, "KolGuide")
            webChromeClient = object : WebChromeClient() {
                // window.open() (item, effect and skill descriptions): show it as a popup over the game, not instead of it
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                    val c = context ?: return false
                    val pop = WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = false // lay the description out at the popup's width so it stays readable
                        settings.textZoom = web?.settings?.textZoom ?: 100
                        webViewClient = WebViewClient()
                    }
                    val box = FrameLayout(c)
                    box.addView(pop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.45).toInt()))
                    val d = com.google.android.material.dialog.MaterialAlertDialogBuilder(c).setView(box).setPositiveButton("Close", null).create()
                    pop.webChromeClient = object : WebChromeClient() { override fun onCloseWindow(window: WebView) { d.dismiss() } }
                    d.setOnDismissListener { pop.destroy() }
                    d.show()
                    (resultMsg.obj as WebView.WebViewTransport).webView = pop
                    resultMsg.sendToTarget()
                    return true
                }

                // KoL pages use alert()/confirm(); the stock WebView dialogs are blank or missing here, so show our own.
                override fun onJsAlert(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                    if (!isAdded) { result.cancel(); return true }
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setMessage(message).setPositiveButton("OK") { _, _ -> result.confirm() }
                        .setOnCancelListener { result.cancel() }.show()
                    return true
                }

                override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult): Boolean {
                    if (!isAdded) { result.cancel(); return true }
                    val input = android.widget.EditText(requireContext()).apply {
                        setText(defaultValue ?: "")
                        setSelectAllOnFocus(true)
                        if (defaultValue?.toIntOrNull() != null) inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    }
                    val box = android.widget.FrameLayout(requireContext()).apply {
                        val pad = (20 * resources.displayMetrics.density).toInt()
                        setPadding(pad, 0, pad, 0)
                        addView(input)
                    }
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setMessage(message).setView(box)
                        .setPositiveButton("OK") { _, _ -> result.confirm(input.text.toString()) }
                        .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                        .setOnCancelListener { result.cancel() }.show()
                    return true
                }

                override fun onJsConfirm(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                    if (!isAdded) { result.cancel(); return true }
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setMessage(message).setPositiveButton("OK") { _, _ -> result.confirm() }
                        .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                        .setOnCancelListener { result.cancel() }.show()
                    return true
                }

                override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    return try {
                        filePicker.launch(params.createIntent())
                        true
                    } catch (_: Exception) {
                        fileCallback = null
                        false
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    if (request.url.scheme == "kolmob") { // links the app injects into the game page (HP in the icon row)
                        if (request.url.host == "restore") when (request.url.lastPathSegment) {
                            "hp" -> restoreTap("restore hp", "HP")()
                            "mp" -> restoreTap("restore mp", "MP")()
                        }
                        return true
                    }
                    val host = request.url.host ?: return false
                    val local = host == "127.0.0.1" || host == "localhost" || host.endsWith("kingdomofloathing.com")
                    if (local || request.url.scheme !in listOf("http", "https")) return false
                    try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)) } catch (_: Exception) {}
                    return true
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    backCallback.isEnabled = view.canGoBack() || overlay != null
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    view.evaluateJavascript(SELECT_JS, null)
                    if (url.contains("lchat.php")) view.evaluateJavascript("window.__kolmobNoWiden=true;", null)
                    if (url.contains("lchat.php")) view.evaluateJavascript(PHONE_JS, null)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(SELECT_JS, null)
                    if (url.contains("game.php")) resumePath?.let { p ->
                        resumePath = null
                        view.postDelayed({ view.evaluateJavascript("try{for(var i=0;i<top.frames.length;i++){if(top.frames[i].name==='mainpane'){top.frames[i].location='/" + p.replace("'", "%27") + "';}}}catch(e){}", null) }, 1500)
                    }
                    if (url.contains("choice.php")) autoChoose(view)
                    if (url.contains("game.php")) applyPanes()
                }
            }
            visibility = View.GONE
        }
        web = wv
        webFrame = frame
        frame.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        message = TextView(ctx).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(KC.soft)
            setPadding(48, 48, 48, 24)
        }
        box.addView(message)
        frame.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onResume() {
        super.onResume()
        backCallback.isEnabled = web?.canGoBack() == true
        updateStatusBar()
        statusBar?.removeCallbacks(vitalsTick)
        statusBar?.postDelayed(vitalsTick, 4000)
    }

    override fun onPause() {
        backCallback.isEnabled = false
        statusBar?.removeCallbacks(vitalsTick)
        super.onPause()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        // Debug hooks (adb broadcast, only in hook-enabled builds): open a path / run JS in the game view.
        viewLifecycleOwner.lifecycleScope.launch {
            KolEngineHost.webRequests.collect { req ->
                if (req.startsWith("zoom:")) applyTextZoom(req.removePrefix("zoom:").toInt())
                else if (req.startsWith("js:")) web?.evaluateJavascript(req.removePrefix("js:")) { Log.i("KolWeb", "js -> $it") }
                else if (port > 0) { KolEngineHost.pendingWeb = null; openInGame(req.removePrefix("/")) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            KolEngineHost.loginState.collect { loggedIn -> if (loggedIn) openGame() else showLoggedOut() }
        }
    }

    /**
     * Open a game page from outside the browser (a relay script such as the Guide, a tool). In the full-site layout it goes into the
     * main pane, so the character pane, menu and chat stay and the page finds its frames; loaded on its own, every link it opens
     * (an adventure, a fight) would run without frames and break the layout.
     */
    private fun openInGame(path: String) {
        if (path.startsWith("game.php") || path.startsWith("lchat")) { go(path); return }
        val js = "(function(){try{var f=null;for(var i=0;i<top.frames.length;i++){try{if(top.frames[i].name==='mainpane')f=top.frames[i];}catch(e){}}" +
            "if(f){f.location='/" + path.replace("\\", "\\\\").replace("'", "%27") + "';return 1;}}catch(e){}return 0;})()"
        web?.evaluateJavascript(js) { r ->
            if (r != "1") { resumePath = path; go("game.php") }
        }
    }

    private var resumePath: String? = null

    private fun applyTextZoom(percent: Int) {
        web?.settings?.textZoom = percent
        requireContext().getSharedPreferences("kolmob", Context.MODE_PRIVATE).edit().putInt("web_text_zoom", percent).apply()
    }

    private fun chooseTextSize() {
        val sizes = listOf("Small (90%)" to 90, "Normal (100%)" to 100, "Large (115%)" to 115, "Larger (135%)" to 135, "Huge (160%)" to 160, "Giant (200%)" to 200)
        val current = web?.settings?.textZoom ?: 100
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Text size in the game")
            .setSingleChoiceItems(sizes.map { it.first }.toTypedArray(), sizes.indexOfFirst { it.second == current }) { d, i ->
                applyTextZoom(sizes[i].second)
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun go(path: String) {
        if (port > 0) web?.loadUrl("http://127.0.0.1:$port/$path")
    }

    private fun loadStart() {
        val pending = KolEngineHost.pendingWeb
        if (pending != null) { KolEngineHost.pendingWeb = null; go(pending); return }
        go("game.php")
    }

    private fun showLoggedOut() {
        web?.visibility = View.GONE
        box.visibility = View.VISIBLE
        message.text = "Log in to open the game"
        port = -1
    }

    private fun openGame() {
        box.visibility = View.VISIBLE
        message.text = "Starting the relay browser..."
        KolEngineHost.startRelay { p ->
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (p <= 0) {
                    message.text = "Could not start the relay server (see Console)"
                    return@runOnUiThread
                }
                box.visibility = View.GONE
                web?.visibility = View.VISIBLE
                if (port != p) {
                    port = p
                    loadStart()
                }
            }
        }
    }

    // ---- side panes: game.php is a frameset (character pane | menu+main | announcements/chat); cycle which columns show ----
    private val paneCols = listOf("200,3*,300", "200,3*,0", "0,3*,0")
    private val paneNames = listOf("Sidebar and announcements", "Sidebar only (announcements hidden)", "Game only")

    private fun panePref() = requireContext().getSharedPreferences("kolmob", Context.MODE_PRIVATE)

    /** The vitals bar stands in for the character pane, so it shows exactly when that pane is hidden. */
    private fun updateStatusBar() {
        val hidden = vitalsWanted()
        statusBar?.visibility = if (hidden && !vitalsInMenu) View.VISIBLE else View.GONE
        if (hidden) refreshVitals()
        else if (vitalsInMenu) { // the character pane is back: take the vitals out of the game's icon row
            vitalsInMenu = false
            web?.evaluateJavascript(MENU_VITALS_JS.replace("__VALS__", "[]").replace("__REMOVE__", "true"), null)
        }
    }

    private fun vitalsWanted() = panePref().getInt("web_panes", 0).coerceIn(0, paneCols.size - 1) == paneCols.size - 1
    private var vitalsInMenu = false

    /** Full number below 10,000; above that 40k, 1.2M, 3.4B, 5T, 6Qa, 7Qi so trillions of meat still fit the bar. */
    private fun compactNumber(n: Long, plainBelow: Long = 1_000_000L): String {
        if (n < plainBelow) return String.format("%,d", n)
        val units = arrayOf("k", "M", "B", "T", "Qa", "Qi")
        var v = n.toDouble()
        var i = -1
        while (v >= 1000.0 && i < units.size - 1) { v /= 1000.0; i++ }
        // carry 999.96k up to 1M instead of showing "1000k"
        if (Math.round(v * 10) >= 10_000 && i < units.size - 1) { v /= 1000.0; i++ }
        val text = if (v >= 100) Math.round(v).toString() else String.format("%.1f", v).removeSuffix(".0")
        return text + units[i]
    }

    private fun refreshVitals() {
        KolEngineHost.vitals { json ->
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                try {
                    val o = org.json.JSONObject(json)
                    if (!o.has("hp")) return@runOnUiThread
                    vt["hp"]?.text = "${compactNumber(o.optLong("hp"), 10_000L)}/${compactNumber(o.optLong("maxHp"), 10_000L)}"
                    vt["mp"]?.text = "${compactNumber(o.optLong("mp"), 10_000L)}/${compactNumber(o.optLong("maxMp"), 10_000L)}"
                    vt["adv"]?.text = "${o.optInt("adv")}"
                    vt["meat"]?.text = compactNumber(o.optLong("meat"))
                    vt["food"]?.text = "${o.optInt("full")}/${o.optInt("fullLimit")}"
                    vt["drink"]?.text = "${o.optInt("drunk")}/${o.optInt("drunkLimit")}"
                    vt["spleen"]?.text = "${o.optInt("spleen")}/${o.optInt("spleenLimit")}"
                    if (vitalsWanted()) {
                        val vals = listOf("hp", "mp", "adv", "food", "drink", "spleen", "meat").joinToString(",", "[", "]") { "\"" + (vt[it]?.text?.toString().orEmpty().replace("\"", "")) + "\"" }
                        web?.evaluateJavascript(MENU_VITALS_JS.replace("__VALS__", vals).replace("__REMOVE__", "false")) { r ->
                            vitalsInMenu = r?.contains("ok") == true
                            statusBar?.visibility = if (vitalsInMenu) View.GONE else View.VISIBLE
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun applyPanes() {
        updateStatusBar()
        val i = panePref().getInt("web_panes", 0).coerceIn(0, paneCols.size - 1)
        web?.evaluateJavascript("(function(){try{var r=top.document.getElementById('rootset');if(r)r.cols='" + paneCols[i] + "';}catch(e){}})()", null)
    }

    private fun cyclePanes() {
        val next = (panePref().getInt("web_panes", 0) + 1) % paneCols.size
        panePref().edit().putInt("web_panes", next).apply()
        applyPanes()
        android.widget.Toast.makeText(requireContext(), paneNames[next], android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---- Guide overlay: cards from the Guide relay script; tap one to open its page in the game ----
    private var webFrame: FrameLayout? = null
    private var overlay: FrameLayout? = null
    private var overlayBody: LinearLayout? = null
    private var guideJson: String? = null
    private var guideError: String? = null
    private var guideLoading = false

    private fun hideGuide() {
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
        overlayBody = null
        backCallback.isEnabled = web?.canGoBack() == true
    }

    private fun showGuide(refresh: Boolean) {
        val ctx = context ?: return
        val frame = webFrame ?: return
        if (port <= 0) { android.widget.Toast.makeText(ctx, "Log in first", android.widget.Toast.LENGTH_SHORT).show(); return }
        hideGuide()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val scrim = FrameLayout(ctx).apply { setBackgroundColor(0xB3000000.toInt()); isClickable = true; setOnClickListener { hideGuide() } }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // taps inside the panel do not dismiss it
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(KC.pick(0xFF1E1E1E.toInt(), 0xFFF5F5F5.toInt())); cornerRadius = 16 * dp
            }
        }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(px(16), px(10), px(8), px(6)) }
        head.addView(TextView(ctx).apply { text = "Guide"; textSize = 20f; setTextColor(KC.text); typeface = android.graphics.Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun textBtn(label: String, onClick: () -> Unit) = TextView(ctx).apply {
            text = label; textSize = 15f; setTextColor(0xFF4CAF50.toInt()); setPadding(px(12), px(10), px(12), px(10))
            setOnClickListener { onClick() }
        }
        head.addView(textBtn("Refresh") { showGuide(true) })
        head.addView(textBtn("Close") { hideGuide() })
        panel.addView(head)
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), 0, px(12), px(12)) }
        panel.addView(android.widget.ScrollView(ctx).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        scrim.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            setMargins(px(10), px(10), px(10), px(10))
        })
        frame.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = scrim
        overlayBody = body
        backCallback.isEnabled = true
        if (refresh || guideJson == null) {
            guideJson = null; guideError = null
            if (!guideLoading) {
                guideLoading = true
                // our own bundled copy, written first so it is always there whatever the user has installed
                Thread {
                    try { com.example.kolmafiaandroid.ui.GuideBundle.install(ctx.applicationContext) } catch (_: Exception) {}
                    activity?.runOnUiThread { if (isAdded) web?.evaluateJavascript(GUIDE_JS, null) }
                }.start()
            }
        }
        renderGuide()
    }

    private fun renderGuide() {
        val ctx = context ?: return
        val body = overlayBody ?: return
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        body.removeAllViews()
        fun note(s: String) = body.addView(TextView(ctx).apply { text = s; textSize = 15f; setTextColor(KC.muted); setPadding(px(4), px(24), px(4), px(24)); gravity = Gravity.CENTER })
        val err = guideError
        val json = guideJson
        if (err != null) { guideLoading = false; note("The Guide could not be loaded: $err"); return }
        if (json == null) { note("Loading the Guide... the first load can take up to a minute."); return }
        guideLoading = false
        val sections = try { org.json.JSONArray(json) } catch (_: Exception) { note("The Guide returned something unreadable."); return }
        if (sections.length() == 0) { note("The Guide has nothing for you right now."); return }
        for (s in 0 until sections.length()) {
            val sec = sections.getJSONObject(s)
            val cards = sec.getJSONArray("c")
            body.addView(TextView(ctx).apply {
                text = sec.optString("s").ifBlank { "Guide" }.uppercase()
                textSize = 12f; setTextColor(KC.muted); typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(px(4), px(14), px(4), px(4))
            })
            for (i in 0 until cards.length()) {
                val c = cards.getJSONObject(i)
                val href = c.optString("h")
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(px(10), px(10), px(10), px(10))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(KC.pick(0xFF2A2A2A.toInt(), 0xFFFFFFFF.toInt())); cornerRadius = 12 * dp; setStroke(px(1), KC.faint)
                    }
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8) }
                    if (href.isNotEmpty()) setOnClickListener { hideGuide(); openInGame(href.removePrefix("/")) }
                }
                val img = android.widget.ImageView(ctx)
                card.addView(img, LinearLayout.LayoutParams(px(36), px(36)).apply { marginEnd = px(10) })
                val src = c.optString("i").removePrefix("/").removePrefix("images/")
                com.example.kolmafiaandroid.ui.KolImages.load(img, src)
                val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(ctx).apply { text = c.optString("t"); textSize = 15f; setTextColor(KC.text); typeface = android.graphics.Typeface.DEFAULT_BOLD })
                val b = c.optString("b")
                if (b.isNotBlank()) col.addView(TextView(ctx).apply { text = b; textSize = 13f; setTextColor(KC.soft); maxLines = 9; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (href.isNotEmpty()) col.addView(TextView(ctx).apply { text = "Tap to open"; textSize = 11f; setTextColor(0xFF4CAF50.toInt()); setPadding(0, px(2), 0, 0) })
                card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                body.addView(card)
            }
        }
    }

    override fun onDestroyView() {
        statusBar?.removeCallbacks(vitalsTick)
        statusBar = null; vt.clear()
        overlay = null; overlayBody = null; webFrame = null; guideLoading = false
        web?.destroy()
        web = null
        super.onDestroyView()
    }

    private companion object {
        /** Fetches the Guide relay page and reduces each checklist entry to {t: title, b: detail, i: icon, h: link}. */
        const val GUIDE_JS = """(function(){fetch('/relay_KolmobGuide.ash').then(function(r){return r.text();}).then(function(t){
var d=new DOMParser().parseFromString(t,'text/html'),out=[];
d.querySelectorAll('.r_cl_checklist_container').forEach(function(c){
 var h=c.querySelector('.r_cl_header_text'),cards=[];
 c.querySelectorAll('.r_cl_l_container').forEach(function(it){
  var st=it.querySelector('.r_cl_subheader');if(!st)return;
  var im=it.querySelector('img'),a=it.querySelector('a[href]'),co=it.querySelector('.r_cl_l_right_content'),hc=co&&co.querySelector('.r_cl_holding_container');
  var href=a?a.getAttribute('href'):'';if(/^(#|javascript)/i.test(href||''))href='';
  var bt='';if(hc){var cl=hc.cloneNode(true);cl.querySelectorAll('div,br,hr,li,p').forEach(function(e){e.insertAdjacentText('beforebegin','\n');});bt=cl.textContent.replace(/[ \t]+/g,' ').replace(/\s*\n\s*/g,'\n').trim().slice(0,300);}
  cards.push({t:st.textContent.trim(),b:bt,i:im?im.getAttribute('src'):'',h:href});});
 if(cards.length)out.push({s:h?h.textContent.trim():'',c:cards});});
KolGuide.done(JSON.stringify(out));}).catch(function(e){KolGuide.fail(String(e));});})()"""

        /** Intercepts taps on <select> and hands them to KolSelect.open; window.__kolSelSet writes the choice back. */
        const val SELECT_JS = """(function(){
var T=window.top;
if(T===window&&window.frames.length===0&&!/game\.php$/.test(location.pathname)&&!window.mainpane){var stub={location:{reload:function(){}},document:document};
 try{window.mainpane=window;window.charpane=window.charpane||stub;window.menupane=window.menupane||stub;window.chatpane=window.chatpane||stub;}catch(x){}}
if(!T.__kolSelSet){T.__kolSelSet=function(idx){var s=T.__kolSelEl;if(!s)return;
 for(var i=0;i<s.options.length;i++){s.options[i].selected=idx.indexOf(i)>=0;}
 s.dispatchEvent(new Event('input',{bubbles:true}));s.dispatchEvent(new Event('change',{bubbles:true}));};}
function hook(w){
 try{var d=w.document;if(!d||d.__kolSelHooked)return;d.__kolSelHooked=true;
 function find(e){var t=e.target;return t&&t.closest?t.closest('select'):null;}
 function open(e){var s=find(e);if(!s||s.disabled)return;e.preventDefault();e.stopPropagation();
  var labels=[],sel=[];for(var i=0;i<s.options.length;i++){labels.push(s.options[i].text);sel.push(s.options[i].selected);}
  var t=(s.getAttribute('aria-label')||s.name||'').replace(/[_-]/g,' ');
  T.__kolSelEl=s;KolSelect.open(JSON.stringify({title:t,labels:labels,selected:sel,multiple:s.multiple}));}
 d.addEventListener('click',function(e){var b=e.target&&e.target.closest?e.target.closest('.bfast'):null;if(!b)return;
  var m=/^use ([^0-9].*)$/i.exec((b.getAttribute('rel')||'').trim());if(!m)return;
  var id=0;try{id=KolItem.id(m[1]);}catch(x){}if(!(id>0))return;
  var k=/pwd: *"([0-9a-f]{32})"/.exec(d.documentElement.innerHTML);if(!k)return;
  e.preventDefault();e.stopPropagation();w.location.href='inv_use.php?which=3&whichitem='+id+'&pwd='+k[1];},true);
 d.addEventListener('mousedown',open,true);
 d.addEventListener('click',function(e){if(find(e)){e.preventDefault();}},true);}catch(x){}}
function walk(w){hook(w);try{for(var i=0;i<w.frames.length;i++){walk(w.frames[i]);}}catch(x){}}
walk(T);
if(!T.__kolSelTimer){T.__kolSelTimer=setInterval(function(){walk(T);},400);}
})();"""
        /** Banner with a 2-second countdown, then KoLmafia's own "auto" button for the choice (choice.php?action=auto). */
        /**
         * Puts all the vitals into the game's own icon row, after a divider next to the "Support the Kingdom" (Mr. Store) icon, as a
         * two-line grid (HP over MP, and so on). HP and MP are links that top off. Returns "ok" when it worked, so the app's own bar
         * can show them instead when it did not; "gone" after removing them.
         */
        private const val MENU_VITALS_JS = """(function(vals,remove){try{
var d=null;for(var i=0;i<top.frames.length;i++){if(top.frames[i].name==='menupane')d=top.frames[i].document;}
if(!d)return 'no';
var el=d.getElementById('kolmob-vitals');
if(remove){if(el)el.parentNode.removeChild(el);return 'gone';}
if(!el){
var img=d.querySelector('img[src$="donate.gif"]');if(!img)return 'no';
var a=img.closest('a')||img;
el=d.createElement('span');el.id='kolmob-vitals';
el.style.cssText='display:inline-grid;grid-template-rows:auto auto;grid-auto-flow:column;column-gap:5px;row-gap:0;margin-left:5px;padding-left:5px;border-left:1px solid #888;font:12px/14px sans-serif;position:relative;top:-9px;vertical-align:middle;white-space:nowrap;color:#222';
var spec=[["hp","restore/hp","Hit points - tap to restore","FM12,21.8 C5,16.4 1.6,12.8 1.6,8.7 C1.6,5.6 4,3.4 6.9,3.4 C9,3.4 10.9,4.5 12,6.3 C13.1,4.5 15,3.4 17.1,3.4 C20,3.4 22.4,5.6 22.4,8.7 C22.4,12.8 19,16.4 12,21.8 Z M5.6,7.6 h1.4 v2.4 h2.2 v-2.4 h1.4 v6.8 h-1.4 v-3 h-2.2 v3 h-1.4 Z M12.4,7.6 h3.4 q1.6,0 1.6,1.7 v0.4 q0,1.7 -1.6,1.7 h-2 v3 h-1.4 Z M13.8,8.9 v1.5 h1.8 q0.4,0 0.4,-0.4 v-0.7 q0,-0.4 -0.4,-0.4 Z"],["mp","restore/mp","Mana - tap to restore","FM16.36,1.47 L16.95,7.05 L22.53,7.64 L19.00,12.00 L22.53,16.36 L16.95,16.95 L16.36,22.53 L12.00,19.00 L7.64,22.53 L7.05,16.95 L1.47,16.36 L5.00,12.00 L1.47,7.64 L7.05,7.05 L7.64,1.47 L12.00,5.00 Z M4.6,14.8 V8 h1.4 l1.8,3 l1.8,-3 h1.4 v6.8 h-1.3 v-4.4 l-1.9,3.1 l-1.9,-3.1 v4.4 Z M12.8,8 h2.9 q1.6,0 1.6,1.6 v0.5 q0,1.6 -1.6,1.6 h-1.6 v3.1 h-1.3 Z M14.1,9.2 v1.4 h1.5 q0.3,0 0.3,-0.3 v-0.8 q0,-0.3 -0.3,-0.3 Z"],["adv","","Adventures left","FM4.6,2.6 h14.8 v2 h-14.8 Z M4.6,19.4 h14.8 v2 h-14.8 Z M6.8,4.6 h10.4 c0,3.2 -1.9,5.4 -3.7,7.4 c1.8,2 3.7,4.2 3.7,7.4 h-10.4 c0,-3.2 1.9,-5.4 3.7,-7.4 C8.7,10 6.8,7.8 6.8,4.6 Z M8.7,5.9 c0.2,1.8 1.3,3.2 2.5,4.6 h1.6 c1.2,-1.4 2.3,-2.8 2.5,-4.6 Z M8.4,17.9 c0.5,-2 1.8,-3.1 3.6,-4.5 c1.8,1.4 3.1,2.5 3.6,4.5 Z M9.9,17.9 c0.5,-1.2 1.2,-1.8 2.1,-2.5 c0.9,0.7 1.6,1.3 2.1,2.5 Z"],["food","","Fullness","FM4.6,2.6 h1.3 v5.2 h1.1 V2.6 h1.3 v5.2 h1.1 V2.6 h1.3 v6.4 c0,1.5 -1,2.6 -2.5,2.8 V21.4 H7 V11.8 C5.6,11.6 4.6,10.5 4.6,9 Z M15.2,2.6 c2.4,1.1 3.6,3.5 3.6,6.8 h-2.2 V21.4 h-1.4 Z"],["drink","","Liver","FM5.4,8.4 h9.6 V20.4 a1.4,1.4 0 0 1 -1.4,1.4 H6.8 a1.4,1.4 0 0 1 -1.4,-1.4 Z M15,10.6 h2 c1.3,0 2.2,0.9 2.2,2.1 v3.4 c0,1.2 -0.9,2.1 -2.2,2.1 H15 v-1.5 h1.9 c0.4,0 0.7,-0.3 0.7,-0.7 v-2.7 c0,-0.4 -0.3,-0.7 -0.7,-0.7 H15 Z M5,8.4 c-0.7,-2.2 0.8,-4 2.8,-3.8 c0.7,-1.4 2.7,-1.7 3.8,-0.6 c1.7,-0.6 3.6,0.6 3.5,2.5 c0,0.6 -0.2,1.4 -0.4,1.9 Z M7.3,10.8 h1.2 v7.4 h-1.2 Z M10.1,10.8 h1.2 v7.4 h-1.2 Z M12.9,10.8 h1.2 v7.4 h-1.2 Z"],["spleen","","Spleen","SM8.4,3.8 C14,2.6 20.4,6.2 20.2,12.2 C20,17.8 15.2,21.2 10.2,20.2 C8.4,19.8 7.8,18.6 8.6,17.4 C9.6,15.8 9.8,14.2 8.8,12.8 C7.6,11.2 5.8,10.8 5.4,9 C5,6.4 6.4,4.4 8.4,3.8 Z M8.8,12.8 L4.2,13.8 M8.8,12.8 L4.6,16.4 M11.6,7.4 C14.6,7.2 17.4,9.2 17.4,12.4"],["meat","","Meat","FM6.6,4.6 C9.6,1.9 15.2,2.4 18.2,6 C20.8,9.2 20.6,13.2 18,16.8 C15.9,19.8 12.2,21.6 9,20.4 C6.2,19.4 6.1,16.6 8.2,14.7 C10,13 10.1,11.2 8.2,9.7 C5.8,7.9 5,6.2 6.6,4.6 Z M10.2,6.4 C11.4,5.7 13,6 13.3,7.1 C13.6,8.1 12.6,8.9 11.5,8.9 C10.2,8.9 9.2,7.2 10.2,6.4 Z"]];
var html='';
for(var k=0;k<spec.length;k++){var c=spec[k];
var ic=c[3].charAt(0)==='S'?'<svg viewBox="0 0 24 24" width="13" height="13" style="margin-right:1px"><path fill="none" stroke="#222" stroke-width="1.5" stroke-linejoin="round" stroke-linecap="round" d="'+c[3].substr(1)+'"/></svg>':'<svg viewBox="0 0 24 24" width="13" height="13" style="margin-right:1px"><path fill="#222" fill-rule="evenodd" d="'+c[3].substr(1)+'"/></svg>';
var inner=ic+'<span id="kv-'+c[0]+'"></span>';
html+=c[1]?'<a href="kolmob://'+c[1]+'" title="'+c[2]+'" style="display:flex;align-items:center;color:#222;text-decoration:none">'+inner+'</a>':'<span title="'+c[2]+'" style="display:flex;align-items:center">'+inner+'</span>';}
el.innerHTML=html;
a.parentNode.insertBefore(el,a.nextSibling);
}
var order=['hp','mp','adv','food','drink','spleen','meat'];
for(var j=0;j<order.length;j++){var t=d.getElementById('kv-'+order[j]);if(t)t.textContent=vals[j];}
return 'ok';}catch(e){return 'err';}})(__VALS__,__REMOVE__)"""

        private const val AUTO_CHOICE_JS = """(function(v){if(window.__kolmobAuto)return;window.__kolmobAuto=true;
var b=document.createElement('div');b.textContent='Auto-choice: option '+v+' in 2 seconds (tap to cancel)';
b.style.cssText='position:fixed;top:0;left:0;right:0;z-index:99999;background:#2e7d32;color:#fff;padding:10px;text-align:center;font:14px sans-serif';
var t=setTimeout(function(){b.remove();location.href='choice.php?action=auto';},2000);
b.onclick=function(){clearTimeout(t);b.textContent='Auto-choice cancelled';setTimeout(function(){b.remove();},1200);};
document.body.appendChild(b);})(__OPT__)"""

        /**
         * Phone fitting for KoL pages. Normal pages get a device-width viewport. Pages that are wider than
         * the screen (the adventure map is a fixed-size picture with absolutely positioned hotspots) are
         * NOT squeezed: the viewport is widened to the page's natural width, so the WebView zooms the
         * whole page out to fit and pinch-zoom / panning keep working.
         */
        const val PHONE_JS = """
(function(){
  // chat is a fluid page: keep it at device width (readable text) instead of zooming out to a wide layout
  if(location.href.indexOf('lchat.php')>=0) window.__kolmobNoWiden=true;
  var m=document.querySelector('meta[name=viewport]');
  if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}
  m.content='width=device-width, initial-scale=1, minimum-scale=0.1, maximum-scale=5, user-scalable=yes';
  if(!document.getElementById('kolmob-css')){
    var s=document.createElement('style');s.id='kolmob-css';
    s.textContent='html{-webkit-text-size-adjust:100%} input,select,textarea{max-width:100%}';
    (document.head||document.documentElement).appendChild(s);
  }
  function natural(){
    var b=document.body, d=document.documentElement, w=Math.max(d.scrollWidth,b?b.scrollWidth:0);
    var all=document.querySelectorAll('img,table,div');
    for(var i=0;i<all.length&&i<400;i++){var r=all[i].getBoundingClientRect(); if(r.right>w) w=Math.ceil(r.right);}
    return w;
  }
  window.__kolmobFit=function(){
    var w=natural();
    if(!window.__kolmobNoWiden && w>window.innerWidth+8){
      m.content='width='+w+', initial-scale=0.1, minimum-scale=0.1, maximum-scale=5, user-scalable=yes';
    }
    return w+' meta='+m.content+' inner='+window.innerWidth;
  };
  window.__kolmobFit();
})();
"""
    }
}
