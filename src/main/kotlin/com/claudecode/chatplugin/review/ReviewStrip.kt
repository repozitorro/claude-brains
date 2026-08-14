package com.claudecode.chatplugin.review

import com.claudecode.chatplugin.ui.HandCursors
import com.claudecode.chatplugin.ui.ReviewActionButton
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.VisibleAreaListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * The strip over the bottom of an editor while its changes await a decision.
 *
 * Positioned here rather than by the platform's floating-toolbar provider,
 * which always puts its toolbars in the top-right corner: the decision belongs
 * at the foot of the file, centred, where the eye already is after reading a
 * change — and it stays put rather than fading in and out, because the count of
 * what is left is the thing telling you the review is unfinished.
 *
 * Doing the placement by hand is what broke this once before: a component given
 * bounds inside a container that lays nothing out never lays out its own
 * children, so the buttons painted where you saw them while every click went
 * through to the panel behind. Hence [reposition] validating, and hence the
 * resize listener as well as the scroll one — the previous version only
 * followed scrolling and drifted whenever the window changed shape.
 */
class ReviewStrip(
    private val project: Project,
    private val editor: Editor,
    private val file: VirtualFile
) {

    private val service get() = EditReviewService.getInstance(project)

    private val counter = JLabel().apply { border = JBUI.Borders.empty(0, 8, 0, 4) }

    private val panel = object : JPanel(FlowLayout(FlowLayout.CENTER, 4, 3)) {
        /** A rounded card, painted here because Swing has no such container. */
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(10)
                val w = width - 1
                val h = height - 1
                // A shadow, so the strip reads as floating over the text rather
                // than as something written into it.
                g2.color = Color(0, 0, 0, 40)
                g2.fillRoundRect(1, 2, w, h, arc, arc)
                g2.color = JBUI.CurrentTheme.Popup.BACKGROUND
                g2.fillRoundRect(0, 0, w, h, arc, arc)
                g2.color = JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
                g2.drawRoundRect(0, 0, w, h, arc, arc)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }.apply {
        isOpaque = false
        border = JBUI.Borders.empty(3, 5)
    }

    private val areaListener = VisibleAreaListener { reposition() }

    private val resizeListener = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) = reposition()
    }

    init {
        panel.add(counter)
        panel.add(
            ReviewActionButton.accept("Accept file", "Accept every change in this file") {
                service.acceptFile(file)
            }
        )
        panel.add(
            ReviewActionButton.reject("Reject file", "Reject every change in this file") {
                service.rejectFile(file)
            }
        )
        panel.add(arrow(AllIcons.Actions.PreviousOccurence, "Previous change") { jump(back = true) })
        panel.add(arrow(AllIcons.Actions.NextOccurence, "Next change") { jump(back = false) })
    }

    /** Navigation is not a decision, so it stays quiet next to the two that are. */
    private fun arrow(icon: Icon, tooltip: String, action: () -> Unit) = JButton(icon).apply {
        toolTipText = tooltip
        isFocusable = false
        margin = JBUI.emptyInsets()
        putClientProperty("JButton.buttonType", "toolBarButton")
        addActionListener { action() }
        HandCursors.on(this)
    }

    fun attach() {
        // Index 0 is the top of Swing's z-order; added last it would sit under
        // the editor's own painting.
        editor.contentComponent.add(panel, 0)
        editor.scrollingModel.addVisibleAreaListener(areaListener)
        editor.contentComponent.addComponentListener(resizeListener)
        refresh()
    }

    fun detach() {
        editor.scrollingModel.removeVisibleAreaListener(areaListener)
        editor.contentComponent.removeComponentListener(resizeListener)
        editor.contentComponent.remove(panel)
        editor.contentComponent.repaint()
    }

    fun refresh() {
        val pending = service.editFor(file)?.pendingHunks.orEmpty()
        panel.isVisible = pending.isNotEmpty()
        counter.text = buildString {
            append(if (pending.size == 1) "1 change" else "${pending.size} changes")
            val elsewhere = service.reviewedFiles().count { it != file }
            if (elsewhere > 0) append(if (elsewhere == 1) " · 1 more file" else " · $elsewhere more files")
        }
        reposition()
    }

    /** Bottom centre of whatever part of the file is currently on screen. */
    private fun reposition() {
        val size: Dimension = panel.preferredSize
        val area = editor.scrollingModel.visibleArea
        panel.setBounds(
            area.x + (area.width - size.width) / 2,
            area.y + area.height - size.height - JBUI.scale(18),
            size.width,
            size.height
        )
        // Without this the buttons keep zero bounds: the editor's content
        // component lays nothing out, so the strip has to lay itself out.
        panel.validate()
        editor.contentComponent.repaint()
    }

    private fun jump(back: Boolean) {
        val lines = service.pendingLines(file)
        when (val step = ReviewNavigation.step(lines, editor.caretModel.logicalPosition.line, back)) {
            is ReviewNavigation.Step.ToLine -> moveTo(step.line)
            ReviewNavigation.Step.ToAnotherFile -> {
                val next = ReviewNavigation.neighbour(service.reviewedFiles(), file, back)
                if (next == null || !service.openAtChange(next, back)) {
                    ReviewNavigation.entryLine(lines, back)?.let { moveTo(it) }
                }
            }
        }
    }

    private fun moveTo(line: Int) {
        editor.caretModel.moveToLogicalPosition(LogicalPosition(line, 0))
        editor.selectionModel.removeSelection()
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        editor.contentComponent.requestFocusInWindow()
    }
}
