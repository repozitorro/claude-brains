package com.claudecode.chatplugin.stats

import com.claudecode.chatplugin.ClaudeSessionManager
import com.claudecode.chatplugin.ui.JcefSupport
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JEditorPane

/**
 * Shows the usage statistics page. Rendered in an embedded browser when JCEF is
 * available, and in a plain `JEditorPane` otherwise (which loses the charts'
 * finer styling but keeps every number and table readable).
 */
class UsageStatsDialog(private val project: Project, private val html: String) :
    DialogWrapper(project, true) {

    init {
        title = "Claude Brains — Usage Statistics"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val browser = if (JcefSupport.isAvailable()) createBrowserPanel() else null
        return (browser ?: fallbackPanel()).apply { preferredSize = Dimension(940, 720) }
    }

    /**
     * Kept in its own method, and returning null on any failure, for the same
     * reason the chat panel does it: whatever goes wrong with an optional
     * capability, the statistics still have to open. Inlining this into the
     * branch above would resolve `JBCefBrowser` even where the class is absent.
     */
    private fun createBrowserPanel(): JComponent? = try {
        JBCefBrowser().let { browser ->
            Disposer.register(disposable, browser)
            browser.loadHTML(html)
            browser.component
        }
    } catch (e: Throwable) {
        LOG.warn("Could not start the embedded browser; showing the plain statistics view", e)
        null
    }

    /** Loses the charts' finer styling, keeps every number and table readable. */
    private fun fallbackPanel(): JComponent =
        JBScrollPane(JEditorPane("text/html", html).apply { isEditable = false })

    override fun createActions(): Array<Action> = arrayOf(okAction)

    companion object {

        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(UsageStatsDialog::class.java)

        /** Reads transcripts off the EDT, then shows the dialog. */
        fun show(project: Project) {
            ProgressManager.getInstance().run(
                object : Task.Backgroundable(project, "Reading Claude Code usage", true) {
                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = true
                        val entries = UsageStatsReader.read()

                        // The CLI reports the rate-limit window's reset time in the
                        // live stream; the plugin caches it per session. Use the
                        // freshest one to bound "current window" usage.
                        val reset = project.getService(ClaudeSessionManager::class.java)
                            ?.sessions?.mapNotNull { it.rateLimit?.resetsAtEpochSec }?.maxOrNull()
                            ?.let { it * 1000 }
                        val windowStart = reset?.minus(FIVE_HOURS_MS)

                        val report = UsageStats.aggregate(
                            entries, windowStartMs = windowStart, windowResetMs = reset
                        )
                        val html = UsageStatsPage.render(report, currentTheme())

                        ApplicationManager.getApplication().invokeLater {
                            if (!project.isDisposed) UsageStatsDialog(project, html).show()
                        }
                    }
                }
            )
        }

        private const val FIVE_HOURS_MS = 5 * 60 * 60 * 1000L

        private fun currentTheme(): UsageStatsPage.Theme {
            val bg = UIUtil.getPanelBackground()
            val fg = UIUtil.getLabelForeground()
            val dark = (0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue) < 128
            val font = UIUtil.getLabelFont()
            return UsageStatsPage.Theme(
                bg = hex(bg),
                fg = hex(fg),
                muted = hex(UIUtil.getInactiveTextColor()),
                surface = hex(blend(bg, fg, 0.06)),
                border = hex(blend(bg, fg, 0.20)),
                accent = if (dark) "#E08B68" else "#C4643F",
                font = "'${font.family}'",
                fontSize = font.size,
                dark = dark
            )
        }

        private fun hex(c: Color) = "#%02x%02x%02x".format(c.red, c.green, c.blue)

        private fun blend(a: Color, b: Color, ratio: Double) = Color(
            (a.red * (1 - ratio) + b.red * ratio).toInt().coerceIn(0, 255),
            (a.green * (1 - ratio) + b.green * ratio).toInt().coerceIn(0, 255),
            (a.blue * (1 - ratio) + b.blue * ratio).toInt().coerceIn(0, 255)
        )
    }
}
