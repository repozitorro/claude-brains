package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.ClaudeCliService
import com.claudecode.chatplugin.ClaudeCodeSettings
import com.claudecode.chatplugin.model.ChatMessage
import com.claudecode.chatplugin.model.ClaudeSession
import com.claudecode.chatplugin.model.FileEdit
import com.claudecode.chatplugin.model.ModelChoice
import com.claudecode.chatplugin.model.PermissionChoice
import com.claudecode.chatplugin.model.Role
import com.claudecode.chatplugin.model.ToolCall
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComboBox
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import javax.swing.JComponent
import javax.swing.TransferHandler
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.Timer

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
        // The prompt is prose you read while writing it, so give it a little more
        // size and room than the surrounding chrome — the default UI font at
        // toolbar size is cramped for anything longer than a sentence.
        font = JBFont.label().biggerOn(1.5f)
        margin = JBUI.insets(4, 5)
        emptyText.text = "Ask Claude…  /  commands   @  files   paste a screenshot"
    }

    private val modelSelector = JComboBox<ModelChoice>().apply {
        // A fresh session adopts the configured default (otherwise that setting
        // would only ever be cosmetic); a restored session keeps its own model.
        if (session.selectedModel == null) {
            session.selectedModel = settings.defaultModel.takeIf { it.isNotBlank() }
        }
        val current = ModelChoice.forId(session.selectedModel)
        ModelChoice.ALL.forEach { addItem(it) }
        if (ModelChoice.ALL.none { it.id == current.id }) addItem(current) // a pinned id we don't list
        selectedItem = current
        toolTipText = "Model for this chat"

        addActionListener { session.selectedModel = (selectedItem as ModelChoice).id }
    }

    private val modeSelector = JComboBox<PermissionChoice>().apply {
        PermissionChoice.ALL.forEach { addItem(it) }
        selectedItem = PermissionChoice.forId(session.permissionMode ?: settings.permissionMode)
        // Set the tooltip on the combo itself: the `modeSelector` property is
        // still null while its own initializer runs, and reaching for it here
        // threw an NPE that took the whole tool window down with it.
        toolTipText = modeTooltip(selectedItem as PermissionChoice)

        addActionListener {
            val choice = selectedItem as PermissionChoice
            session.permissionMode = choice.id
            toolTipText = modeTooltip(choice)
        }
    }

    private fun modeTooltip(choice: PermissionChoice): String =
        if (choice.hint.isNotBlank()) choice.hint else "Permission mode for this chat"

    private val statusLabel = JLabel(" ").apply {
        font = font.deriveFont(10f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        border = JBUI.Borders.empty(0, 6)
    }

    private val composer = ComposerPanel(JBUI.CurrentTheme.Focus.focusColor())

    /**
     * Shown when a turn comes back 401.
     *
     * `claude auth status` reports a stored login as valid even once its token
     * has expired (verified against CLI 2.1.205), so an expired session can only
     * be discovered when a request actually fails. Rather than leaving the user
     * with an error to read, the failure carries the way out with it.
     */
    private val authBanner: JPanel = createAuthBanner()

    /**
     * Built in a function so the Dismiss button can close over a local rather
     * than the property it is initializing — the compiler rejects the latter,
     * and routing around that check through a method is exactly what produced
     * the empty tool window in 0.3.1.
     */
    private fun createAuthBanner(): JPanel {
        val banner = JPanel(BorderLayout())
        banner.isVisible = false
        banner.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBUI.CurrentTheme.NotificationWarning.borderColor(), 0, 0, 1, 0),
            JBUI.Borders.empty(6, 8)
        )
        banner.background = JBUI.CurrentTheme.NotificationWarning.backgroundColor()
        banner.add(
            JLabel("Your Claude Code login has expired.").apply {
                foreground = JBUI.CurrentTheme.NotificationWarning.foregroundColor()
            },
            BorderLayout.CENTER
        )
        banner.add(
            JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(JButton("Sign in").apply { addActionListener { signInFromBanner() } })
                add(JButton("Dismiss").apply { addActionListener { banner.isVisible = false } })
            },
            BorderLayout.EAST
        )
        return banner
    }

    private val sendButton = JButton("Send", AllIcons.Actions.Execute).apply {
        toolTipText = "Send (Enter)"
        addActionListener { onSendOrStop() }
    }

    /** Compact icon-only button, styled like the IDE's own toolbar controls. */
    private fun iconButton(icon: javax.swing.Icon, tooltip: String, action: () -> Unit) =
        JButton(icon).apply {
            toolTipText = tooltip
            isFocusable = false
            putClientProperty("JButton.buttonType", "toolBarButton")
            margin = JBUI.insets(2)
            addActionListener { action() }
        }

    private val attachButton = iconButton(
        AllIcons.FileTypes.Image, "Attach an image (or paste a screenshot into the prompt)"
    ) { chooseImage() }

    init {
        preferredSize = Dimension(420, 600)

        // Top bar: what this chat runs as (model, permission mode) on the left,
        // what you can do with the transcript on the right.
        val topBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 6, 2, 4)
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(modelSelector)
                add(modeSelector)
            }, BorderLayout.WEST)
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                add(iconButton(AllIcons.Actions.Copy, "Copy the conversation as Markdown") { copyTranscript() })
                add(iconButton(AllIcons.ToolbarDecorator.Export, "Export the conversation to a Markdown file") { exportTranscript() })
            }, BorderLayout.EAST)
        }

        // Composer: one card holding the prompt, with its controls on a footer
        // row — the image button and the usage readout on the left, Send right.
        inputArea.apply {
            isOpaque = false
            border = JBUI.Borders.empty(2)
            rows = 3
        }
        val composerFooter = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(attachButton)
                add(statusLabel)
            }, BorderLayout.WEST)
            add(sendButton, BorderLayout.EAST)
        }
        composer.add(inputArea, BorderLayout.CENTER)
        composer.add(composerFooter, BorderLayout.SOUTH)

        val composerWrapper = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 6, 6, 6)
            isOpaque = false
            add(composer, BorderLayout.CENTER)
        }

        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(authBanner, BorderLayout.NORTH)
                add(topBar, BorderLayout.CENTER)
            },
            BorderLayout.NORTH
        )
        add(chatView.component, BorderLayout.CENTER)
        add(composerWrapper, BorderLayout.SOUTH)

        // The card's outline follows focus, like the IDE's own input fields.
        inputArea.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent) { composer.focused = true }
            override fun focusLost(e: java.awt.event.FocusEvent) { composer.focused = false }
        })

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
        // Handled through the transfer handler rather than a Ctrl+V key binding:
        // the IDE's own $Paste action claims that shortcut before Swing key
        // bindings run, so a binding here was routinely bypassed. Everything —
        // IDE paste, Swing paste and drag-and-drop — funnels through importData.
        inputArea.transferHandler = object : TransferHandler() {

            override fun canImport(support: TransferSupport): Boolean =
                support.isDataFlavorSupported(DataFlavor.imageFlavor) ||
                    support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                    support.isDataFlavorSupported(DataFlavor.stringFlavor)

            override fun importData(support: TransferSupport): Boolean {
                // A screenshot: no file exists yet, so write one for Claude to read.
                if (support.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                    val file = ImageAttachments.saveClipboardImage(support.transferable)
                    if (file != null) {
                        insertAtCaret("`${file.absolutePath}` ")
                        statusLabel.text = "attached ${file.name}"
                        return true
                    }
                }
                // Image files dropped or copied from a file manager: reference
                // them where they already are instead of duplicating them.
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    val files = runCatching {
                        @Suppress("UNCHECKED_CAST")
                        support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<java.io.File>
                    }.getOrNull().orEmpty()
                    if (files.isNotEmpty()) {
                        files.forEach { insertAtCaret("`${it.absolutePath}` ") }
                        statusLabel.text = "attached ${files.size} file(s)"
                        return true
                    }
                }
                val text = runCatching {
                    support.transferable.getTransferData(DataFlavor.stringFlavor) as String
                }.getOrNull() ?: return false
                inputArea.replaceSelection(text)
                return true
            }

            // Keep copy/cut working out of the prompt box.
            override fun getSourceActions(c: JComponent?): Int = COPY_OR_MOVE

            override fun createTransferable(c: JComponent?): Transferable? =
                inputArea.selectedText?.let { java.awt.datatransfer.StringSelection(it) }

            override fun exportDone(source: JComponent?, data: Transferable?, action: Int) {
                if (action == MOVE) inputArea.replaceSelection("")
            }
        }
    }

    private fun copyTranscript() {
        val markdown = TranscriptExporter.toMarkdown(session)
        CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(markdown))
        statusLabel.text = "conversation copied"
    }

    private fun exportTranscript() {
        val descriptor = FileSaverDescriptor("Export Conversation", "Save the transcript as Markdown", "md")
        val suggested = session.displayName.replace(Regex("[^A-Za-z0-9._-]"), "-") + ".md"
        val target = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as com.intellij.openapi.vfs.VirtualFile?, suggested) ?: return
        try {
            target.file.writeText(TranscriptExporter.toMarkdown(session))
            statusLabel.text = "exported ${target.file.name}"
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Could not write the file: ${e.message}", "Claude Brains")
        }
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
        sendButton.icon = if (busy) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
        sendButton.toolTipText = if (busy) "Stop this turn" else "Send (Enter)"
        if (busy) statusLabel.text = "…thinking" else updateAnalyticsLabel()
    }

    /** Cumulative token/cost analytics for this session, plus the rate-limit window. */
    private fun updateAnalyticsLabel() {
        val parts = mutableListOf<String>()
        if (session.turnCount > 0) {
            parts += "$" + fmt("%.4f", session.totalCostUsd)
            parts += "${formatTokens(session.totalInputTokens)} in / ${formatTokens(session.totalOutputTokens)} out"
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

    /** Number formatting pinned to Locale.ROOT so figures don't shift with the IDE locale. */
    private fun fmt(pattern: String, vararg args: Any): String =
        String.format(java.util.Locale.ROOT, pattern, *args)

    private fun formatTokens(n: Long): String = when {
        n >= 1_000_000 -> fmt("%.1fM", n / 1_000_000.0)
        n >= 1_000 -> fmt("%.1fk", n / 1_000.0)
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
            // The CLI's own /login is interactive-only, but signing in is exactly
            // what someone typing it wants — so run the real sign-in instead of
            // turning them away.
            "/login" -> signInFromBanner()
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
                    "- `/login` — sign in again (opens a terminal)\n" +
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

    /** Hands sign-in to the CLI in a terminal; credentials never enter the plugin. */
    private fun signInFromBanner() {
        val auth = com.claudecode.chatplugin.auth.ClaudeAuth.getInstance(project)
        val launched = auth.launchSignIn()
        addSystemBubble(
            if (launched) {
                "A terminal is running `${auth.signInCommand()}`. Finish signing in there, " +
                    "then send your message again."
            } else {
                "Couldn't open a terminal. Run `${auth.signInCommand()}` yourself, " +
                    "then send your message again."
            }
        )
    }

    private fun analyticsSummary(): String = if (session.turnCount == 0) {
        "No usage yet in this session."
    } else {
        fmt(
            "**Session usage** — %d turns · $%.4f · %d input / %d output tokens",
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

        // Streamed updates are coalesced; terminal states repaint immediately.
        fun repaint() = scheduleRender(assistantIndex, assistantMessage)
        fun repaintNow() = renderNow(assistantIndex, assistantMessage)

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

            override fun onToolResult(toolUseId: String?, isError: Boolean, output: String?) {
                ApplicationManager.getApplication().invokeLater {
                    val tc = assistantMessage.toolCalls.lastOrNull { it.id == toolUseId }
                        ?: assistantMessage.toolCalls.lastOrNull { it.status == ToolCall.Status.RUNNING }
                    tc?.status = if (isError) ToolCall.Status.ERROR else ToolCall.Status.OK
                    tc?.output = output
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

            override fun onSessionExpired() {
                ApplicationManager.getApplication().invokeLater {
                    addSystemBubble(
                        "ℹ️ The earlier conversation is no longer stored by the CLI, so this " +
                            "message was sent with a fresh context. The transcript above is kept " +
                            "for reference, but Claude can't see it."
                    )
                }
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
                            authBanner.isVisible = true
                            "\n\nSign in again using the banner above, then send this message once more."
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
                    repaintNow()

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
                    repaintNow()
                    setBusy(false)
                }
            }
        })
    }

    // --- Render coalescing ---
    // Re-rendering a message costs O(its length), so repainting on every streamed
    // token is quadratic in the reply size and visibly stutters on long answers.
    // Updates are therefore coalesced: at most one repaint per RENDER_INTERVAL_MS,
    // with the latest state always rendered by the trailing tick.
    private var pendingRender: Pair<Int, ChatMessage>? = null

    private val renderTimer = Timer(RENDER_INTERVAL_MS) {
        pendingRender?.let { (index, message) -> chatView.render(index, message) }
        pendingRender = null
    }.apply { isRepeats = false }

    private fun scheduleRender(index: Int, message: ChatMessage) {
        pendingRender = index to message
        if (!renderTimer.isRunning) renderTimer.start()
    }

    /** Renders immediately, cancelling any coalesced update (for final/terminal states). */
    private fun renderNow(index: Int, message: ChatMessage) {
        renderTimer.stop()
        pendingRender = null
        chatView.render(index, message)
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        const val RENDER_INTERVAL_MS = 50
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
