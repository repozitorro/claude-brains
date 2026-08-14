package com.claudecode.chatplugin.review

import com.claudecode.chatplugin.model.ChatMessage
import com.claudecode.chatplugin.model.FileEdit

/**
 * Putting the files back to how they were earlier in the conversation.
 *
 * Reverting one edit is easy; the useful case is "this went wrong five
 * messages ago". Later edits are written on top of earlier ones, so undoing
 * them out of order would restore text that was never on disk — the work is
 * entirely in doing it newest first.
 *
 * What this deliberately does **not** do is truncate the conversation. The CLI
 * keeps its own transcript and resumes from it, so dropping messages here would
 * leave the chat showing less than Claude remembers — a disagreement that would
 * only surface later, as an answer referring to something no longer on screen.
 * The files go back; the conversation stands.
 */
object ConversationRestore {

    /**
     * The edits to undo to reach the state before [fromIndex], newest first.
     *
     * Only resolved edits: one whose before/after was never worked out has
     * nothing to restore from.
     */
    fun editsToRevert(messages: List<ChatMessage>, fromIndex: Int): List<FileEdit> {
        if (fromIndex < 0 || fromIndex >= messages.size) return emptyList()
        return messages.drop(fromIndex)
            .flatMap { it.edits }
            .filter { it.isResolved }
            .reversed()
    }

    /**
     * Whether a message is worth offering the action on at all — there is
     * something to undo from here or after it.
     */
    fun hasSomethingToRestore(messages: List<ChatMessage>, fromIndex: Int): Boolean =
        editsToRevert(messages, fromIndex).any { it.canRevert }

    /** The files that would be touched, named for the question asked before touching them. */
    fun affectedFiles(edits: List<FileEdit>): List<String> =
        edits.filter { it.canRevert }.map { it.fileName }.distinct()
}
