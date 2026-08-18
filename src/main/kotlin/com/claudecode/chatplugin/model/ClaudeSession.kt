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

    /**
     * What this CLI reported it can do, from the last turn's `init` event.
     *
     * Not persisted: it describes the installed CLI, which can change between
     * sessions, and the next turn will say so again.
     */
    @Volatile
    var capabilities: com.claudecode.chatplugin.cli.SessionCapabilities? = null

    /**
     * Whether the next turn should branch from [cliSessionId] rather than
     * continue it.
     *
     * Set when a chat is opened as a branch of another. Cleared once the CLI
     * has answered with an id of its own — and not before, so a turn that fails
     * branches on the next attempt instead of writing into the original.
     */
    @Volatile
    var forkOnNextTurn: Boolean = false

    /** Subagent for this chat, from the CLI's own list. Null = its default. */
    var selectedAgent: String? = null

    /** Effort level for this chat: low…max. Null = the CLI's default. */
    var selectedEffort: String? = null

    /** Model override, e.g. "claude-opus-5" or the alias "opus". Null = CLI default. */
    var selectedModel: String? = null

    /** Permission mode for this chat. Null = fall back to the project setting. */
    var permissionMode: String? = null

    /**
     * Tools granted from a blocked message, for this chat only.
     *
     * Deliberately not persisted and deliberately not the project setting: this
     * is the answer to "let it do that", given once, in the conversation where
     * it came up. Anything meant to outlive the chat goes to Settings instead,
     * which is a separate button and a deliberate act.
     */
    val grantedTools: MutableSet<String> = java.util.concurrent.CopyOnWriteArraySet()

    /**
     * Programs answered with "always" on a live permission card, by
     * [com.claudecode.chatplugin.permissions.AutoApproval] key.
     *
     * Same reasoning as [grantedTools], and deliberately separate: these were
     * granted to a question the CLI asked, not to one it refused.
     */
    val autoApproved: MutableSet<String> = java.util.concurrent.CopyOnWriteArraySet()

    /**
     * Where this chat's CLI should send permission questions.
     *
     * Set by the panel that can show them and answer them; null when there is
     * no such panel, or no endpoint could be opened, in which case the CLI goes
     * back to deciding alone. Not persisted: the port changes every run.
     */
    @Volatile
    var approvalEndpoint: String? = null

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
