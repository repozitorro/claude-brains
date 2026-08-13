package com.claudecode.chatplugin.actions

import com.claudecode.chatplugin.ClaudeSessionManager
import com.claudecode.chatplugin.ui.ChatToolWindowFactory
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Renames the chat that's open.
 *
 * Parallel conversations are only worth having if you can tell them apart, and
 * "Chat 3" stops meaning anything by the third one. The name is part of the
 * persisted session, so it survives a restart like the rest of it.
 */
class RenameSessionAction : AnAction(
    "Rename Chat",
    "Give this conversation a name you'll recognise",
    AllIcons.Actions.Edit
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { session(it) } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = session(project) ?: return

        val chosen = Messages.showInputDialog(
            project,
            "Name for this chat:",
            "Rename Chat",
            null,
            session.displayName,
            NOT_BLANK
        )?.trim() ?: return

        if (chosen.isEmpty() || chosen == session.displayName) return
        project.getService(ClaudeSessionManager::class.java).renameSession(session, chosen)
    }

    private fun session(project: Project) = toolWindow(project)
        ?.contentManager?.selectedContent
        ?.getUserData(ChatToolWindowFactory.SESSION_KEY)

    private fun toolWindow(project: Project): ToolWindow? =
        ToolWindowManager.getInstance(project).getToolWindow("Claude Brains")

    private companion object {
        /** A blank name would leave an unlabelled tab that can't be told from its neighbours. */
        val NOT_BLANK = object : InputValidator {
            override fun checkInput(inputString: String?): Boolean = !inputString.isNullOrBlank()
            override fun canClose(inputString: String?): Boolean = checkInput(inputString)
        }
    }
}
