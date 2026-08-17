package com.claudecode.chatplugin.permissions

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire the CLI actually speaks.
 *
 * Every expectation here was read off a live 2.1.232 conversation rather than a
 * specification: the CLI was pointed at a stand-in server, and what it sent and
 * what it accepted back was recorded. That is why the assertions are so
 * literal — `behavior`, `updatedInput`, the decision as JSON *text* inside a
 * tool result. Each of those was a thing that had to be got exactly right
 * before the CLI would run anything.
 */
class McpApprovalProtocolTest {

    private fun rpc(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private fun protocol(decision: ApprovalDecision) = McpApprovalProtocol { _, _, _ -> decision }

    private fun allowAll() = protocol(ApprovalDecision.Allow(null))

    @Test
    fun `a notification is not answered at all`() {
        // No id means no reply, by the spec — and answering anyway is what makes
        // a client hang up on you.
        assertNull(allowAll().handle(rpc("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")))
    }

    @Test
    fun `initialize agrees to whichever protocol version the client named`() {
        val response = allowAll().handle(
            rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}""")
        )!!
        val result = response.getAsJsonObject("result")
        assertEquals("2025-11-25", result.get("protocolVersion").asString)
        assertEquals(
            McpApprovalProtocol.SERVER_NAME,
            result.getAsJsonObject("serverInfo").get("name").asString
        )
    }

    @Test
    fun `the tool is listed under the name the flag will be given`() {
        val response = allowAll().handle(rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""))!!
        val tools = response.getAsJsonObject("result").getAsJsonArray("tools")
        assertEquals(1, tools.size())
        val name = tools[0].asJsonObject.get("name").asString
        assertEquals(McpApprovalProtocol.TOOL_NAME, name)
        // --permission-prompt-tool is given the qualified form, and the CLI
        // refuses to start if it does not match a tool it can see.
        assertEquals("mcp__${McpApprovalProtocol.SERVER_NAME}__$name", McpApprovalProtocol.QUALIFIED_TOOL_NAME)
    }

    @Test
    fun `an allowed call answers with the arguments unchanged`() {
        val input = JsonObject().apply { addProperty("command", "npm test") }
        val decision = decisionFrom(protocol(ApprovalDecision.Allow(input)).handle(CALL)!!)
        assertEquals("allow", decision.get("behavior").asString)
        assertEquals("npm test", decision.getAsJsonObject("updatedInput").get("command").asString)
    }

    @Test
    fun `a refusal carries the reason back to the model`() {
        val decision = decisionFrom(protocol(ApprovalDecision.Deny("The user declined this in the IDE.")).handle(CALL)!!)
        assertEquals("deny", decision.get("behavior").asString)
        assertEquals("The user declined this in the IDE.", decision.get("message").asString)
    }

    @Test
    fun `the decision travels as text inside a tool result`() {
        // Not as a structured result and not as the result itself: the CLI reads
        // the first content block and parses its text as JSON.
        val result = protocol(ApprovalDecision.Allow(null)).handle(CALL)!!.getAsJsonObject("result")
        val block = result.getAsJsonArray("content")[0].asJsonObject
        assertEquals("text", block.get("type").asString)
        assertTrue(block.get("text").asString.startsWith("{"))
    }

    @Test
    fun `the tool call is passed on as the CLI described it`() {
        var seenTool: String? = null
        var seenCommand: String? = null
        var seenId: String? = null
        val protocol = McpApprovalProtocol { toolName, input, toolUseId ->
            seenTool = toolName
            seenCommand = input.get("command").asString
            seenId = toolUseId
            ApprovalDecision.Allow(null)
        }
        protocol.handle(CALL)
        assertEquals("PowerShell", seenTool)
        assertEquals("npm test", seenCommand)
        assertEquals("toolu_01", seenId)
    }

    @Test
    fun `an unknown method is answered rather than dropped`() {
        // The client probes for things this server does not have; a truthful
        // empty result keeps the session up where an error would not.
        val response = allowAll().handle(rpc("""{"jsonrpc":"2.0","id":9,"method":"server/discover"}"""))!!
        assertEquals(0, response.getAsJsonObject("result").size())
    }

    private fun decisionFrom(response: JsonObject): JsonObject {
        val text = response.getAsJsonObject("result")
            .getAsJsonArray("content")[0].asJsonObject
            .get("text").asString
        return JsonParser.parseString(text).asJsonObject
    }

    private companion object {
        /** Shaped exactly like a request recorded from CLI 2.1.232. */
        val CALL: JsonObject = JsonParser.parseString(
            """
            {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
              "name":"approval_prompt",
              "arguments":{"tool_name":"PowerShell","input":{"command":"npm test"},"tool_use_id":"toolu_01"}
            }}
            """
        ).asJsonObject
    }
}
