package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.model.ChatMessage
import com.claudecode.chatplugin.model.ClaudeSession
import com.claudecode.chatplugin.model.EditOp
import com.claudecode.chatplugin.model.FileEdit
import com.claudecode.chatplugin.model.Role
import com.claudecode.chatplugin.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptExporterTest {

    private fun session(name: String = "Chat 1"): ClaudeSession = ClaudeSession(name)

    @Test
    fun `exports turns under headings`() {
        val s = session("Refactor auth")
        s.messages.add(ChatMessage(Role.USER, "rename the helper"))
        s.messages.add(ChatMessage(Role.ASSISTANT, "Done — renamed it."))

        val md = TranscriptExporter.toMarkdown(s)

        assertTrue(md.startsWith("# Refactor auth"))
        assertTrue(md.contains("## You\n\nrename the helper"))
        assertTrue(md.contains("## Claude\n\nDone — renamed it."))
        assertTrue(md.endsWith("\n"))
    }

    @Test
    fun `includes tool activity and edited file paths`() {
        val s = session()
        s.messages.add(
            ChatMessage(
                Role.ASSISTANT, "All set.",
                toolCalls = mutableListOf(
                    ToolCall("1", "Bash npm test", ToolCall.Status.OK),
                    ToolCall("2", "Grep TODO", ToolCall.Status.ERROR)
                ),
                edits = mutableListOf(
                    FileEdit("/repo/src/App.kt", "Edit", null).apply { ops.add(EditOp("a", "b", null, false)) }
                )
            )
        )

        val md = TranscriptExporter.toMarkdown(s)

        assertTrue(md.contains("- ✓ Bash npm test"))
        assertTrue(md.contains("- ✗ Grep TODO"))
        assertTrue(md.contains("- ✎ Edit /repo/src/App.kt"))
    }

    @Test
    fun `reports model and usage when present`() {
        val s = session().apply {
            selectedModel = "opus"
            turnCount = 2
            totalCostUsd = 0.1234
            totalInputTokens = 1500
            totalOutputTokens = 300
        }

        val md = TranscriptExporter.toMarkdown(s)

        assertTrue(md.contains("- Model: `opus`"))
        assertTrue(md.contains("2 turns"))
        assertTrue(md.contains("$0.1234"))
        assertTrue(md.contains("1500 in / 300 out tokens"))
    }

    @Test
    fun `a fresh session exports just its title`() {
        val md = TranscriptExporter.toMarkdown(session("Empty"))

        assertEquals("# Empty\n", md)
        assertFalse(md.contains("Model"))
    }
}
