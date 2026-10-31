package com.example.kolmafiaandroid

import com.example.kolmafiaandroid.ui.KC

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.view.inputmethod.InputMethodManager
import com.google.android.material.textfield.TextInputLayout
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.kolmafiaandroid.databinding.ActivityMainBinding
import com.example.kolmafiaandroid.fragments.AdventureFragment
import com.example.kolmafiaandroid.fragments.DashboardFragment
import com.example.kolmafiaandroid.fragments.InventoryFragment
import com.example.kolmafiaandroid.tools.ToolsFragment
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Main activity: ViewPager2 + bottom navigation. Nothing works without a session, so a login
 * overlay covers the whole app until KoLmafia reports a logged-in character.
 */
class MainActivity : AppCompatActivity() {
    private var macroTick: Runnable? = null
    private val macroHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onDestroy() {
        macroTick?.let { macroHandler.removeCallbacks(it) }
        macroTick = null
        super.onDestroy()
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var loginOverlay: View
    private var pendingChatKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        com.example.kolmafiaandroid.ui.KC.refresh(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPager()
        setupBottomNavigation()
        setupChatHandle()
        com.example.kolmafiaandroid.ChatNotifier.onUnreadChanged = {
            val n = com.example.kolmafiaandroid.ChatNotifier.unreadTotal
            val badge = binding.bottomNavigation.getOrCreateBadge(R.id.nav_menu)
            badge.isVisible = n > 0
            if (n > 0) badge.number = n
            updateChatHandleBadge(n)
        }
        setupLoginOverlay()
        // fields that appear later (chat input, forms): clear the full-screen keyboard editor as soon as one takes focus
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, nf ->
            if (nf is android.widget.EditText) com.example.kolmafiaandroid.ui.LandscapeFit.noExtractUi(nf)
        }
        // first run: the use-at-your-own-risk agreement covers everything, including the login screen
        com.example.kolmafiaandroid.ui.Eula.showIfNeeded(this, findViewById(android.R.id.content))
        // Chat, Tools and the other full-screen dialogs must not cover the main navigation
        supportFragmentManager.registerFragmentLifecycleCallbacks(object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: androidx.fragment.app.FragmentManager, f: Fragment, v: View, savedInstanceState: android.os.Bundle?) {
                com.example.kolmafiaandroid.ui.LandscapeFit.noExtractUi(v)
                // landscape is short: let screens built for portrait scroll as one instead of squeezing their list (see LandscapeFit)
                if ((f is androidx.fragment.app.DialogFragment || f.parentFragment is androidx.fragment.app.DialogFragment) && resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                    com.example.kolmafiaandroid.ui.LandscapeFit.apply(v)
                }
            }
            override fun onFragmentStarted(fm: androidx.fragment.app.FragmentManager, f: Fragment) {
                (f as? androidx.fragment.app.DialogFragment)?.dialog?.let { fitDialogAboveNav(it) }
            }
            override fun onFragmentResumed(fm: androidx.fragment.app.FragmentManager, f: Fragment) {
                // again after the dialog's own onStart/onResume, and once more after layout, so nothing can undo it
                (f as? androidx.fragment.app.DialogFragment)?.dialog?.let { d ->
                    fitDialogAboveNav(d)
                    binding.root.post { fitDialogAboveNav(d) }
                }
            }
        }, true)
        pendingChatKey = intent?.getStringExtra("open_chat")
        try {
            val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            net.sourceforge.kolmafia.KolEngine.setDarkTheme(night)
        } catch (_: Throwable) { }
        lifecycleScope.launch { KolEngineHost.prompts.collect { showScriptPrompt(it) } }
        // keep the screen on while a script runs (the phone would otherwise go dark mid-script); can be switched off in Settings
        lifecycleScope.launch {
            KolEngineHost.running.collect { applyKeepAwake(it) }
        }
        // anything that asks for a KoL page (tools, quest helpers, ...) lands in the Game tab
        lifecycleScope.launch { KolEngineHost.webRequests.collect { dismissOpenDialogs(); openTab(2) } }
        lifecycleScope.launch {
            KolEngineHost.debugOpen.collect { id ->
                dismissOpenDialogs()
                val fm = supportFragmentManager
                val found = com.example.kolmafiaandroid.tools.ToolRegistry.find(id)
                if (found != null) found.open.invoke(fm) else when (id) {
                    "diet" -> com.example.kolmafiaandroid.tools.DietFragment.show(fm)
                    "quests" -> com.example.kolmafiaandroid.tools.QuestsFragment.show(fm)
                    "moods" -> com.example.kolmafiaandroid.tools.MoodFragment.show(fm)
                    "craft" -> com.example.kolmafiaandroid.tools.CraftFragment.show(fm)
                    "synth" -> com.example.kolmafiaandroid.tools.SynthesizeFragment.show(fm)
                    "fax" -> com.example.kolmafiaandroid.tools.FaxFragment.show(fm)
                    "chat" -> openChatPanel()
                }
            }
        }
    }

    private var promptDialog: androidx.appcompat.app.AlertDialog? = null

    /** A running script asked something (user_confirm / user_prompt / ...): there is no keyboard to type at, so ask here. */
    private fun showScriptPrompt(text: String) {
        if (promptDialog?.isShowing == true) return
        // blank arguments mean "no arguments": answer KoLmafia's missing-parameter question without asking
        if (KolEngineHost.blankParams) {
            Regex("Please input a value for (\\w+) (\\w+)").find(text)?.let { m ->
                KolEngineHost.answerPrompt(when (m.groupValues[1]) { "int", "float" -> "0"; "boolean" -> "false"; else -> " " })
                return
            }
        }
        val all = text.lines()
        val kind = all.firstOrNull() ?: "input"
        // the question is the last lines the script printed (minus KoLmafia's own hint lines)
        val shown = all.drop(1)
            .filter { it.isNotBlank() && !it.startsWith("Preference ") && !it.contains(" => ") && !it.startsWith("(Y/N") && !it.startsWith("(type your") && !it.startsWith("(leave blank") && !it.startsWith("Press enter") && !it.startsWith("Returned:") }
            .takeLast(3).joinToString("\n")
        val dp = resources.displayMetrics.density
        val b = com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(this).setTitle("A script is asking").setMessage(shown).setCancelable(false)
        when (kind) {
            "confirm" -> b.setPositiveButton("Yes") { _, _ -> KolEngineHost.answerPrompt("y") }
                .setNegativeButton("No") { _, _ -> KolEngineHost.answerPrompt("n") }
            "alert" -> b.setPositiveButton("OK") { _, _ -> KolEngineHost.answerPrompt(" ") }
            else -> {
                val input = EditText(this).apply { setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt()) }
                b.setView(input)
                    .setPositiveButton("Send") { _, _ -> KolEngineHost.answerPrompt(input.text.toString().ifEmpty { " " }) }
                    .setNegativeButton("Skip") { _, _ -> KolEngineHost.answerPrompt(" ") }
            }
        }
        promptDialog = b.show()
        // scripts that say "will default to 'No' in 15 seconds" (user_confirm with a timeout): honour it
        Regex("(?i)default(?:s)? to '?([A-Za-z]+)'? in (\\d+) seconds").find(text)?.let { m ->
            val secs = m.groupValues[2].toLongOrNull() ?: return@let
            val dflt = m.groupValues[1].lowercase()
            val dlg = promptDialog
            binding.root.postDelayed({
                if (dlg?.isShowing == true) {
                    dlg.dismiss()
                    KolEngineHost.answerPrompt(if (kind == "confirm") (if (dflt.startsWith("y")) "y" else "n") else " ")
                }
            }, secs * 1000)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("open_chat")?.let { key ->
            pendingChatKey = key
            if (KolEngineHost.loginState.value) openPendingChat()
        }
    }

    private fun openPendingChat() {
        val key = pendingChatKey ?: return
        pendingChatKey = null
        com.example.kolmafiaandroid.tools.ChatFragment().apply { arguments = android.os.Bundle().apply { putString("key", key) } }
            .show(supportFragmentManager, "chat")
    }

    // ---- "More" sheet: Chat, Quests, Tools, Settings, switch account / log out ----

    /** Make a full-screen dialog end where the bottom bar starts, and let touches below it reach the bar. */
    fun fitDialogAboveNav(dialog: android.app.Dialog) {
        if (dialog === com.example.kolmafiaandroid.tools.ChatFragment.panelDialog) { fitChatPanel(dialog); return }
        val w = dialog.window ?: return
        val loc = IntArray(2)
        binding.bottomNavigation.getLocationOnScreen(loc)
        var navTop = loc[1]
        if (navTop <= 0) navTop = resources.displayMetrics.heightPixels - binding.bottomNavigation.height - (48 * resources.displayMetrics.density).toInt()
        if (navTop <= 0) return
        w.setGravity(android.view.Gravity.TOP)
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, navTop)
        w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        // some dialog windows start below the status bar, which pushes their bottom past the bar: trim the overshoot
        val decor = w.decorView
        decor.post {
            val loc = IntArray(2)
            decor.getLocationOnScreen(loc)
            val over = loc[1] + decor.height - navTop
            if (over > 2) {
                val lp = w.attributes
                lp.height = decor.height - over
                w.attributes = lp
            }
        }
    }

    /** Chat is a panel on the lower part of the screen (the game stays playable above it) or full screen; the bottom bar always shows. */
    private fun fitChatPanel(dialog: android.app.Dialog) {
        val w = dialog.window ?: return
        val loc = IntArray(2)
        binding.bottomNavigation.getLocationOnScreen(loc)
        var navTop = loc[1]
        if (navTop <= 0) navTop = resources.displayMetrics.heightPixels - binding.bottomNavigation.height - (48 * resources.displayMetrics.density).toInt()
        if (navTop <= 0) return
        val dm = resources.displayMetrics
        w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        // landscape is short: a lower panel would leave a few lines for messages, so chat takes the whole height there
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (com.example.kolmafiaandroid.tools.ChatFragment.panelFull || landscape) {
            w.setGravity(android.view.Gravity.TOP)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, navTop)
            val lp = w.attributes; lp.y = 0; w.attributes = lp
        } else {
            val h = (navTop * 0.5f).toInt().coerceAtLeast((190 * dm.density).toInt()).coerceAtMost(navTop)
            w.setGravity(android.view.Gravity.TOP)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, h)
            val lp = w.attributes; lp.y = navTop - h; w.attributes = lp
        }
    }

    /** For tall centred dialogs (not full-screen): trim the height so the dialog ends above the bottom bar. */
    fun capDialogAboveNav(dialog: android.app.Dialog) {
        val w = dialog.window ?: return
        val decor = w.decorView
        decor.post {
            val nav = IntArray(2)
            binding.bottomNavigation.getLocationOnScreen(nav)
            val loc = IntArray(2)
            decor.getLocationOnScreen(loc)
            val limit = nav[1] - (8 * resources.displayMetrics.density).toInt()
            if (nav[1] > 0 && loc[1] + decor.height > limit) {
                // re-centre what fits in the space above the bar; what is taller starts at the top of the screen and is cut at the bar
                // (its list scrolls). Landscape is short, so pinning the top where a centred dialog started left only a sliver.
                val lp = w.attributes
                lp.gravity = android.view.Gravity.TOP
                if (decor.height <= limit) {
                    lp.y = ((limit - decor.height) / 2).coerceAtLeast(0)
                } else {
                    lp.y = 0
                    lp.height = limit
                }
                w.attributes = lp
            }
        }
    }

    /** Tapping the bottom bar leaves whatever full-screen dialog is open. */
    private fun dismissOpenDialogs() {
        for (f in supportFragmentManager.fragments) (f as? androidx.fragment.app.DialogFragment)?.dismissAllowingStateLoss()
        moreSheet?.let { if (it.isShowing) it.dismiss() } // the More sheet is a plain dialog, not a fragment
    }

    /** Pre-select a location on the Adventure tab and go there. */
    fun openAdventureAt(location: String, exact: Boolean = true) {
        val prefs = getSharedPreferences("kolmob", MODE_PRIVATE).edit()
        // an exact location is pre-selected; a zone or partial name opens the picker pre-filtered
        if (exact) prefs.putString("adv_location", location) else prefs.putString("adv_search", location)
        prefs.apply()
        dismissOpenDialogs()
        openTab(1)
    }

    /** Open a KoL page in the Game tab. */
    fun openGamePage(path: String) {
        KolEngineHost.pendingWeb = path
        KolEngineHost.webRequests.tryEmit(path)
        openTab(2)
    }

    private fun openTab(index: Int) {
        binding.viewPager.currentItem = index
    }

    private var moreSheet: com.google.android.material.bottomsheet.BottomSheetDialog? = null

    private fun showMoreSheet() {
        if (moreSheet?.isShowing == true) return // a second tap on the menu button must not stack another sheet
        val dp = resources.displayMetrics.density
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        moreSheet = sheet
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, (16 * dp).toInt())
        }
        fun row(icon: Int, title: String, subtitle: String, action: () -> Unit) {
            val r = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
                isClickable = true
                val tv = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { sheet.dismiss(); action() }
            }
            r.addView(android.widget.ImageView(this).apply { setImageResource(icon); setColorFilter(0xFF4CAF50.toInt()) },
                android.widget.LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt()).apply { marginEnd = (20 * dp).toInt() })
            val texts = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = title; textSize = 16f; setTextColor(KC.text) })
            if (subtitle.isNotEmpty()) texts.addView(TextView(this).apply { text = subtitle; textSize = 12f; setTextColor(KC.muted) })
            r.addView(texts)
            box.addView(r)
        }
        val unread = com.example.kolmafiaandroid.ChatNotifier.unreadTotal
        val threads = com.example.kolmafiaandroid.ChatNotifier.unreadTabs
        // the floating chat button replaces this row; it comes back only if the button is hidden
        if (!getSharedPreferences("kolmob", MODE_PRIVATE).getBoolean("chat_handle", true)) {
            row(R.drawable.web_chat, if (unread > 0) "Chat  ($unread)" else "Chat",
                if (unread > 0) "$unread new in $threads conversation${if (threads == 1) "" else "s"}" else "Channels, whispers and alerts") {
                com.example.kolmafiaandroid.tools.ChatFragment().show(supportFragmentManager, "chat")
            }
        }
        for (t in com.example.kolmafiaandroid.tools.ToolRegistry.tools.filter { it.home == null && it.group == "Social" }) {
            val icon = when (t.id) { "mail" -> R.drawable.web_chat; "clan" -> R.drawable.web_clan; else -> R.drawable.web_person }
            row(icon, t.title, t.subtitle.substringBefore(".").take(48)) { t.open(supportFragmentManager) }
        }
        row(R.drawable.ic_tools, "Advanced", "Logs, preferences, database, macros") { ToolsFragment.show(supportFragmentManager) }
        row(R.drawable.ic_settings, "Settings", "KoL display options and this app") { showSettings() }
        row(R.drawable.ic_logout, "Switch account or log out", KolEngineHost.currentUser) {
            autoLoginAllowed = false
            KolEngineHost.logout()
        }
        sheet.setContentView(android.widget.ScrollView(this).apply { addView(box) }) // scrolls when the screen is short (landscape)
        sheet.behavior.skipCollapsed = true
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.show()
        fitDialogAboveNav(sheet)
    }

    private fun applyKeepAwake(running: Boolean) {
        val wanted = running && getSharedPreferences("kolmob", MODE_PRIVATE).getBoolean("keep_awake", true)
        if (wanted) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showSettings() {
        val root = filesDir.resolve("kolmafia").absolutePath
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        val dp = resources.displayMetrics.density
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        fun note(t: String, size: Float = 12f) = TextView(this).apply { text = t; textSize = size; setTextColor(KC.muted) }
        box.addView(note("Theme", 13f))
        val themeGroup = android.widget.RadioGroup(this)
        val cur = KC.mode(this)
        for ((value, title) in listOf("system" to "Follow phone", "dark" to "Dark", "light" to "Light")) {
            themeGroup.addView(android.widget.RadioButton(this).apply {
                text = title
                setTextColor(KC.soft)
                isChecked = cur == value
                setOnClickListener {
                    getSharedPreferences("kolmob", MODE_PRIVATE).edit().putString("theme_mode", value).apply()
                    KC.apply(this@MainActivity)
                    recreate()
                }
            })
        }
        box.addView(themeGroup)
        box.addView(com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = "Keep the screen on while a script runs"
            setTextColor(KC.soft)
            isChecked = getSharedPreferences("kolmob", MODE_PRIVATE).getBoolean("keep_awake", true)
            setOnCheckedChangeListener { _, on ->
                getSharedPreferences("kolmob", MODE_PRIVATE).edit().putBoolean("keep_awake", on).apply()
                applyKeepAwake(KolEngineHost.running.value)
            }
        })
        box.addView(com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = "Show the chat button on the right edge"
            setTextColor(KC.soft)
            isChecked = getSharedPreferences("kolmob", MODE_PRIVATE).getBoolean("chat_handle", true)
            setOnCheckedChangeListener { _, on -> setChatHandleVisible(on) }
        })
        box.addView(com.google.android.material.checkbox.MaterialCheckBox(this).apply {
            text = "Start chat when I log in (so missed messages are counted)"
            setTextColor(KC.soft)
            isChecked = getSharedPreferences("kolmob", MODE_PRIVATE).getBoolean("chat_autostart", false)
            setOnCheckedChangeListener { _, on ->
                getSharedPreferences("kolmob", MODE_PRIVATE).edit().putBoolean("chat_autostart", on).apply()
            }
        })
        box.addView(note("Chat only polls KoL while you are active; once chat goes quiet (away mode) it stops until you open the panel, unless chat alerts are on.", 12f))
        box.addView(note("Keep logs for", 13f).apply { setPadding(0, (12 * dp).toInt(), 0, 0) })
        val keepSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, LogPruner.choices.map { it.second })
            setSelection(LogPruner.choices.indexOfFirst { it.first == LogPruner.keepDays(this@MainActivity) }.coerceAtLeast(0))
            var first = true
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (first) { first = false; return }
                    LogPruner.setKeepDays(this@MainActivity, LogPruner.choices[pos].first)
                    Thread {
                        val n = LogPruner.prune(this@MainActivity)
                        if (n > 0) runOnUiThread { android.widget.Toast.makeText(this@MainActivity, "Deleted $n old log file${if (n == 1) "" else "s"}", android.widget.Toast.LENGTH_SHORT).show() }
                    }.start()
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
        box.addView(keepSpinner)
        box.addView(note("Session, chat and debug logs older than this are deleted when you pick it and each time the app starts."))
        box.addView(note("KoL display options (these are your KoL account settings, so they also change the desktop site)", 13f).apply { setPadding(0, (12 * dp).toInt(), 0, 0) })
        val loading = note("Loading from KoL...")
        box.addView(loading)
        val dialog = com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(this)
            .setTitle("Settings")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Open console") { _, _ -> openTab(3) }
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
        KolEngineHost.interfaceOptions { json ->
            runOnUiThread {
                box.removeView(loading)
                val o = org.json.JSONObject(json)
                if (!o.optBoolean("ok")) {
                    box.addView(note("Could not read your KoL options."))
                } else {
                    val flags = listOf(
                        "flag_compactchar" to "Compact character pane",
                        "flag_shortcharpane" to "Short character pane",
                        "flag_compacteffects" to "Compact effect display",
                        "flag_slimhpmpdisplay" to "Slim HP/MP/Meat/Adv display"
                    )
                    for ((flag, title) in flags) {
                        box.addView(com.google.android.material.checkbox.MaterialCheckBox(this).apply {
                            text = title
                            setTextColor(KC.soft)
                            isChecked = o.optBoolean(flag)
                            setOnCheckedChangeListener { _, on -> KolEngineHost.setAccountOption(flag, if (on) "1" else "0") }
                        })
                    }
                    box.addView(note("Top menu style", 13f).apply { setPadding(0, (12 * dp).toInt(), 0, 0) })
                    val group = android.widget.RadioGroup(this)
                    val styles = listOf("fancy" to "Icons or text", "compact" to "Drop-downs", "normal" to "Links")
                    for ((value, title) in styles) {
                        group.addView(android.widget.RadioButton(this).apply {
                            text = title
                            setTextColor(KC.soft)
                            isChecked = o.optString("menu") == value
                            setOnClickListener { KolEngineHost.setAccountOption("menu", value) }
                        })
                    }
                    box.addView(group)
                    box.addView(note("Reload the Game tab to see changes.\nKolmob $version · data: $root", 11f).apply { setPadding(0, (12 * dp).toInt(), 0, 0) })
                }
            }
        }
    }

    // ---- login overlay ----

    private var autoLoginAllowed = true // only on a cold start; never right after an explicit logout

    private fun setupLoginOverlay() {
        val content = findViewById<ViewGroup>(android.R.id.content)
        loginOverlay = layoutInflater.inflate(R.layout.fragment_login, content, false).apply {
            isClickable = true // swallow touches so nothing underneath can be used
            isFocusable = true
        }
        content.addView(loginOverlay)

        val accounts = AccountStore(this)
        val accountsTitle = loginOverlay.findViewById<TextView>(R.id.accounts_title)
        val accountsBox = loginOverlay.findViewById<android.widget.LinearLayout>(R.id.accounts_container)
        val formTitle = loginOverlay.findViewById<TextView>(R.id.form_title)
        val userLayout = loginOverlay.findViewById<TextInputLayout>(R.id.username_layout)
        val passLayout = loginOverlay.findViewById<TextInputLayout>(R.id.password_layout)
        val user = loginOverlay.findViewById<EditText>(R.id.username_input)
        val pass = loginOverlay.findViewById<EditText>(R.id.password_input)
        val remember = loginOverlay.findViewById<CheckBox>(R.id.remember_check)
        val progress = loginOverlay.findViewById<View>(R.id.login_progress)
        val button = loginOverlay.findViewById<Button>(R.id.login_button)
        val status = loginOverlay.findViewById<TextView>(R.id.status_text)
        var engineReady = false

        fun setBusy(busy: Boolean) {
            progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
            button.isEnabled = !busy && engineReady
            user.isEnabled = !busy
            pass.isEnabled = !busy
            for (i in 0 until accountsBox.childCount) accountsBox.getChildAt(i).isEnabled = !busy && engineReady
        }

        fun signIn(name: String, password: String, rememberIt: Boolean) {
            status.text = "Signing in as $name…"
            setBusy(true)
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(loginOverlay.windowToken, 0)
            KolEngineHost.login(name, password) { ok ->
                runOnUiThread {
                    setBusy(false)
                    if (ok) {
                        status.text = ""
                        val real = KolEngineHost.currentUser.ifEmpty { name }
                        if (rememberIt) accounts.remember(real, password) else { accounts.add(real); accounts.forgetPassword(real) }
                        accounts.setLastUser(real)
                        pass.setText("")
                    } else {
                        status.text = ""
                        passLayout.error = "Sign-in failed. Check the name and password."
                    }
                }
            }
        }

        fun doLogin() {
            val u = user.text.toString().trim()
            val p = pass.text.toString()
            userLayout.error = if (u.isEmpty()) "Enter the username" else null
            passLayout.error = if (p.isEmpty()) "Enter the password" else null
            if (u.isEmpty() || p.isEmpty()) return
            signIn(u, p, remember.isChecked)
        }

        // The accounts on this phone: tap one to sign in (or to be asked for its password).
        fun rebuildAccounts() {
            val list = accounts.accounts()
            accountsBox.removeAllViews()
            val has = list.isNotEmpty()
            accountsTitle.visibility = if (has) View.VISIBLE else View.GONE
            accountsBox.visibility = if (has) View.VISIBLE else View.GONE
            formTitle.text = if (has) "Add or sign in to another account" else "Sign in"
            val dp = resources.displayMetrics.density
            for (a in list) {
                val label = android.text.SpannableString(a.name + "\n" + if (a.remembered) "Password saved. Tap to sign in" else "Tap to enter the password")
                label.setSpan(android.text.style.RelativeSizeSpan(0.75f), a.name.length, label.length, 0)
                accountsBox.addView(
                    com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = label
                        isAllCaps = false
                        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                        setIconResource(R.drawable.nav_character)
                        iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_START
                        isEnabled = engineReady
                        setOnClickListener {
                            val saved = if (a.remembered) accounts.password(a.name) else null
                            if (saved != null) {
                                signIn(a.name, saved, true)
                            } else {
                                user.setText(a.name)
                                pass.setText("")
                                status.text = "Enter the password for ${a.name}"
                                pass.requestFocus()
                                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(pass, 0)
                            }
                        }
                        setOnLongClickListener {
                            val options = (if (a.remembered) arrayOf("Forget the saved password", "Remove this account") else arrayOf("Remove this account"))
                            com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(this@MainActivity)
                                .setTitle(a.name)
                                .setItems(options) { _, i ->
                                    if (options[i].startsWith("Forget")) accounts.forgetPassword(a.name) else accounts.remove(a.name)
                                    rebuildAccounts()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                            true
                        }
                    },
                    android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (64 * dp).toInt()).apply { bottomMargin = (8 * dp).toInt() }
                )
            }
        }

        button.setOnClickListener { doLogin() }
        pass.setOnEditorActionListener { _, _, _ -> doLogin(); true }
        user.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { userLayout.error = null }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        pass.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { passLayout.error = null }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        lifecycleScope.launch {
            KolEngineHost.loginState.combine(KolEngineHost.readyState) { loggedIn, ready -> loggedIn to ready }
                .collect { (loggedIn, ready) ->
                    loginOverlay.visibility = if (loggedIn) View.GONE else View.VISIBLE
                    ChatNotifier.refreshService()
                    if (loggedIn) openPendingChat()
                    if (loggedIn) com.example.kolmafiaandroid.tools.MacroStore.runLoginMacros(applicationContext)
                    if (loggedIn && macroTick == null) {
                        // scheduled macros (daily, every N hours) are checked about once a minute while the app is open
                        // The loop holds only the application context and is cancelled in onDestroy; if it captured this activity
                        // (or outlived it) every screen rebuild would leak the old activity, its WebView and its views.
                        val app = applicationContext
                        val h = macroHandler
                        val r = object : Runnable { override fun run() { com.example.kolmafiaandroid.tools.MacroStore.runLoginMacros(app); h.postDelayed(this, 60_000) } }
                        h.postDelayed(r, 60_000); macroTick = r
                    }
                    if (loggedIn) return@collect
                    engineReady = ready
                    rebuildAccounts()
                    if (!ready) {
                        setBusy(true)
                        status.text = "Starting the KoLmafia engine…"
                    } else {
                        setBusy(false)
                        if (status.text.toString().startsWith("Starting")) status.text = ""
                        // Cold start: sign straight in as the last account if its password is saved.
                        val last = accounts.lastUser()
                        val saved = if (autoLoginAllowed && last != null && com.example.kolmafiaandroid.ui.Eula.accepted(this@MainActivity)) accounts.password(last) else null
                        if (saved != null) {
                            autoLoginAllowed = false
                            signIn(last!!, saved, true)
                        }
                    }
                }
        }
    }

    // ---- tabs ----

    private fun setupViewPager() {
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 4

            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> DashboardFragment()
                1 -> AdventureFragment()
                2 -> KolWebFragment()
                3 -> ConsoleFragment()
                else -> DashboardFragment()
            }
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                // The Game tab is a WebView that needs horizontal drags (maps, wide tables); the bottom bar
                // still switches tabs, so page swiping is turned off there.
                binding.viewPager.isUserInputEnabled = position != 2
                val item = when (position) {
                    0 -> R.id.nav_dashboard
                    1 -> R.id.nav_adventure
                    2 -> R.id.nav_combat
                    3 -> R.id.nav_scripts
                    else -> R.id.nav_dashboard
                }
                binding.bottomNavigation.menu.findItem(item)?.isChecked = true
                applyNavVisibility()
            }
        })
        binding.viewPager.post { applyNavVisibility() }
    }

    /** In landscape the Game tab takes the whole height: the bottom tabs hide there (the Game toolbar's menu has them). */
    private fun applyNavVisibility() {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        binding.bottomNavigation.visibility = if (landscape && binding.viewPager.currentItem == 2) View.GONE else View.VISIBLE
    }

    fun goToTab(index: Int) { dismissOpenDialogs(); binding.viewPager.currentItem = index }
    fun openMore() { showMoreSheet() }

    // ---- chat button: a tab on the right edge, on every screen; tap opens the chat panel ----

    private var chatHandle: View? = null
    private var chatBadge: TextView? = null

    fun openChatPanel() {
        if (supportFragmentManager.findFragmentByTag("chat") != null) return
        com.example.kolmafiaandroid.tools.ChatFragment().show(supportFragmentManager, "chat")
    }

    private fun updateChatHandleBadge(n: Int) {
        chatBadge?.apply { visibility = if (n > 0) View.VISIBLE else View.GONE; text = if (n > 99) "99+" else n.toString() }
    }

    fun setChatHandleVisible(show: Boolean) {
        getSharedPreferences("kolmob", MODE_PRIVATE).edit().putBoolean("chat_handle", show).apply()
        chatHandle?.visibility = if (show && KolEngineHost.currentUser.isNotEmpty()) View.VISIBLE else View.GONE
        reserveChatHandleStrip()
    }

    /** Landscape: the floating chat button would sit over the right edge of every screen (row arrows, the Run button), so leave it a strip of its own. */
    private fun reserveChatHandleStrip() {
        val lp = binding.viewPager.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val want = if (land && chatHandle?.visibility == View.VISIBLE) (30 * resources.displayMetrics.density).toInt() else 0
        if (lp.marginEnd != want) { lp.marginEnd = want; binding.viewPager.layoutParams = lp }
    }

    private fun setupChatHandle() {
        val prefs = getSharedPreferences("kolmob", MODE_PRIVATE)
        val dp = resources.displayMetrics.density
        val content = findViewById<ViewGroup>(android.R.id.content)
        val tab = android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xCC2E7D32.toInt())
                cornerRadii = floatArrayOf(14 * dp, 14 * dp, 0f, 0f, 0f, 0f, 14 * dp, 14 * dp)
            }
            elevation = 6 * dp
            contentDescription = "Chat"
            addView(android.widget.ImageView(this@MainActivity).apply { setImageResource(R.drawable.web_chat); setColorFilter(0xFFFFFFFF.toInt()) },
                android.widget.FrameLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt(), android.view.Gravity.CENTER).apply { marginStart = (2 * dp).toInt() })
        }
        val badge = TextView(this).apply {
            textSize = 10f; setTextColor(0xFFFFFFFF.toInt()); gravity = android.view.Gravity.CENTER
            setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFE53935.toInt()); cornerRadius = 9 * dp }
            visibility = View.GONE
        }
        tab.addView(badge, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, (18 * dp).toInt(), android.view.Gravity.TOP or android.view.Gravity.START).apply { topMargin = (2 * dp).toInt() })
        val lp = android.widget.FrameLayout.LayoutParams((30 * dp).toInt(), (60 * dp).toInt(), android.view.Gravity.END or android.view.Gravity.TOP)
        content.post { lp.topMargin = prefs.getInt("chat_handle_y", (content.height * 0.68f).toInt()).coerceIn(0, (content.height - (130 * dp)).toInt().coerceAtLeast(0)); tab.layoutParams = lp }
        lp.topMargin = prefs.getInt("chat_handle_y", (480 * dp).toInt())
        content.addView(tab, lp)
        chatHandle = tab
        chatBadge = badge
        // tap = open, drag up/down = move, long-press = hide
        var downY = 0f; var startMargin = 0; var moved = false; var longFired = false
        val longPress = Runnable {
            longFired = true
            com.example.kolmafiaandroid.ui.ScrollSafeDialogBuilder(this)
                .setTitle("Hide the chat button?")
                .setMessage("You can still open Chat from the More menu, or bring the button back in Settings.")
                .setPositiveButton("Hide") { _, _ -> setChatHandleVisible(false) }
                .setNegativeButton("Keep", null).show()
        }
        tab.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startMargin = lp.topMargin; moved = false; longFired = false; v.postDelayed(longPress, 700) }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (Math.abs(dy) > 12 * dp) { moved = true; v.removeCallbacks(longPress) }
                    if (moved) {
                        lp.topMargin = (startMargin + dy).toInt().coerceIn(0, (content.height - 60 * dp).toInt().coerceAtLeast(0))
                        v.layoutParams = lp
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (moved) prefs.edit().putInt("chat_handle_y", lp.topMargin).apply()
                    else if (!longFired) openChatPanel()
                }
                MotionEvent.ACTION_CANCEL -> v.removeCallbacks(longPress)
            }
            true
        }
        tab.visibility = View.GONE
        lifecycleScope.launch {
            KolEngineHost.loginState.collect {
                val show = prefs.getBoolean("chat_handle", true) && KolEngineHost.currentUser.isNotEmpty()
                tab.visibility = if (show) View.VISIBLE else View.GONE
                reserveChatHandleStrip()
            }
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemReselectedListener { dismissOpenDialogs() } // tapping the current tab closes a dialog too
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            dismissOpenDialogs()
            when (item.itemId) {
                R.id.nav_dashboard -> binding.viewPager.currentItem = 0
                R.id.nav_adventure -> binding.viewPager.currentItem = 1
                R.id.nav_combat -> binding.viewPager.currentItem = 2
                R.id.nav_scripts -> binding.viewPager.currentItem = 3
                R.id.nav_menu -> { showMoreSheet(); return@setOnItemSelectedListener false } // opens a sheet; does not stay selected
            }
            true
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
