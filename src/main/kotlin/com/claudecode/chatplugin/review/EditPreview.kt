package com.claudecode.chatplugin.review

import com.claudecode.chatplugin.model.DiffLine
import com.claudecode.chatplugin.model.FileEdit
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.progress.DumbProgressIndicator

/**
 * The change, small enough to read where it happened.
 *
 * A line saying `Edit App.kt  diff  revert` tells you a file was touched and
 * nothing about what was done to it — deciding required opening a separate
 * window, for every file, every turn. Most edits are a handful of lines and
 * belong in the conversation next to the sentence explaining them.
 *
 * Only the changed lines are shown, with no surrounding context. Context is
 * what the diff window is for; this is for "is that what I meant?".
 */
object EditPreview {

    /** Beyond this it stops being a glance and the diff window is the right tool. */
    const val MAX_LINES = 24

    fun of(edit: FileEdit, maxLines: Int = MAX_LINES): List<DiffLine> {
        val before = edit.beforeText ?: return emptyList()
        val after = edit.afterText ?: return emptyList()
        if (before == after) return emptyList()

        val fragments = runCatching {
            ComparisonManager.getInstance()
                .compareLines(before, after, ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE)
        }.getOrNull() ?: return emptyList()

        val beforeLines = before.split("\n")
        val afterLines = after.split("\n")
        val out = mutableListOf<DiffLine>()

        for ((index, fragment) in fragments.withIndex()) {
            if (index > 0) out += DiffLine(DiffLine.Kind.GAP, "")
            beforeLines.subList(
                fragment.startLine1.coerceIn(0, beforeLines.size),
                fragment.endLine1.coerceIn(0, beforeLines.size)
            ).forEach { out += DiffLine(DiffLine.Kind.REMOVED, it) }
            afterLines.subList(
                fragment.startLine2.coerceIn(0, afterLines.size),
                fragment.endLine2.coerceIn(0, afterLines.size)
            ).forEach { out += DiffLine(DiffLine.Kind.ADDED, it) }

            if (out.size > maxLines) {
                // Cut on a whole change rather than mid-hunk: half a replacement
                // reads as a deletion.
                return out.take(maxLines) + DiffLine(DiffLine.Kind.GAP, "…")
            }
        }
        return out
    }
}
