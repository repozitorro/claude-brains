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
    val itemIndex: Int,
    /**
     * A second index, where one index is not enough to say what was clicked.
     *
     * A question from Claude needs both: which tool call it belongs to, and
     * which of its options was chosen. Absent (-1) for everything else, and
     * left off the token entirely so the common form stays four fields.
     */
    val optionIndex: Int = NONE
) {

    fun token(): String = if (optionIndex == NONE) {
        "$PREFIX:$messageIndex:$action:$itemIndex"
    } else {
        "$PREFIX:$messageIndex:$action:$itemIndex:$optionIndex"
    }

    companion object {
        private const val PREFIX = "claudebrains"

        /** No second index — the click addressed one thing, not one of several. */
        const val NONE = -1

        /** Null for anything that is not one of ours, including ordinary links. */
        fun parse(href: String): ChatLink? {
            val parts = href.split(':')
            if (parts.size !in 4..5 || parts[0] != PREFIX) return null
            val messageIndex = parts[1].toIntOrNull() ?: return null
            val itemIndex = parts[3].toIntOrNull() ?: return null
            if (parts[2].isEmpty()) return null
            val optionIndex = if (parts.size == 5) (parts[4].toIntOrNull() ?: return null) else NONE
            return ChatLink(messageIndex, parts[2], itemIndex, optionIndex)
        }
    }
}
