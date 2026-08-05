package com.claudecode.chatplugin

import com.claudecode.chatplugin.model.ClaudeSession
import com.claudecode.chatplugin.model.EditOp
import com.claudecode.chatplugin.model.FileEdit
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

/**
 * Wraps the `claude` CLI as a subprocess and streams its output back to the UI.
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
 *   {"type":"assistant","message":{...full block...}}            // COMPLETE block (ignored - dup)
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
 */
@Service(Service.Level.PROJECT)
class ClaudeCliService(private val project: Project) : com.intellij.openapi.Disposable {

    private val log = Logger.getInstance(ClaudeCliService::class.java)
    private val executor = Executors.newCachedThreadPool()

    /** Shuts the worker pool down with the project, so its threads don't outlive it. */
    override fun dispose() {
        executor.shutdownNow()
    }

    private val settings get() = ClaudeCodeSettings.getInstance(project)

    /** Metadata from the terminal `result` event, handed to [StreamListener.onComplete]. */
    data class TurnResult(
        val isError: Boolean,
        val costUsd: Double?,
        val inputTokens: Int?,
        val outputTokens: Int?,
        val durationMs: Long?,
        /** Tools the CLI refused to run this turn (from `result.permission_denials`). */
        val permissionDenials: List<String> = emptyList(),
        /** HTTP status when the turn failed against the API (e.g. 401 for expired auth). */
        val apiErrorStatus: Int? = null,
        /**
         * Human-readable failure text. On a failed turn the CLI puts the reason in
         * `result.result` (where a successful turn would repeat the answer), so this
         * is only populated when [isError] is true — reading it unconditionally
         * would duplicate the streamed reply.
         */
        val errorMessage: String? = null
    )

    /**
     * Callbacks for a single streamed turn. A plain interface (NOT a Kotlin
     * `fun interface`, which may only declare one abstract method) so it can
     * carry the several callbacks a streamed turn needs.
     */
    interface StreamListener {
        fun onTextChunk(chunk: String)
        fun onThinkingChunk(chunk: String) {}
        /** A tool Claude invoked this turn, already summarised for display (e.g. "Read foo.kt"). */
        fun onToolUse(id: String?, display: String) {}
        /** The result of a previously reported tool call, correlated by its [toolUseId]. */
        fun onToolResult(toolUseId: String?, isError: Boolean) {}
        /** A file-mutating tool call (Edit/MultiEdit/Write), for diff review. */
        fun onFileEdit(edit: FileEdit) {}
        fun onRateLimit(rateLimit: ClaudeSession.RateLimit) {}
        /** MCP servers that did not come up, as "name (status)" strings. */
        fun onMcpFailures(failed: List<String>) {}
        fun onSessionId(cliSessionId: String)
        /** The stored `--resume` id was gone, so the turn was retried with a fresh context. */
        fun onSessionExpired() {}
        fun onComplete(result: TurnResult)
        fun onError(message: String)
    }

    /**
     * Sends [prompt] for the given [session]. Resumes the CLI's own session
     * (`--resume`) once this session has an id, so multi-turn context is
     * preserved independently per chat tab.
     */
    fun sendPrompt(session: ClaudeSession, prompt: String, listener: StreamListener) {
        if (session.isBusy) {
            listener.onError("This session is already waiting on a response.")
            return
        }
        session.isBusy = true

        executor.submit {
            try {
                runProcess(session, prompt, listener)
            } catch (e: Exception) {
                log.warn("Claude CLI invocation failed", e)
                listener.onError(e.message ?: e.toString())
            } finally {
                session.isBusy = false
                session.process = null
            }
        }
    }

    /** Terminates the running CLI process for [session], if any (Stop button). */
    fun cancel(session: ClaudeSession) {
        session.process?.destroy()
    }

