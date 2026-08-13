package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.cli.PermissionDenial
import com.claudecode.chatplugin.model.ChatMessage
import com.claudecode.chatplugin.model.PermissionRequest
import com.claudecode.chatplugin.model.Role
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Answering a refusal from inside the chat.
 *
 * The CLI will not ask — in print mode there is no terminal, and it offers a
 * host no way to answer on its behalf. So the question is put after the fact,
 * and these links are the whole interface to it: they must carry the message
 * index, because a single handler resolves every click in the transcript.
 */
class PermissionRequestRenderingTest {

    private fun asking(pattern: String = "Bash(git *)") = ChatMessage(
        Role.SYSTEM,
        "Blocked.",
        permissionRequest = PermissionRequest(
            denials = listOf(PermissionDenial("Bash", "git add a.kt")),
            pattern = pattern,
            prompt = "commit my work"
        )
    )

    @Test
    fun `an unanswered request offers both answers`() {
        val html = MessageRenderer.fragment(asking(), msgIndex = 4, streaming = false)

        assertTrue(html, html.contains("claudebrains:4:permallow:0"))
        assertTrue(html, html.contains("claudebrains:4:permalways:0"))
        assertTrue(html, html.contains("claudebrains:4:permdeny:0"))
        // Emitted for both views, as the edit links are.
        assertTrue(html, html.contains("href='claudebrains:4:permallow:0'"))
        assertTrue(html, html.contains("data-cb='claudebrains:4:permallow:0'"))
        assertTrue("the pattern is what is being agreed to", html.contains("Bash(git *)"))
    }

    @Test
    fun `an answered request records the decision instead of asking again`() {
        val message = asking()
        message.permissionRequest!!.answer = PermissionRequest.Answer.ALLOWED_HERE

        val html = MessageRenderer.fragment(message, msgIndex = 4, streaming = false)

        assertTrue(html, html.contains("Allowed in this chat"))
        assertFalse("no second decision to make", html.contains("claudebrains:4:permallow:0"))
    }

    @Test
    fun `a declined request says so rather than going quiet`() {
        val message = asking()
        message.permissionRequest!!.answer = PermissionRequest.Answer.DENIED

        val html = MessageRenderer.fragment(message, msgIndex = 0, streaming = false)

        assertTrue(html, html.contains("Declined"))
        assertFalse(html, html.contains("permdeny"))
    }

    @Test
    fun `a message with nothing to decide carries no buttons`() {
        val html = MessageRenderer.fragment(ChatMessage(Role.SYSTEM, "Just a note."), 0, streaming = false)

        assertFalse(html, html.contains("cb-perm"))
    }

    @Test
    fun `the pattern is escaped, not rendered`() {
        // Patterns are built from a blocked command, which is Claude's text.
        val html = MessageRenderer.fragment(asking("Bash(<script> *)"), 0, streaming = false)

        assertFalse(html, html.contains("<script>"))
        assertTrue(html, html.contains("&lt;script&gt;"))
    }
}
