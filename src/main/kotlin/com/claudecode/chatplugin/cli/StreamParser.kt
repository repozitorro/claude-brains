package com.claudecode.chatplugin.cli

import com.claudecode.chatplugin.model.ClaudeSession
import com.claudecode.chatplugin.model.EditOp
import com.claudecode.chatplugin.model.FileEdit
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger

/**
 * Turns the CLI's `stream-json` output into [StreamListener] calls, one line at
 * a time.
 *
 * The protocol below was verified against Claude Code CLI **2.1.205** using:
 *
 *     claude -p "..." --output-format stream-json --verbose --include-partial-messages
 *
 * Each stdout line is a self-contained JSON object. The ones we care about:
 *
 *   {"type":"system","subtype":"init","session_id":"...", ...}   // start; has session_id
 *   {"type":"stream_event","event":{"type":"content_block_delta",
 *        "delta":{"type":"text_delta","text":"..."}}}            // streamed answer tokens
 *   {"type":"stream_event","event":{"type":"content_block_delta",
 *        "delta":{"type":"thinking_delta","thinking":"..."}}}    // streamed reasoning tokens
 *   {"type":"assistant","message":{...full block...}}            // COMPLETE block (text ignored - dup)
 *   {"type":"result","subtype":"success","result":"...",
 *        "total_cost_usd":..., "usage":{...}, "duration_ms":...}  // terminal event
 *
 * Every line also carries a top-level `session_id`.
 *
 * Correctness notes (why we only read the deltas):
 *  - The full text arrives THREE times per turn: as `text_delta`s, again in the
 *    `assistant` event, and again in `result.result`. Reading more than one of
 *    these would duplicate the reply. We stream the deltas and treat `result`
 *    purely as the end-of-turn signal (plus cost/usage metadata).
 *
 * This is the part of the plugin most exposed to CLI drift, which is why it is
 * a plain class with no IDE or process dependencies: `StreamParserTest` replays
 * a recorded turn through it.
 */
