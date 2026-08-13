package com.claudecode.chatplugin.review

/**
 * Where the next / previous button lands.
 *
 * A turn usually changes several files, and review runs until every change has
 * been decided — so stepping is over the whole turn, not over one file. The
 * rules live here, away from the editor, because the interesting cases are the
 * boundaries: the last change in a file, the last file, and the file that is
 * the only one left.
 */
internal object ReviewNavigation {

    sealed interface Step {
        /** Another change in the file already open. */
        data class ToLine(val line: Int) : Step

        /** This file is done in this direction; continue in another one. */
        object ToAnotherFile : Step
    }

    /**
     * The step to take inside the current file, given the lines that still hold
     * changes and where the caret is.
     */
    fun step(lines: List<Int>, caretLine: Int, back: Boolean): Step {
        val next = if (back) lines.lastOrNull { it < caretLine } else lines.firstOrNull { it > caretLine }
        return if (next != null) Step.ToLine(next) else Step.ToAnotherFile
    }

    /**
     * The file to continue in, wrapping around the ends. Null when [current] is
     * the only one left — the caller then wraps inside it, so a single-file
     * review still steps in a circle instead of going dead at the last change.
     */
    fun <T> neighbour(files: List<T>, current: T, back: Boolean): T? {
        if (files.size <= 1) return null
        val index = files.indexOf(current)
        if (index < 0) return if (back) files.last() else files.first()
        val step = if (back) -1 else 1
        return files[(index + step + files.size) % files.size]
    }

    /** Where to land in a file arrived at from elsewhere: its first change, or its last when going back. */
    fun entryLine(lines: List<Int>, back: Boolean): Int? =
        if (back) lines.maxOrNull() else lines.minOrNull()
}
