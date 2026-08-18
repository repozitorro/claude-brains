package com.claudecode.chatplugin.ui

/**
 * The address of a clickable action in the transcript.
 *
 * Both rendering surfaces drive clicks through a string, because the Swing
 * fallback has only a `HyperlinkListener` and an `href` to work with. That
 * string was written in one place and taken apart in another, with nothing
 * holding the two together — a renderer that emitted a fourth field, or a
 * handler that expected one, would simply have stopped working, silently, for
 * whichever button was affected.
 *
 * So both ends are here. [token] writes it, [parse] reads it, and a test pins
 * the round trip.
 */
data class ChatLink(
    val messageIndex: Int,
    val action: String,
    /**
     * Which item within the message the click was on.
     *
     * An edit, for `diff` and `revert`; a tool call, for the permission
     * answers. Meaningless (and conventionally 0, or -1) for the actions that
     * address the message as a whole.
     */
    val itemIndex: Int
) {

    fun token(): String = "$PREFIX:$messageIndex:$action:$itemIndex"

    companion object {
        private const val PREFIX = "claudebrains"

        /** Null for anything that is not one of ours, including ordinary links. */
        fun parse(href: String): ChatLink? {
            val parts = href.split(':')
            if (parts.size != 4 || parts[0] != PREFIX) return null
            val messageIndex = parts[1].toIntOrNull() ?: return null
            val itemIndex = parts[3].toIntOrNull() ?: return null
            if (parts[2].isEmpty()) return null
            return ChatLink(messageIndex, parts[2], itemIndex)
        }
    }
}
