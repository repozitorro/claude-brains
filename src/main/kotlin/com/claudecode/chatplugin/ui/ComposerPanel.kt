package com.claudecode.chatplugin.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JPanel

/**
 * The rounded card the prompt input sits in.
 *
 * Swing has no rounded container, so the shape is painted here: a filled
 * round-rect in the IDE's field colour with a 1px outline that switches to the
 * accent while the input has focus — the same "this is where you type" cue the
 * IDE's own search fields give.
 */
class ComposerPanel(private val accent: Color) : JPanel(BorderLayout()) {

    var focused: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                repaint()
            }
        }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 8)
    }

    private fun fieldBackground(): Color = UIUtil.getTextFieldBackground()

    private fun outline(): Color =
        if (focused) accent else JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(10)
            val w = width - 1
            val h = height - 1

            g2.color = fieldBackground()
            g2.fillRoundRect(0, 0, w, h, arc, arc)

            g2.color = outline()
            g2.drawRoundRect(0, 0, w, h, arc, arc)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
