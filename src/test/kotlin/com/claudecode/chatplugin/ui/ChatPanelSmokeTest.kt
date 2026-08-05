package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.ClaudeSessionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Builds the chat panel the way the tool window does.
 *
 * This exists because a property initializer that reached for the property it
 * was initializing threw an NPE, which the tool window swallowed into an empty
 * "Nothing to show" panel — the plugin looked installed but had no UI at all.
 * Nothing in the unit tests touched construction, so nothing caught it.
 */
class ChatPanelSmokeTest : BasePlatformTestCase() {

    override fun setUp() {
        // Keep the embedded browser out of a headless test; the panel falls back
        // to the Swing view, which is what we want to exercise here anyway.
        System.setProperty("ide.browser.jcef.enabled", "false")
        super.setUp()
    }

    fun testPanelBuildsForAFreshSession() {
        val manager = project.getService(ClaudeSessionManager::class.java)
        val session = manager.createSession("Smoke")

        val panel = ChatPanel(project, session)

        assertTrue("panel should have laid out its children", panel.componentCount > 0)
        panel.dispose()
    }

    fun testSignInScreenBuildsForEveryAuthState() {
        listOf(
            com.claudecode.chatplugin.auth.AuthStatus.SignedOut,
            com.claudecode.chatplugin.auth.AuthStatus.Unavailable("claude: command not found"),
            com.claudecode.chatplugin.auth.AuthStatus.SignedIn("a@b.c", "pro", "claude.ai", "Org")
        ).forEach { status ->
            val panel = SignInPanel(project, status) { /* no-op */ }
            assertTrue(status.toString(), panel.componentCount > 0)
        }
    }

    fun testPanelBuildsForARestoredSessionWithPinnedSettings() {
        val manager = project.getService(ClaudeSessionManager::class.java)
        val session = manager.createSession("Restored").apply {
            selectedModel = "claude-opus-4-8"
            permissionMode = "plan"
        }

        val panel = ChatPanel(project, session)

        assertTrue(panel.componentCount > 0)
        panel.dispose()
    }
}
