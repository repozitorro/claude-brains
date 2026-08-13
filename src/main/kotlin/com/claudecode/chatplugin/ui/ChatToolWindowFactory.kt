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
            // The content's own disposer runs both when the tab is closed and
            // when the content manager is torn down at project close — the case
            // `contentRemoved` deliberately steps out of, and therefore the case
            // that used to leave the panel's timers and browser running.
            content.setDisposer(panel)
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
                if (DeleteSessionAction.isConfirming) {
                    event.content.putUserData(DELETE_ON_PURPOSE, true) // the action already asked
                    return
                }
                if (DeleteSessionAction.confirmDelete(project, session.displayName)) {
                    event.content.putUserData(DELETE_ON_PURPOSE, true)
                } else {
                    event.consume() // keep the tab
                }
            }

            override fun contentRemoved(event: ContentManagerEvent) {
                // A tab goes away for two very different reasons: the user
                // deleted the conversation, or the IDE is shutting the tool
                // window down. Only the first may touch the stored sessions.
                //
                // This used to tell them apart by asking whether the project was
                // disposed — and at teardown it is not disposed yet. So some
                // tabs slipped through, their sessions were removed from the
                // list, and the state written on the way out no longer had them:
                // conversations vanished on restart, a different few each time.
                //
                // Intent is now recorded where it actually exists — the removal
                // the user was asked about — instead of being inferred from
                // lifecycle state that says nothing about intent.
                if (event.content.getUserData(DELETE_ON_PURPOSE) != true) return
                event.content.getUserData(SESSION_KEY)?.let { sessionManager.closeSession(it) }
            }
        })

        // Toolbar "+" action to start a new parallel session tab.
        toolWindow.setTitleActions(
            listOfNotNull(
                com.intellij.openapi.actionSystem.ActionManager.getInstance()
                    .getAction("ClaudeCodeChat.NewSession"),
                com.intellij.openapi.actionSystem.ActionManager.getInstance()
                    .getAction("ClaudeCodeChat.RenameSession"),
                com.intellij.openapi.actionSystem.ActionManager.getInstance()
                    .getAction("ClaudeCodeChat.DeleteSession"),
                com.intellij.openapi.actionSystem.ActionManager.getInstance()
                    .getAction("ClaudeCodeChat.UsageStats")
            )
        )

        sessionManager.addChangeListener(toolWindow.disposable) {
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || toolWindow.isDisposed) return@invokeLater

                // A tab's title follows its session's name, so renaming from
                // anywhere shows up here rather than only where it was done.
                toolWindow.contentManager.contents.forEach { content ->
                    val session = content.getUserData(SESSION_KEY) ?: return@forEach
                    if (content.displayName != session.displayName) {
                        content.displayName = session.displayName
                    }
                }

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

        /**
         * Marks a tab the user has actually chosen to delete.
         *
         * Set only on the path where the deletion was asked about, so closing
         * the IDE — which removes every tab without asking anyone — cannot be
         * mistaken for a decision to throw the conversations away.
         */
        internal val DELETE_ON_PURPOSE =
            com.intellij.openapi.util.Key.create<Boolean>("claude.chat.deleteOnPurpose")
    }
}
