package com.claudecode.chatplugin.model

/**
 * A non-edit tool call Claude made (Read, Bash, Grep, ...), tracked so its
 * result status can be filled in when the matching `tool_result` arrives.
 * Correlated by [id] (the CLI's `tool_use` id).
 */
data class ToolCall(
    val id: String?,
    val display: String,
    var status: Status = Status.RUNNING,
    /** Trimmed result text (command output, error message), shown collapsed. */
    var output: String? = null,
    /**
     * The permission question for *this* call, when the CLI stopped to ask one.
     *
     * It lives on the tool call rather than in a message of its own so that the
     * question, the command and its output are one thing on screen. Correlated
     * by [id]: the approval request carries the same `tool_use_id`.
     */
    var approval: com.claudecode.chatplugin.permissions.ApprovalRequest? = null
) {
    enum class Status { RUNNING, OK, ERROR }
}
