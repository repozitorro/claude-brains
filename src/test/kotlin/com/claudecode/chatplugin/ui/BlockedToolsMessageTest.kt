package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.cli.PermissionDenial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The message shown when the CLI refuses to run something.
 *
 * It exists to end a specific dead end: Claude repeating "please approve this",
 * the panel answering "pick Accept edits", and the reader already being on
 * Accept edits. Advice that describes the state you are in is worse than none —
 * it reads as the plugin not understanding its own situation.
 */
class BlockedToolsMessageTest {

    private fun bash(vararg commands: String) = commands.map { PermissionDenial("Bash", it) }

    @Test
    fun `on accept-edits it says why a command was still refused`() {
        val text = BlockedToolsMessage.format(bash("git add src/App.kt"), effectiveMode = "acceptEdits")

        assertTrue(text, text.contains("file edits only"))
        // The dead end: never tell someone to choose the mode they are on.
        assertFalse(
            "must not suggest the mode already in force",
            text.contains("switch this chat to **Accept edits**", ignoreCase = true)
        )
    }

    @Test
    fun `it names what was blocked, not just the tool`() {
        // "Bash was blocked" three times tells you nothing about what to allow.
        val text = BlockedToolsMessage.format(
            bash("git add a.kt", "git add b.kt", "git add c.kt"),
            effectiveMode = "acceptEdits"
        )

        assertTrue(text, text.contains("Bash × 3"))
        assertTrue(text, text.contains("git add a.kt"))
    }

    @Test
    fun `the suggested pattern unblocks the command without opening everything`() {
        assertEquals("Bash(git *)", BlockedToolsMessage.suggestedPattern(bash("git add x", "git commit -m y")))
        assertEquals(
            "two programs, both named",
            "Bash(git *) Bash(npm *)",
            BlockedToolsMessage.suggestedPattern(bash("git add x", "npm test"))
        )
    }

    @Test
    fun `a blocked edit on the CLI's own rules does point at Accept edits`() {
        // Here it is the right advice, because the chat is not on that mode.
        val text = BlockedToolsMessage.format(
            listOf(PermissionDenial("Edit", "/repo/App.kt")),
            effectiveMode = null
        )

        assertTrue(text, text.contains("Accept edits"))
    }

    @Test
    fun `plan mode is explained as a choice, not a fault`() {
        val text = BlockedToolsMessage.format(bash("git add x"), effectiveMode = "plan")

        assertTrue(text, text.contains("read-only by design"))
        // Allowing a tool cannot help while the whole mode is read-only.
        assertFalse(text, text.contains("Allowed tools"))
    }

    @Test
    fun `every message offers a way out that needs no settings at all`() {
        listOf("acceptEdits", "plan", null).forEach { mode ->
            val text = BlockedToolsMessage.format(bash("git push"), mode)
            assertTrue("$mode: $text", text.contains("yourself") || text.contains("Switch this chat"))
        }
    }

    @Test
    fun `nothing blocked says nothing`() {
        assertEquals("", BlockedToolsMessage.format(emptyList(), "acceptEdits"))
    }
}
