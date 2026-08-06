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
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.Icon

/**
 * Draws one file's pending changes into an open editor: the new lines
 * highlighted, the lines they replaced shown in red directly above, and an
 * Accept / Reject control on every change.
 *
 * The actions appear twice on purpose — as labels inside the inlay, which is
 * the shape the user asked for, and as a gutter icon, which uses the platform's
 * own click handling. If the inlay hit-testing is off by a few pixels the
 * change is still reviewable.
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

    fun attach() {
        val review = service.editFor(file) ?: return
        editor.addEditorMouseListener(mouseListener)

        review.pendingHunks.forEach { hunk ->
            if (!hunk.isAlive) return@forEach
            addLineHighlight(hunk)
            addRemovedLinesInlay(hunk)
        }
    }

    fun detach() {
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

        override fun calcHeightInPixels(inlay: Inlay<*>): Int = lineHeight() * (lines.size + 1)

        override fun paint(inlay: Inlay<*>, g: Graphics, target: Rectangle, attributes: TextAttributes) {
            val lh = lineHeight()
            val metrics = g.getFontMetrics(editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN))
            g.font = editor.colorsScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)

            // Removed lines, on the deletion colour the IDE uses in its own diffs.
            val removedBg = TextDiffType.DELETED.getColor(editor)
            lines.forEachIndexed { i, line ->
                val y = target.y + i * lh
                g.color = removedBg
                g.fillRect(target.x, y, target.width, lh)
                g.color = editor.colorsScheme.defaultForeground
                g.drawString(line, target.x + 4, y + metrics.ascent)
            }

            // Action row underneath the removed text.
            val rowY = target.y + lines.size * lh
            g.color = editor.colorsScheme.defaultBackground
            g.fillRect(target.x, rowY, target.width, lh)

            val accept = "✓ Accept"
            val reject = "✗ Reject"
            val gap = 16
            val ax = target.x + 4
            val aw = metrics.stringWidth(accept)
            val rx = ax + aw + gap
            val rw = metrics.stringWidth(reject)

            g.color = TextDiffType.INSERTED.getColor(editor)
            g.fillRect(ax - 3, rowY + 2, aw + 6, lh - 4)
            g.color = removedBg
            g.fillRect(rx - 3, rowY + 2, rw + 6, lh - 4)

            g.color = editor.colorsScheme.defaultForeground
            g.drawString(accept, ax, rowY + metrics.ascent)
            g.drawString(reject, rx, rowY + metrics.ascent)

            // Local coordinates, so a click can be resolved without repainting.
            acceptBounds = Rectangle(ax - 3 - target.x, rowY + 2 - target.y, aw + 6, lh - 4)
            rejectBounds = Rectangle(rx - 3 - target.x, rowY + 2 - target.y, rw + 6, lh - 4)
        }

        fun hitTest(local: java.awt.Point): Action? = when {
            acceptBounds?.contains(local) == true -> Action.ACCEPT
            rejectBounds?.contains(local) == true -> Action.REJECT
            else -> null
        }
    }
}