    private fun runProcess(
        session: ClaudeSession,
        prompt: String,
        listener: StreamListener,
        allowResume: Boolean = true
    ) {
        val claudeCommand = settings.claudeCommand
        val command = buildList {
            add(claudeCommand)
            add("-p")
            add(prompt)
            add("--output-format")
            add("stream-json")
            add("--verbose")                 // required alongside stream-json
            add("--include-partial-messages") // enables token-by-token text/thinking deltas
            settings.permissionMode.takeIf { it.isNotBlank() && it != "default" }?.let {
                add("--permission-mode")
                add(it)
            }
            settings.allowedTools.trim().takeIf { it.isNotEmpty() }?.let {
                add("--allowedTools")
                add(it)
            }
            settings.disallowedTools.trim().takeIf { it.isNotEmpty() }?.let {
                add("--disallowedTools")
                add(it)
            }
            if (allowResume) {
                session.cliSessionId?.let {
                    add("--resume")
                    add(it)
                }
            }
            session.selectedModel?.let {
                add("--model")
                add(it)
            }
        }

        val usedResume = command.contains("--resume")

        val workingDir = project.basePath?.let { java.io.File(it) }
        val process = try {
            ProcessBuilder(command)
                .apply { if (workingDir != null) directory(workingDir) }
                .redirectErrorStream(false)
                .start()
        } catch (e: java.io.IOException) {
            listener.onError(
                "Could not launch '$claudeCommand'. Is the Claude Code CLI installed " +
                    "and on your PATH? You can set a full path in Settings > Tools > " +
                    "Claude Brains. (${e.message})"
            )
            return
        }
        session.process = process

        val stderr = StringBuilder()
        val stderrThread = Thread {
            BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).forEachLine { line ->
                log.info("claude stderr: $line")
                stderr.appendLine(line)
            }
        }.apply { isDaemon = true; start() }

