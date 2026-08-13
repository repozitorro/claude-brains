package com.claudecode.chatplugin.actions

import com.claudecode.chatplugin.ClaudeSessionManager
import com.claudecode.chatplugin.ui.ChatToolWindowFactory
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Deletes the chat that's open, after asking.
 *
 * Closing a tab already discards its conversation, which is easy to do by
 * accident; this is the deliberate route, and it says what is about to be lost.
 */
class DeleteSessionAction : AnAction(
    "Delete Chat",
    "Delete this conversation",
    AllIcons.Actions.GC
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { toolWindow(it) }
            ?.contentManager?.selectedContent != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val toolWindow = toolWindow(project) ?: return
        val content = toolWindow.contentManager.selectedContent ?: return
        val session = content.getUserData(ChatToolWindowFactory.SESSION_KEY)
        val name = session?.displayName ?: content.displayName ?: "this chat"

        if (!confirmDelete(project, name)) return

        // Removing the tab is what deletes the session: the tool window's own
        // listener does that, so both routes stay in step. The flag stops that
        // listener asking the same question a second time.
        //
        // The intent is marked here as well as on the listener's own path,
        // because the listener now deletes nothing that isn't marked — and this
        // route must not depend on which callbacks removeContent happens to
        // fire.
        content.putUserData(ChatToolWindowFactory.DELETE_ON_PURPOSE, true)
        isConfirming = true
        try {
            toolWindow.contentManager.removeContent(content, true)
        } finally {
            isConfirming = false
        }

        // Never leave the panel with nothing in it.
        val manager = project.getService(ClaudeSessionManager::class.java)
        if (manager.sessions.isEmpty()) manager.createSession()
    }

    private fun toolWindow(project: Project): ToolWindow? =
        ToolWindowManager.getInstance(project).getToolWindow("Claude Brains")

    companion object {

        /** True while this action is doing the asking, so the tab listener doesn't. */
        @Volatile
        var isConfirming: Boolean = false
            internal set

        /** The one question both delete routes ask, worded the same way. */
        fun confirmDelete(project: Project, name: String): Boolean = MessageDialogBuilder
            .yesNo("Delete “$name”?", buildString {
                append("The conversation is removed from Claude Brains and can't be brought back.\n\n")
                append("Claude Code's own transcript of it, on disk, is left alone — this doesn't ")
                append("touch the CLI's files.")
            })
            .yesText("Delete")
            .noText("Keep")
            .icon(AllIcons.General.WarningDialog)
            .ask(project)
    }
}
