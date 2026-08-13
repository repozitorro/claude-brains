package com.claudecode.chatplugin.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * The strip that floats over the bottom of an editor while the file has changes
 * waiting on a decision: step between them, or take the file as a whole.
 *
 * It is added to the editor's content component and repositioned as the view
 * scrolls, so it stays put over the visible area instead of scrolling away with
 * the text.
 */
class ReviewFloatingToolbar(
    private val project: Project,
    private val editor: Editor,
    private val file: VirtualFile
) {

    private val service get() = EditReviewService.getInstance(project)

    private val counter = JLabel().apply {
        border = JBUI.Borders.empty(0, 6, 0, 2)
    }

    private val panel = JPanel(FlowLayout(FlowLayout.CENTER, 2, 2)).apply {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1),
            JBUI.Borders.empty(2)
        )
        background = JBUI.CurrentTheme.Popup.BACKGROUND
        isOpaque = true

        add(counter)
        // "file" is in the label, not only the tooltip: the painted buttons
        // inside a change say Accept and Reject too, and these decide the whole
        // file rather than the one change you are looking at.
        add(
            com.claudecode.chatplugin.ui.ReviewActionButton.accept(
                "Accept file", "Accept every change in this file"
            ) { service.acceptFile(file) }
        )
        add(
            com.claudecode.chatplugin.ui.ReviewActionButton.reject(
                "Reject file", "Reject every change in this file"
            ) { service.rejectFile(file) }
        )
        add(button(AllIcons.Actions.PreviousOccurence, "Previous change, across every changed file") {
            jump(back = true)
        })
        add(button(AllIcons.Actions.NextOccurence, "Next change, across every changed file") {
            jump(back = false)
        })
    }

    private val areaListener = VisibleAreaListener { reposition() }

    private fun button(icon: Icon, tooltip: String, action: () -> Unit) = JButton(icon).apply {
        toolTipText = tooltip
        isFocusable = false
        margin = JBUI.emptyInsets()
        putClientProperty("JButton.buttonType", "toolBarButton")
        addActionListener { action() }
    }.let { com.claudecode.chatplugin.ui.HandCursors.on(it) }

    fun attach() {
        // Index 0 is the top of Swing's z-order: added at the end, the strip
        // ends up behind the editor's own painting.
        editor.contentComponent.add(panel, 0)
        editor.scrollingModel.addVisibleAreaListener(areaListener)
        refresh()
    }

    fun detach() {
        editor.scrollingModel.removeVisibleAreaListener(areaListener)
        editor.contentComponent.remove(panel)
        editor.contentComponent.repaint()
    }

    fun refresh() {
        val pending = service.editFor(file)?.pendingHunks.orEmpty()
        panel.isVisible = pending.isNotEmpty()
        counter.text = buildString {
            append(if (pending.size == 1) "1 change" else "${pending.size} changes")
            // Say that stepping will leave this file, so arriving somewhere else
            // reads as the button working rather than as it misfiring.
            val elsewhere = service.reviewedFiles().count { it != file }
            if (elsewhere > 0) {
                append(if (elsewhere == 1) " · 1 more file" else " · $elsewhere more files")
            }
        }
        reposition()
    }

    /**
     * Steps to the next (or previous) change — continuing into the other files
     * of the turn once this one has none left in that direction.
     */
    private fun jump(back: Boolean) {
        val lines = service.pendingLines(file)

        when (val step = ReviewNavigation.step(lines, editor.caretModel.logicalPosition.line, back)) {
            is ReviewNavigation.Step.ToLine -> moveTo(step.line)
            ReviewNavigation.Step.ToAnotherFile -> {
                val next = ReviewNavigation.neighbour(service.reviewedFiles(), file, back)
                when {
                    // Another file still has changes: carry on there.
                    next != null && service.openAtChange(next, back) -> Unit
                    // This is the only file left, so wrap inside it rather than
                    // going dead at the last change.
                    else -> ReviewNavigation.entryLine(lines, back)?.let { moveTo(it) }
                }
            }
        }
    }

    private fun moveTo(line: Int) {
        editor.caretModel.moveToLogicalPosition(com.intellij.openapi.editor.LogicalPosition(line, 0))
        editor.selectionModel.removeSelection()
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        // The caret is only visible in a focused editor, and the strip's buttons
        // are deliberately not focusable — without this the view scrolls but
        // nothing shows where it landed.
        editor.contentComponent.requestFocusInWindow()
    }

    private fun reposition() {
        val size = panel.preferredSize
        val area = editor.scrollingModel.visibleArea
        panel.setBounds(
            area.x + (area.width - size.width) / 2,
            area.y + area.height - size.height - JBUI.scale(16),
            size.width,
            size.height
        )
        // The editor's content component lays nothing out, so the strip has to
        // lay itself out: without this its buttons keep zero bounds, which means
        // they never paint where they appear to be and a click lands on the
        // panel behind them instead of on a button.
        panel.validate()
        editor.contentComponent.repaint()
    }

}
