package com.claudecode.chatplugin.limits

import com.claudecode.chatplugin.stats.UsageStats
import com.claudecode.chatplugin.stats.UsageStatsReader
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Holds what is known about the account's rate-limit windows.
 *
 * The CLI reports a window's type, status and reset time once per turn, so the
 * knowledge is shared across chats rather than tied to one — a limit belongs to
 * the account, not to a conversation.
 *
 * No remaining-quota figure exists anywhere the plugin can read (not in the
 * stream, not in the transcripts, not in the CLI's own state), so instead of
 * inventing one this tracks **consumption since the window opened**, counted
 * from the CLI's transcripts.
 */
@Service(Service.Level.PROJECT)
class RateLimitService(private val project: Project) {

    private val windows = LinkedHashMap<String, RateLimitWindow>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Tokens used since the current window opened, per window type. */
    private val usedTokens = HashMap<String, Long>()
    private val lastUsageScan = AtomicLong(0)

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun fireChanged() = listeners.forEach { runCatching { it() } }

    /** Latest snapshot per window type, newest report wins. */
    fun windows(): List<RateLimitWindow> = synchronized(windows) { windows.values.toList() }

    fun primary(): RateLimitWindow? =
        windows().minByOrNull { it.resetsAtEpochSec ?: Long.MAX_VALUE }

    fun usedIn(window: RateLimitWindow): Long? = synchronized(usedTokens) { usedTokens[window.type] }

    fun update(window: RateLimitWindow) {
        val changed = synchronized(windows) { windows.put(window.type, window) != window }
        if (changed) {
            fireChanged()
            refreshUsage()
        }
    }

    /**
     * Recounts tokens spent inside each window, off the EDT.
     *
     * Reading every transcript is not free, so it runs at most once a minute —
     * the figure is a rolling total, not something that needs to be exact to
     * the second.
     */
    fun refreshUsage(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val last = lastUsageScan.get()
        if (!force && now - last < USAGE_SCAN_INTERVAL_MS) return
        if (!lastUsageScan.compareAndSet(last, now)) return

        ApplicationManager.getApplication().executeOnPooledThread {
            val entries = runCatching { UsageStatsReader.read() }.getOrNull() ?: return@executeOnPooledThread
            val totals = HashMap<String, Long>()
            for (window in windows()) {
                val start = window.startedAtMillis() ?: continue
                totals[window.type] = entries.filter { it.timestampMs >= start }.sumOf { it.total }
            }
            synchronized(usedTokens) {
                usedTokens.clear()
                usedTokens.putAll(totals)
            }
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) fireChanged()
            }
        }
    }

    /**
     * One line for the UI, e.g.
     * `5-hour window · resets in 2h 13m · 1.6M tokens used`.
     * Null when nothing has been reported yet.
     */
    fun summary(): String? {
        val window = primary() ?: return null
        val parts = mutableListOf(window.displayName() + " window")
        window.countdown()?.let { parts += "resets $it" }
        usedIn(window)?.let { parts += "${UsageStats.formatTokens(it)} tokens used" }
        if (!window.isHealthy) parts += window.status
        if (window.isUsingOverage) parts += "on overage"
        return parts.joinToString("  ·  ")
    }

    /** Explains, in the tooltip, why there is no "N% left" here. */
    fun explanation(): String = buildString {
        append("<html>")
        append("Claude Code reports when a limit window resets, and whether requests are ")
        append("currently allowed — but never how much of the limit is left. ")
        append("So this shows what has actually been spent since the window opened, ")
        append("counted from the CLI's own session transcripts.")
        windows().forEach { w ->
            append("<br><br><b>").append(w.displayName()).append("</b>: ").append(w.status)
            w.countdown()?.let { append(", resets ").append(it) }
        }
        append("</html>")
    }

    companion object {
        private const val USAGE_SCAN_INTERVAL_MS = 60_000L

        fun getInstance(project: Project): RateLimitService =
            project.getService(RateLimitService::class.java)
    }
}
