package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.auth.AuthStatus
import com.claudecode.chatplugin.auth.ClaudeAuth
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Shown in place of the chat when the CLI isn't signed in (or isn't runnable).
 *
 * The plugin never collects credentials: "Sign in" opens a terminal running the
 * CLI's own `auth login`, the user completes the flow there, and this panel
 * simply re-checks afterwards. A copyable command is always offered as the
 * fallback, so the screen is still useful if no terminal can be launched.
 */
class SignInPanel(
    private val project: Project,
    private var status: AuthStatus,
    private val onSignedIn: () -> Unit
) : JPanel(BorderLayout()) {

    private val auth = ClaudeAuth.getInstance(project)

    private val headline = JBLabel().apply { font = JBFont.label().asBold().biggerOn(3f) }
    private val explanation = JBLabel().apply { foreground = JBUI.CurrentTheme.Label.disabledForeground() }
    private val hint = JBLabel().apply {
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        font = JBFont.small()
    }

    private val signInButton = JButton("Sign in", AllIcons.General.User).apply {
        addActionListener { signIn() }
    }
    private val recheckButton = JButton("I've signed in — check again", AllIcons.Actions.Refresh).apply {
        addActionListener { recheck() }
    }
    private val copyButton = JButton("Copy command", AllIcons.Actions.Copy).apply {
        addActionListener {
            CopyPasteManager.getInstance().setContents(StringSelection(auth.signInCommand()))
            hint.text = "Copied — run it in any terminal, then choose “check again”."
        }
    }

    init {
        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(28, 24)
            isOpaque = false

            add(headline.alignLeft())
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(explanation.alignLeft())
            add(Box.createVerticalStrut(JBUI.scale(18)))
            add(buttonRow())
            add(Box.createVerticalStrut(JBUI.scale(12)))
            add(hint.alignLeft())
        }
        add(column, BorderLayout.NORTH)
        applyStatus(status)
    }

    private fun buttonRow() = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        add(signInButton)
        add(recheckButton)
        add(copyButton)
    }

    private fun JBLabel.alignLeft(): JBLabel = apply { alignmentX = Component.LEFT_ALIGNMENT }

    private fun applyStatus(status: AuthStatus) {
        this.status = status
        when (status) {
            is AuthStatus.SignedOut -> {
                headline.text = "Sign in to Claude Code"
                explanation.text = "<html>This panel talks to the <code>claude</code> CLI and reuses its login, " +
                    "so there's no API key to paste. Signing in opens your terminal and runs the CLI's own " +
                    "sign-in — your credentials never pass through this plugin.</html>"
                hint.text = "Signing in also unlocks your existing subscription's limits."
                signInButton.isVisible = true
                copyButton.isVisible = true
            }
            is AuthStatus.Unavailable -> {
                headline.text = "Claude Code CLI not found"
                explanation.text = "<html>${status.reason}<br><br>Install it with " +
                    "<code>npm install -g @anthropic-ai/claude-code</code>, or point Claude Brains at an " +
                    "existing install in Settings → Tools → Claude Brains.</html>"
                hint.text = " "
                // Signing in can't work until the CLI itself runs.
                signInButton.isVisible = false
                copyButton.isVisible = false
            }
            is AuthStatus.SignedIn -> {
                headline.text = "Signed in"
                explanation.text = "<html>${status.email ?: "Your account"} — ${status.plan ?: "active"}</html>"
                hint.text = " "
                signInButton.isVisible = false
                copyButton.isVisible = false
            }
        }
    }

    private fun signIn() {
        val launched = auth.launchSignIn()
        hint.text = if (launched) {
            "A terminal is running “${auth.signInCommand()}”. Finish there, then choose “check again”."
        } else {
            "Couldn't open a terminal. Copy the command and run it yourself, then choose “check again”."
        }
    }

    private fun recheck() {
        recheckButton.isEnabled = false
        hint.text = "Checking…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val fresh = auth.status()
            ApplicationManager.getApplication().invokeLater {
                recheckButton.isEnabled = true
                if (fresh is AuthStatus.SignedIn) {
                    applyStatus(fresh)
                    onSignedIn()
                } else {
                    applyStatus(fresh)
                    hint.text = "Still signed out — finish the sign-in in the terminal, then check again."
                }
            }
        }
    }
}
