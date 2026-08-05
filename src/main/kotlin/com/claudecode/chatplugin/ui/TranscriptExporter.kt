package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.model.ChatMessage
import com.claudecode.chatplugin.model.ClaudeSession
import com.claudecode.chatplugin.model.Role

/**
 * Serialises a conversation to Markdown for copying or saving.
 *
 * Deliberately plain: headings per turn, fenced blocks left as Claude wrote
 * them, and a short trailer of what the turn actually did (tools, edits) so an
 * exported transcript still shows which files were touched.
 */
object TranscriptExporter {

    fun toMarkdown(session: ClaudeSession): String {
        val out = StringBuilder()
        out.append("# ").append(session.displayName).append("\n\n")

        session.selectedModel?.let { out.append("- Model: `").append(it).append("`\n") }
        if (session.turnCount > 0) {
            out.append("- Usage: ").append(session.turnCount).append(" turns, ")
                // Locale.ROOT: an exported transcript should read the same (and diff
                // the same) whatever decimal separator the machine happens to use.
                .append("$" + String.format(java.util.Locale.ROOT, "%.4f", session.totalCostUsd)).append(", ")
                .append(session.totalInputTokens).append(" in / ")
                .append(session.totalOutputTokens).append(" out tokens\n")
        }
        if (out.last() != '\n' || session.selectedModel != null || session.turnCount > 0) out.append("\n")

        session.messages.forEach { message ->
            out.append("## ").append(heading(message)).append("\n\n")
            if (message.text.isNotBlank()) out.append(message.text.trim()).append("\n\n")
            appendActivity(out, message)
        }
        return out.toString().trimEnd() + "\n"
    }

    private fun heading(message: ChatMessage): String = when (message.role) {
        Role.USER -> "You"
        Role.SYSTEM -> "Claude Brains"
        else -> "Claude"
    }

    private fun appendActivity(out: StringBuilder, message: ChatMessage) {
        if (message.toolCalls.isEmpty() && message.edits.isEmpty()) return

        message.toolCalls.forEach { tc ->
            val mark = when (tc.status) {
                com.claudecode.chatplugin.model.ToolCall.Status.OK -> "✓"
                com.claudecode.chatplugin.model.ToolCall.Status.ERROR -> "✗"
                else -> "•"
            }
            out.append("- ").append(mark).append(' ').append(tc.display).append('\n')
        }
        message.edits.forEach { edit ->
            out.append("- ✎ ").append(edit.toolName).append(' ').append(edit.filePath).append('\n')
        }
        out.append('\n')
    }
}
