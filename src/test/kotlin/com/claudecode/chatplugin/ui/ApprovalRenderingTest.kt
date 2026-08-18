package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.model.ChatMessage
import com.claudecode.chatplugin.model.Role
import com.claudecode.chatplugin.model.ToolCall
import com.claudecode.chatplugin.permissions.ApprovalDecision
import com.claudecode.chatplugin.permissions.ApprovalRequest
import com.claudecode.chatplugin.permissions.ApprovalSummary
import com.google.gson.JsonParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the question is asked, and what is left behind once it is answered.
 *
 * Both of these were reported from a running IDE, and both were the same
 * mistake: the question was a message of its own. It sank to the bottom of the
 * conversation as the assistant kept writing above it, and the same command
 * appeared twice — once as the row that ran it, once as the card that asked.
 * It belongs to the call, so it renders inside the call.
 */
class ApprovalRenderingTest {

    private fun request(command: String = "npm test"): ApprovalRequest {
        val input = JsonParser.parseString("""{"command":"$command"}""").asJsonObject
        return ApprovalRequest(
            toolName = "Bash",
            input = input,
            toolUseId = "toolu_01",
            summary = ApprovalSummary.of("Bash", input, "D:\\Work\\lms-human-front")
        )
    }

    private fun messageAsking(request: ApprovalRequest): ChatMessage {
        val message = ChatMessage(Role.ASSISTANT, "Running it now.")
        message.toolCalls.add(ToolCall("toolu_01", "Bash npm test", approval = request))
        return message
    }

    @Test
    fun `the question renders inside the call it is about`() {
        val html = MessageRenderer.fragment(messageAsking(request()), 3, streaming = false)
        val row = html.substringAfter("<li class='cb-act'")
        assertTrue("the card should be inside the tool row", row.contains("cb-ask"))
        assertTrue("the command should be there in full", row.contains("npm test"))
    }

    @Test
    fun `a row that is asking opens itself`() {
        // It is the reason the turn stopped. Collapsed, it is a question nobody
        // can see they have been asked.
        val html = MessageRenderer.fragment(messageAsking(request()), 0, streaming = false)
        assertTrue(html.contains("<details open>"))
    }

    @Test
    fun `the buttons name the call, not just the message`() {
        // A turn can stop on several calls, and one click handler resolves them
        // all: without the call index every button would answer the first one.
        val html = MessageRenderer.fragment(messageAsking(request()), 3, streaming = false)
        assertTrue(html.contains("claudebrains:3:askrun:0"))
        assertTrue(html.contains("claudebrains:3:askskip:0"))
    }

    @Test
    fun `the second call's buttons carry its own index`() {
        val first = request()
        val second = request("npm run build")
        val message = ChatMessage(Role.ASSISTANT, "")
        message.toolCalls.add(ToolCall("toolu_01", "Bash npm test", approval = first))
        message.toolCalls.add(ToolCall("toolu_02", "Bash npm run build", approval = second))
        val html = MessageRenderer.fragment(message, 1, streaming = false)
        assertTrue(html.contains("claudebrains:1:askrun:0"))
        assertTrue(html.contains("claudebrains:1:askrun:1"))
    }

    @Test
    fun `an answered question leaves the row and one word behind`() {
        val request = request()
        request.decide(ApprovalDecision.Allow(request.input))
        val message = messageAsking(request)
        message.toolCalls[0].output = "3 passing"
        val html = MessageRenderer.fragment(message, 0, streaming = false)

        assertFalse("the buttons are gone once it is decided", html.contains("askrun"))
        assertTrue("but the row says the decision was yours", html.contains("allowed"))
        assertTrue("and the output is what the row now holds", html.contains("3 passing"))
    }

    @Test
    fun `a skipped call says so`() {
        val request = request()
        request.decide(ApprovalDecision.Deny("The user declined this in the IDE."))
        val html = MessageRenderer.fragment(messageAsking(request), 0, streaming = false)
        assertTrue(html.contains("skipped"))
        assertFalse(html.contains("askskip"))
    }

    @Test
    fun `the assistant's own text is not replaced by the card`() {
        // The card lives on the assistant's message now. Whatever it does, the
        // reply has to survive it.
        val html = MessageRenderer.fragment(messageAsking(request()), 0, streaming = false)
        assertTrue(html.contains("Running it now."))
    }
}
