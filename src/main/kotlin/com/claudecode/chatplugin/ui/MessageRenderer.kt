package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.model.ChatMessage
import com.claudecode.chatplugin.model.FileEdit
import com.claudecode.chatplugin.model.PermissionRequest
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

    /**
     * `escapeHtml(true)` is load-bearing, not cosmetic: CommonMark passes raw
     * HTML through by default, and this fragment is written straight into the
     * embedded browser's DOM via `innerHTML`. A reply that quotes a file, a web
     * page or tool output containing `<script>` or `onerror=` would otherwise
     * execute inside a page that holds a bridge back into the IDE
     * (`window.cbLink` → diff/revert). None of the plugin's own markup goes
     * through here — it is assembled around [md], not by it.
     */
    private val renderer: HtmlRenderer = HtmlRenderer.builder().escapeHtml(true).build()

    private fun md(markdown: String): String = renderer.render(parser.parse(markdown) as Node)

    private fun escape(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\n", "<br/>")

    /**
     * Reasoning, collapsed once it has finished streaming. `<details>` is native
     * in the JCEF view; the Swing fallback's HTML 3.2 engine ignores the tag and
     * simply shows the content, which is an acceptable degradation.
     */
    private fun thinkingBlock(inner: String, streaming: Boolean): String =
        "<details class='cb-think'${if (streaming) " open" else ""}>" +
            "<summary>Reasoning</summary>" +
            "<div class='cb-think-body'>$inner</div></details>"

    /**
     * The activity strip. Status is carried as a `data-status` attribute and
     * painted as a coloured dot by the stylesheet, rather than spelled out in
     * emoji — it reads faster and stays consistent with the IDE's own chrome.
     */
    private fun toolCallsBlock(calls: List<ToolCall>): String {
        if (calls.isEmpty()) return ""
        val rows = calls.joinToString("") { tc ->
            val status = when (tc.status) {
                ToolCall.Status.OK -> "ok"
                ToolCall.Status.ERROR -> "error"
                ToolCall.Status.RUNNING -> "running"
            }
            val label = "<span class='cb-dot'></span><span class='cb-act-label'>" +
                escape(tc.display) + "</span>"
            val output = tc.output
            val inner = if (output.isNullOrBlank()) {
                "<div class='cb-act-row'>$label</div>"
            } else {
                // Failures open by default — that output is the reason you're looking.
                val open = if (tc.status == ToolCall.Status.ERROR) " open" else ""
                "<details$open><summary>$label</summary>" +
                    "<pre class='cb-out'>" + escapeKeepNewlines(output) + "</pre></details>"
            }
            "<li class='cb-act' data-status='$status'>$inner</li>"
        }
        return "<ul class='cb-activity'>$rows</ul>"
    }

    /** Escapes for display inside `<pre>`, where real newlines already break lines. */
    private fun escapeKeepNewlines(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun editsBlock(edits: List<FileEdit>, msgIndex: Int, withLinks: Boolean): String {
        if (edits.isEmpty()) return ""
        val rows = StringBuilder()
        edits.forEachIndexed { i, e ->
            rows.append("<li class='cb-edit'><span class='cb-file'>")
                .append(escape(e.toolName)).append(" <b>").append(escape(e.fileName)).append("</b></span>")
            if (withLinks && e.isResolved) {
                rows.append(link(msgIndex, "diff", i, "diff"))
                if (e.canRevert) rows.append(link(msgIndex, "revert", i, "revert", danger = true))
            }
            rows.append("</li>")
        }
        // One-click undo for the whole turn, once more than one edit is revertible.
        if (withLinks && edits.count { it.isResolved && it.canRevert } > 1) {
            rows.append("<li class='cb-edit'>")
                .append(link(msgIndex, "revertall", -1, "revert all", danger = true))
                .append("</li>")
        }
        return "<ul class='cb-edits'>$rows</ul>"
    }

    /**
     * The refusal, and the two answers to it.
     *
     * Rendered as links for the same reason the edit actions are: the Swing
     * fallback drives them through a HyperlinkListener, so they have to be real
     * anchors rather than buttons.
     */
    private fun permissionBlock(request: PermissionRequest, msgIndex: Int): String {
        val answered = request.answer
        if (answered != null) {
            val said = when (answered) {
                PermissionRequest.Answer.ALLOWED_HERE -> "Allowed in this chat"
                PermissionRequest.Answer.ALLOWED_ALWAYS -> "Allowed from now on"
                PermissionRequest.Answer.DENIED -> "Declined"
            }
            return "<div class='cb-perm'><span class='cb-perm-done'>$said — " +
                "<code>${escape(request.pattern)}</code></span></div>"
        }
        return "<div class='cb-perm'>" +
            "<span class='cb-perm-ask'>Allow <code>${escape(request.pattern)}</code>?</span>" +
            link(msgIndex, "permallow", 0, "Allow in this chat") +
            link(msgIndex, "permalways", 0, "Always allow") +
            // Running it yourself is not a permission — it grants nothing and
            // changes no setting — so it sits apart from the two that do.
            (request.command?.let { link(msgIndex, "permterminal", 0, "Run in terminal") } ?: "") +
            link(msgIndex, "permdeny", 0, "No", danger = true) +
            "</div>"
    }

    private fun link(msgIndex: Int, action: String, editIndex: Int, text: String, danger: Boolean = false): String {
        val token = "claudebrains:$msgIndex:$action:$editIndex"
        val cls = if (danger) "cb-btn danger" else "cb-btn"
        return "<a class='$cls' href='$token' data-cb='$token'>$text</a>"
    }

    /** Inner-HTML fragment for [message] (no `<html>` wrapper). */
    fun fragment(message: ChatMessage, msgIndex: Int, streaming: Boolean): String {
        val body = StringBuilder()
        if (message.thinking.isNotBlank()) {
            body.append(
                thinkingBlock(
                    if (streaming) escape(message.thinking) else md(message.thinking),
                    streaming // keep it open while it streams, collapsed once done
                )
            )
        }
        body.append(toolCallsBlock(message.toolCalls))
        body.append(editsBlock(message.edits, msgIndex, withLinks = !streaming))
        if (streaming) {
            body.append(escape(message.text))
            body.append("<span class='cb-caret'>&#9611;</span>")
        } else {
            body.append(md(message.text.ifEmpty { " " }))
            // Below the explanation, where the decision belongs: you read what
            // was refused and why, then answer.
            message.permissionRequest?.let { body.append(permissionBlock(it, msgIndex)) }
        }
        return body.toString()
    }

    /**
     * Wraps a [fragment] in a full `<html>` page for the Swing `JEditorPane`
     * fallback. Swing's HTML 3.2 engine supports only a fraction of the
     * stylesheet the JCEF view uses, so this restates the few rules it can
     * honour — the layout degrades, but stays readable.
     */
    fun page(fragment: String): String = """
        <html>
        <head>
        <style>
            body { font-family: sans-serif; font-size: 12px; }
            pre { background: #2b2b2b; color: #e0e0e0; padding: 6px; }
            code { font-family: monospace; }
            p { margin: 4px 0; }
            a { color: #CC785C; }
            ul.cb-activity, ul.cb-edits { margin: 4px 0; }
            li.cb-act, li.cb-edit { font-family: monospace; font-size: 11px; }
            .cb-think-body { color: #888888; font-style: italic; }
            .cb-out { font-family: monospace; font-size: 11px; }
        </style>
        </head>
        <body>$fragment</body>
        </html>
    """.trimIndent()
}
