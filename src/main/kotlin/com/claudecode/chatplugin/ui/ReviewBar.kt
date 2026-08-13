package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.review.EditReviewService
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * The strip above the prompt that appears while Claude's edits are unreviewed.
 *
 * It is a view onto [EditReviewService] — the same state the in-editor controls
 * act on — so accepting a change in a file updates the count here, and Accept
 * all clears the markers in every open editor.
 */
class ReviewBar(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val service = EditReviewService.getInstance(project)

    private val summary = JLabel().apply {
        border = JBUI.Borders.empty(0, 2)
    }

    init {
        isVisible = false
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBUI.CurrentTheme.Advertiser.borderColor(), 0, 0, 1, 0),
            JBUI.Borders.empty(5, 8)
        )

        add(summary, BorderLayout.CENTER)
        add(
            JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(
                    ReviewActionButton.accept("Accept all", "Keep every change Claude made") {
                        service.acceptAll()
                    }
                )
                add(
                    ReviewActionButton.reject(
                        "Reject all", "Restore every file to how it was before this turn"
                    ) { rejectAllWithConfirmation() }
                )
            },
            BorderLayout.EAST
        )

        HandCursors.applyTo(this)

        service.addChangeListener(this) {
            ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) refresh() }
        }
        refresh()
    }

    /**
     * The counts are read before asking, not after: accepting the dialog is
     * what empties them, and a question about "0 changes in 0 files" would be
     * the last thing the user sees before their work is put back.
     */
    private fun rejectAllWithConfirmation() {
        val changes = service.pendingHunkCount
        val files = service.pendingFileCount
        if (changes == 0) return
        if (ReviewConfirmations.confirmRejectAll(project, changes, files)) service.rejectAll()
    }

    /** Nothing of its own to release; being [Disposable] is what unsubscribes it. */
    override fun dispose() = Unit

    fun refresh() {
        val hunks = service.pendingHunkCount
        val files = service.pendingFileCount
        isVisible = hunks > 0
        summary.text = when {
            hunks == 0 -> " "
            files == 1 -> "$hunks ${plural(hunks, "change")} awaiting review"
            else -> "$hunks ${plural(hunks, "change")} in $files files awaiting review"
        }
        revalidate()
        repaint()
    }

    private fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"
}
