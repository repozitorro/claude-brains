package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.model.ChatMessage
import com.claudecode.chatplugin.model.FileEdit
import com.claudecode.chatplugin.model.ToolCall
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Renders a chat message to an HTML **fragment** (the inner body of one message,
 * no `<html>` wrapper). The same fragment feeds two rendering surfaces:
 *  - [JcefChatView] inserts it into a persistent Chromium page (which then runs
 *    highlight.js and adds copy buttons).
 *  - [SwingChatView] wraps it with [page] for a plain `JEditorPane` fallback.
 *
 * Two content modes: while a reply streams, text is escaped as-is (parsing
 * half-written markdown flashes broken HTML); once complete, CommonMark runs.
 *
 * Edit links carry the message index so a single click handler can resolve them
 * regardless of view: `claudebrains:<msgIndex>:<action>:<editIndex>`, emitted as
 * both `href` (for the Swing HyperlinkListener) and `data-cb` (for the JCEF click
 * handler).
 */
object MessageRenderer {

    private val parser: Parser = Parser.builder().build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder().build()

    private fun md(markdown: String): String = renderer.render(parser.parse(markdown) as Node)

    private fun escape(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\n", "<br/>")

    private fun thinkingBlock(inner: String): String =
        "<div style='color:#888888; font-style:italic; border-left:2px solid #666666; " +
            "padding-left:8px; margin-bottom:6px; white-space:pre-wrap;'>$inner</div>"

    private fun toolCallsBlock(calls: List<ToolCall>): String {
        if (calls.isEmpty()) return ""
        val rows = calls.joinToString("<br/>") { tc ->
            val (glyph, color) = when (tc.status) {
                ToolCall.Status.OK -> "&#10003;" to "#7a9a7a"       // ✓
                ToolCall.Status.ERROR -> "&#10007;" to "#c86a6a"    // ✗
                ToolCall.Status.RUNNING -> "&#128295;" to "#7a9a7a" // 🔧
            }
            "<span style='color:$color;'>$glyph " + escape(tc.display) + "</span>"
        }
        return "<div style='font-family:monospace; font-size:11px; margin:4px 0;'>$rows</div>"
    }

    private fun editsBlock(edits: List<FileEdit>, msgIndex: Int, withLinks: Boolean): String {
        if (edits.isEmpty()) return ""
        val rows = StringBuilder()
        edits.forEachIndexed { i, e ->
            if (i > 0) rows.append("<br/>")
            rows.append("&#9999; ").append(escape(e.toolName)).append(" ").append(escape(e.fileName)) // ✏
            if (withLinks && e.isResolved) {
                rows.append(link(msgIndex, "diff", i, "diff"))
                if (e.canRevert) rows.append(link(msgIndex, "revert", i, "revert"))
            }
        }
        // One-click undo for the whole turn, once more than one edit is revertible.
        if (withLinks && edits.count { it.isResolved && it.canRevert } > 1) {
            rows.append("<br/>").append(link(msgIndex, "revertall", -1, "revert all"))
        }
        return "<div style='color:#c8a45c; font-family:monospace; font-size:11px; margin:4px 0;'>$rows</div>"
    }

    private fun link(msgIndex: Int, action: String, editIndex: Int, text: String): String {
        val token = "claudebrains:$msgIndex:$action:$editIndex"
        return " &nbsp;<a href='$token' data-cb='$token'>$text</a>"
    }

    /** Inner-HTML fragment for [message] (no `<html>` wrapper). */
    fun fragment(message: ChatMessage, msgIndex: Int, streaming: Boolean): String {
        val body = StringBuilder()
        if (message.thinking.isNotBlank()) {
            body.append(thinkingBlock(if (streaming) escape(message.thinking) else md(message.thinking)))
        }
        body.append(toolCallsBlock(message.toolCalls))
        body.append(editsBlock(message.edits, msgIndex, withLinks = !streaming))
        if (streaming) {
            body.append(escape(message.text))
            body.append("<span style='opacity:0.5;'>&#9611;</span>")
        } else {
            body.append(md(message.text.ifEmpty { " " }))
        }
        return body.toString()
    }

    /** Wraps a [fragment] in a full `<html>` page for the Swing `JEditorPane` fallback. */
    fun page(fragment: String): String = """
        <html>
        <head>
        <style>
            body { font-family: sans-serif; font-size: 12px; }
            pre { background: #2b2b2b; color: #e0e0e0; padding: 6px; border-radius: 4px; }
            code { font-family: monospace; }
            p { margin: 4px 0; }
            a { color: #499bd6; }
        </style>
        </head>
        <body>$fragment</body>
        </html>
    """.trimIndent()
}
