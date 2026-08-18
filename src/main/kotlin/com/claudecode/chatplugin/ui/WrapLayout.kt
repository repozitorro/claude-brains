package com.claudecode.chatplugin.ui

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * A [FlowLayout] that reports the height it will actually need.
 *
 * Plain `FlowLayout` wraps its children when it runs out of width but still
 * claims, as its preferred size, the one row it would have liked. Inside a
 * `BorderLayout.WEST`, which hands out preferred width and nothing less, the
 * result is a row that overruns whatever is in `EAST` — four dropdowns sliding
 * under the buttons beside them in a narrow tool window, which is exactly what
 * happened when the third and fourth were added.
 *
 * This measures the wrapping properly, so the row grows downwards instead of
 * sideways into its neighbours.
 */
class WrapLayout(align: Int = LEFT, hgap: Int = 4, vgap: Int = 0) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, preferred = true)

    override fun minimumLayoutSize(target: Container): Dimension =
        layoutSize(target, preferred = false).also { it.width -= hgap + 1 }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            // The width to wrap against. A container that has not been laid out
            // yet has none, and the enclosing scroll pane — if there is one —
            // knows better than the container does.
            var targetWidth = target.size.width
            if (targetWidth == 0) {
                val scroll = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target)
                targetWidth = if (scroll != null) scroll.width else Int.MAX_VALUE
            }

            val insets = target.insets
            val horizontalInsets = insets.left + insets.right + hgap * 2
            val maxWidth = (targetWidth - horizontalInsets).coerceAtLeast(1)

            val dim = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0

            fun closeRow() {
                dim.width = maxOf(dim.width, rowWidth)
                if (dim.height > 0) dim.height += vgap
                dim.height += rowHeight
                rowWidth = 0
                rowHeight = 0
            }

            for (i in 0 until target.componentCount) {
                val component = target.getComponent(i)
                if (!component.isVisible) continue
                val size = if (preferred) component.preferredSize else component.minimumSize
                if (rowWidth + size.width > maxWidth && rowWidth > 0) closeRow()
                if (rowWidth != 0) rowWidth += hgap
                rowWidth += size.width
                rowHeight = maxOf(rowHeight, size.height)
            }
            closeRow()

            dim.width += horizontalInsets
            dim.height += insets.top + insets.bottom + vgap * 2
            return dim
        }
    }
}
