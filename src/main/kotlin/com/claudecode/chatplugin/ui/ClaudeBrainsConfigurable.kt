package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.ClaudeCliService
import com.claudecode.chatplugin.ClaudeCodeSettings
import com.claudecode.chatplugin.model.ModelChoice
import com.claudecode.chatplugin.model.PermissionChoice
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Settings page (Settings > Tools > Claude Brains) backing the persisted
 * [ClaudeCodeSettings]. Registered in plugin.xml as a projectConfigurable.
 */
class ClaudeBrainsConfigurable(private val project: Project) : Configurable {

    private val settings = ClaudeCodeSettings.getInstance(project)

    private val commandField = JBTextField()
    private val modelCombo = JComboBox<ModelChoice>().apply { ModelChoice.ALL.forEach { addItem(it) } }
    private val permissionCombo = JComboBox<PermissionChoice>().apply {
        PermissionChoice.ALL.forEach { addItem(it) }
    }
    private val allowedField = JBTextField()
    private val disallowedField = JBTextField()

    private val mcpArea = JBTextArea(5, 40).apply {
        isEditable = false
        lineWrap = true
        text = "Click Refresh to list configured MCP servers."
    }

    private val refreshMcpButton = JButton("Refresh").apply {
        addActionListener { loadMcpServers() }
    }

    /** Runs `claude mcp list` off the EDT and shows the result. */
    private fun loadMcpServers() {
        mcpArea.text = "Loading…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val output = ClaudeCliService.getInstance(project).listMcpServers()
            ApplicationManager.getApplication().invokeLater { mcpArea.text = output }
        }
    }

    override fun getDisplayName(): String = "Claude Brains"

    override fun createComponent(): JComponent {
        reset()
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Claude CLI command or path:", commandField, 1, false)
            .addLabeledComponent("Default model:", modelCombo, 1, false)
            .addLabeledComponent("Permission mode:", permissionCombo, 1, false)
            .addLabeledComponent("Allowed tools (space/comma):", allowedField, 1, false)
            .addLabeledComponent("Disallowed tools (space/comma):", disallowedField, 1, false)
            .addComponent(JLabel(
                "<html><small>In the tool lists you can just write a command — <code>git</code> allows " +
                    "<code>git add -A</code> through whichever shell runs it. A capitalised name means the " +
                    "whole tool (<code>Edit</code>), and the CLI's own form (<code>Bash(git *)</code>) is " +
                    "passed through as written.</small></html>"
            ))
            .addComponent(JLabel(
                "<html><small>Defaults for new chats — each chat can override both from its own toolbar.<br>" +
                    "<b>Accept edits</b> (the default) applies file edits and then marks each one up in the " +
                    "editor for you to accept or reject; <b>Plan</b> is read-only; <b>Bypass permissions</b> " +
                    "runs everything unprompted. <b>CLI default</b> passes no flag at all — but note this " +
                    "chat has no terminal, so anything your CLI would prompt about is refused rather than " +
                    "asked. The remaining modes are passed through as-is.</small></html>"
            ))
            .addSeparator()
            .addLabeledComponent("MCP servers:", refreshMcpButton, 1, false)
            .addComponent(JBScrollPane(mcpArea))
            .addComponent(JLabel(
                "<html><small>Read-only view of <code>claude mcp list</code>. Add or remove servers " +
                    "with <code>claude mcp add</code> / <code>claude mcp remove</code> — this plugin " +
                    "never rewrites your MCP configuration. To keep a server's tools out of a chat, " +
                    "put its tool names in the disallowed list above.</small></html>"
            ))
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .also { HandCursors.applyTo(it) }
    }

    override fun isModified(): Boolean =
        commandField.text != settings.claudeCommand ||
            (modelCombo.selectedItem as ModelChoice).id.orEmpty() != settings.defaultModel ||
            (permissionCombo.selectedItem as PermissionChoice).id.orEmpty() != settings.permissionMode ||
            allowedField.text != settings.allowedTools ||
            disallowedField.text != settings.disallowedTools

    override fun apply() {
        settings.claudeCommand = commandField.text.trim().ifBlank { "claude" }
        settings.defaultModel = (modelCombo.selectedItem as ModelChoice).id.orEmpty()
        settings.permissionMode = (permissionCombo.selectedItem as PermissionChoice).id.orEmpty()
        settings.allowedTools = allowedField.text.trim()
        settings.disallowedTools = disallowedField.text.trim()
    }

    override fun reset() {
        commandField.text = settings.claudeCommand
        modelCombo.selectedItem = ModelChoice.forId(settings.defaultModel)
        permissionCombo.selectedItem = PermissionChoice.forId(settings.permissionMode)
        allowedField.text = settings.allowedTools
        disallowedField.text = settings.disallowedTools
    }
}
