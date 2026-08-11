package com.claudecode.chatplugin.review

import com.intellij.openapi.editor.RangeMarker

/**
 * One reviewable change inside a file.
 *
 * The current (post-edit) lines are held by a [RangeMarker] rather than plain
 * offsets: rejecting one hunk rewrites part of the document, and every other
 * hunk's position shifts with it. The marker follows those shifts, so hunks
 * stay correct in any order the user reviews them.
 */
class Hunk(
    /**
     * The document the [marker] lives in, held directly.
     *
     * Deliberately not read back off the marker: `RangeMarker.getDocument()`
     * can go through `FileDocumentManager`, which asserts that a read action is
     * held. That made painting a hunk — and stepping between hunks — throw on
     * the EDT, where no read action is in force. The document is known at
     * construction, so there is nothing to look up.
     */
    val document: com.intellij.openapi.editor.Document,
    /** Covers the lines Claude produced. Empty range for a pure deletion. */
    val marker: RangeMarker,
    /** The lines that were there before, restored verbatim on reject. */
    val removedText: String,
    val kind: Kind
) {
    enum class Kind { INSERT, DELETE, MODIFY }

    /** False once the document has changed underneath in a way that invalidated it. */
    val isAlive: Boolean get() = marker.isValid

    var resolved: Boolean = false
        private set

    fun markResolved() {
        resolved = true
    }

    /** Line the hunk starts on, for scrolling and for ordering. */
    fun startLine(): Int =
        if (marker.isValid) document.getLineNumber(marker.startOffset) else -1

    /** Removed lines, for rendering them above the change. */
    fun removedLines(): List<String> =
        if (removedText.isEmpty()) emptyList() else removedText.removeSuffix("\n").split("\n")
}
