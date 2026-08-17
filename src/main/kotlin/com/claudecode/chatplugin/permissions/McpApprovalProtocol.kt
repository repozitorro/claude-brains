package com.claudecode.chatplugin.permissions

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The MCP server the CLI asks for permission, reduced to the four messages that
 * conversation actually uses.
 *
 * `--permission-prompt-tool <tool>` (undocumented in `--help`, present and
 * working in 2.1.232) makes the CLI call one MCP tool instead of refusing, and
 * wait for the answer. It insists the tool be an MCP tool, so the plugin has to
 * be an MCP server — but only just: `initialize`, `tools/list`, `tools/call`,
 * and the handshake noise around them.
 *
 * Kept apart from the transport so the wire format can be tested by handing it
 * JSON, which is what a protocol built from observation deserves.
 */
class McpApprovalProtocol(private val ask: (toolName: String, input: JsonObject, toolUseId: String?) -> ApprovalDecision) {

    /**
     * Answers one JSON-RPC request. Returns null for a notification, which by
     * the spec is not answered at all.
     */
    fun handle(request: JsonObject): JsonObject? {
        val id = request.get("id")
        if (id == null || id.isJsonNull) return null // notification
        val method = request.get("method")?.asString.orEmpty()

        val result = when (method) {
            "initialize" -> initialize(request)
            "tools/list" -> JsonObject().apply { add("tools", JsonArray().apply { add(toolDescriptor()) }) }
            "tools/call" -> callTool(request)
            // Anything else it asks about, it asks about optionally: an empty
            // result is a truthful "nothing here" and keeps the session alive.
            else -> JsonObject()
        }

        return JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            add("result", result)
        }
    }

    private fun initialize(request: JsonObject): JsonObject = JsonObject().apply {
        // Echo the client's protocol version rather than pinning one: this
        // server is four messages wide and every version of them is the same.
        val version = request.getAsJsonObject("params")
            ?.get("protocolVersion")?.takeIf { it.isJsonPrimitive }?.asString
            ?: PROTOCOL_VERSION
        addProperty("protocolVersion", version)
        add("capabilities", JsonObject().apply { add("tools", JsonObject()) })
        add("serverInfo", JsonObject().apply {
            addProperty("name", SERVER_NAME)
            addProperty("version", "1.0.0")
        })
    }

    private fun toolDescriptor(): JsonObject = JsonObject().apply {
        addProperty("name", TOOL_NAME)
        addProperty("description", "Ask the person at the IDE whether to run this tool call.")
        add("inputSchema", JsonParser.parseString(INPUT_SCHEMA).asJsonObject)
    }

    private fun callTool(request: JsonObject): JsonObject {
        val arguments = request.getAsJsonObject("params")?.getAsJsonObject("arguments") ?: JsonObject()
        val toolName = arguments.get("tool_name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val input = arguments.getAsJsonObject("input") ?: JsonObject()
        val toolUseId = arguments.get("tool_use_id")?.takeIf { it.isJsonPrimitive }?.asString

        // This blocks — deliberately, and for as long as it takes someone to
        // read the card and click. The CLI waits (verified to 90s and beyond);
        // the caller is responsible for not waiting forever.
        val decision = ask(toolName, input, toolUseId)

        // The decision travels as JSON *text* inside a tool result, which is
        // the shape the CLI parses: it reported wanting
        // `{behavior: 'allow', updatedInput?: object} | {behavior: 'deny', message: string}`.
        val payload = JsonObject().apply {
            when (decision) {
                is ApprovalDecision.Allow -> {
                    addProperty("behavior", "allow")
                    decision.updatedInput?.let { add("updatedInput", it) }
                }

                is ApprovalDecision.Deny -> {
                    addProperty("behavior", "deny")
                    addProperty("message", decision.message)
                }
            }
        }

        return JsonObject().apply {
            add("content", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", payload.toString())
                })
            })
        }
    }

    companion object {
        const val SERVER_NAME = "claude-brains-approvals"
        const val TOOL_NAME = "approval_prompt"

        /** What `--permission-prompt-tool` has to be given. */
        const val QUALIFIED_TOOL_NAME = "mcp__${SERVER_NAME}__$TOOL_NAME"

        /**
         * The MCP configuration naming [endpointUrl] as this server.
         *
         * Given to the CLI as a file, not inline: `--mcp-config` takes either,
         * but on Windows an inline argument reaches it with the quotes stripped
         * and `//` turned into `\`, and it then goes looking for a file by that
         * name.
         */
        fun mcpConfigJson(endpointUrl: String): String = JsonObject().apply {
            add("mcpServers", JsonObject().apply {
                add(SERVER_NAME, JsonObject().apply {
                    addProperty("type", "http")
                    addProperty("url", endpointUrl)
                })
            })
        }.toString()

        private const val PROTOCOL_VERSION = "2025-06-18"

        private const val INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "tool_name": { "type": "string" },
                "input": { "type": "object" },
                "tool_use_id": { "type": "string" }
              },
              "required": ["tool_name", "input"]
            }
        """
    }
}
