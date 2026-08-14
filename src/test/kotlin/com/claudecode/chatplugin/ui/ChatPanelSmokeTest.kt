package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.ClaudeSessionManager
import com.claudecode.chatplugin.limits.RateLimitService
import com.intellij.openapi.util.Disposer
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
        Disposer.dispose(panel)
    }

    /**
     * Panels come and go with every tab; the services they subscribe to live as
     * long as the project. Each closed tab used to leave its closure behind,
     * holding the whole panel — invisible until the IDE was restarted.
     */
    fun testDisposingAPanelUnsubscribesItFromTheServices() {
        val manager = project.getService(ClaudeSessionManager::class.java)
        val limits = RateLimitService.getInstance(project)
        val before = limits.listenerCount

        val panels = (1..3).map { ChatPanel(project, manager.createSession("Leak $it")) }
        assertEquals("each panel should subscribe once", before + 3, limits.listenerCount)

        // Disposed through the Disposer, which is how the tool window's content
        // releases a tab — the path that also has to release the panel's children.
        panels.forEach { Disposer.dispose(it) }

        assertEquals("nothing should be left subscribed", before, limits.listenerCount)
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

    fun testTypingDuringATurnQueuesInsteadOfLosingIt() {
        // What used to happen: "this session is already waiting on a response",
        // and the thought you had while reading the answer was gone. Nothing is
        // sent here — the session is marked busy, which is the branch under test.
        val manager = project.getService(ClaudeSessionManager::class.java)
        val session = manager.createSession("Queue")
        val panel = ChatPanel(project, session)
        session.isBusy = true

        panel.submitText("and also fix the tests")
        panel.submitText("and rename that class")

        assertEquals(2, panel.queuedCount)
        // Shown as it was typed: a message that disappears on Enter cannot be
        // told apart from one that was dropped.
        assertEquals(
            listOf("and also fix the tests", "and rename that class"),
            session.messages.map { it.text }
        )

        Disposer.dispose(panel)
    }

    fun testBlankInputIsNotQueued() {
        val manager = project.getService(ClaudeSessionManager::class.java)
        val session = manager.createSession("Blank")
        val panel = ChatPanel(project, session)
        session.isBusy = true

        panel.submitText("   ")
        panel.submitText("")

        assertEquals(0, panel.queuedCount)
        Disposer.dispose(panel)
    }

    fun testPanelBuildsForARestoredSessionWithPinnedSettings() {
        val manager = project.getService(ClaudeSessionManager::class.java)
        val session = manager.createSession("Restored").apply {
            selectedModel = "claude-opus-4-8"
            permissionMode = "plan"
        }

        val panel = ChatPanel(project, session)

        assertTrue(panel.componentCount > 0)
        Disposer.dispose(panel)
    }
}
