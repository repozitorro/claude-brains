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
        add(button(AllIcons.Actions.Checked, "Accept every change in this file") {
            service.acceptFile(file)
        })
        add(button(AllIcons.Actions.Cancel, "Reject every change in this file") {
            service.rejectFile(file)
        })
        add(button(AllIcons.Actions.PreviousOccurence, "Previous change") { jump(back = true) })
        add(button(AllIcons.Actions.NextOccurence, "Next change") { jump(back = false) })
    }

    private val areaListener = VisibleAreaListener { reposition() }

    private fun button(icon: Icon, tooltip: String, action: () -> Unit) = JButton(icon).apply {
        toolTipText = tooltip
        isFocusable = false
        margin = JBUI.emptyInsets()
        putClientProperty("JButton.buttonType", "toolBarButton")
        addActionListener { action() }
    }

    fun attach() {
        editor.contentComponent.add(panel)
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
        counter.text = when (pending.size) {
            0 -> ""
            1 -> "1 change"
            else -> "${pending.size} changes"
        }
        reposition()
    }

    /** Moves the caret to the next (or previous) change and scrolls it into view. */
    private fun jump(back: Boolean) {
        val lines = service.editFor(file)?.pendingHunks.orEmpty()
            .map { it.startLine() }.filter { it >= 0 }.sorted()
        if (lines.isEmpty()) return

        val current = editor.caretModel.logicalPosition.line
        val target = if (back) {
            lines.lastOrNull { it < current } ?: lines.last()
        } else {
            lines.firstOrNull { it > current } ?: lines.first()
        }
        editor.caretModel.moveToLogicalPosition(com.intellij.openapi.editor.LogicalPosition(target, 0))
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
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
        editor.contentComponent.repaint()
    }
}
