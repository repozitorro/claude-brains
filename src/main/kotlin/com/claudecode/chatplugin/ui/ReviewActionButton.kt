package com.claudecode.chatplugin.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JButton

/**
 * The one pair of colours every accept / reject control uses.
 *
 * Keep and discard are opposite decisions, and they appear in three places —
 * painted inside a change, on the strip over the editor, and above the prompt.
 * Telling them apart should not depend on which of the three you are looking at,
 * so the colours live in one place rather than being chosen per site.
 */
object ReviewColors {

    /** Darker on light themes, lighter on dark ones, so one light label works on both. */
    val ACCEPT: Color = JBColor(Color(0x3D8B47), Color(0x4E9A55))
    val REJECT: Color = JBColor(Color(0xC0392B), Color(0xC15B5B))

    /**
     * Fixed rather than taken from the theme: both fills are saturated, and a
     * light theme's own foreground is dark text — which is what makes a coloured
     * button unreadable.
     */
    val ON_FILL: Color = Color(0xF5, 0xF5, 0xF5)
}

/**
 * A button that carries its meaning in its colour.
 *
 * Swing's own background is ignored by most IntelliJ themes — a plain
 * `background = green` simply does not show — so the fill is painted here and
 * the label is set light enough to stay legible on it.
 *
 * Text only, deliberately. Tinting a platform icon to match meant
 * `IconUtil.colorize`, which is a Kotlin function with a default argument: the
 * call compiles into `colorize$default`, a synthetic bridge that exists in
 * 2024.1 and not in 2025.3 or 2026.2. It built cleanly and would have thrown
 * NoSuchMethodError on the IDE people actually run, taking the review controls
 * down with it. A word needs no bridge.
 */
class ReviewActionButton(
    text: String,
    private val fill: Color,
    tooltip: String,
    action: () -> Unit
) : JButton(text) {

    init {
        toolTipText = tooltip
        foreground = ReviewColors.ON_FILL
        isFocusable = false
        isContentAreaFilled = false   // the LaF must not paint over the fill
        isBorderPainted = false
        isOpaque = false
        margin = JBUI.insets(2, 8)
        addActionListener { action() }
        HandCursors.on(this)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(6)
            g2.color = if (model.isPressed) fill.darker() else if (model.isRollover) fill.brighter() else fill
            g2.fillRoundRect(0, 0, width, height, arc, arc)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    companion object {
        fun accept(text: String, tooltip: String, action: () -> Unit) =
            ReviewActionButton(text, ReviewColors.ACCEPT, tooltip, action)

        fun reject(text: String, tooltip: String, action: () -> Unit) =
            ReviewActionButton(text, ReviewColors.REJECT, tooltip, action)
    }
}
