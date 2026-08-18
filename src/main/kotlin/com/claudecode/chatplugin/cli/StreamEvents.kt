package com.claudecode.chatplugin.cli

import com.claudecode.chatplugin.model.ClaudeSession
import com.claudecode.chatplugin.model.FileEdit

/**
 * A tool call the CLI refused to run.
 *
 * [detail] is what the call was actually for — the command, the path — because
 * "Bash was blocked" three times over says nothing about what to allow, and
 * allowing all of Bash to unstick one `git add` is not the trade anyone wants.
 */
data class PermissionDenial(val toolName: String, val detail: String? = null)

/** Metadata from the terminal `result` event, handed to [StreamListener.onComplete]. */
data class TurnResult(
    val isError: Boolean,
    val costUsd: Double?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val durationMs: Long?,
    /** Tools the CLI refused to run this turn (from `result.permission_denials`). */
    val permissionDenials: List<PermissionDenial> = emptyList(),
    /**
     * How much of the model's context this turn carried, and how much it holds.
     * Everything the request was built from counts: fresh input plus whatever
     * was read from or written to the cache.
     */
    val contextTokens: Long? = null,
    val contextWindow: Long? = null,
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
 * `fun interface`, which may only declare one abstract method) so it can carry
 * the several callbacks a streamed turn needs.
 */
interface StreamListener {
    fun onTextChunk(chunk: String)

    /**
     * A new block of reply text is starting, after something else came first.
     *
     * The reply resumes in a fresh block every time a tool runs, and run
     * together they read as one broken sentence: "…не вигадувати свій.Тепер
     * створю…".
     */
    fun onTextBlockStart() {}
    fun onThinkingChunk(chunk: String) {}
    /** A tool Claude invoked this turn, already summarised for display (e.g. "Read foo.kt"). */
    fun onToolUse(id: String?, display: String) {}
    /** The result of a previously reported tool call, correlated by its [toolUseId]. */
    fun onToolResult(toolUseId: String?, isError: Boolean, output: String?) {}
    /** A file-mutating tool call (Edit/MultiEdit/Write), for diff review. */
    fun onFileEdit(edit: FileEdit) {}
    fun onRateLimit(rateLimit: ClaudeSession.RateLimit) {}
    /** MCP servers that did not come up, as "name (status)" strings. */
    fun onMcpFailures(failed: List<String>) {}

    /** What the CLI says it can do this turn — commands, skills, agents. */
    fun onCapabilities(capabilities: SessionCapabilities) {}
    fun onSessionId(cliSessionId: String)
    /** The stored `--resume` id was gone, so the turn was retried with a fresh context. */
    fun onSessionExpired() {}
    fun onComplete(result: TurnResult)
    fun onError(message: String)
}
