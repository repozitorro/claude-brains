package com.claudecode.chatplugin.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.awt.Cursor
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel

class HandCursorsTest {

    private val hand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

    @Test
    fun `every button under the root gets the hand, however deeply nested`() {
        val deep = JButton("deep")
        val shallow = JButton("shallow")
        val root = JPanel().apply {
            add(shallow)
            add(JPanel().apply { add(JPanel().apply { add(deep) }) })
        }

        HandCursors.applyTo(root)

        assertEquals(hand, shallow.cursor)
        assertEquals(hand, deep.cursor)
    }

    @Test
    fun `things that aren't buttons are left alone`() {
        val label = JLabel("just text")
        val root = JPanel().apply { add(label) }

        HandCursors.applyTo(root)

        assertNotEquals(hand, label.cursor)
    }

    @Test
    fun `a combo box keeps its own cursor`() {
        // A combo owns an arrow button; a hand on that alone, with the arrow
        // everywhere else on the control, reads as a bug rather than a hint.
        val combo = JComboBox(arrayOf("a", "b"))
        val root = JPanel().apply { add(combo) }

        HandCursors.applyTo(root)

        combo.components.filterIsInstance<javax.swing.AbstractButton>().forEach {
            assertNotEquals("the combo's arrow should not become a hand", hand, it.cursor)
        }
    }

    @Test
    fun `a button handed in directly is set too`() {
        val button = HandCursors.on(JButton("inline"))

        assertEquals(hand, button.cursor)
    }
}
