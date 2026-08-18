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
    private fun toolCallsBlock(calls: List<ToolCall>, msgIndex: Int): String {
        if (calls.isEmpty()) return ""
        val rows = calls.mapIndexed { callIndex, tc ->
            val status = when (tc.status) {
                ToolCall.Status.OK -> "ok"
                ToolCall.Status.ERROR -> "error"
                ToolCall.Status.RUNNING -> "running"
            }
            val label = "<span class='cb-dot'></span><span class='cb-act-label'>" +
                escape(tc.display) + "</span>" + approvalMark(tc.approval)
            val output = tc.output
            val asking = tc.approval?.takeIf { !it.isDecided }
            // The question, the command and its output are one call, so they
            // are one row. Asked in a block of its own it drifted away from
            // what it was about — the assistant kept writing above it, and the
            // card sank to the bottom of the conversation.
            val body = buildString {
                asking?.let { append(approvalBody(it, msgIndex, callIndex)) }
                if (!output.isNullOrBlank()) {
                    append("<pre class='cb-out'>").append(escapeKeepNewlines(output)).append("</pre>")
                }
            }
            val inner = if (body.isEmpty()) {
                "<div class='cb-act-row'>$label</div>"
            } else {
                // Failures open by default — that output is the reason you're
                // looking. So does a question: it is the reason you were
                // interrupted, and it cannot be answered unread.
                val open = if (tc.status == ToolCall.Status.ERROR || asking != null) " open" else ""
                // A row that opens has to look like one. The platform marker is
                // hidden (it sits in the wrong place and cannot be styled), so
                // this stands in for it — and appears only where there is
                // something to open, which is what makes it mean anything.
                "<details$open><summary>$label<span class='cb-chev'>›</span></summary>$body</details>"
            }
            "<li class='cb-act' data-status='$status'>$inner</li>"
        }.joinToString("")
        return "<ul class='cb-activity'>$rows</ul>"
    }

    /** Escapes for display inside `<pre>`, where real newlines already break lines. */
    private fun escapeKeepNewlines(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /**
     * The changed lines themselves.
     *
     * Every line is escaped: this is code Claude wrote, quoted from a file, and
     * it goes into the page as markup unless it is neutralised first — the same
     * reason the reply itself is escaped.
     */
    private fun previewBlock(preview: List<com.claudecode.chatplugin.model.DiffLine>): String {
        if (preview.isEmpty()) return ""
        val rows = preview.joinToString("") { line ->
            when (line.kind) {
                com.claudecode.chatplugin.model.DiffLine.Kind.ADDED ->
                    "<div class='cb-d cb-d-add'>+ ${escapeKeepNewlines(line.text)}</div>"
                com.claudecode.chatplugin.model.DiffLine.Kind.REMOVED ->
                    "<div class='cb-d cb-d-del'>- ${escapeKeepNewlines(line.text)}</div>"
                com.claudecode.chatplugin.model.DiffLine.Kind.GAP ->
                    "<div class='cb-d cb-d-gap'>${escapeKeepNewlines(line.text.ifEmpty { "⋯" })}</div>"
            }
        }
        return "<div class='cb-diff'>$rows</div>"
    }

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
            // Under its own file, so several changed files stay readable as a
            // list rather than one long ribbon of green and red.
            if (withLinks) rows.append("<li class='cb-edit-body'>").append(previewBlock(e.preview)).append("</li>")
        }
        // One-click undo for the whole turn, once more than one edit is revertible.
        if (withLinks && edits.count { it.isResolved && it.canRevert } > 1) {
            rows.append("<li class='cb-edit'>")
                .append(link(msgIndex, "revertall", -1, "revert all", danger = true))
                .append("</li>")
        }
        // Everything from here on, for when the wrong turn was several messages
        // back. Offered on every edited message; the handler decides whether
        // there is anything later to undo.
        if (withLinks && edits.any { it.isResolved && it.canRevert }) {
            rows.append("<li class='cb-edit'>")
                .append(link(msgIndex, "restorehere", -1, "restore files to before this", danger = true))
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
            // The long way round, one click instead of four: the message can
            // describe the path to the setting, but it shouldn't have to.
            link(msgIndex, "opensettings", 0, "Settings…") +
            "</div>"
    }

    /**
     * The question asked *before* anything happens: a card holding one tool
     * call, with the two answers to it.
     *
     * The shape is deliberate. What kind of thing this is and where it happens
     * goes on top, because that decides most answers on its own; the call
     * itself sits below in monospace, for the answers that need reading; the
     * buttons are last and on the right, where a decision belongs.
     */
    private fun approvalBlock(request: com.claudecode.chatplugin.permissions.ApprovalRequest, msgIndex: Int): String {
        val decided = request.decision
        if (decided != null) {
            val said = when (decided) {
                is com.claudecode.chatplugin.permissions.ApprovalDecision.Allow ->
                    if (decided.remembered) "Ran — allowed earlier in this chat" else "Ran"
                is com.claudecode.chatplugin.permissions.ApprovalDecision.Deny -> escape(decided.message)
            }
            val state = if (decided is com.claudecode.chatplugin.permissions.ApprovalDecision.Allow) "ran" else "skipped"
            return "<div class='cb-ask' data-state='$state'>${askHead(request)}" +
                "<div class='cb-ask-actions'><span class='cb-ask-done'>$said</span></div></div>"
        }
        return approvalBody(request, msgIndex, 0)
    }

    /**
     * The question itself: what is about to happen, and the two answers.
     *
     * Rendered inside the tool call it belongs to — [callIndex] is which one,
     * carried in the link so the click lands on the right question when a turn
     * has several in flight.
     */
    private fun approvalBody(
        request: com.claudecode.chatplugin.permissions.ApprovalRequest,
        msgIndex: Int,
        callIndex: Int
    ): String {
        val always = com.claudecode.chatplugin.permissions.AutoApproval
            .label(request.toolName, request.input)
            ?.let { link(msgIndex, "askalways", callIndex, "Always allow ${escape(it)}") }
            .orEmpty()

        return "<div class='cb-ask' data-state='pending'>${askHead(request)}" +
            "<div class='cb-ask-actions'>" +
            always +
            link(msgIndex, "askskip", callIndex, "Skip") +
            link(msgIndex, "askrun", callIndex, "Run", primary = true) +
            "</div></div>"
    }

    /** Where it happens, then the call itself, verbatim. */
    private fun askHead(request: com.claudecode.chatplugin.permissions.ApprovalRequest): String =
        "<div class='cb-ask-head'>${escape(request.summary.title)}</div>" +
            request.summary.detail?.let { "<div class='cb-ask-body'>${escape(it)}</div>" }.orEmpty()

    /** A word on a settled row saying the decision was yours, not the CLI's. */
    private fun approvalMark(request: com.claudecode.chatplugin.permissions.ApprovalRequest?): String {
        val decided = request?.decision ?: return ""
        val word = when (decided) {
            is com.claudecode.chatplugin.permissions.ApprovalDecision.Allow ->
                if (decided.remembered) "allowed earlier" else "allowed"
            is com.claudecode.chatplugin.permissions.ApprovalDecision.Deny -> "skipped"
        }
        return "<span class='cb-ask-mark'>$word</span>"
    }

    private fun link(
        msgIndex: Int,
        action: String,
        editIndex: Int,
        text: String,
        danger: Boolean = false,
        primary: Boolean = false
    ): String {
        val token = ChatLink(msgIndex, action, editIndex).token()
        val cls = "cb-btn" + (if (danger) " danger" else "") + (if (primary) " primary" else "")
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
        body.append(toolCallsBlock(message.toolCalls, msgIndex))
        body.append(editsBlock(message.edits, msgIndex, withLinks = !streaming))
        if (streaming) {
            body.append(escape(message.text))
            body.append("<span class='cb-caret'>&#9611;</span>")
        } else {
            // A live card already says everything the text says — the text is
            // there for the reloaded transcript, where the card is gone.
            if (message.approvalRequest == null) body.append(md(message.text.ifEmpty { " " }))
            // Below the explanation, where the decision belongs: you read what
            // was refused and why, then answer.
            message.permissionRequest?.let { body.append(permissionBlock(it, msgIndex)) }
            message.approvalRequest?.let { body.append(approvalBlock(it, msgIndex)) }
            // Offered on your own turns, because those are the points a
            // conversation is worth going back to and taking differently.
            if (message.role == com.claudecode.chatplugin.model.Role.USER) {
                body.append("<div class='cb-perm'>")
                    .append(link(msgIndex, "branchhere", -1, "branch from here"))
                    .append("</div>")
            }
            message.problems?.takeIf { it.isNotEmpty() }?.let {
                body.append("<div class='cb-perm'>")
                    .append(link(msgIndex, "fixproblems", 0, "Ask Claude to fix these"))
                    .append("</div>")
            }
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
