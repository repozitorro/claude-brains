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

    /** Percentages, straight from the CLI's own `/usage` report. */
    @Volatile
    private var bars: List<LimitBar> = emptyList()
    private val lastBarsRead = AtomicLong(0)

    fun limitBars(): List<LimitBar> = bars

    /**
     * Refreshes the percentages by asking the CLI.
     *
     * Each call spawns the CLI briefly and leaves a (zero-token) session
     * transcript behind, so it is rate-limited to once a minute and skipped
     * entirely when the panel isn't on screen.
     */
    fun refreshBars(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val last = lastBarsRead.get()
        if (!force && now - last < BARS_INTERVAL_MS) return
        if (!lastBarsRead.compareAndSet(last, now)) return

        ApplicationManager.getApplication().executeOnPooledThread {
            val fresh = UsageLimitsReader.read(project)
            if (fresh.isNotEmpty() && fresh != bars) {
                bars = fresh
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) fireChanged()
                }
            }
        }
    }

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
        // The CLI's own percentages when they're available — they are the real
        // answer to "how much is left" and nothing here can improve on them.
        val bars = limitBars()
        if (bars.isNotEmpty()) {
            val parts = bars.map { "${it.shortLabel()} ${it.percentUsed}%" }.toMutableList()
            primary()?.countdown()?.let { parts += "resets $it" }
            return parts.joinToString("  ·  ")
        }

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
        if (limitBars().isNotEmpty()) {
            append("Percentages come from the CLI's own <code>/usage</code> report, refreshed ")
            append("every minute while this panel is open.")
            limitBars().forEach { bar ->
                append("<br><br><b>").append(bar.label).append("</b>: ")
                append(bar.percentUsed).append("% used, resets ").append(bar.resetsAt)
            }
            append("</html>")
            return@buildString
        }
        append("Claude Code's machine-readable stream reports when a limit window resets ")
        append("and whether requests are allowed, but not how much is left. ")
        append("Until its usage report can be read, this shows what has actually been spent ")
        append("since the window opened, counted from the CLI's own session transcripts.")
        windows().forEach { w ->
            append("<br><br><b>").append(w.displayName()).append("</b>: ").append(w.status)
            w.countdown()?.let { append(", resets ").append(it) }
        }
        append("</html>")
    }

    companion object {
        private const val USAGE_SCAN_INTERVAL_MS = 60_000L
        private const val BARS_INTERVAL_MS = 60_000L

        fun getInstance(project: Project): RateLimitService =
            project.getService(RateLimitService::class.java)
    }
}
