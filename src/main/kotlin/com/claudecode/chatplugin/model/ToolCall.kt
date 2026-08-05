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
    var output: String? = null
) {
    enum class Status { RUNNING, OK, ERROR }
}
