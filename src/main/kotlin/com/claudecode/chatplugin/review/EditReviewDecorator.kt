package com.claudecode.chatplugin.review

import com.intellij.diff.util.TextDiffType
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.JBUI
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.Icon

/**
 * Draws one file's pending changes into an open editor: the new lines
 * highlighted, the lines they replaced shown in red directly above, and
 * Accept / Reject at the right edge of each change — plus a strip floating over
 * the bottom of the editor for stepping between changes and deciding the file
 * as a whole.
 *
 * Per-change actions appear twice on purpose: as buttons in the inlay, and in
 * the gutter icon's menu, which uses the platform's own click handling. If the
 * inlay hit-testing is off by a few pixels the change is still reviewable.
 */
class EditReviewDecorator(
    private val project: Project,
    private val editor: Editor,
    private val file: VirtualFile
) {

    private val highlighters = mutableListOf<RangeHighlighter>()
    private val inlays = mutableListOf<Inlay<*>>()
    private val renderers = mutableMapOf<Inlay<*>, RemovedLinesRenderer>()

    private val service get() = EditReviewService.getInstance(project)

    private val mouseListener = object : EditorMouseListener {
        override fun mouseClicked(event: EditorMouseEvent) {
            val point = event.mouseEvent.point
            val inlay = editor.inlayModel.getElementAt(point) ?: return
            val renderer = renderers[inlay] ?: return
            val bounds = inlay.bounds ?: return
            val local = java.awt.Point(point.x - bounds.x, point.y - bounds.y)
            when (renderer.hitTest(local)) {
                RemovedLinesRenderer.Action.ACCEPT -> { service.accept(file, renderer.hunk); event.consume() }
                RemovedLinesRenderer.Action.REJECT -> { service.reject(file, renderer.hunk); event.consume() }
                null -> Unit
            }
        }
    }

    private var toolbar: ReviewFloatingToolbar? = null

    fun attach() {
        val review = service.editFor(file) ?: return
        editor.addEditorMouseListener(mouseListener)

        review.pendingHunks.forEach { hunk ->
            if (!hunk.isAlive) return@forEach
            addLineHighlight(hunk)
            addRemovedLinesInlay(hunk)
        }

        toolbar = ReviewFloatingToolbar(project, editor, file).also { it.attach() }
    }

    fun detach() {
        toolbar?.detach()
        toolbar = null
        editor.removeEditorMouseListener(mouseListener)
        highlighters.forEach { runCatching { editor.markupModel.removeHighlighter(it) } }
        highlighters.clear()
        inlays.forEach { runCatching { it.dispose() } }
        inlays.clear()
        renderers.clear()
    }

    /** Repaints from scratch — simpler and less error-prone than patching in place. */
    fun refresh() {
        detach()
        attach()
    }

    private fun addLineHighlight(hunk: Hunk) {
        if (hunk.kind == Hunk.Kind.DELETE) return // nothing new to highlight
        val diffType = if (hunk.kind == Hunk.Kind.INSERT) TextDiffType.INSERTED else TextDiffType.MODIFIED
        val attributes = TextAttributes().apply {
            backgroundColor = diffType.getColor(editor)
        }
        val highlighter = editor.markupModel.addRangeHighlighter(
            hunk.marker.startOffset,
            hunk.marker.endOffset,
            HighlighterLayer.SELECTION - 1,
            attributes,
            HighlighterTargetArea.LINES_IN_RANGE
        )
        highlighter.gutterIconRenderer = HunkGutterRenderer(hunk)
        highlighters.add(highlighter)
    }

    private fun addRemovedLinesInlay(hunk: Hunk) {
        val renderer = RemovedLinesRenderer(hunk, editor)
        val inlay = editor.inlayModel.addBlockElement(
            hunk.marker.startOffset,
            /* relatesToPrecedingText = */ false,
            /* showAbove = */ true,
            /* priority = */ 0,
            renderer
        ) ?: return
        inlays.add(inlay)
        renderers[inlay] = renderer
    }

    /** One icon whose menu carries both actions — a gutter icon can only be one. */
    private inner class HunkGutterRenderer(private val hunk: Hunk) : GutterIconRenderer() {

        override fun getIcon(): Icon = AllIcons.Actions.Diff
        override fun getTooltipText(): String = "Claude changed these lines — accept or reject"
        override fun isNavigateAction(): Boolean = true

        override fun getPopupMenuActions(): ActionGroup = DefaultActionGroup(
            object : AnAction("Accept This Change", null, AllIcons.Actions.Commit) {
                override fun actionPerformed(e: AnActionEvent) = service.accept(file, hunk)
            },
            object : AnAction("Reject This Change", null, AllIcons.Actions.Rollback) {
                override fun actionPerformed(e: AnActionEvent) = service.reject(file, hunk)
            }
        )

        override fun equals(other: Any?): Boolean = other is HunkGutterRenderer && other.hunk === hunk
        override fun hashCode(): Int = System.identityHashCode(hunk)
    }

    /**
     * Paints the replaced lines above the change, in red, with the two actions
     * on their own row. Painting records where the labels landed so a click can
     * be mapped back to them.
     */
    class RemovedLinesRenderer(val hunk: Hunk, private val editor: Editor) : EditorCustomElementRenderer {

        enum class Action { ACCEPT, REJECT }

        private var acceptBounds: Rectangle? = null
        private var rejectBounds: Rectangle? = null

        private val lines: List<String> get() = hunk.removedLines()

        private fun lineHeight() = editor.lineHeight

        override fun calcWidthInPixels(inlay: Inlay<*>): Int = editor.component.width

        /** A pure insertion has no removed lines, but still needs a row for its buttons. */
        override fun calcHeightInPixels(inlay: Inlay<*>): Int = lineHeight() * maxOf(lines.size, 1)

        override fun paint(inlay: Inlay<*>, g: Graphics, target: Rectangle, attributes: TextAttributes) {
            val lh = lineHeight()
            val font = editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
            val metrics = g.getFontMetrics(font)
            g.font = font

            val removedBg = TextDiffType.DELETED.getColor(editor)
            if (lines.isEmpty()) {
                // Nothing was removed; keep the row subtle so it reads as a
                // control strip rather than as deleted code.
                g.color = editor.colorsScheme.defaultBackground
                g.fillRect(target.x, target.y, target.width, lh)
            } else {
                lines.forEachIndexed { i, line ->
                    val y = target.y + i * lh
                    g.color = removedBg
                    g.fillRect(target.x, y, target.width, lh)
                    g.color = editor.colorsScheme.defaultForeground
                    g.drawString(line, target.x + 4, y + metrics.ascent)
                }
            }

            // Buttons sit at the right edge of the change's first row, out of the
            // way of the code itself.
            val accept = "Accept"
            val reject = "Reject"
            val padding = 8
            val gap = 4
            val aw = metrics.stringWidth(accept) + padding * 2
            val rw = metrics.stringWidth(reject) + padding * 2
            val h = lh - 4
            val rx = target.x + target.width - rw - 12
            val ax = rx - gap - aw
            val y = target.y + 2

            g.color = JBUI.CurrentTheme.Focus.focusColor()
            g.fillRect(ax, y, aw, h)
            g.color = removedBg
            g.fillRect(rx, y, rw, h)

            g.color = editor.colorsScheme.defaultForeground
            g.drawString(accept, ax + padding, y + metrics.ascent - 2)
            g.drawString(reject, rx + padding, y + metrics.ascent - 2)

            // Local coordinates, so a click can be resolved without repainting.
            acceptBounds = Rectangle(ax - target.x, y - target.y, aw, h)
            rejectBounds = Rectangle(rx - target.x, y - target.y, rw, h)
        }

        fun hitTest(local: java.awt.Point): Action? = when {
            acceptBounds?.contains(local) == true -> Action.ACCEPT
            rejectBounds?.contains(local) == true -> Action.REJECT
            else -> null
        }
    }
}
