package com.claudecode.chatplugin.actions

import com.claudecode.chatplugin.ClaudeSessionManager
import com.claudecode.chatplugin.ui.ChatPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Sends what the terminal is showing to the chat.
 *
 * A failed build, a stack trace, the output of a test run — this is the thing
 * people most often want Claude to look at, and until now it was a manual
 * copy out of one panel and a paste into another. The editor has had "send
 * selection" from the start; the terminal is where the errors actually appear.
 *
 * Selected text wins when there is any. Otherwise the tail of the terminal is
 * taken, because the useful part of a long build log is the end of it.
 */
class SendTerminalToClaudeAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { terminalText(it) != null } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val text = terminalText(project) ?: return

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude Brains") ?: return
        toolWindow.show {
            project.getService(ClaudeSessionManager::class.java).getOrCreateDefault()
            val cm = toolWindow.contentManager
            val content = cm.selectedContent ?: cm.contents.firstOrNull()
            (content?.component as? ChatPanel)?.prefillInput("From the terminal:\n```\n$text\n```\n")
        }
    }

    /** Null when there is no terminal, or nothing in it worth sending. */
    private fun terminalText(project: Project): String? = try {
        // The manager hands back a raw Set from Java, so the element type has to
        // be stated here rather than inferred.
        val widgets = org.jetbrains.plugins.terminal.TerminalToolWindowManager
            .getInstance(project).terminalWidgets
            .filterIsInstance<com.intellij.terminal.JBTerminalWidget>()

        val selected = widgets.firstNotNullOfOrNull { it.selectedText?.trim()?.ifEmpty { null } }
        val whole = selected ?: widgets.firstNotNullOfOrNull { it.text?.trim()?.ifEmpty { null } }
        whole?.let { tail(it) }
    } catch (e: Throwable) {
        // The terminal is an optional dependency; without it there is nothing
        // to send and the action simply stays disabled.
        null
    }

    /** The end of a long log is the part that explains the failure. */
    private fun tail(text: String): String {
        val lines = text.lines()
        return if (lines.size <= MAX_LINES) text
        else "… (${lines.size - MAX_LINES} earlier lines omitted)\n" + lines.takeLast(MAX_LINES).joinToString("\n")
    }

    private companion object {
        const val MAX_LINES = 200
    }
}
