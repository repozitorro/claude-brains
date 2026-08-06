package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.ClaudeSessionManager
import com.claudecode.chatplugin.actions.DeleteSessionAction
import com.claudecode.chatplugin.auth.AuthStatus
import com.claudecode.chatplugin.auth.ClaudeAuth
import com.claudecode.chatplugin.model.ClaudeSession
import com.intellij.openapi.application.ApplicationManager
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

        fun openChat() {
            // Restore a tab per persisted session (or open one fresh session if none).
            if (sessionManager.sessions.isEmpty()) {
                addTabFor(sessionManager.getOrCreateDefault())
            } else {
                sessionManager.sessions.forEach { addTabFor(it) }
            }
        }

        // Sending a prompt without a signed-in CLI just fails with a raw error,
        // so check first and offer the sign-in screen instead of a broken chat.
        // The check runs off the EDT; the chat opens as soon as it comes back.
        ApplicationManager.getApplication().executeOnPooledThread {
            val status = ClaudeAuth.getInstance(project).status()
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || toolWindow.isDisposed) return@invokeLater
                if (status is AuthStatus.SignedIn) {
                    openChat()
                    return@invokeLater
                }
                val gate = SignInPanel(project, status) {
                    // Signed in now: drop the gate and open the conversations.
                    toolWindow.contentManager.contents
                        .filter { it.component is SignInPanel }
                        .forEach { toolWindow.contentManager.removeContent(it, true) }
                    openChat()
                }
                val content = contentFactory.createContent(gate, "Sign in", false)
                content.isCloseable = false
                toolWindow.contentManager.addContent(content)
            }
        }

        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            /**
             * Closing a tab discards its conversation, so it asks first — the
             * same question the trash button asks. Without this, the tab's own
             * close button was a silent delete.
             */
            override fun contentRemoveQuery(event: ContentManagerEvent) {
                if (project.isDisposed || toolWindow.isDisposed) return
                val session = event.content.getUserData(SESSION_KEY) ?: return
                if (DeleteSessionAction.isConfirming) return // the action already asked
                if (!DeleteSessionAction.confirmDelete(project, session.displayName)) {
                    event.consume() // keep the tab
                }
            }

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
            listOfNotNull(
                com.intellij.openapi.actionSystem.ActionManager.getInstance()
                    .getAction("ClaudeCodeChat.NewSession"),
                com.intellij.openapi.actionSystem.ActionManager.getInstance()
                    .getAction("ClaudeCodeChat.DeleteSession"),
                com.intellij.openapi.actionSystem.ActionManager.getInstance()
                    .getAction("ClaudeCodeChat.UsageStats")
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
