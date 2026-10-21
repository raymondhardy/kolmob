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
        override fun handleOnBackPressed() { if (web?.canGoBack() == true) web?.goBack() else isEnabled = false }
    }

    /** Game shortcuts as icons: description (tooltip + accessibility), KoL page, icon. */
    private data class Shortcut(val desc: String, val path: String, val icon: Int)

    private val menu = listOf(
        Shortcut("Adventure map", "place.php?whichplace=town", R.drawable.web_map),
        Shortcut("Inventory", "inventory.php", R.drawable.web_bag),
        Shortcut("Character", "charsheet.php", R.drawable.web_person),
        Shortcut("Skills", "skills.php", R.drawable.web_bolt),
        Shortcut("Quests", "questlog.php", R.drawable.web_quests),
        Shortcut("Familiar", "familiar.php", R.drawable.web_paw),
        Shortcut("Mall", "mall.php", R.drawable.web_cart),
        Shortcut("Chat", "lchat.php", R.drawable.web_chat),
        Shortcut("Clan", "clan_hall.php", R.drawable.web_clan),
        Shortcut("KoL options", "account.php", R.drawable.web_gear),
    )

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
        // the three most-used pages stay on the bar; everything else lives in the hamburger menu
        menu.take(3).forEach { sc -> row.addView(icon(sc.icon, sc.desc) { openInGame(sc.path) }) }
        row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f)) // pushes the hamburger to the right edge
        val more = icon(R.drawable.web_menu, "More pages") { }
        more.setOnClickListener { anchor ->
            val popup = android.widget.PopupMenu(ctx, anchor)
            menu.drop(3).forEachIndexed { i, sc -> popup.menu.add(0, i, i, sc.desc).setIcon(sc.icon) }
            popup.menu.add(0, 100, 100, "Text size…").setIcon(R.drawable.web_text_size)
            popup.setForceShowIcon(true)
            popup.setOnMenuItemClickListener { item ->
                if (item.itemId == 100) chooseTextSize() else openInGame(menu.drop(3)[item.itemId].path)
                true
            }
            popup.show()
        }
        row.addView(more)
        shortcuts = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(row) }
        root.addView(shortcuts)

        val frame = FrameLayout(ctx)
        val wv = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.useWideViewPort = true // honour the viewport meta we inject in phone layout
            settings.loadWithOverviewMode = true
            settings.textZoom = ctx.getSharedPreferences("kolmob", Context.MODE_PRIVATE).getInt("web_text_zoom", 115)
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
            webChromeClient = object : WebChromeClient() {
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
                    val host = request.url.host ?: return false
                    val local = host == "127.0.0.1" || host == "localhost" || host.endsWith("kingdomofloathing.com")
                    if (local || request.url.scheme !in listOf("http", "https")) return false
                    try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)) } catch (_: Exception) {}
                    return true
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    backCallback.isEnabled = view.canGoBack()
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
                }
            }
            visibility = View.GONE
        }
        web = wv
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
    }

    override fun onPause() {
        backCallback.isEnabled = false
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

    override fun onDestroyView() {
        web?.destroy()
        web = null
        super.onDestroyView()
    }

    private companion object {
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
