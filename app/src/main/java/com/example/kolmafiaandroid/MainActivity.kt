package com.example.kolmafiaandroid

import com.example.kolmafiaandroid.ui.KC

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
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
        com.example.kolmafiaandroid.ChatNotifier.onUnreadChanged = {
            val n = com.example.kolmafiaandroid.ChatNotifier.unreadTotal
            val badge = binding.bottomNavigation.getOrCreateBadge(R.id.nav_menu)
            badge.isVisible = n > 0
            if (n > 0) badge.number = n
        }
        setupLoginOverlay()
        // first run: the use-at-your-own-risk agreement covers everything, including the login screen
        com.example.kolmafiaandroid.ui.Eula.showIfNeeded(this, findViewById(android.R.id.content))
        // Chat, Tools and the other full-screen dialogs must not cover the main navigation
        supportFragmentManager.registerFragmentLifecycleCallbacks(object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
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
        lifecycleScope.launch { KolEngineHost.prompts.collect { showScriptPrompt(it) } }
        // anything that asks for a KoL page (tools, quest helpers, ...) lands in the Game tab
        lifecycleScope.launch { KolEngineHost.webRequests.collect { dismissOpenDialogs(); openTab(2) } }
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
        val b = com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("A script is asking").setMessage(shown).setCancelable(false)
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
                // centred dialogs shrink around their middle: pin the top where it is and cut the bottom
                val lp = w.attributes
                lp.gravity = android.view.Gravity.TOP
                lp.y = loc[1]
                lp.height = limit - loc[1]
                w.attributes = lp
            }
        }
    }

    /** Tapping the bottom bar leaves whatever full-screen dialog is open. */
    private fun dismissOpenDialogs() {
        for (f in supportFragmentManager.fragments) (f as? androidx.fragment.app.DialogFragment)?.dismissAllowingStateLoss()
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

    private fun showMoreSheet() {
        val dp = resources.displayMetrics.density
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
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
        row(R.drawable.web_chat, if (unread > 0) "Chat  ($unread)" else "Chat",
            if (unread > 0) "$unread new in $threads conversation${if (threads == 1) "" else "s"}" else "Channels, whispers and alerts") {
            com.example.kolmafiaandroid.tools.ChatFragment().show(supportFragmentManager, "chat")
        }
        row(R.drawable.ic_tools, "Tools", "Items, maximizer, moods, database, logs…") { ToolsFragment.show(supportFragmentManager) }
        row(R.drawable.ic_settings, "Settings", "KoL display options and this app") { showSettings() }
        row(R.drawable.ic_logout, "Switch account or log out", KolEngineHost.currentUser) {
            autoLoginAllowed = false
            KolEngineHost.logout()
        }
        sheet.setContentView(box)
        sheet.show()
        fitDialogAboveNav(sheet)
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
        box.addView(note("KoL display options (these are your KoL account settings, so they also change the desktop site)", 13f).apply { setPadding(0, (12 * dp).toInt(), 0, 0) })
        val loading = note("Loading from KoL...")
        box.addView(loading)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@MainActivity)
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
                        val h = android.os.Handler(android.os.Looper.getMainLooper())
                        val r = object : Runnable { override fun run() { com.example.kolmafiaandroid.tools.MacroStore.runLoginMacros(applicationContext); h.postDelayed(this, 60_000) } }
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
            }
        })
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
