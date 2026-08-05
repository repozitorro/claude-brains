package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.model.ChatMessage
import com.claudecode.chatplugin.model.Role
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent

/**
 * Fallback [ChatView] backed by one `JEditorPane` per message inside a scrolling
 * `BoxLayout` column. Used when JCEF isn't available. HTML support is limited
 * (Swing's HTML 3.2), so no syntax highlighting — just markdown structure.
 */
class SwingChatView(private val onLink: (String) -> Unit) : ChatView {

    private val messagesContainer = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val scrollPane = JBScrollPane(messagesContainer).apply {
        border = JBUI.Borders.empty()
        verticalScrollBar.unitIncrement = 16
    }

    private val panes = HashMap<Int, JEditorPane>()

    override val component: JComponent get() = scrollPane

    override fun clear() {
        panes.clear()
        messagesContainer.removeAll()
        messagesContainer.revalidate()
        messagesContainer.repaint()
    }

    override fun render(index: Int, message: ChatMessage) {
        val html = MessageRenderer.page(MessageRenderer.fragment(message, index, message.isStreaming))
        val existing = panes[index]
        if (existing != null) {
            existing.text = html
        } else {
            addBubble(index, message, html)
        }
        scrollToBottom()
    }

    private fun addBubble(index: Int, message: ChatMessage, html: String) {
        val pane = JEditorPane("text/html", html).apply {
            isEditable = false
            border = JBUI.Borders.empty(6, 10)
            alignmentX = JComponent.LEFT_ALIGNMENT
            background = if (message.role == Role.USER) {
                JBUI.CurrentTheme.ActionButton.pressedBackground()
            } else {
                background
            }
            addHyperlinkListener { ev ->
                if (ev.eventType == HyperlinkEvent.EventType.ACTIVATED) ev.description?.let(onLink)
            }
        }
        panes[index] = pane

        val wrapper = JPanel(BorderLayout()).apply {
            val labelText = when (message.role) {
                Role.USER -> "You"
                Role.SYSTEM -> "Claude Brains"
                else -> "Claude"
            }
            val label = JLabel(labelText).apply {
                font = font.deriveFont(java.awt.Font.BOLD, 10f)
                border = JBUI.Borders.empty(4, 10, 0, 0)
                if (message.role == Role.SYSTEM) foreground = JBUI.CurrentTheme.Label.disabledForeground()
            }
            add(label, BorderLayout.NORTH)
            add(pane, BorderLayout.CENTER)
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, maximumSize.height)
        }
        messagesContainer.add(wrapper)
        messagesContainer.revalidate()
    }

    override fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val bar = scrollPane.verticalScrollBar
            bar.value = bar.maximum
        }
    }
}
