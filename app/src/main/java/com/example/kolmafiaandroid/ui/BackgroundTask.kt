package com.example.kolmafiaandroid.ui

import androidx.fragment.app.FragmentActivity
import com.example.kolmafiaandroid.KolEngineHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * One long engine task started from a screen (a fax request, for one). The screen shows its progress while open;
 * when the screen is hidden, MainActivity shows a small spinner tab on the edge that reopens it.
 */
object BackgroundTask {
    class Task(val title: String, val started: Long, val reopen: (FragmentActivity) -> Unit)

    val current = MutableStateFlow<Task?>(null)
    /** True while the task's own screen is open, so the edge tab stays out of the way. */
    val screenOpen = MutableStateFlow(false)
    /** KoLmafia's latest console line since the task started. */
    val lastLine = MutableStateFlow("")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lines: Job? = null

    fun start(title: String, reopen: (FragmentActivity) -> Unit) {
        val skip = KolEngineHost.lines.replayCache.size
        lastLine.value = ""
        current.value = Task(title, System.currentTimeMillis(), reopen)
        lines?.cancel()
        lines = scope.launch {
            KolEngineHost.lines.drop(skip).collect { line ->
                // KoLmafia's lines can carry HTML comments (e.g. "<!-- monsterid: 1 -->"), escaped or cut off at the line end
                val plain = android.text.Html.fromHtml(line, 0).toString().replace(Regex("<!--[\\s\\S]*?(-->|$)"), "").trim()
                // "Returned: ..." is the ASH snippet's own result, not news from KoLmafia
                if (plain.isNotEmpty() && !plain.startsWith("Returned:")) lastLine.value = plain
            }
        }
    }

    fun finish() {
        lines?.cancel(); lines = null
        current.value = null
    }
}
