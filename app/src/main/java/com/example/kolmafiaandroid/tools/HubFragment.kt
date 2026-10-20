package com.example.kolmafiaandroid.tools

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.example.kolmafiaandroid.MainActivity
import com.example.kolmafiaandroid.R
import com.example.kolmafiaandroid.ui.KC
import com.example.kolmafiaandroid.ui.dp
import com.example.kolmafiaandroid.ui.label
import com.example.kolmafiaandroid.ui.textTabs

/**
 * One screen for a family of related things, with a tab per part (mail: Inbox / Write; clan: Stash / Fax / Hall...).
 * A part is either a native screen shown inside the hub or one of KoL's own pages (opens on the Game tab).
 */
abstract class HubFragment : DialogFragment() {
    class Section(val label: String, val fragment: (() -> Fragment)? = null, val page: String? = null)

    abstract val hubTitle: String
    abstract fun sections(): List<Section>

    private val hostId = View.generateViewId()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialogStyle)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).also { it.requestWindowFeature(Window.FEATURE_NO_TITLE) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val secs = sections()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(KC.pick(0xFF121212.toInt(), 0xFFF2F2F2.toInt())) }
        root.addView(label(ctx, hubTitle, 20f, 0xFF4CAF50.toInt(), true).apply { setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 4)) })
        var current = -1
        val host = FrameLayout(ctx).apply { id = hostId }
        fun show(i: Int) {
            val s = secs[i]
            if (s.page != null) {
                dismissAllowingStateLoss()
                (activity as? MainActivity)?.openGamePage(s.page)
                return
            }
            if (i == current) return
            current = i
            childFragmentManager.beginTransaction().replace(hostId, s.fragment!!(), "hub$i").commitAllowingStateLoss()
        }
        val start = (arguments?.getInt("tab") ?: 0).coerceIn(0, secs.size - 1)
        val tabs = textTabs(ctx, secs.map { it.label }, start) { show(it) }
        tabs.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
        root.addView(tabs)
        root.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        if (savedInstanceState == null) show(start)
        return root
    }
}

/** Kmail: read your inbox or write a message. */
class MailHubFragment : HubFragment() {
    override val hubTitle = "Mail"
    override fun sections() = listOf(
        Section("Inbox", { InboxFragment() }),
        Section("Write", { MailFragment() }),
    )

    companion object {
        fun show(fm: androidx.fragment.app.FragmentManager, tab: Int = 0) =
            MailHubFragment().apply { arguments = Bundle().apply { putInt("tab", tab) } }.show(fm, "mailhub")
    }
}

/** Everything about your clan in one place. */
class ClanHubFragment : HubFragment() {
    override val hubTitle = "Clan"
    override fun sections() = listOf(
        Section("Stash", { ClanFragment() }),
        Section("Fax", { FaxFragment() }),
        Section("Hall", page = "clan_hall.php"),
        Section("Whitelist", page = "clan_signup.php"),
    )

    companion object {
        fun show(fm: androidx.fragment.app.FragmentManager, tab: Int = 0) =
            ClanHubFragment().apply { arguments = Bundle().apply { putInt("tab", tab) } }.show(fm, "clanhub")
    }
}
