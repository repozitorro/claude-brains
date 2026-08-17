package com.claudecode.chatplugin.model

enum class Role { USER, ASSISTANT, SYSTEM }

/**
 * A single message in a chat session. [text] is mutated in place while an
 * assistant reply is streaming in, so the UI can observe partial content.
 */
data class ChatMessage(
    val role: Role,
    var text: String,
    /** Streamed reasoning ("thinking") content, shown muted above [text]. */
    var thinking: String = "",
    /** Non-edit tool invocations Claude made this turn, with their result status. */
    val toolCalls: MutableList<ToolCall> = mutableListOf(),
    /** File-mutating edits Claude made this turn, shown as clickable diff/revert links. */
    val edits: MutableList<FileEdit> = mutableListOf(),
    var isStreaming: Boolean = false,
    /** A refusal the user can answer, shown with Allow / Deny beneath this message. */
    var permissionRequest: PermissionRequest? = null,
    /**
     * A tool call the CLI has paused on, waiting to be told what to do.
     *
     * Unlike [permissionRequest] — which is a refusal being reconsidered after
     * the fact — nothing has happened yet, and the CLI is holding the call open
     * until this is answered.
     */
    var approvalRequest: com.claudecode.chatplugin.permissions.ApprovalRequest? = null,
    /** Errors the IDE found in the changed files, offered back to Claude with one click. */
    var problems: List<com.claudecode.chatplugin.review.ProjectProblems.Problem>? = null
)
