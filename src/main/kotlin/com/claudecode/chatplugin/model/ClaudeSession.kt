package com.claudecode.chatplugin.model

import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Represents one independent conversation. Each session keeps its own
 * `claude` CLI session id so it can be resumed with `--resume <id>`,
 * letting several conversations run in parallel without mixing context.
 */
class ClaudeSession(var displayName: String) {
    val id: String = UUID.randomUUID().toString()
    val messages: MutableList<ChatMessage> = CopyOnWriteArrayList()

    /** The CLI's own session id, captured from the first streamed response. */
    var cliSessionId: String? = null

    /** Model override, e.g. "claude-opus-5" or the alias "opus". Null = CLI default. */
    var selectedModel: String? = null

    /** Permission mode for this chat. Null = fall back to the project setting. */
    var permissionMode: String? = null

    @Volatile
    var isBusy: Boolean = false

    /** The currently running CLI process, if any, so the turn can be cancelled. */
    @Volatile
    var process: Process? = null

    // --- Cumulative usage analytics for this session ---
    var totalCostUsd: Double = 0.0
    var totalInputTokens: Long = 0
    var totalOutputTokens: Long = 0
    var turnCount: Int = 0

    /** How full the model's context was on the last turn, and how big it is. */
    var contextTokens: Long? = null
    var contextWindow: Long? = null

    /** Latest rate-limit snapshot from the CLI's `rate_limit_event`, if any. */
    var rateLimit: RateLimit? = null

    /** A `rate_limit_event.rate_limit_info` snapshot. */
    data class RateLimit(
        val status: String,           // e.g. "allowed"
        val type: String,             // e.g. "five_hour"
        val resetsAtEpochSec: Long?,  // unix seconds when the window resets
        val isUsingOverage: Boolean = false,
        val overageStatus: String? = null
    )
}
