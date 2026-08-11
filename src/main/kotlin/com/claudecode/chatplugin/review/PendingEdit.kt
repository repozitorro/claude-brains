package com.claudecode.chatplugin.review

import com.claudecode.chatplugin.model.FileEdit
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * One file awaiting review, split into individually acceptable [Hunk]s.
 *
 * Hunks come from the platform's own line diff between the reconstructed
 * "before" text and what the CLI wrote, so the split matches what the IDE would
 * show in its diff viewer.
 */
class PendingEdit private constructor(
    val file: VirtualFile,
    val document: Document,
    val hunks: List<Hunk>
) {

    val pendingHunks: List<Hunk> get() = hunks.filter { !it.resolved && it.isAlive }

    val isFinished: Boolean get() = pendingHunks.isEmpty()

    /** Restores this hunk's original lines. Undoable, like any editor change. */
    fun reject(project: Project, hunk: Hunk): Boolean {
        if (hunk.resolved || !hunk.isAlive) return false
        WriteCommandAction.runWriteCommandAction(project, "Reject Claude Change", null, {
            document.replaceString(hunk.marker.startOffset, hunk.marker.endOffset, hunk.removedText)
        })
        hunk.markResolved()
        return true
    }

    /** The text is already on disk, so accepting only drops the review state. */
    fun accept(hunk: Hunk) = hunk.markResolved()

    fun rejectAll(project: Project) {
        // Later hunks first: rejecting shifts everything below it, and although
        // the markers track that, going bottom-up keeps the document edits
        // independent and the undo history readable.
        pendingHunks.sortedByDescending { it.startLine() }.forEach { reject(project, it) }
    }

    fun acceptAll() = pendingHunks.forEach { it.markResolved() }

    fun dispose() = hunks.forEach { runCatching { it.marker.dispose() } }

    companion object {

        /**
         * Builds the review state for [edit], or null when there is nothing
         * reviewable.
         *
         * Requires [FileEdit.canRevert]: that flag means the reconstructed
         * "before" text provably reproduces what is on disk. Without it the
         * before-text may be wrong, and rejecting a hunk would write wrong
         * content into the user's file — so those edits keep the chat's
         * whole-file diff link instead.
         */
        fun create(edit: FileEdit, file: VirtualFile, document: Document): PendingEdit? {
            // Reconstruct against the document's own text. Markers will be placed
            // in this document, so it — not a separately-read disk snapshot — is
            // what the hunks must describe. If the file has moved on since Claude
            // wrote it, the reconstruction simply isn't exact and review declines.
            val after = document.text
            val before = edit.reconstructBefore(after) ?: return null
            if (before == after) return null

            val fragments = ComparisonManager.getInstance()
                .compareLines(before, after, ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE)

            val beforeLines = before.split("\n")
            val hunks = fragments.mapNotNull { fragment ->
                val newStart = fragment.startLine2
                val newEnd = fragment.endLine2
                val oldStart = fragment.startLine1
                val oldEnd = fragment.endLine1

                val removed = if (oldEnd > oldStart) {
                    beforeLines.subList(oldStart, oldEnd).joinToString("\n", postfix = "\n")
                } else {
                    ""
                }
                val kind = when {
                    oldEnd == oldStart -> Hunk.Kind.INSERT
                    newEnd == newStart -> Hunk.Kind.DELETE
                    else -> Hunk.Kind.MODIFY
                }

                val startOffset = document.getLineStartOffset(newStart.coerceIn(0, document.lineCount))
                val endOffset = if (newEnd > newStart) {
                    // Include the trailing newline so replacing the range with the
                    // removed text (which ends in one) keeps line structure intact.
                    val last = (newEnd - 1).coerceIn(0, document.lineCount - 1)
                    minOf(document.getLineEndOffset(last) + 1, document.textLength)
                } else {
                    startOffset
                }
                if (startOffset > endOffset) return@mapNotNull null

                Hunk(document, document.createRangeMarker(startOffset, endOffset), removed, kind)
            }

            return if (hunks.isEmpty()) null else PendingEdit(file, document, hunks)
        }
    }
}
