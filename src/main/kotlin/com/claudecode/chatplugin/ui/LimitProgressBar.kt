package com.claudecode.chatplugin.ui

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent

/**
 * A thin bar showing how much of the current limit window has been used.
 *
 * A percentage in text is easy to skim past; a filling line is read without
 * reading. It stays hidden until the CLI has actually reported a figure, so it
 * never implies a number the plugin doesn't have.
 */
class LimitProgressBar : JComponent() {

    /** 0–100, or null when nothing has been reported yet. */
    var percent: Int? = null
        set(value) {
            val clamped = value?.coerceIn(0, 100)
            if (field == clamped) return
            field = clamped
            isVisible = clamped != null
            revalidate()
            repaint()
        }

    init {
        isVisible = false
        isOpaque = false
        preferredSize = Dimension(0, JBUI.scale(BAR_HEIGHT))
        minimumSize = Dimension(0, JBUI.scale(BAR_HEIGHT))
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, JBUI.scale(BAR_HEIGHT))

    private fun fillColour(used: Int): Color = when {
        // The accent for normal use; the closer the window is to full, the more
        // the bar says so on its own.
        used >= 90 -> Color(0xC1, 0x5B, 0x5B)
        used >= 75 -> Color(0xE0, 0x6C, 0x2E)
        else -> Color(0xE8, 0x83, 0x3A)
    }

    override fun paintComponent(g: Graphics) {
        val used = percent ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val h = JBUI.scale(BAR_HEIGHT)
            val arc = h
            val trackWidth = width - JBUI.scale(2)
            if (trackWidth <= 0) return

            g2.color = JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()
            g2.fillRoundRect(0, 0, trackWidth, h, arc, arc)

            val filled = (trackWidth * used / 100.0).toInt().coerceAtLeast(if (used > 0) h else 0)
            if (filled > 0) {
                g2.color = fillColour(used)
                g2.fillRoundRect(0, 0, filled, h, arc, arc)
            }
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        /**
         * 3px read as a hairline and was easy to miss entirely; 4 still sits
         * quietly under the text but is actually visible on a normal display.
         */
        const val BAR_HEIGHT = 4
    }
}
