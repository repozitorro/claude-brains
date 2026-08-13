package com.claudecode.chatplugin.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder

/**
 * The question asked before throwing work away.
 *
 * Reject all is the only control that rewrites several files at once, from a
 * button sitting next to one that does the opposite. Accepting changes nothing
 * on disk — the text is already there — so only this side asks.
 *
 * Both routes to it (the bar above the prompt and the IDE action) come through
 * here, so they cannot drift into asking differently, or one of them into not
 * asking at all.
 */
object ReviewConfirmations {

    fun confirmRejectAll(project: Project, changes: Int, files: Int): Boolean =
        MessageDialogBuilder
            .yesNo("Reject all changes?", message(changes, files))
            .yesText("Reject all")
            .noText("Keep")
            .icon(AllIcons.General.WarningDialog)
            .ask(project)

    /**
     * Says what is about to happen in the user's own terms — how much, and in
     * how many files — and that it is not a one-way door.
     *
     * Separated from the dialog so the wording can be tested; a count that says
     * "1 changes in 1 files" reads as a bug in everything around it.
     */
    internal fun message(changes: Int, files: Int): String = buildString {
        append(if (changes == 1) "1 change" else "$changes changes")
        append(if (files == 1) " in 1 file" else " in $files files")
        append(" will be put back to how they were before Claude's edits.\n\n")
        append("Rejecting is an ordinary edit, so Undo in each editor can bring it back — ")
        append("one step per change.")
    }
}