class StreamParser(
    /**
     * Reads a file's current content, for the "before" of a `Write` (which
     * cannot be reversed from the tool call alone). Injected so a test can
     * describe a file system instead of touching one.
     */
    private val snapshotReader: (String) -> String? = ::readFileSnapshot
) {

    private val log = Logger.getInstance(StreamParser::class.java)

    /**
     * Whether a content block has begun and has yet to produce anything.
     *
     * Per-turn state, which is why one of these is built for each turn rather
     * than shared: two chats streaming at once would otherwise take each
     * other's paragraph breaks.
     */
    private var blockStarted = false

    /** Processes one JSON line. Returns a [TurnResult] for the terminal `result` event, else null. */
    fun parse(line: String, listener: StreamListener): TurnResult? {
        val json = try {
            JsonParser.parseString(line).asJsonObject
        } catch (e: Exception) {
            // Not JSON (a stray log line) - ignore rather than corrupt the transcript.
            log.debug("Ignoring non-JSON stdout line: $line")
            return null
        }

        json.get("session_id")?.takeIf { it.isJsonPrimitive }?.let { listener.onSessionId(it.asString) }

        when (json.get("type")?.asString) {
            "stream_event" -> {
                val event = json.getAsJsonObject("event") ?: return null
                // A reply is written in blocks, and a turn that stops to run a
                // tool comes back in a new one. Nothing in a delta says which
                // block it belongs to — the index restarts from 0 with every
                // assistant message, so "one" and "three" either side of a tool
                // call both arrive as index 0 (verified against 2.1.232). What
                // separates them is that a block *started*, so that is what is
                // tracked, and the first text out of a new block says so.
                if (event.get("type")?.asString == "content_block_start") blockStarted = true
                if (event.get("type")?.asString == "content_block_delta") {
                    val delta = event.getAsJsonObject("delta") ?: return null
                    when (delta.get("type")?.asString) {
                        "text_delta" -> delta.get("text")?.asString?.let {
                            if (blockStarted) listener.onTextBlockStart()
                            blockStarted = false
                            listener.onTextChunk(it)
                        }
                        "thinking_delta" -> delta.get("thinking")?.asString?.let {
                            blockStarted = false
                            listener.onThinkingChunk(it)
                        }
                    }
                }
            }
            // Complete assistant blocks carry any tool_use calls (text is handled by
            // the deltas above, so we deliberately read only tool_use here).
            "assistant" -> {
                val content = json.getAsJsonObject("message")?.getAsJsonArray("content") ?: return null
                content.forEach { el ->
                    val block = el.asJsonObject
                    if (block.get("type")?.asString != "tool_use") return@forEach
                    val name = block.get("name")?.asString
                    val edit = parseFileEdit(name, block.getAsJsonObject("input"))
                    if (edit != null) listener.onFileEdit(edit)
                    else listener.onToolUse(block.get("id")?.asString, summariseToolUse(block))
                }
            }
            // Tool results come back as user events; correlate by tool_use_id.
            "user" -> {
                val content = json.getAsJsonObject("message")?.getAsJsonArray("content") ?: return null
                content.forEach { el ->
                    val block = el.asJsonObject
                    if (block.get("type")?.asString == "tool_result") {
                        val isError = block.get("is_error")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                        listener.onToolResult(
                            block.get("tool_use_id")?.asString,
                            isError,
                            extractToolResultText(block.get("content"))
                        )
                    }
                }
            }
            // The init event lists MCP servers and whether each one came up.
            "system" -> {
                if (json.get("subtype")?.asString == "init") {
                    val failed = json.getAsJsonArray("mcp_servers")?.mapNotNull { el ->
                        val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                        val status = o.get("status")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
                        val name = o.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: "unnamed"
                        if (status.equals("connected", ignoreCase = true)) null else "$name ($status)"
                    }.orEmpty()
                    if (failed.isNotEmpty()) listener.onMcpFailures(failed)

                    // The same event names every command, skill and agent this
                    // CLI has. Nothing written here could know the user's own.
                    SessionCapabilities.from(json).takeIf { !it.isEmpty }?.let(listener::onCapabilities)
                }
            }
            "rate_limit_event" -> {
                json.getAsJsonObject("rate_limit_info")?.let { info ->
                    fun str(key: String) = info.get(key)?.takeIf { it.isJsonPrimitive }?.asString
                    fun bool(key: String) = info.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                    listener.onRateLimit(
                        ClaudeSession.RateLimit(
                            status = str("status") ?: "",
                            type = str("rateLimitType") ?: "",
                            resetsAtEpochSec = info.get("resetsAt")?.takeIf { it.isJsonPrimitive }?.asLong,
                            isUsingOverage = bool("isUsingOverage"),
                            overageStatus = str("overageStatus")
                        )
                    )
                }
            }
            "result" -> {
                val usage = json.getAsJsonObject("usage")
                val isError = json.get("is_error")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                return TurnResult(
                    isError = isError,
                    costUsd = json.get("total_cost_usd")?.takeIf { it.isJsonPrimitive }?.asDouble,
                    inputTokens = usage?.get("input_tokens")?.takeIf { it.isJsonPrimitive }?.asInt,
                    outputTokens = usage?.get("output_tokens")?.takeIf { it.isJsonPrimitive }?.asInt,
                    durationMs = json.get("duration_ms")?.takeIf { it.isJsonPrimitive }?.asLong,
                    // The totals across every request the turn made: the wrong
                    // number for "how full is the context", and the right one
                    // for "how much did this send".
                    promptTokens = usage?.let {
                        num(it, "input_tokens") + num(it, "cache_read_input_tokens") +
                            num(it, "cache_creation_input_tokens")
                    },
                    contextTokens = contextTokens(usage),
                    contextWindow = parseContextWindow(json.getAsJsonObject("modelUsage")),
                    permissionDenials = parsePermissionDenials(json.getAsJsonArray("permission_denials")),
                    apiErrorStatus = json.get("api_error_status")?.takeIf { it.isJsonPrimitive }?.asInt,
                    errorMessage = if (isError) json.get("result")?.takeIf { it.isJsonPrimitive }?.asString else null
                )
            }
        }
        return null
    }

    /**
     * Pulls display text out of a `tool_result.content`, which is either a plain
     * string or an array of blocks (text, images, ...). Long output is clamped —
     * this is a preview in a chat bubble, not a terminal.
     */
    internal fun extractToolResultText(content: JsonElement?): String? {
        val raw = when {
            content == null -> null
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> content.asJsonArray.mapNotNull { el ->
                val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                when (o.get("type")?.asString) {
                    "text" -> o.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                    "image" -> "[image]"
                    else -> null
                }
            }.joinToString("\n").ifBlank { null }
            else -> null
        }
        val trimmed = raw?.trim()?.ifEmpty { null } ?: return null
        return if (trimmed.length > MAX_TOOL_OUTPUT) {
            trimmed.take(MAX_TOOL_OUTPUT) + "\n… (truncated)"
        } else {
            trimmed
        }
    }

    /** Parses a file-mutating tool_use (Edit/MultiEdit/Write) into a [FileEdit], else null. */
    private fun parseFileEdit(name: String?, input: JsonObject?): FileEdit? {
        if (input == null) return null
        val path = input.get("file_path")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        fun str(o: JsonObject, key: String) = o.get(key)?.takeIf { it.isJsonPrimitive }?.asString
        fun bool(o: JsonObject, key: String) = o.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

        return when (name) {
            "Edit" -> FileEdit(path, name, snapshotReader(path)).apply {
                ops.add(EditOp(str(input, "old_string"), str(input, "new_string"), null, bool(input, "replace_all")))
            }
            "MultiEdit" -> FileEdit(path, name, snapshotReader(path)).apply {
                input.getAsJsonArray("edits")?.forEach { e ->
                    val o = e.asJsonObject
                    ops.add(EditOp(str(o, "old_string"), str(o, "new_string"), null, bool(o, "replace_all")))
                }
            }
            "Write" -> FileEdit(path, name, snapshotReader(path)).apply {
                ops.add(EditOp(null, null, str(input, "content"), false))
            }
            else -> null
        }
    }

    private fun num(obj: JsonObject, key: String): Long =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

    /**
     * The context size from `result.modelUsage`, which reports it per model.
     *
     * A turn can touch more than one model (a sub-agent on a smaller one, say);
     * the largest window is the one the conversation itself is bounded by.
     */
    /**
     * How full the model's context was when the turn finished.
     *
     * Not the turn's totals, which is what this used to read and why the panel
     * once claimed `1.3M / 1.0M (128%)`. A turn that stops to run tools is
     * several requests, and each one re-reads the cached prefix, so the totals
     * count the same tokens over and over: they measure traffic, not occupancy,
     * and pass the window as soon as the turn is long enough.
     *
     * The last iteration is one request, and that is the thing bounded by the
     * context window. Where the CLI reports no iterations — a turn that never
     * called a tool — the totals *are* the single request, so they stand.
     */
    private fun contextTokens(usage: JsonObject?): Long? {
        if (usage == null) return null
        val last = usage.getAsJsonArray("iterations")
            ?.lastOrNull { it.isJsonObject }
            ?.asJsonObject
        val from = last ?: usage
        return num(from, "input_tokens") +
            num(from, "cache_read_input_tokens") +
            num(from, "cache_creation_input_tokens")
    }

    private fun parseContextWindow(modelUsage: JsonObject?): Long? =
        modelUsage?.entrySet()
            ?.mapNotNull { (_, value) ->
                value.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("contextWindow")?.takeIf { it.isJsonPrimitive }?.asLong
            }
            ?.maxOrNull()
            ?.takeIf { it > 0 }

    /**
     * Extracts the refused calls from `result.permission_denials`, defensively.
     *
     * The tool's input is carried along where it exists: the difference between
     * "Bash was blocked" and "`git add …` was blocked" is the difference between
     * a message you can act on and one you cannot.
     */
    private fun parsePermissionDenials(arr: JsonArray?): List<PermissionDenial> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            when {
                el.isJsonPrimitive -> PermissionDenial(el.asString)
                el.isJsonObject -> {
                    val o = el.asJsonObject
                    val name = listOf("tool_name", "toolName", "tool", "name")
                        .firstNotNullOfOrNull { k -> o.get(k)?.takeIf { it.isJsonPrimitive }?.asString }
                        ?: return@mapNotNull null
                    PermissionDenial(name, detailOf(o.getAsJsonObject("tool_input")))
                }
                else -> null
            }
        }
    }

    /** The one field of a tool's input worth naming back to the user. */
    private fun detailOf(input: JsonObject?): String? {
        if (input == null) return null
        val key = listOf("command", "file_path", "path", "pattern", "url", "query")
            .firstOrNull { input.has(it) } ?: return null
        return input.get(key)?.takeIf { it.isJsonPrimitive }?.asString
            ?.replace(Regex("\\s+"), " ")
            ?.let { if (it.length > MAX_DENIAL_DETAIL) it.take(MAX_DENIAL_DETAIL - 1) + "…" else it }
    }

    /**
     * Drops a leading `cd somewhere &&`, so the row shows the command that
     * matters rather than the one that only chose a directory.
     *
     * Only a leading one, and only when something follows it: `cd /tmp` on its
     * own is the whole command, and is left alone.
     */
    internal fun dropDirectoryPreamble(command: String): String {
        var rest = command.trim()
        while (true) {
            val match = PREAMBLE.find(rest) ?: return rest
            val remainder = rest.removeRange(match.range).trim()
            if (remainder.isEmpty()) return rest
            rest = remainder
        }
    }

    /** Turns a tool_use block into a compact one-liner like "Read foo.kt" or "Bash npm test". */
    private fun summariseToolUse(block: JsonObject): String {
        val name = block.get("name")?.asString ?: "tool"
        val input = block.getAsJsonObject("input") ?: return name
        val argKey = listOf("file_path", "path", "command", "pattern", "url", "query", "prompt")
            .firstOrNull { input.has(it) }
        val arg = argKey?.let { input.get(it)?.takeIf { v -> v.isJsonPrimitive }?.asString } ?: return name
        // Shorten file paths to their basename; clamp long commands.
        val shown = if (argKey == "file_path" || argKey == "path") {
            arg.replace('\\', '/').substringAfterLast('/')
        } else {
            // `cd "D:\Work\project" && npm run graphify -- query …` spends its
            // whole width on the part that chooses a directory, and the command
            // it actually runs falls off the end. The CLI writes that preamble
            // constantly, having no other way to choose one.
            dropDirectoryPreamble(arg)
                .replace(Regex("\\s+"), " ")
                .let { if (it.length > 60) it.take(57) + "…" else it }
        }
        return "$name $shown"
    }

    companion object {
        /** Tool output beyond this is clamped before it reaches the chat. */
        /**
         * How much of a tool's output is kept.
         *
         * 2000 was a cautious first number and it cut most real output off
         * mid-sentence: a test run, a build log, a query that answers with a
         * list. The block it lands in scrolls, so length costs a scrollbar
         * rather than a wall of text, and this is per tool call in memory only
         * — none of it is persisted.
         */
        internal const val MAX_TOOL_OUTPUT = 20_000

        /** A blocked command is quoted back in one line, not in full. */
        internal const val MAX_DENIAL_DETAIL = 80

        /**
         * A leading `cd …` (or `pushd`) joined to the rest by `&&` or `;`.
         *
         * The path may be quoted and may contain spaces, so it is taken up to
         * the joining operator rather than to the first space.
         */
        private val PREAMBLE = Regex("""^\s*(cd|pushd)\s+("[^"]*"|'[^']*'|\S+)\s*(&&|;)\s*""")
    }
}

/** Best-effort snapshot of a file's current on-disk content (the "before" for Write). */
private fun readFileSnapshot(path: String): String? = try {
    java.io.File(path).takeIf { it.isFile }?.readText()
} catch (e: Exception) {
    null
}
