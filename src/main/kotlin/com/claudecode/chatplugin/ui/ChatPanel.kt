package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.ClaudeCliService
import com.claudecode.chatplugin.ClaudeCodeSettings
import com.claudecode.chatplugin.ClaudeSessionManager
import com.claudecode.chatplugin.model.ChatMessage
import com.claudecode.chatplugin.model.ClaudeSession
import com.claudecode.chatplugin.model.FileEdit
import com.claudecode.chatplugin.model.ModelChoice
import com.claudecode.chatplugin.model.PermissionChoice
import com.claudecode.chatplugin.model.PermissionRequest
import com.claudecode.chatplugin.permissions.ApprovalDecision
import com.claudecode.chatplugin.permissions.ApprovalService
import com.claudecode.chatplugin.permissions.AutoApproval
import com.claudecode.chatplugin.review.ConversationRestore
import com.claudecode.chatplugin.review.ProjectProblems
import com.claudecode.chatplugin.model.Role
import com.claudecode.chatplugin.model.ToolCall
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
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
class ChatPanel(private val project: Project, private val session: ClaudeSession) :
    JPanel(BorderLayout()), com.intellij.openapi.Disposable {

    /**
     * Set once this panel has been disposed.
     *
     * A turn already in flight keeps posting to the EDT after its tab is gone,
     * and those callbacks would otherwise render into a released browser. The
     * CLI process is killed on dispose, but its listener can still have work
     * queued, so every rendering path checks this first.
     */
    @Volatile
    private var disposed = false

    private val cliService = project.getService(ClaudeCliService::class.java)
    private val settings = ClaudeCodeSettings.getInstance(project)

    /**
     * The rich renderer when the IDE can host a browser, the Swing one otherwise.
     *
     * Creation is guarded as well as the availability check: whatever goes wrong
     * with an optional capability, it must not stop the chat from opening. The
     * whole tool window came up empty in 2026.2 for exactly this reason.
     */
    private val chatView: ChatView = createChatView()

    private fun createChatView(): ChatView =
        if (JcefChatView.isAvailable()) {
            try {
                // Parented to this panel, not to the project: the browser has to
                // go when its tab does, or every closed chat keeps one alive.
                JcefChatView(this, ::handleEditLink)
            } catch (e: Throwable) {
                LOG.warn("Could not start the embedded browser; using the Swing view", e)
                SwingChatView(::handleEditLink)
            }
        } else {
            SwingChatView(::handleEditLink)
        }

    /**
     * Where this chat's CLI sends its permission questions.
     *
     * Opened here because this is the object that can answer them, and closed
     * with the tab: a chat that is gone cannot decide anything, and its
     * endpoint should not outlive it.
     */
    private val approvalEndpoint: String? =
        if (settings.askBeforeActing) {
            ApprovalService.getInstance(project).endpointFor(session, this, ::showApproval)
        } else {
            null
        }

    private val fileSearch = ProjectFileSearch(project, this)
    private val symbolSearch = ProjectSymbolSearch(project, this)

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

    /**
     * How hard the model works on a turn — the CLI's own `--effort`.
     *
     * A direct lever on what a turn costs, which matters more here than
     * anywhere: the panel already shows how much of the week is gone.
     */
    private val effortSelector = JComboBox<String>().apply {
        EFFORT_LEVELS.forEach { addItem(it) }
        selectedItem = session.selectedEffort ?: settings.defaultEffort.ifBlank { CLI_DEFAULT }
        toolTipText = "How much thinking this chat spends per turn"
        addActionListener {
            session.selectedEffort = (selectedItem as? String)?.takeIf { it != CLI_DEFAULT }
        }
    }

    /**
     * Which subagent runs the turn — `Explore`, `Plan`, whatever this machine
     * has.
     *
     * Hidden until the list is known, and the only place it can be known is the
     * CLI's own `init` event, which arrives with the first turn. Shown before
     * then it offered exactly one choice, "Default agent", truncated to
     * "Defa…gent" — a control that explained nothing and did nothing.
     *
     * Asking the CLI up front was the obvious alternative and is the wrong one:
     * the init event only comes with a turn, and a turn calls the model. A
     * dropdown is not worth spending someone's quota on.
     */
    private val agentSelector = JComboBox<String>().apply {
        addItem(DEFAULT_AGENT)
        toolTipText = "Which agent runs this chat's turns — known once the CLI has answered once"
        isVisible = false
        addActionListener {
            session.selectedAgent = (selectedItem as? String)?.takeIf { it != DEFAULT_AGENT }
        }
    }

    /** Fills the agent list in once the CLI has said what it has. */
    private fun refreshAgentChoices() {
        val agents = session.capabilities?.agents.orEmpty()
        // A restored chat already knows what it chose, and hiding the control
        // that says so would make the choice look lost.
        agentSelector.isVisible = agents.isNotEmpty() || session.selectedAgent != null
        if (agents.isEmpty()) return
        val chosen = session.selectedAgent
        if ((0 until agentSelector.itemCount).map { agentSelector.getItemAt(it) } == listOf(DEFAULT_AGENT) + agents) {
            return // already showing this list; leave the selection alone
        }
        agentSelector.removeAllItems()
        agentSelector.addItem(DEFAULT_AGENT)
        agents.forEach { agentSelector.addItem(it) }
        agentSelector.selectedItem = chosen ?: DEFAULT_AGENT
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

    /**
     * `JBFont.small()`, not `deriveFont(10f)`.
     *
     * A hard 10pt is an absolute size: it ignores the IDE's font setting and
     * display scaling entirely, so on a HiDPI screen — or for anyone who has
     * turned the IDE font up — everything else grows and this stays a 10-pixel
     * smudge. JBFont.small() is derived from the current label font and scales
     * with it.
     *
     * The colour is the IDE's secondary text rather than its *disabled* text,
     * which is the dimmest thing in the palette and meant for controls you
     * cannot use — not for a figure you are supposed to read.
     */
    private fun readableSecondary(label: JLabel): JLabel = label.apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(1, 6)
    }

    private val statusLabel = readableSecondary(JLabel(" "))

    private val limitService = com.claudecode.chatplugin.limits.RateLimitService.getInstance(project)

    /** Window type, reset countdown and what's been spent inside it. */
    private val limitLabel = readableSecondary(JLabel(" "))

    /** How full the current limit window is, read at a glance rather than parsed. */
    private val limitBar = LimitProgressBar()

    /**
     * Refreshes the limit readout every minute: the countdown has to tick, and
     * the percentages come from asking the CLI, which is only worth doing while
     * someone can see the answer.
     */
    private val limitTicker = Timer(LIMIT_TICK_MS) {
        if (isShowing) limitService.refreshBars()
        updateLimitLabel()
    }.apply { isRepeats = true }

    private val composer = ComposerPanel(JBUI.CurrentTheme.Focus.focusColor())

    private val reviewService = com.claudecode.chatplugin.review.EditReviewService.getInstance(project).also {
        // Touch the decorations service so it starts listening for editors; it
        // does the drawing, and nothing else would instantiate it.
        com.claudecode.chatplugin.review.EditReviewDecorations.getInstance(project)
    }

    /** Accept all / Reject all for the changes Claude has made but you haven't reviewed. */
    private val reviewBar = ReviewBar(project)

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
        // The service builds the command line and the panel owns the endpoint,
        // so the session is where the two meet.
        session.approvalEndpoint = approvalEndpoint
        // A restored chat may already know its agents, or its own choice.
        refreshAgentChoices()

        // Top bar: what this chat runs as (model, permission mode) on the left,
        // what you can do with the transcript on the right — with the account's
        // limits on their own line underneath, where they read as a state of the
        // account rather than as one more control.
        val controlsRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            // WrapLayout, not FlowLayout: a row that cannot fit has to report a
            // taller size rather than run on under the buttons beside it.
            //
            // Which is also why none of these selectors is given a width. Two
            // were, to keep the row narrow, and both then clipped their own
            // labels to "Defa…ffort" and "Defa…gent" — a number I guessed twice
            // and got wrong twice. Sizing to content cannot be wrong, and the
            // row now has somewhere to go when the sum of them is too much.
            add(JPanel(WrapLayout(java.awt.FlowLayout.LEFT, 4, 2)).apply {
                isOpaque = false
                add(modelSelector)
                add(modeSelector)
                add(effortSelector)
                add(agentSelector)
            }, BorderLayout.CENTER)
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                add(
                    iconButton(
                        AllIcons.FileTypes.Text,
                        "Project rules — CLAUDE.md, read at the start of every conversation here"
                    ) { openProjectRules() }
                )
                add(iconButton(AllIcons.Actions.Copy, "Copy the conversation as Markdown") { copyTranscript() })
                add(iconButton(AllIcons.ToolbarDecorator.Export, "Export the conversation to a Markdown file") { exportTranscript() })
            }, BorderLayout.EAST)
        }

        val topBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 6, 2, 4)
            add(controlsRow, BorderLayout.NORTH)
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(2, 4, 0, 4)
                    add(limitLabel, BorderLayout.NORTH)
                    add(limitBar, BorderLayout.SOUTH)
                },
                BorderLayout.SOUTH
            )
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
            // Review controls sit directly above the prompt: that's where you
            // are when you decide whether to keep what Claude just did.
            add(reviewBar, BorderLayout.NORTH)
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

        // Walk the project now, off the EDT, so the first `@` has something to
        // offer instead of paying for the walk mid-keystroke.
        fileSearch.warmUp()
        symbolSearch.warmUp()

        // The review bar subscribes to the review service, so it has to be
        // released with this panel rather than outliving it.
        com.intellij.openapi.util.Disposer.register(this, reviewBar)

        // Repaint any history the session already has (persisted or pre-filled).
        session.messages.forEachIndexed { i, m -> renderTo(i, m) }
        updateAnalyticsLabel()

        limitService.addChangeListener(this) {
            ApplicationManager.getApplication().invokeLater { if (!project.isDisposed) updateLimitLabel() }
        }
        updateLimitLabel()
        limitService.refreshBars(force = true)
        limitTicker.start()

        // One pass over the finished layout: every button in this panel, including
        // the ones on the review bar and the sign-in banner.
        HandCursors.applyTo(this)
    }

    /**
     * Called by the tool window's content disposer, so it runs when the tab is
     * closed *and* when the project closes — the latter used to be missed, which
     * left the limit ticker firing at a dead project once a minute.
     */
    override fun dispose() {
        disposed = true
        limitTicker.stop()
        renderTimer.stop()
        pendingRender = null
        // A turn still running belongs to this tab. Left alone the CLI process
        // would outlive the panel with nowhere to report back to.
        if (session.isBusy) cliService.cancel(session)
        chatView.dispose()
    }

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
        // Ctrl+V never reaches Swing here: the IDE's $Paste action takes the
        // shortcut first and pastes text straight into the component, without
        // consulting the transfer handler. An action registered *on the
        // component* outranks the global one, so that is where image paste has
        // to live; anything that isn't an image is handed back to normal paste.
        val pasteImage = object : com.intellij.openapi.project.DumbAwareAction() {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                if (!pasteImageFromClipboard()) inputArea.paste()
            }
        }
        val pasteShortcuts = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .getAction(com.intellij.openapi.actionSystem.IdeActions.ACTION_PASTE)
            ?.shortcutSet
        if (pasteShortcuts != null) pasteImage.registerCustomShortcutSet(pasteShortcuts, inputArea)

        // The transfer handler still covers drag-and-drop, and paste on any
        // platform where the action route doesn't apply.
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
                    statusLabel.text = "could not read the dropped image"
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

    /**
     * Opens the project's CLAUDE.md, offering to create it when there isn't one.
     *
     * Creating is asked about rather than done: a file at the project root is
     * the kind of thing that ends up committed, and Claude reads it on every
     * turn from then on.
     */
    private fun openProjectRules() {
        val basePath = project.basePath ?: return
        val file = ProjectRules.fileIn(basePath)

        if (!file.exists()) {
            val create = com.intellij.openapi.ui.MessageDialogBuilder
                .yesNo(
                    "Create ${ProjectRules.FILE_NAME}?",
                    "There is no ${ProjectRules.FILE_NAME} in this project yet.\n\n" +
                        "It holds standing instructions for Claude — how to build and test, what to " +
                        "leave alone — and the CLI reads it at the start of every conversation here.\n\n" +
                        "It will be created at the project root, with a short outline to fill in."
                )
                .yesText("Create")
                .noText("Cancel")
                .ask(project)
            if (!create) return

            val written = runCatching { file.writeText(ProjectRules.template(project.name)) }
            if (written.isFailure) {
                Messages.showErrorDialog(
                    project,
                    "Could not create ${file.path}: ${written.exceptionOrNull()?.message}",
                    "Claude Brains"
                )
                return
            }
        }

        val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByPath(file.path)
        if (virtualFile == null) {
            statusLabel.text = "could not open ${ProjectRules.FILE_NAME}"
            return
        }
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(virtualFile, true)
    }

    private fun copyTranscript() {
        val markdown = TranscriptExporter.toMarkdown(session)
        CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(markdown))
        statusLabel.text = "conversation copied"
    }

    private fun exportTranscript() {
        // Deprecated from 2025 on, and knowingly kept: 2024.1 ships no other
        // constructor for this at all (checked against the SDK, not from
        // memory), and that is the floor sinceBuild promises. Reaching for the
        // newer one would compile here and throw NoSuchMethodError there —
        // exactly the failure the plugin verifier exists to prevent. Replace it
        // when the floor moves past 2024.x, not before.
        @Suppress("DEPRECATION")
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

    /**
     * Saves an image sitting on the clipboard and references it in the prompt.
     * Returns false when the clipboard holds no image, so the caller can fall
     * back to an ordinary paste.
     *
     * Reads through the IDE's own [CopyPasteManager] first — inside the IDE that
     * is the authoritative view of the clipboard — and only then the AWT one.
     */
    private fun pasteImageFromClipboard(): Boolean {
        val contents = runCatching { CopyPasteManager.getInstance().contents }.getOrNull()
            ?: runCatching {
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
            }.getOrNull()

        // Logged because this path is invisible when it goes wrong: an image
        // paste that quietly does nothing is indistinguishable from a shortcut
        // that never reached us. The flavour list says which of the two it was.
        LOG.info(
            "paste into prompt: clipboard=" +
                (contents?.transferDataFlavors?.joinToString { it.humanPresentableName } ?: "unavailable")
        )
        if (contents == null) return false

        if (!contents.isDataFlavorSupported(DataFlavor.imageFlavor)) return false

        val file = ImageAttachments.saveClipboardImage(contents)
        if (file == null) {
            statusLabel.text = "could not read the image from the clipboard"
            return false
        }
        insertAtCaret("`${file.absolutePath}` ")
        statusLabel.text = "attached ${file.name}"
        return true
    }

    private fun chooseImage() {
        // The factory's createSingleFileDescriptor() is what this used to call;
        // it is deprecated from 2025 on, and its replacement does not exist in
        // 2024.1, which is the floor this still supports. The constructor it
        // delegated to is available and current in both: one file, no folders,
        // no jars, no multiselect.
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
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
                    val matches = SlashCommands.matching(text, session.capabilities)
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
        val files = fileSearch.search(query)
        // Symbols after files: you usually mean a path, and when you mean a
        // class you will recognise it lower down. Marked so the two kinds of
        // entry can be told apart at a glance.
        val symbols = symbolSearch.search(query).map { SYMBOL_PREFIX + it }
        val matches = files + symbols
        if (matches.isEmpty()) return

        val popup = JBPopupFactory.getInstance().createListPopup(
            object : com.intellij.openapi.ui.popup.util.BaseListPopupStep<String>("Project Files & Symbols", matches) {
                override fun getTextFor(value: String) = value
                override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                    if (selectedValue.startsWith(SYMBOL_PREFIX)) {
                        val name = selectedValue.removePrefix(SYMBOL_PREFIX)
                        // The file is what Claude can act on; the symbol was
                        // only how it was found.
                        val path = symbolSearch.fileOf(name)
                        if (path == null) {
                            statusLabel.text = "couldn't locate $name"
                            return FINAL_CHOICE
                        }
                        insertFileReference(path, query)
                    } else {
                        insertFileReference(selectedValue, query)
                    }
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
        if (session.isBusy) {
            // Stop means stop: anything still waiting was queued behind a turn
            // the user has just decided against, and firing it next would be
            // the opposite of what the button says.
            val abandoned = queued.size
            queued.clear()
            cliService.cancel(session)
            if (abandoned > 0) {
                addSystemBubble(
                    "Stopped. " + (if (abandoned == 1) "1 queued message was" else "$abandoned queued messages were") +
                        " not sent."
                )
            }
        } else {
            sendCurrentInput()
        }
    }

    private fun setBusy(busy: Boolean) {
        sendButton.text = if (busy) "Stop" else "Send"
        sendButton.icon = if (busy) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
        sendButton.toolTipText = if (busy) "Stop this turn" else "Send (Enter)"
        if (busy) statusLabel.text = "…thinking" + queuedSuffix() else updateAnalyticsLabel()
    }

    /** Cumulative token/cost analytics for this session, plus the rate-limit window. */
    private fun updateAnalyticsLabel() {
        // Only this conversation's own spend. The account's limits and their
        // reset live under the model selectors — repeating the countdown here
        // just said the same thing twice.
        val parts = mutableListOf<String>()
        if (session.turnCount > 0) {
            parts += "$" + fmt("%.4f", session.totalCostUsd)
            parts += "${formatTokens(session.totalInputTokens)} in / ${formatTokens(session.totalOutputTokens)} out"
        }
        // How close this conversation is to filling the model's context — the
        // thing that decides when it has to be compacted or started over.
        val used = session.contextTokens
        val window = session.contextWindow
        if (used != null && window != null && window > 0) {
            parts += "context ${formatTokens(used)} / ${formatTokens(window)} " +
                "(${fmt("%.0f", used * 100.0 / window)}%)"
        }
        if (session.isBusy) {
            // Mid-turn the useful number is how much is still waiting, not what
            // the conversation has cost so far.
            statusLabel.text = "…thinking" + queuedSuffix()
            return
        }
        statusLabel.text = if (parts.isEmpty()) " " else parts.joinToString("  ·  ")
        statusLabel.toolTipText =
            if (parts.isEmpty()) null else "What this conversation has cost so far"
    }

    /** Number formatting pinned to Locale.ROOT so figures don't shift with the IDE locale. */
    private fun fmt(pattern: String, vararg args: Any): String =
        String.format(java.util.Locale.ROOT, pattern, *args)

    private fun updateLimitLabel() {
        val summary = limitService.summary()
        limitLabel.isVisible = summary != null
        limitLabel.text = summary ?: " "
        limitLabel.toolTipText = if (summary != null) limitService.explanation() else null

        // The session window is the one that bites first, so that's the one the
        // bar tracks.
        val session = limitService.limitBars().firstOrNull { it.shortLabel() == "Session" }
            ?: limitService.limitBars().firstOrNull()
        limitBar.percent = session?.percentUsed
        limitBar.toolTipText = session?.let { "${it.label}: ${it.percentUsed}% used, resets ${it.resetsAt}" }
        // Once the window is filling up, the readout stops being background
        // information — so it stops being drawn as background information.
        limitLabel.foreground = limitColour(session?.percentUsed)
    }

    /** Secondary while there is room, then amber, then red — matching the bar under it. */
    private fun limitColour(percent: Int?): java.awt.Color = when {
        percent == null -> UIUtil.getContextHelpForeground()
        percent >= 90 -> JBColor(java.awt.Color(0xC0392B), java.awt.Color(0xE0796F))
        percent >= 75 -> JBColor(java.awt.Color(0xA85B00), java.awt.Color(0xE0904A))
        else -> UIUtil.getContextHelpForeground()
    }

    private fun formatTokens(n: Long): String = when {
        n >= 1_000_000 -> fmt("%.1fM", n / 1_000_000.0)
        n >= 1_000 -> fmt("%.1fk", n / 1_000.0)
        else -> n.toString()
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

    /**
     * Turns a refusal into a question with two answers.
     *
     * Reached only when the CLI decided alone — no approval endpoint for this
     * chat, so it refused and said what it refused. Asking afterwards is then
     * the only place the question can be put, and answering yes means granting
     * the tool and sending the same message again, which from the outside is
     * the conversation carrying on. When an endpoint *is* open the question
     * arrives before anything happens instead; see [showApproval].
     */
    private fun askToAllow(
        denials: List<com.claudecode.chatplugin.cli.PermissionDenial>,
        mode: String?,
        prompt: String
    ) {
        val explanation = BlockedToolsMessage.format(denials, mode)
        val pattern = BlockedToolsMessage.suggestedPattern(denials)
        val message = ChatMessage(Role.SYSTEM, explanation)
        // Without a pattern there is nothing specific to grant, so the message
        // stands on its own rather than offering a button that would do nothing.
        if (pattern != null) {
            // Only a command can be handed to a shell; a refused Write has
            // nothing to run, so that button is simply not offered.
            val command = denials.firstOrNull { it.toolName in COMMAND_TOOLS }?.detail
                ?.takeIf { TerminalRunner.isAvailable() }
            message.permissionRequest = PermissionRequest(denials, pattern, prompt, command)
        }
        addAndRender(message)
    }

    /**
     * Puts every file back to how it stood before the message at [index].
     *
     * Newest edit first: later ones were written on top of earlier ones, and
     * undoing them in the order they happened would restore text that was never
     * on disk.
     */
    private fun restoreFilesToBefore(index: Int) {
        val edits = ConversationRestore.editsToRevert(session.messages, index)
        val revertible = edits.filter { it.canRevert }
        if (revertible.isEmpty()) {
            addSystemBubble("Nothing here can be restored — none of these edits can be undone exactly.")
            return
        }

        val files = ConversationRestore.affectedFiles(edits)
        val skipped = edits.size - revertible.size
        val confirmed = com.intellij.openapi.ui.MessageDialogBuilder
            .yesNo(
                "Restore files to before this message?",
                buildString {
                    append(files.size).append(if (files.size == 1) " file" else " files")
                    append(" will go back to how they were before this point: ")
                    append(files.joinToString(", "))
                    append(".\n\n")
                    if (skipped > 0) {
                        append(skipped)
                        append(if (skipped == 1) " later edit cannot" else " later edits cannot")
                        append(" be undone exactly and will be left as they are.\n\n")
                    }
                    append("The conversation itself is not touched — Claude still remembers all of it.")
                }
            )
            .yesText("Restore")
            .noText("Keep")
            .icon(AllIcons.General.WarningDialog)
            .ask(project)
        if (!confirmed) return

        val restored = revertible.count { DiffReviewer.revert(project, it) }

        // The review markers describe changes that are no longer in the file.
        // Dropping them leaves the editor honest without touching content.
        files.forEach { name ->
            revertible.firstOrNull { it.fileName == name }?.filePath?.let { path ->
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(path)
                    ?.let { reviewService.acceptFile(it) }
            }
        }

        addSystemBubble(
            "Restored $restored of ${revertible.size} " +
                (if (revertible.size == 1) "edit" else "edits") +
                ". The conversation is unchanged — Claude still has all of it in context, " +
                "so say what you want done differently."
        )
    }

    /**
     * Looks for problems in the changed files once the IDE has had a moment to
     * find them, and offers them back to Claude.
     *
     * Timed rather than event-driven on purpose: analysis runs per file as each
     * opens, so there is no single "finished" moment to wait for, and being a
     * few seconds late costs nothing here. Checked twice — the second pass
     * catches the file that was still being analysed on the first.
     */
    private fun scheduleProblemCheck(edits: List<FileEdit>) {
        val files = edits.mapNotNull {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it.filePath)
        }
        if (files.isEmpty()) return

        var attempt = 0
        val timer = Timer(PROBLEM_CHECK_DELAY_MS) { event ->
            attempt++
            if (disposed || project.isDisposed) {
                (event.source as? Timer)?.stop()
                return@Timer
            }
            val problems = ProjectProblems.collect(project, files)
            if (problems.isNotEmpty()) {
                (event.source as? Timer)?.stop()
                reportProblems(problems)
            } else if (attempt >= PROBLEM_CHECK_ATTEMPTS) {
                (event.source as? Timer)?.stop()
            }
        }
        timer.isRepeats = true
        timer.start()
    }

    /** Shows what the IDE found, with one click to hand it back. */
    private fun reportProblems(problems: List<ProjectProblems.Problem>) {
        val listed = problems.joinToString("\n") { "- `${it.fileName}:${it.line}` — ${it.description}" }
        val message = ChatMessage(
            Role.SYSTEM,
            "⚠️ The IDE reports ${problems.size} " +
                (if (problems.size == 1) "error" else "errors") +
                " in the files Claude changed:\n\n$listed"
        )
        message.problems = problems
        addAndRender(message)
    }

    /**
     * Hands the refused command to a shell, leaving the permission alone.
     *
     * Running it yourself grants nothing, so the question stays open: you may
     * still want to allow it afterwards, or not.
     */
    private fun runInTerminal(message: ChatMessage) {
        val command = message.permissionRequest?.command ?: return
        if (TerminalRunner.run(project, command)) {
            statusLabel.text = "running in terminal"
            return
        }
        // No terminal to hand it to, so hand it over the only other way.
        CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(command))
        addSystemBubble("Couldn't open a terminal, so the command is on your clipboard:\n\n```\n$command\n```")
    }

    /**
     * Shows one tool call the CLI is holding open, waiting on an answer.
     *
     * Called from the endpoint's own thread, which is parked on the answer, so
     * everything here goes to the EDT and nothing here blocks.
     */
    private fun showApproval(request: com.claudecode.chatplugin.permissions.ApprovalRequest) {
        ApplicationManager.getApplication().invokeLater {
            // The turn may have ended between the question being asked and this
            // arriving, in which case the answer would go nowhere.
            if (request.isDecided) return@invokeLater
            statusLabel.text = "waiting on you"

            // Onto the call it is about, so the question, the command and its
            // output stay one thing. The two arrive by different routes — the
            // call on the CLI's stdout, the question over HTTP — so either can
            // be first; when the call has not landed yet the question waits for
            // it by id.
            val id = request.toolUseId
            if (id != null && attachApproval(id, request)) return@invokeLater
            if (id != null) {
                awaitingCall[id] = request
                return@invokeLater
            }

            // No id to match on: a card of its own is all that is left.
            val message = ChatMessage(Role.SYSTEM, "")
            message.approvalRequest = request
            val index = addAndRender(message)
            watchForDecision(request, index)
        }
    }

    /** Puts [request] on the tool call it names, if that call is on screen yet. */
    private fun attachApproval(
        toolUseId: String,
        request: com.claudecode.chatplugin.permissions.ApprovalRequest
    ): Boolean {
        for (index in session.messages.indices.reversed()) {
            val call = session.messages[index].toolCalls.firstOrNull { it.id == toolUseId } ?: continue
            call.approval = request
            watchForDecision(request, index)
            renderNow(index, session.messages[index])
            return true
        }
        return false
    }

    /**
     * Redraws the card when it is answered from anywhere but a click.
     *
     * The turn ending, the chat closing and the backstop timeout all settle a
     * request without going through the panel, and a card left offering **Run**
     * to a CLI that has stopped listening invites a click into nothing.
     */
    private fun watchForDecision(request: com.claudecode.chatplugin.permissions.ApprovalRequest, index: Int) {
        request.onDecided = {
            ApplicationManager.getApplication().invokeLater {
                session.messages.getOrNull(index)?.let { renderNow(index, it) }
            }
        }
    }

    /**
     * Questions that arrived before the call they are about, by `tool_use_id`.
     *
     * Cleared when the call lands. An entry left here is answered by the
     * service's own timeout rather than being leaked: the map holds a reference,
     * not the obligation.
     */
    private val awaitingCall =
        java.util.concurrent.ConcurrentHashMap<String, com.claudecode.chatplugin.permissions.ApprovalRequest>()

    /**
     * Sends back the option the user picked.
     *
     * The tool itself is declined, because running `AskUserQuestion` needs a
     * terminal this chat has not got — but the answer goes with the refusal,
     * which is the whole thing the model was waiting for. From where the user
     * sits, they answered a question.
     */
    private fun answerQuestion(message: ChatMessage, index: Int, callIndex: Int, encoded: Int) {
        val request = message.toolCalls.getOrNull(callIndex)?.approval ?: message.approvalRequest ?: return
        if (request.isDecided) return

        val questions = com.claudecode.chatplugin.permissions.UserQuestion.parseAll(request.input)
        val (question, option) =
            com.claudecode.chatplugin.permissions.UserQuestion.decodeChoice(questions, encoded) ?: return

        request.decide(
            ApprovalDecision.Deny(com.claudecode.chatplugin.permissions.UserQuestion.answer(question, option))
        )
        if (message.approvalRequest === request) message.text = "Answered: ${option.label}"
        renderNow(index, message)
        statusLabel.text = "answered ${option.label}"
    }

    /** Answers the card, which releases the CLI thread parked on it. */
    private fun answerApproval(message: ChatMessage, index: Int, callIndex: Int, action: String) {
        val request = message.toolCalls.getOrNull(callIndex)?.approval
            ?: message.approvalRequest
            ?: return
        if (request.isDecided) return // already answered; the card is a record now

        val decision = when (action) {
            "askrun" -> ApprovalDecision.Allow(request.input)
            "askalways" -> {
                AutoApproval.key(request.toolName, request.input)?.let { session.autoApproved.add(it) }
                ApprovalDecision.Allow(request.input)
            }
            // What the model is told. It says what happened and nothing more:
            // the user is right there and can say what to do instead themselves.
            else -> ApprovalDecision.Deny("The user declined this in the IDE.")
        }
        request.decide(decision)
        // A card of its own has no text but the record, and would come back
        // from a restart as an empty bubble without it. A card living on a tool
        // call must not do this: that message is the assistant's reply, and its
        // text is the reply.
        if (message.approvalRequest === request) message.text = record(request, decision)
        renderNow(index, message)
        statusLabel.text = if (decision is ApprovalDecision.Allow) "allowed" else "skipped"
    }

    /**
     * Opens a new chat carrying on from [index], leaving this one alone.
     *
     * The CLI does the real work: `--resume` with `--fork-session` keeps the
     * history and continues under a new id, so both conversations remember
     * everything up to the branch and neither can write into the other. The
     * transcript here is copied so the new tab reads as a continuation rather
     * than starting blank on top of a context you cannot see.
     */
    private fun branchFrom(index: Int) {
        val manager = project.getService(ClaudeSessionManager::class.java)
        val branch = manager.createSession("${session.displayName} ↳")
        branch.cliSessionId = session.cliSessionId
        branch.forkOnNextTurn = session.cliSessionId != null
        branch.selectedModel = session.selectedModel
        branch.selectedAgent = session.selectedAgent
        branch.selectedEffort = session.selectedEffort
        branch.permissionMode = session.permissionMode
        // Text only, and freshly built: `copy()` would hand both chats the same
        // mutable lists of edits, and reverting a file in one would appear to
        // have happened in the other. This is the same reduction the transcript
        // survives a restart as.
        session.messages.take(index + 1).forEach {
            branch.messages.add(ChatMessage(it.role, it.text, it.thinking))
        }

        // Creating it is enough: the tool window follows the session list and
        // builds the tab itself.
        statusLabel.text = if (branch.forkOnNextTurn) {
            "branched — the original is untouched"
        } else {
            // Nothing to fork from yet: this chat has never run a turn, so the
            // new one simply starts where it would have.
            "branched"
        }
    }

    /** One line saying what was asked and what was answered. */
    private fun record(
        request: com.claudecode.chatplugin.permissions.ApprovalRequest,
        decision: ApprovalDecision
    ): String {
        val what = request.summary.detail ?: request.summary.title
        val verb = if (decision is ApprovalDecision.Allow) "Ran" else "Skipped"
        return "$verb `${what.lineSequence().first().take(160)}`"
    }

    /** Applies the user's answer, and asks again when it was yes. */
    private fun answerPermission(message: ChatMessage, index: Int, answer: PermissionRequest.Answer) {
        val request = message.permissionRequest ?: return
        if (request.answer != null) return // already decided; the links are a record now

        request.answer = answer
        when (answer) {
            PermissionRequest.Answer.ALLOWED_HERE -> session.grantedTools.add(request.pattern)
            PermissionRequest.Answer.ALLOWED_ALWAYS -> {
                session.grantedTools.add(request.pattern)
                settings.allowedTools = listOf(settings.allowedTools, request.pattern)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            }
            PermissionRequest.Answer.DENIED -> Unit
        }
        renderNow(index, message)

        if (answer == PermissionRequest.Answer.DENIED) {
            statusLabel.text = "declined ${request.pattern}"
            return
        }
        if (session.isBusy) {
            addSystemBubble("Allowed `${request.pattern}`. Send your message again once this turn finishes.")
            return
        }
        addSystemBubble("Allowed `${request.pattern}` — asking again.")
        sendPrompt(request.prompt)
    }

    /** Appends [message] to the transcript and renders it, returning its index. */
    private fun addAndRender(message: ChatMessage): Int {
        session.messages.add(message)
        val index = session.messages.lastIndex
        renderTo(index, message)
        return index
    }

    /**
     * The one way anything reaches the view, so a late callback from a turn
     * whose tab has closed can't touch a released browser.
     */
    private fun renderTo(index: Int, message: ChatMessage) {
        if (!disposed) chatView.render(index, message)
    }

    private fun sendCurrentInput() {
        val prompt = inputArea.text.trim()
        if (prompt.isEmpty()) return
        inputArea.text = ""
        submitText(prompt)
    }

    /**
     * Takes what the user typed, now or when there is room for it.
     *
     * Typing during a turn used to be answered with "this session is already
     * waiting on a response" and the text was gone — the thought you had while
     * reading, lost because the reading was still happening. It waits its turn
     * instead.
     *
     * Everything queues, slash commands included. One rule is easier to live
     * with than a list of exceptions, and `/clear` arriving in the middle of
     * the turn it was meant to follow would be its own surprise.
     */
    internal fun submitText(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty()) return

        if (session.isBusy) {
            queued.add(prompt)
            // Shown straight away: it is going to be sent verbatim, and a
            // message that vanishes on Enter is indistinguishable from one that
            // was dropped.
            addAndRender(ChatMessage(Role.USER, prompt))
            updateAnalyticsLabel()
            return
        }
        dispatch(prompt, alreadyShown = false)
    }

    private fun dispatch(prompt: String, alreadyShown: Boolean) {
        if (prompt.startsWith("/") && handleSlashCommand(prompt)) return
        sendPrompt(prompt, alreadyShown)
    }

    /** Sends the next thing waiting, once the turn that was in the way has ended. */
    private fun sendNextQueued() {
        if (disposed || session.isBusy) return
        val next = queued.removeFirstOrNull() ?: return
        dispatch(next, alreadyShown = true)
    }

    /** Messages typed while a turn was running, in the order they were typed. */
    private val queued = ArrayDeque<String>()

    internal val queuedCount: Int get() = queued.size

    private fun queuedSuffix(): String = when (queued.size) {
        0 -> ""
        1 -> "  ·  1 queued"
        else -> "  ·  ${queued.size} queued"
    }

    /**
     * Writes unsaved editor content to disk before the CLI reads it.
     *
     * The CLI opens files from disk. Anything typed and not saved is invisible
     * to it — so asking about a file you have just edited got an answer about
     * the version before your edit, with nothing to suggest that had happened.
     *
     * Only this project's files, and only the ones actually unsaved: saving
     * every open document in the IDE would reach into work that has nothing to
     * do with the question being asked.
     */
    private fun saveProjectDocuments() {
        val documentManager = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
        val index = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)

        val ours = documentManager.unsavedDocuments.filter { document ->
            val file = documentManager.getFile(document) ?: return@filter false
            runCatching { index.isInContent(file) }.getOrDefault(false)
        }
        if (ours.isEmpty()) return

        ApplicationManager.getApplication().runWriteAction {
            ours.forEach { runCatching { documentManager.saveDocument(it) } }
        }
        statusLabel.text = if (ours.size == 1) "saved 1 file" else "saved ${ours.size} files"
    }

    /**
     * Sends [prompt] as a turn. Separate from the input box because a granted
     * permission asks the same question again without anyone retyping it.
     */
    private fun sendPrompt(prompt: String, alreadyShown: Boolean = false) {
        if (prompt.isBlank() || session.isBusy) return

        saveProjectDocuments()

        // A queued message is already in the transcript from when it was typed.
        if (!alreadyShown) addAndRender(ChatMessage(Role.USER, prompt))

        val assistantMessage = ChatMessage(Role.ASSISTANT, "", isStreaming = true)
        val assistantIndex = addAndRender(assistantMessage)

        setBusy(true)

        // Streamed updates are coalesced; terminal states repaint immediately.
        fun repaint() = scheduleRender(assistantIndex, assistantMessage)
        fun repaintNow() = renderNow(assistantIndex, assistantMessage)

        cliService.sendPrompt(session, prompt, object : com.claudecode.chatplugin.cli.StreamListener {
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
                    val call = ToolCall(id, display)
                    // A question that got here first has been waiting for this.
                    call.approval = id?.let { awaitingCall.remove(it) }
                    call.approval?.let { watchForDecision(it, assistantIndex) }
                    assistantMessage.toolCalls.add(call)
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
                    // A limit belongs to the account, not to this chat, so it goes
                    // to the shared service every panel reads from.
                    limitService.update(
                        com.claudecode.chatplugin.limits.RateLimitWindow(
                            type = rateLimit.type,
                            status = rateLimit.status,
                            resetsAtEpochSec = rateLimit.resetsAtEpochSec,
                            isUsingOverage = rateLimit.isUsingOverage,
                            overageStatus = rateLimit.overageStatus
                        )
                    )
                }
            }

            override fun onCapabilities(capabilities: com.claudecode.chatplugin.cli.SessionCapabilities) {
                ApplicationManager.getApplication().invokeLater {
                    session.capabilities = capabilities
                    // The agent list is only known once a turn has reported it,
                    // so the selector fills in rather than starting complete.
                    refreshAgentChoices()
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

            override fun onComplete(result: com.claudecode.chatplugin.cli.TurnResult) {
                ApplicationManager.getApplication().invokeLater {
                    assistantMessage.isStreaming = false
                    // Nothing is left to receive an answer; the service has
                    // already refused whatever was still open.
                    awaitingCall.clear()
                    if (TurnOutcome.needsSignIn(result)) authBanner.isVisible = true
                    TurnOutcome.errorText(result, assistantMessage.text.isBlank())
                        ?.let { assistantMessage.text += it }
                    // The CLI has finished writing to disk; reconstruct each edit's
                    // before/after now so the diff/revert links become live.
                    assistantMessage.edits.forEach { edit ->
                        val after = try {
                            java.io.File(edit.filePath).takeIf { it.isFile }?.readText()
                        } catch (e: Exception) {
                            null
                        }
                        edit.resolve(after)
                        // Worked out once, here, rather than on every repaint of
                        // the conversation.
                        edit.preview = com.claudecode.chatplugin.review.EditPreview.of(edit)
                    }
                    repaintNow()

                    // The IDE analyses the changed files as they open, so what it
                    // knows isn't known yet. Ask again shortly rather than
                    // reporting an empty result the moment the turn ends.
                    if (assistantMessage.edits.isNotEmpty()) scheduleProblemCheck(assistantMessage.edits)

                    // Hand the edits to inline review: this opens the files and
                    // marks each change up for accept/reject in the editor.
                    if (assistantMessage.edits.isNotEmpty()) {
                        val outcome = reviewService.submit(assistantMessage.edits)
                        if (outcome.unreviewable.isNotEmpty()) {
                            addSystemBubble(TurnOutcome.unreviewableMessage(outcome.unreviewable))
                        }
                        if (outcome.conflicted.isNotEmpty()) {
                            addSystemBubble(TurnOutcome.conflictedMessage(outcome.conflicted))
                        }
                    }

                    // Accumulate session analytics.
                    session.turnCount++
                    result.contextTokens?.let { session.contextTokens = it }
                    result.contextWindow?.let { session.contextWindow = it }
                    result.costUsd?.let { session.totalCostUsd += it }
                    result.inputTokens?.let { session.totalInputTokens += it }
                    result.outputTokens?.let { session.totalOutputTokens += it }

                    if (result.permissionDenials.isNotEmpty()) {
                        // The mode that actually applied, not the project setting:
                        // this chat's own choice wins, and naming the wrong one
                        // sends people to change a setting that isn't in effect.
                        val mode = session.permissionMode?.takeIf { it.isNotBlank() }
                            ?: settings.permissionMode.takeIf { it.isNotBlank() }
                        askToAllow(result.permissionDenials, mode, prompt)
                    }

                    setBusy(false)
                    sendNextQueued()
                }
            }

            override fun onError(message: String) {
                ApplicationManager.getApplication().invokeLater {
                    assistantMessage.isStreaming = false
                    assistantMessage.text += "\n\n**Error:** $message"
                    repaintNow()
                    setBusy(false)
                    sendNextQueued()
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
        pendingRender?.let { (index, message) -> renderTo(index, message) }
        pendingRender = null
    }.apply { isRepeats = false }

    private fun scheduleRender(index: Int, message: ChatMessage) {
        if (disposed) return
        pendingRender = index to message
        if (!renderTimer.isRunning) renderTimer.start()
    }

    /** Renders immediately, cancelling any coalesced update (for final/terminal states). */
    private fun renderNow(index: Int, message: ChatMessage) {
        renderTimer.stop()
        pendingRender = null
        renderTo(index, message)
    }

    private companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ChatPanel::class.java)
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

        /** Tools whose refused input is a shell command, so it can be run as one. */
        val COMMAND_TOOLS = setOf("Bash", "PowerShell")

        /** The three answers to a live approval card. */
        val APPROVAL_ACTIONS = setOf("askrun", "askalways", "askskip")


        /** What the CLI accepts for --effort, plus the entry that passes none. */
        /** The entry in each selector that passes no flag at all. */
        const val CLI_DEFAULT = "Default effort"
        const val DEFAULT_AGENT = "Default agent"
        val EFFORT_LEVELS = listOf(CLI_DEFAULT, "low", "medium", "high", "xhigh", "max")

        /** Marks a symbol entry in the `@` popup, where everything else is a path. */
        const val SYMBOL_PREFIX = "◆ "
        const val RENDER_INTERVAL_MS = 50
        const val LIMIT_TICK_MS = 60_000

        /** How long the IDE gets to analyse a changed file before we look. */
        const val PROBLEM_CHECK_DELAY_MS = 2_500
        const val PROBLEM_CHECK_ATTEMPTS = 3
    }

    /**
     * Handles clicks on the diff/revert links (from either view), addressed as
     * `claudebrains:<msgIndex>:<action>:<editIndex>`. May be invoked off the EDT
     * (JCEF query thread), so it hops back on.
     */
    private fun handleEditLink(href: String) {
        ApplicationManager.getApplication().invokeLater {
            val link = ChatLink.parse(href) ?: return@invokeLater
            val message = session.messages.getOrNull(link.messageIndex) ?: return@invokeLater
            val index = link.messageIndex

            when (link.action) {
                "permterminal" -> runInTerminal(message)
                "opensettings" -> cliService.openSettings()

                in APPROVAL_ACTIONS -> answerApproval(message, index, link.itemIndex, link.action)
                "askanswer" -> answerQuestion(message, index, link.itemIndex, link.optionIndex)

                "fixproblems" ->
                    message.problems?.takeIf { it.isNotEmpty() }?.let { sendPrompt(ProjectProblems.describe(it)) }

                "permallow" -> answerPermission(message, index, PermissionRequest.Answer.ALLOWED_HERE)
                "permalways" -> answerPermission(message, index, PermissionRequest.Answer.ALLOWED_ALWAYS)
                "permdeny" -> answerPermission(message, index, PermissionRequest.Answer.DENIED)

                "restorehere" -> restoreFilesToBefore(index)
                "branchhere" -> branchFrom(index)

                "revertall" -> {
                    val targets = message.edits.filter { it.isResolved && it.canRevert }
                    val reverted = targets.count { DiffReviewer.revert(project, it) }
                    statusLabel.text = "reverted $reverted of ${targets.size} file(s)"
                }

                // The rest address one edit, and say nothing without it.
                "diff" -> message.edits.getOrNull(link.itemIndex)?.let { DiffReviewer.showDiff(project, it) }
                "revert" -> message.edits.getOrNull(link.itemIndex)?.let { edit ->
                    val ok = DiffReviewer.revert(project, edit)
                    statusLabel.text = if (ok) "reverted ${edit.fileName}" else "could not revert ${edit.fileName}"
                }
            }
        }
    }
}
