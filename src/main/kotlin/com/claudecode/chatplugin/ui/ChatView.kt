package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.model.ChatMessage
import javax.swing.JComponent

/**
 * The message-rendering surface of a chat panel. Two implementations exist:
 * [JcefChatView] (embedded Chromium — rich markdown, syntax highlighting) and
 * [SwingChatView] (a `JEditorPane` fallback for when JCEF isn't available).
 *
 * Messages are addressed by their index in the transcript. [render] both adds a
 * message (first time an index is seen) and updates it (subsequent calls with
 * the same index, e.g. while streaming).
 */
interface ChatView {
    val component: JComponent

    /** Removes all rendered messages. */
    fun clear()

    /** Renders or updates the message at [index]. */
    fun render(index: Int, message: ChatMessage)

    fun scrollToBottom()

    /** Releases native resources (e.g. the JCEF browser). */
    fun dispose() {}
}
