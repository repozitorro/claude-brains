package com.claudecode.chatplugin.actions

import com.claudecode.chatplugin.ClaudeSessionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class NewSessionAction : AnAction("New Session", "Start a new parallel Claude Code chat session", com.intellij.icons.AllIcons.General.Add) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val name = Messages.showInputDialog(
            project,
            "Name for this chat session:",
            "New Claude Code Session",
            null,
            "",
            null
        ) ?: return

        val sessionManager = project.getService(ClaudeSessionManager::class.java)
        sessionManager.createSession(name.ifBlank { null })
    }
}