        var completed: TurnResult? = null
        BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                handleLine(line, listener)?.let { completed = it }
            }
        }

        val exitCode = process.waitFor()
        stderrThread.join(500)

        val stderrText = stderr.toString().trim()
        val result = completed

        // The stored session id can be pruned by the CLI, in which case every
        // later turn in a restored tab would fail. Detect that one case and
        // retry once from a fresh context instead of leaving the tab broken.
        if (result != null && result.isError && allowResume && usedResume &&
            STALE_SESSION.containsMatchIn(stderrText)
        ) {
            log.info("Stored --resume id was stale; retrying with a fresh context")
            session.cliSessionId = null
            listener.onSessionExpired()
            runProcess(session, prompt, listener, allowResume = false)
            return
        }

        when {
            result != null -> listener.onComplete(
                // Some failures (a stale session id among them) report no text in
                // the result event and only explain themselves on stderr.
                if (result.isError && result.errorMessage.isNullOrBlank() && stderrText.isNotEmpty()) {
                    result.copy(errorMessage = stderrText)
                } else {
                    result
                }
            )
            exitCode != 0 -> listener.onError(
                "claude exited with code $exitCode" + if (stderrText.isNotEmpty()) "\n$stderrText" else ""
            )
            else -> listener.onComplete(
                TurnResult(isError = false, costUsd = null, inputTokens = null, outputTokens = null, durationMs = null)
            )
        }
    }

    /** Processes one JSON line. Returns a [TurnResult] for the terminal `result` event, else null. */
    private fun handleLine(line: String, listener: StreamListener): TurnResult? {
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
                if (event.get("type")?.asString == "content_block_delta") {
                    val delta = event.getAsJsonObject("delta") ?: return null
                    when (delta.get("type")?.asString) {
                        "text_delta" -> delta.get("text")?.asString?.let { listener.onTextChunk(it) }
                        "thinking_delta" -> delta.get("thinking")?.asString?.let { listener.onThinkingChunk(it) }
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
                        listener.onToolResult(block.get("tool_use_id")?.asString, isError)
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
                }
            }
            "rate_limit_event" -> {
                json.getAsJsonObject("rate_limit_info")?.let { info ->
                    listener.onRateLimit(
                        ClaudeSession.RateLimit(
                            status = info.get("status")?.asString ?: "",
                            type = info.get("rateLimitType")?.asString ?: "",
                            resetsAtEpochSec = info.get("resetsAt")?.takeIf { it.isJsonPrimitive }?.asLong
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
                    permissionDenials = parsePermissionDenials(json.getAsJsonArray("permission_denials")),
                    apiErrorStatus = json.get("api_error_status")?.takeIf { it.isJsonPrimitive }?.asInt,
                    errorMessage = if (isError) json.get("result")?.takeIf { it.isJsonPrimitive }?.asString else null
                )
            }
        }
        return null
    }

    /** Parses a file-mutating tool_use (Edit/MultiEdit/Write) into a [FileEdit], else null. */
    private fun parseFileEdit(name: String?, input: JsonObject?): FileEdit? {
        if (input == null) return null
        val path = input.get("file_path")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        fun str(o: JsonObject, key: String) = o.get(key)?.takeIf { it.isJsonPrimitive }?.asString
        fun bool(o: JsonObject, key: String) = o.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

        return when (name) {
            "Edit" -> FileEdit(path, name, readSnapshot(path)).apply {
                ops.add(EditOp(str(input, "old_string"), str(input, "new_string"), null, bool(input, "replace_all")))
            }
            "MultiEdit" -> FileEdit(path, name, readSnapshot(path)).apply {
                input.getAsJsonArray("edits")?.forEach { e ->
                    val o = e.asJsonObject
                    ops.add(EditOp(str(o, "old_string"), str(o, "new_string"), null, bool(o, "replace_all")))
                }
            }
            "Write" -> FileEdit(path, name, readSnapshot(path)).apply {
                ops.add(EditOp(null, null, str(input, "content"), false))
            }
            else -> null
        }
    }

    /** Extracts tool names from the `result.permission_denials` array, defensively. */
    private fun parsePermissionDenials(arr: com.google.gson.JsonArray?): List<String> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            when {
                el.isJsonPrimitive -> el.asString
                el.isJsonObject -> {
                    val o = el.asJsonObject
                    listOf("tool_name", "toolName", "tool", "name")
                        .firstNotNullOfOrNull { k -> o.get(k)?.takeIf { it.isJsonPrimitive }?.asString }
                        ?: el.toString()
                }
                else -> null
            }
        }
    }

    /** Best-effort snapshot of a file's current on-disk content (the "before" for Write). */
    private fun readSnapshot(path: String): String? = try {
        java.io.File(path).takeIf { it.isFile }?.readText()
    } catch (e: Exception) {
        null
    }

    /** Turns a tool_use block into a compact one-liner like "Read foo.kt" or "Bash npm test". */
    private fun summariseToolUse(block: com.google.gson.JsonObject): String {
        val name = block.get("name")?.asString ?: "tool"
        val input = block.getAsJsonObject("input") ?: return name
        val argKey = listOf("file_path", "path", "command", "pattern", "url", "query", "prompt")
            .firstOrNull { input.has(it) }
        val arg = argKey?.let { input.get(it)?.takeIf { v -> v.isJsonPrimitive }?.asString } ?: return name
        // Shorten file paths to their basename; clamp long commands.
        val shown = if (argKey == "file_path" || argKey == "path") {
            arg.replace('\\', '/').substringAfterLast('/')
        } else {
            arg.replace(Regex("\\s+"), " ").let { if (it.length > 60) it.take(57) + "…" else it }
        }
        return "$name $shown"
    }

    /**
     * Runs `claude mcp list` and returns its raw output (stdout+stderr).
     *
     * Adding/removing servers stays a CLI concern (`claude mcp add/remove`) —
     * this only surfaces what's configured, so the plugin never rewrites the
     * user's MCP configuration behind their back. Blocking; call off the EDT.
     */
    fun listMcpServers(): String = try {
        val process = ProcessBuilder(listOf(settings.claudeCommand, "mcp", "list"))
            .apply { project.basePath?.let { directory(java.io.File(it)) } }
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroy()
            "Timed out waiting for '${settings.claudeCommand} mcp list'."
        } else {
            output.trim().ifEmpty { "No output from '${settings.claudeCommand} mcp list'." }
        }
    } catch (e: Exception) {
        log.warn("claude mcp list failed", e)
        "Could not run '${settings.claudeCommand} mcp list': ${e.message}"
    }

    fun openSettings() {
        ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, com.claudecode.chatplugin.ui.ClaudeBrainsConfigurable::class.java)
    }

    companion object {
        fun getInstance(project: Project): ClaudeCliService = project.getService(ClaudeCliService::class.java)

        /**
         * How the CLI reports an unknown `--resume` id (verified against 2.1.205:
         * exit 1, `result.subtype == "error_during_execution"`, and this line on
         * stderr — the result event itself carries no explanatory text).
         */
        internal val STALE_SESSION = Regex("No conversation found with session ID", RegexOption.IGNORE_CASE)
    }
}
