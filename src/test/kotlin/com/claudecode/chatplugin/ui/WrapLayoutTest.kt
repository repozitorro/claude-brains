package com.claudecode.chatplugin.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * A row of controls that grows downwards rather than into its neighbours.
 *
 * Four dropdowns went into a `BorderLayout` region that grants preferred width
 * and nothing less. Plain `FlowLayout` wraps its children when it runs out of
 * room but keeps reporting the single row it would have preferred, so the row
 * ran on underneath the buttons beside it — which is what the toolbar did the
 * moment effort and agent were added to model and permission mode.
 */
class WrapLayoutTest {

    private fun row(layout: java.awt.LayoutManager, widths: List<Int>, containerWidth: Int): JPanel =
        JPanel(layout).apply {
            widths.forEach { w ->
                add(JPanel().apply { preferredSize = Dimension(w, 24); minimumSize = Dimension(w, 24) })
            }
            size = Dimension(containerWidth, 100)
        }

    @Test
    fun `everything on one row when there is room for it`() {
        val panel = row(WrapLayout(FlowLayout.LEFT, 4, 2), listOf(100, 100, 100), containerWidth = 600)
        assertEquals("one row of 24px content", 28, panel.preferredSize.height)
    }

    @Test
    fun `a row too wide reports the height it will really take`() {
        // Three 100px children in 240px is two rows, and saying so is the whole
        // point: the width claim is what used to overrun the neighbours.
        val panel = row(WrapLayout(FlowLayout.LEFT, 4, 2), listOf(100, 100, 100), containerWidth = 240)
        assertTrue("expected a taller preferred size, got ${panel.preferredSize}", panel.preferredSize.height > 28)
    }

    @Test
    fun `plain FlowLayout is the thing this replaces`() {
        // Pinned so the difference is on the record: given the same children and
        // the same narrow container, FlowLayout still claims one row.
        val wrapped = row(WrapLayout(FlowLayout.LEFT, 4, 2), listOf(100, 100, 100), containerWidth = 240)
        val plain = row(FlowLayout(FlowLayout.LEFT, 4, 2), listOf(100, 100, 100), containerWidth = 240)
        assertTrue(wrapped.preferredSize.height > plain.preferredSize.height)
    }

    @Test
    fun `an invisible control takes no room`() {
        val panel = row(WrapLayout(FlowLayout.LEFT, 4, 2), listOf(100, 100), containerWidth = 600)
        panel.getComponent(1).isVisible = false
        assertTrue(panel.preferredSize.width < 210)
    }

    @Test
    fun `an empty row has no height of its own`() {
        val panel = JPanel(WrapLayout(FlowLayout.LEFT, 4, 2)).apply { size = Dimension(200, 50) }
        assertEquals(4, panel.preferredSize.height)
    }
}
