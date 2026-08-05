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
    var isStreaming: Boolean = false
)
