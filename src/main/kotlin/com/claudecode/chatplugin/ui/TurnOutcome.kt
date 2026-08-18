package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.cli.TurnResult
import com.claudecode.chatplugin.model.FileEdit

/**
 * What the user is told when a turn ends.
 *
 * Pulled out of the stream listener because this is the part of it that can be
 * wrong on its own. The listener around it is glue — it moves an event onto the
 * EDT and calls something else — and extracting *that* would have produced a
 * class with fifteen collaborators in its constructor: the coupling made
 * visible rather than reduced. The wording is different. A turn that fails
 * silently, or a warning that says a file was left out without saying the
 * user's work is safe, is a real defect, and none of it was reachable by a test.
 */
object TurnOutcome {

    /**
     * The text to append to a reply when the turn ended badly, or null when it
     * ended well.
     *
     * A failed turn carries its reason in the result event rather than as
     * streamed text, so without this the chat shows an empty answer and no
     * explanation at all.
     */
    fun errorText(result: TurnResult, replyIsEmpty: Boolean): String? {
        if (!result.isError) return null
        val reason = result.errorMessage?.takeIf { it.isNotBlank() } ?: "the turn ended with an error"
        // Being signed out is the one failure with a specific next step, and
        // saying it here saves reading an error that explains nothing.
        val hint = if (result.apiErrorStatus == UNAUTHORIZED) {
            "\n\nSign in again using the banner above, then send this message once more."
        } else {
            ""
        }
        val prefix = if (replyIsEmpty) "" else "\n\n"
        return "$prefix**Error:** $reason$hint"
    }

    /** True when the failure is one the sign-in banner should answer. */
    fun needsSignIn(result: TurnResult): Boolean = result.isError && result.apiErrorStatus == UNAUTHORIZED

    /**
     * Why some edits could not be marked up in the editor.
     *
     * Line-by-line review needs the pre-edit content reconstructed exactly;
     * where it cannot be, accepting or rejecting a hunk would be guesswork, and
     * saying so beats a review that quietly does the wrong thing.
     */
    fun unreviewableMessage(files: List<FileEdit>): String =
        "Couldn't mark up ${files.joinToString { it.fileName }} for inline review — the pre-edit " +
            "content can't be reconstructed exactly, so accepting or rejecting line by line would be " +
            "guesswork. Use the **diff** link above to review the whole file instead."

    /**
     * Two versions of a file exist and only the user can choose between them.
     *
     * Deliberately not an apology: nothing was lost, and the message's job is
     * to say exactly what is where, so the choice can be made without opening
     * anything to find out.
     */
    fun conflictedMessage(files: List<FileEdit>): String =
        "⚠️ **You have unsaved changes** in ${files.joinToString { it.fileName }}, and Claude edited " +
            "the same file on disk.\n\n" +
            "Your version is untouched in the editor, so nothing of yours was lost — but it was left " +
            "out of inline review, because reloading it would have discarded your work. Use the " +
            "**diff** link above to see Claude's version, then either save yours over it or reload " +
            "the file from disk to take Claude's."

    private const val UNAUTHORIZED = 401
}
