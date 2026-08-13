package com.claudecode.chatplugin

import com.claudecode.chatplugin.model.ChatMessage
import com.claudecode.chatplugin.model.Role
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * What survives closing the IDE.
 *
 * These conversations only exist in this file: the CLI keeps its own
 * transcripts, but the tabs, their `--resume` ids and their usage totals are
 * the plugin's alone. Losing one is not a cosmetic fault, and nothing exercised
 * the save/load round trip until conversations started disappearing on restart.
 */
class ClaudeSessionManagerTest : BasePlatformTestCase() {

    private fun manager() = ClaudeSessionManager(project)

    /** Saves [from] and loads it into a fresh manager, as an IDE restart would. */
    private fun restart(from: ClaudeSessionManager): ClaudeSessionManager =
        manager().apply { loadState(from.state) }

    fun testEverySessionComesBack() {
        val before = manager()
        before.createSession("Alpha")
        before.createSession("Beta")
        before.createSession("Gamma")

        val after = restart(before)

        assertEquals(listOf("Alpha", "Beta", "Gamma"), after.sessions.map { it.displayName })
    }

    fun testWhatMakesASessionResumableIsKept() {
        val before = manager()
        before.createSession("Work").apply {
            cliSessionId = "abc-123"
            selectedModel = "claude-opus-5"
            permissionMode = "plan"
            totalCostUsd = 1.25
            totalInputTokens = 900
            totalOutputTokens = 100
            turnCount = 3
            messages.add(ChatMessage(Role.USER, "hello"))
            messages.add(ChatMessage(Role.ASSISTANT, "hi"))
        }

        val restored = restart(before).sessions.single()

        // Without the CLI id the conversation reopens with no context at all,
        // which looks like the transcript lying about what Claude can see.
        assertEquals("abc-123", restored.cliSessionId)
        assertEquals("claude-opus-5", restored.selectedModel)
        assertEquals("plan", restored.permissionMode)
        assertEquals(3, restored.turnCount)
        assertEquals(1.25, restored.totalCostUsd, 1e-9)
        assertEquals(900L, restored.totalInputTokens)
        assertEquals(listOf("hello", "hi"), restored.messages.map { it.text })
    }

    fun testDeletingOneSessionLeavesTheRest() {
        val before = manager()
        before.createSession("Keep me")
        val doomed = before.createSession("Delete me")
        before.createSession("Keep me too")

        before.closeSession(doomed)

        assertEquals(listOf("Keep me", "Keep me too"), restart(before).sessions.map { it.displayName })
    }

    fun testAHalfStreamedReplyIsNotSavedMidSentence() {
        val before = manager()
        before.createSession("Busy").apply {
            messages.add(ChatMessage(Role.USER, "go"))
            messages.add(ChatMessage(Role.ASSISTANT, "partial…", isStreaming = true))
        }

        val restored = restart(before).sessions.single()

        assertEquals(listOf("go"), restored.messages.map { it.text })
    }

    fun testARenamedChatKeepsItsNameAndEverythingElse() {
        val before = manager()
        val session = before.createSession("Chat 1").apply {
            cliSessionId = "abc-123"
            messages.add(ChatMessage(Role.USER, "hello"))
        }

        before.renameSession(session, "Refactor the parser")

        val restored = restart(before).sessions.single()
        assertEquals("Refactor the parser", restored.displayName)
        // Renaming is a label, not a new conversation: the context behind it
        // has to come along.
        assertEquals("abc-123", restored.cliSessionId)
        assertEquals(listOf("hello"), restored.messages.map { it.text })
    }

    fun testRenamingReleasesTheAutoNumberItWasUsing() {
        // Auto-numbering follows the names actually in use rather than a
        // high-water mark, so renaming "Chat 2" puts that number back in
        // circulation. That is fine — nothing is called it any more — and the
        // property worth holding is the one below: no two tabs share a name.
        val before = manager()
        before.createSession()                       // Chat 1
        val second = before.createSession()          // Chat 2
        before.renameSession(second, "Release notes")

        val after = restart(before)
        assertEquals(listOf("Chat 1", "Release notes"), after.sessions.map { it.displayName })

        after.createSession()

        val names = after.sessions.map { it.displayName }
        assertEquals(listOf("Chat 1", "Release notes", "Chat 2"), names)
        assertEquals("names must stay unique", names.size, names.toSet().size)
    }

    fun testANewSessionCannotTakeTheNameOfARestoredOne() {
        val before = manager()
        before.createSession() // "Chat 1"
        before.createSession() // "Chat 2"

        val after = restart(before)
        val fresh = after.createSession()

        assertEquals("Chat 3", fresh.displayName)
        assertEquals(listOf("Chat 1", "Chat 2", "Chat 3"), after.sessions.map { it.displayName })
    }
}
