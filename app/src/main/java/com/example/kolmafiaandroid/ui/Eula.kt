package com.example.kolmafiaandroid.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton

/** First-run "use at your own risk" agreement. Shown over everything, including the login screen, until accepted. */
object Eula {
    private const val VERSION = 1
    private const val KEY = "eula_accepted_version"

    fun accepted(ctx: Context): Boolean =
        try { ctx.getSharedPreferences("kolmob", Context.MODE_PRIVATE).getInt(KEY, 0) >= VERSION } catch (_: Exception) { false }

    val TEXT = """
END USER LICENSE AGREEMENT AND ASSUMPTION OF RISK

Please read this agreement carefully. By tapping "I Accept" you agree to all of it. If you do not agree, tap "Decline" and do not use this app.

1. WHAT THIS APP IS
This app ("Kolmob", "the App") is an unofficial, independent, experimental port of the open-source KoLmafia program to Android devices. It is not made, endorsed, sponsored or supported by Asymmetric Publications, LLC (the operator of the Kingdom of Loathing), by the KoLmafia project or its contributors, or by Google. All trademarks belong to their owners.

2. USE AT YOUR OWN RISK
You use the App entirely at your own risk. The App is experimental software and may contain bugs, crash, behave unpredictably, or stop working at any time, including after updates to the game, to Android, or to the App itself.

3. NO WARRANTY
THE APP IS PROVIDED "AS IS" AND "AS AVAILABLE", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, ACCURACY, NON-INFRINGEMENT, OR UNINTERRUPTED OR ERROR-FREE OPERATION.

4. YOUR DEVICE
The App runs long-lived background work, uses network, storage, memory and battery, and may run scripts written by other people. The authors are not responsible for any damage to your device or its software, including (without limitation) crashes, data corruption, boot problems, "bricking", overheating, battery drain or wear, storage exhaustion, excess data charges, or voided warranties.

5. YOUR GAME ACCOUNT, ITEMS AND PROGRESS
The App can spend adventures, meat, items and other resources, send messages and meat, buy, sell and use things, and change settings on your game account, either because you asked it to or because a script or automation you installed did. The authors are not responsible for any loss, gain, theft or deletion of meat, items, adventures, turns, ascensions, hardcore or other runs, leaderboard or ranking position, clan standing, messages, or any other game data, whether caused by the App, by KoLmafia code, by a script, by a bug, by a mistake, or by the game itself. Always check what an automation or script will do before you run it, and do not run anything you do not trust.

6. GAME RULES AND ACCOUNT ACTION
You are solely responsible for following the Kingdom of Loathing's terms of service and rules, and the rules of any clan, channel or community you take part in. Automation, third-party scripts and unusual use of the game may lead to warnings, restrictions or suspension of your account by the game's operators. The authors are not responsible for any such action.

7. YOUR CREDENTIALS
If you choose to save a login on your device, it is stored on the device only, protected by the platform's secure storage where available. You are responsible for the security of your device, your account, and your password. The authors are not responsible for unauthorised access that results from lost, stolen, shared, rooted or compromised devices.

8. THIRD-PARTY SOFTWARE AND SCRIPTS
The App contains and can download code, scripts and data from third parties, including the KoLmafia project and script repositories. They are provided under their own licences and terms, and the authors do not review or control them and make no promises about them or their safety.

9. LIMITATION OF LIABILITY
TO THE FULLEST EXTENT PERMITTED BY LAW, IN NO EVENT SHALL THE AUTHORS, CONTRIBUTORS OR DISTRIBUTORS OF THE APP BE LIABLE FOR ANY CLAIM, DAMAGES OR LOSS OF ANY KIND, WHETHER DIRECT, INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL, EXEMPLARY OR PUNITIVE, INCLUDING LOSS OF DATA, ITEMS, MEAT, PROGRESS, PROFITS, OR DEVICE FUNCTION, ARISING FROM OR RELATED TO THE APP OR ITS USE OR INABILITY TO USE IT, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGES, AND WHETHER IN CONTRACT, TORT (INCLUDING NEGLIGENCE) OR OTHERWISE. Some places do not allow some of these limits, so they apply only as far as the law of your place allows.

10. INDEMNITY
You agree to hold the authors and contributors harmless from any claim arising out of your use of the App or your breach of this agreement, as far as the law allows.

11. BACK UP AND BE CAREFUL
Keep your own backups. Consider trying new features and scripts on a throwaway account first.

12. CHANGES AND ENDING
This agreement may change in later versions of the App, and you may be asked to accept it again. You can stop using the App at any time by uninstalling it.

13. WHOLE AGREEMENT
This is the whole agreement about the App between you and its authors. If any part is found unenforceable, the rest stays in effect.
""".trimIndent()

    /** Adds the agreement over [root] unless it was already accepted. [onAccepted] runs after the user accepts. */
    fun showIfNeeded(activity: Activity, root: ViewGroup, onAccepted: () -> Unit = {}) {
        if (accepted(activity)) return
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val overlay = FrameLayout(activity).apply {
            setBackgroundColor(activity.getColor(com.example.kolmafiaandroid.R.color.colorSurface))
            isClickable = true
            isFocusable = true
            // swallow touches so nothing underneath can be used before accepting
            setOnTouchListener { _, _ -> true }
        }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(36), px(20), px(16))
        }
        col.addView(TextView(activity).apply {
            text = "Before you start"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(KC.text)
        })
        col.addView(TextView(activity).apply {
            text = "Use this app at your own risk."
            textSize = 14f
            setTextColor(KC.muted)
            setPadding(0, px(4), 0, px(12))
        })
        col.addView(ScrollView(activity).apply {
            addView(TextView(activity).apply {
                text = TEXT
                textSize = 13f
                setTextColor(KC.soft)
                setLineSpacing(0f, 1.15f)
                setPadding(px(2), px(2), px(2), px(12))
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val agree = CheckBox(activity).apply {
            text = "I have read this and understand I am using the app at my own risk."
            setTextColor(KC.text)
        }
        col.addView(agree)
        val accept = MaterialButton(activity).apply { text = "I Accept"; isEnabled = false }
        val decline = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "Decline and exit" }
        agree.setOnCheckedChangeListener { _, c -> accept.isEnabled = c }
        accept.setOnClickListener {
            try { activity.getSharedPreferences("kolmob", Context.MODE_PRIVATE).edit().putInt(KEY, VERSION).apply() } catch (_: Exception) {}
            root.removeView(overlay)
            onAccepted()
        }
        decline.setOnClickListener { activity.finishAffinity() }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(decline, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(8) })
        row.addView(accept, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)
        overlay.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}
