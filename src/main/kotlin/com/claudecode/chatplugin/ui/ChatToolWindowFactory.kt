package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.ClaudeSessionManager
import com.claudecode.chatplugin.model.ClaudeSession
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener

/**
 * Each chat session gets its own tab in the tool window, so several tasks
 * can run as genuinely parallel, independent conversations.
 */
class ChatToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val sessionManager = project.getService(ClaudeSessionManager::class.java)
        val contentFactory = ContentFactory.getInstance()

        fun addTabFor(session: ClaudeSession) {
            val panel = ChatPanel(project, session)
            val content = contentFactory.createContent(panel, session.displayName, false)
            content.isCloseable = true
            content.putUserData(SESSION_KEY, session)
            toolWindow.contentManager.addContent(content)
            toolWindow.contentManager.setSelectedContent(content)
        }

        // Restore a tab per persisted session (or open one fresh session if none).
        if (sessionManager.sessions.isEmpty()) {
            addTabFor(sessionManager.getOrCreateDefault())
        } else {
            sessionManager.sessions.forEach { addTabFor(it) }
        }

        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                // Only treat this as a real "delete this conversation" when the user
                // closed the tab — NOT when the tool window is torn down on project
                // close, which would otherwise wipe the persisted sessions.
                if (project.isDisposed || toolWindow.isDisposed) return
                (event.content.component as? ChatPanel)?.dispose()
                event.content.getUserData(SESSION_KEY)?.let { sessionManager.closeSession(it) }
            }
        })

        // Toolbar "+" action to start a new parallel session tab.
        toolWindow.setTitleActions(
            listOf(
                com.intellij.openapi.actionSystem.ActionManager.getInstance()
                    .getAction("ClaudeCodeChat.NewSession")
            )
        )

        sessionManager.addChangeListener {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                val existingSessions = toolWindow.contentManager.contents
                    .mapNotNull { it.getUserData(SESSION_KEY) }
                    .toSet()
                sessionManager.sessions
                    .filter { it !in existingSessions }
                    .forEach { addTabFor(it) }
            }
        }
    }

    companion object {
        val SESSION_KEY = com.intellij.openapi.util.Key.create<ClaudeSession>("claude.chat.session")
    }
}
