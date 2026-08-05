package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.ClaudeCliService
import com.claudecode.chatplugin.ClaudeCodeSettings
import com.claudecode.chatplugin.model.ChatMessage
import com.claudecode.chatplugin.model.ClaudeSession
import com.claudecode.chatplugin.model.FileEdit
import com.claudecode.chatplugin.model.Role
import com.claudecode.chatplugin.model.ToolCall
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Chat UI for a single [ClaudeSession]. One of these lives inside each tool
 * window tab, so parallel sessions each get their own independent panel.
 *
 * The message surface is a [ChatView]: an embedded browser ([JcefChatView])
 * when JCEF is available, otherwise a plain Swing fallback ([SwingChatView]).
 * Messages are addressed by their index in [ClaudeSession.messages].
 */
class ChatPanel(private val project: Project, private val session: ClaudeSession) : JPanel(BorderLayout()) {

    private val cliService = project.getService(ClaudeCliService::class.java)
    private val settings = ClaudeCodeSettings.getInstance(project)

    private val chatView: ChatView =
        if (JcefChatView.isAvailable()) JcefChatView(project, ::handleEditLink)
        else SwingChatView(::handleEditLink)

    private val fileSearch = ProjectFileSearch(project)

    private val inputArea = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Ask Claude... (/ for commands, @ for files, Enter to send, Shift+Enter for newline)"
    }

    // Model aliases are accepted by `claude --model` and stay valid across
    // point releases, unlike pinned ids.
    private val modelSelector = JComboBox(arrayOf("(default)", "opus", "sonnet", "haiku")).apply {
        // A fresh session adopts the configured default (otherwise that setting
        // would only ever be cosmetic); a restored session keeps its own model.
        if (session.selectedModel == null) {
            session.selectedModel = settings.defaultModel.takeIf { it.isNotBlank() }
        }
        val current = session.selectedModel
        // A persisted model may be a pinned id that isn't one of the aliases above.
        if (current != null && (0 until itemCount).none { getItemAt(it) == current }) addItem(current)
        selectedItem = current ?: "(default)"

        addActionListener {
            val choice = selectedItem as String
            session.selectedModel = if (choice == "(default)") null else choice
        }
    }

    private val statusLabel = JLabel(" ").apply {
        font = font.deriveFont(10f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        border = JBUI.Borders.empty(0, 6)
    }

    private val sendButton = JButton("Send").apply {
        addActionListener { onSendOrStop() }
    }

    private val attachButton = JButton("🖼").apply {
        toolTipText = "Attach an image (or just paste a screenshot into the prompt)"
        addActionListener { chooseImage() }
    }

    init {
        preferredSize = Dimension(420, 600)

        val topBar = JPanel(BorderLayout()).apply {
            add(JLabel("  Model:"), BorderLayout.WEST)
            add(modelSelector, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.EAST)
        }

        val buttons = JPanel(BorderLayout()).apply {
            add(attachButton, BorderLayout.WEST)
            add(sendButton, BorderLayout.EAST)
        }
        val inputPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(JBScrollPane(inputArea), BorderLayout.CENTER)
            add(buttons, BorderLayout.EAST)
        }

        add(topBar, BorderLayout.NORTH)
        add(chatView.component, BorderLayout.CENTER)
        add(inputPanel, BorderLayout.SOUTH)

        installSlashCommandPopup()
        installEnterToSend()
        installImagePaste()

        // Repaint any history the session already has (persisted or pre-filled).
        session.messages.forEachIndexed { i, m -> chatView.render(i, m) }
        updateAnalyticsLabel()
    }

    fun dispose() = chatView.dispose()

    /** Pre-fills the input box, e.g. from "Send Selection to Claude". Does not send automatically. */
    fun prefillInput(text: String) {
        inputArea.text = text
        inputArea.caretPosition = inputArea.document.length // ready to keep typing
        inputArea.requestFocusInWindow()
    }

    private fun installEnterToSend() {
        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    sendCurrentInput()
                }
            }
        })
    }

    /**
     * Intercepts paste when the clipboard holds an image (a screenshot): the
     * image is written to a temp PNG and its path inserted, since that's how the
     * headless CLI consumes images — Claude opens the path with its Read tool.
     * Text pastes fall through to the normal editor behaviour.
     */
    private fun installImagePaste() {
        val pasteKey = KeyStroke.getKeyStroke(KeyEvent.VK_V, java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
        val actionKey = "claudeBrainsPasteImage"
        val fallback = inputArea.getActionForKeyStroke(pasteKey)
        inputArea.inputMap.put(pasteKey, actionKey)
        inputArea.actionMap.put(actionKey, object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                if (ImageAttachments.clipboardHasImage()) {
                    val file = ImageAttachments.saveClipboardImage()
                    if (file != null) {
                        insertAtCaret("`${file.absolutePath}` ")
                        statusLabel.text = "attached ${file.name}"
                        return
                    }
                    statusLabel.text = "could not read image from clipboard"
                }
                fallback?.actionPerformed(e) ?: inputArea.paste()
            }
        })
    }

    private fun chooseImage() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withFileFilter { it.extension?.lowercase() in IMAGE_EXTENSIONS }
            .apply { title = "Attach Image" }
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        insertAtCaret("`${chosen.path}` ")
        inputArea.requestFocusInWindow()
    }

    private fun insertAtCaret(text: String) {
        inputArea.document.insertString(inputArea.caretPosition, text, null)
    }

    private fun installSlashCommandPopup() {
        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER || e.keyCode == KeyEvent.VK_ESCAPE) return
                val text = inputArea.text
                if (text.startsWith("/") && !text.contains("\n")) {
                    val matches = SlashCommands.matching(text)
                    if (matches.isNotEmpty()) showSlashPopup(matches)
                    return
                }
                currentFileQuery()?.let { showFilePopup(it) }
            }
        })
    }

    /**
     * The `@...` token immediately before the caret, if the caret is currently
     * inside one (i.e. an unbroken run of non-space characters starting at `@`).
     */
    private fun currentFileQuery(): String? {
        val caret = inputArea.caretPosition
        val text = inputArea.text
        if (caret == 0 || caret > text.length) return null
        val start = text.lastIndexOf('@', caret - 1)
        if (start < 0) return null
        val token = text.substring(start + 1, caret)
        if (token.any { it.isWhitespace() }) return null
        // Require '@' to start a word, so emails/annotations mid-word don't trigger it.
        if (start > 0 && !text[start - 1].isWhitespace()) return null
        return token
    }

    private fun showFilePopup(query: String) {
        val matches = fileSearch.search(query)
        if (matches.isEmpty()) return
        val popup = JBPopupFactory.getInstance().createListPopup(
            object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<String>("Project Files", matches) {
                override fun getTextFor(value: String) = value
                override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                    insertFileReference(selectedValue, query)
                    return FINAL_CHOICE
                }
            }
        )
        popup.showUnderneathOf(inputArea)
    }

    /** Replaces the `@query` token at the caret with the chosen path. */
    private fun insertFileReference(path: String, query: String) {
        val caret = inputArea.caretPosition
        val start = caret - query.length - 1 // include the '@'
        if (start < 0) return
        inputArea.document.remove(start, query.length + 1)
        inputArea.document.insertString(start, "`$path` ", null)
        inputArea.requestFocusInWindow()
    }

    private fun showSlashPopup(matches: List<SlashCommands.Command>) {
        val popup = JBPopupFactory.getInstance().createListPopup(object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<SlashCommands.Command>(
            "Slash Commands", matches
        ) {
            override fun getTextFor(value: SlashCommands.Command) = "${value.name} — ${value.description}"
            override fun onChosen(selectedValue: SlashCommands.Command, finalChoice: Boolean): PopupStep<*>? {
                inputArea.text = selectedValue.name + " "
                return FINAL_CHOICE
            }
        })
        popup.showUnderneathOf(inputArea)
    }

    private fun onSendOrStop() {
        if (session.isBusy) cliService.cancel(session) else sendCurrentInput()
    }

    private fun setBusy(busy: Boolean) {
        sendButton.text = if (busy) "Stop" else "Send"
        if (busy) statusLabel.text = "…thinking" else updateAnalyticsLabel()
    }

    /** Cumulative token/cost analytics for this session, plus the rate-limit window. */
    private fun updateAnalyticsLabel() {
        val parts = mutableListOf<String>()
        if (session.turnCount > 0) {
            parts += "$%.4f".format(session.totalCostUsd)
            parts += "%s in / %s out".format(
                formatTokens(session.totalInputTokens), formatTokens(session.totalOutputTokens)
            )
        }
        session.rateLimit?.let { rl ->
            rl.resetsAtEpochSec?.let { parts += "resets " + formatCountdown(it) }
        }
        statusLabel.text = if (parts.isEmpty()) " " else parts.joinToString("  ·  ")
        statusLabel.toolTipText = session.rateLimit?.let {
            "Rate-limit window: ${it.type} (${it.status}). The CLI does not expose an exact " +
                "% of your limit; this shows cumulative usage for this chat plus the window reset."
        }
    }

    private fun formatTokens(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fk".format(n / 1_000.0)
        else -> n.toString()
    }

    private fun formatCountdown(epochSec: Long): String {
        val secs = epochSec - System.currentTimeMillis() / 1000
        if (secs <= 0) return "now"
        val h = secs / 3600
        val m = (secs % 3600) / 60
        return if (h > 0) "in ${h}h${m}m" else "in ${m}m"
    }

    /**
     * Handles the slash commands that make sense in this headless chat locally,
     * and gives an honest note for the interactive-only ones (which would
     * otherwise be silently sent to `claude -p` as literal prompt text).
     * Returns true if the input was consumed as a command.
     */
    private fun handleSlashCommand(prompt: String): Boolean {
        when (prompt.substringBefore(' ').lowercase()) {
            "/clear" -> {
                session.messages.clear()
                session.cliSessionId = null
                chatView.clear()
                addSystemBubble("Conversation cleared — the next message starts a fresh context.")
            }
            "/cost" -> addSystemBubble(analyticsSummary())
            "/model" -> {
                modelSelector.showPopup()
                addSystemBubble("Pick a model from the **Model** dropdown at the top of this panel.")
            }
            "/help" -> addSystemBubble(
                "**Commands available in this chat:**\n\n" +
                    "- `/clear` — start a fresh context\n" +
                    "- `/cost` — show this session's token/cost usage\n" +
                    "- `/model` — switch model (or use the dropdown)\n" +
                    "- `/help` — this list\n\n" +
                    "Type `@` to reference a project file by path.\n\n" +
                    "Other Claude Code slash commands are interactive-terminal only and don't apply here."
            )
            else -> {
                if (SlashCommands.ALL.any { it.name == prompt.substringBefore(' ').lowercase() }) {
                    addSystemBubble(
                        "`${prompt.substringBefore(' ')}` is an interactive Claude Code command and isn't " +
                            "available in this headless chat. Supported here: `/clear`, `/cost`, `/model`, `/help`."
                    )
                } else {
                    return false // not a command we recognise → send as a normal prompt
                }
            }
        }
        return true
    }

    private fun analyticsSummary(): String = if (session.turnCount == 0) {
        "No usage yet in this session."
    } else {
        "**Session usage** — %d turns · $%.4f · %d input / %d output tokens".format(
            session.turnCount, session.totalCostUsd, session.totalInputTokens, session.totalOutputTokens
        )
    }

    private fun addSystemBubble(markdown: String) {
        addAndRender(ChatMessage(Role.SYSTEM, markdown))
    }

    /** Appends [message] to the transcript and renders it, returning its index. */
    private fun addAndRender(message: ChatMessage): Int {
        session.messages.add(message)
        val index = session.messages.lastIndex
        chatView.render(index, message)
        return index
    }

    private fun sendCurrentInput() {
        val prompt = inputArea.text.trim()
        if (prompt.isEmpty() || session.isBusy) return
        if (prompt.startsWith("/") && handleSlashCommand(prompt)) {
            inputArea.text = ""
            return
        }
        inputArea.text = ""

        addAndRender(ChatMessage(Role.USER, prompt))

        val assistantMessage = ChatMessage(Role.ASSISTANT, "", isStreaming = true)
        val assistantIndex = addAndRender(assistantMessage)

        setBusy(true)

        fun repaint() = chatView.render(assistantIndex, assistantMessage)

        cliService.sendPrompt(session, prompt, object : ClaudeCliService.StreamListener {
            override fun onTextChunk(chunk: String) {
                ApplicationManager.getApplication().invokeLater {
                    assistantMessage.text += chunk
                    repaint()
                }
            }

            override fun onThinkingChunk(chunk: String) {
                ApplicationManager.getApplication().invokeLater {
                    assistantMessage.thinking += chunk
                    repaint()
                }
            }

            override fun onToolUse(id: String?, display: String) {
                ApplicationManager.getApplication().invokeLater {
                    assistantMessage.toolCalls.add(ToolCall(id, display))
                    repaint()
                }
            }

            override fun onToolResult(toolUseId: String?, isError: Boolean) {
                ApplicationManager.getApplication().invokeLater {
                    val tc = assistantMessage.toolCalls.lastOrNull { it.id == toolUseId }
                        ?: assistantMessage.toolCalls.lastOrNull { it.status == ToolCall.Status.RUNNING }
                    tc?.status = if (isError) ToolCall.Status.ERROR else ToolCall.Status.OK
                    repaint()
                }
            }

            override fun onFileEdit(edit: FileEdit) {
                ApplicationManager.getApplication().invokeLater {
                    assistantMessage.edits.add(edit)
                    repaint()
                }
            }

            override fun onRateLimit(rateLimit: ClaudeSession.RateLimit) {
                ApplicationManager.getApplication().invokeLater {
                    session.rateLimit = rateLimit
                }
            }

            override fun onMcpFailures(failed: List<String>) {
                ApplicationManager.getApplication().invokeLater {
                    addSystemBubble(
                        "⚠️ MCP server(s) did not start: ${failed.joinToString(", ")}. " +
                            "Their tools are unavailable this turn — check `claude mcp list`."
                    )
                }
            }

            override fun onSessionId(cliSessionId: String) {
                session.cliSessionId = cliSessionId
            }

            override fun onComplete(result: ClaudeCliService.TurnResult) {
                ApplicationManager.getApplication().invokeLater {
                    assistantMessage.isStreaming = false
                    if (result.isError) {
                        // A failed turn carries its reason in the result event rather
                        // than as streamed text, so surface it instead of a blank reply.
                        val reason = result.errorMessage?.takeIf { it.isNotBlank() }
                            ?: "the turn ended with an error"
                        val hint = if (result.apiErrorStatus == 401) {
                            "\n\nYour Claude Code login has expired. Run `claude` in a terminal " +
                                "and sign in again, then retry."
                        } else {
                            ""
                        }
                        val prefix = if (assistantMessage.text.isBlank()) "" else "\n\n"
                        assistantMessage.text += "$prefix**Error:** $reason$hint"
                    }
                    // The CLI has finished writing to disk; reconstruct each edit's
                    // before/after now so the diff/revert links become live.
                    assistantMessage.edits.forEach { edit ->
                        val after = try {
                            java.io.File(edit.filePath).takeIf { it.isFile }?.readText()
                        } catch (e: Exception) {
                            null
                        }
                        edit.resolve(after)
                    }
                    repaint()

                    // Accumulate session analytics.
                    session.turnCount++
                    result.costUsd?.let { session.totalCostUsd += it }
                    result.inputTokens?.let { session.totalInputTokens += it }
                    result.outputTokens?.let { session.totalOutputTokens += it }

                    if (result.permissionDenials.isNotEmpty()) {
                        addSystemBubble(
                            "⚠️ Blocked (permission mode: `${settings.permissionMode}`): " +
                                "${result.permissionDenials.joinToString(", ")}. " +
                                "Loosen it in Settings → Tools → Claude Brains if this was intended."
                        )
                    }

                    setBusy(false)
                }
            }

            override fun onError(message: String) {
                ApplicationManager.getApplication().invokeLater {
                    assistantMessage.isStreaming = false
                    assistantMessage.text += "\n\n**Error:** $message"
                    repaint()
                    setBusy(false)
                }
            }
        })
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    }

    /**
     * Handles clicks on the diff/revert links (from either view), addressed as
     * `claudebrains:<msgIndex>:<action>:<editIndex>`. May be invoked off the EDT
     * (JCEF query thread), so it hops back on.
     */
    private fun handleEditLink(href: String) {
        ApplicationManager.getApplication().invokeLater {
            val parts = href.split(":")
            if (parts.size != 4 || parts[0] != "claudebrains") return@invokeLater
            val msgIndex = parts[1].toIntOrNull() ?: return@invokeLater
            val editIndex = parts[3].toIntOrNull() ?: return@invokeLater
            val message = session.messages.getOrNull(msgIndex) ?: return@invokeLater

            if (parts[2] == "revertall") {
                val targets = message.edits.filter { it.isResolved && it.canRevert }
                val reverted = targets.count { DiffReviewer.revert(project, it) }
                statusLabel.text = "reverted $reverted of ${targets.size} file(s)"
                return@invokeLater
            }

            val edit = message.edits.getOrNull(editIndex) ?: return@invokeLater
            when (parts[2]) {
                "diff" -> DiffReviewer.showDiff(project, edit)
                "revert" -> {
                    val ok = DiffReviewer.revert(project, edit)
                    statusLabel.text = if (ok) "reverted ${edit.fileName}" else "could not revert ${edit.fileName}"
                }
            }
        }
    }
}
