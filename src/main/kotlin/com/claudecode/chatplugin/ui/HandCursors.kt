package com.claudecode.chatplugin.ui

import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import javax.swing.AbstractButton
import javax.swing.JComboBox

/**
 * Gives every button a hand cursor.
 *
 * Swing leaves the default arrow (or, over an editor, the text caret) on
 * buttons, which reads as "not clickable" next to everything else in the IDE
 * that does change. Applying this by walking a panel once — rather than at each
 * button — means buttons added later are covered too, and no site can be
 * forgotten.
 */
object HandCursors {

    private val HAND: Cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

    /** Sets the hand cursor on [button] and returns it, for use inline. */
    fun <T : AbstractButton> on(button: T): T = button.apply { cursor = HAND }

    /** Sets it on every button under [root]. */
    fun applyTo(root: Component) {
        if (root is AbstractButton) {
            root.cursor = HAND
            return
        }
        // A combo box owns an arrow button; giving that a hand cursor while the
        // rest of the control keeps the arrow looks like a mistake.
        if (root is JComboBox<*>) return
        if (root is Container) root.components.forEach { applyTo(it) }
    }
}
