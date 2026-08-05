package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.model.ChatMessage
import com.claudecode.chatplugin.model.EditOp
import com.claudecode.chatplugin.model.FileEdit
import com.claudecode.chatplugin.model.Role
import com.claudecode.chatplugin.model.ToolCall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the HTML fragments that drive both chat views — in particular the
 * diff/revert link tokens, which are the contract between the renderer and
 * `ChatPanel.handleEditLink`.
 */
class MessageRendererTest {

    /** A resolved, revertible edit of [name]: "a" was replaced with "b". */
    private fun revertibleEdit(name: String): FileEdit =
        FileEdit(name, "Edit", null).apply {
            ops.add(EditOp("a", "b", null, false))
            resolve("b")
        }

    private fun assistant(vararg edits: FileEdit) =
        ChatMessage(Role.ASSISTANT, "done", edits = edits.toMutableList())

    @Test
    fun `resolved edit renders diff and revert links with the message index`() {
        val html = MessageRenderer.fragment(assistant(revertibleEdit("a.kt")), msgIndex = 7, streaming = false)

        assertTrue(html.contains("claudebrains:7:diff:0"))
        assertTrue(html.contains("claudebrains:7:revert:0"))
        // Emitted for both views: href (Swing) and data-cb (JCEF).
        assertTrue(html.contains("href='claudebrains:7:diff:0'"))
        assertTrue(html.contains("data-cb='claudebrains:7:diff:0'"))
    }

    @Test
    fun `unrevertible edit still offers diff but no revert`() {
        // new_string already present elsewhere → ambiguous → canRevert == false
        val edit = FileEdit("a.kt", "Edit", null).apply {
            ops.add(EditOp("y", "x", null, false))
            resolve("x = 1;\ny = x;")
        }
        val html = MessageRenderer.fragment(assistant(edit), msgIndex = 0, streaming = false)

        assertFalse(edit.canRevert)
        assertTrue(html.contains("claudebrains:0:diff:0"))
        assertFalse(html.contains("claudebrains:0:revert:0"))
    }

    @Test
    fun `revert all appears only when more than one edit is revertible`() {
        val one = MessageRenderer.fragment(assistant(revertibleEdit("a.kt")), 0, streaming = false)
        assertFalse(one.contains("revertall"))

        val two = MessageRenderer.fragment(
            assistant(revertibleEdit("a.kt"), revertibleEdit("b.kt")), 0, streaming = false
        )
        assertTrue(two.contains("claudebrains:0:revertall:-1"))
        assertTrue(two.contains(">revert all</a>"))
    }

    @Test
    fun `streaming fragments carry no action links`() {
        val msg = assistant(revertibleEdit("a.kt")).apply { isStreaming = true }
        val html = MessageRenderer.fragment(msg, 0, streaming = true)

        assertFalse(html.contains("claudebrains:"))
        assertTrue(html.contains("a.kt")) // the edit is still listed, just not clickable
    }

    @Test
    fun `tool call status is reflected and text is escaped`() {
        val msg = ChatMessage(Role.ASSISTANT, "", toolCalls = mutableListOf(
            ToolCall("id1", "Bash echo <script>", ToolCall.Status.ERROR)
        ))
        val html = MessageRenderer.fragment(msg, 0, streaming = false)

        assertTrue(html.contains("&#10007;"))              // ✗ for ERROR
        assertTrue(html.contains("&lt;script&gt;"))        // escaped, not raw HTML
        assertFalse(html.contains("<script>"))
    }
}
