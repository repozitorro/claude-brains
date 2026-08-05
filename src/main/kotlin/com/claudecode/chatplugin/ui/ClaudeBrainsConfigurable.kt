package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.ClaudeCliService
import com.claudecode.chatplugin.ClaudeCodeSettings
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
    private val modelCombo = JComboBox(arrayOf("(default)", "opus", "sonnet", "haiku"))
    private val permissionCombo = JComboBox(ClaudeCodeSettings.PERMISSION_MODES.toTypedArray())
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
                "<html><small>Permission modes: <b>default</b> asks per action; " +
                    "<b>acceptEdits</b> auto-accepts file edits; <b>plan</b> is read-only planning; " +
                    "<b>bypassPermissions</b> runs everything without prompts (use with care).</small></html>"
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
    }

    private fun modelToCombo(v: String) = v.ifBlank { "(default)" }
    private fun comboToModel(v: String) = if (v == "(default)") "" else v

    override fun isModified(): Boolean =
        commandField.text != settings.claudeCommand ||
            comboToModel(modelCombo.selectedItem as String) != settings.defaultModel ||
            (permissionCombo.selectedItem as String) != settings.permissionMode ||
            allowedField.text != settings.allowedTools ||
            disallowedField.text != settings.disallowedTools

    override fun apply() {
        settings.claudeCommand = commandField.text.trim().ifBlank { "claude" }
        settings.defaultModel = comboToModel(modelCombo.selectedItem as String)
        settings.permissionMode = permissionCombo.selectedItem as String
        settings.allowedTools = allowedField.text.trim()
        settings.disallowedTools = disallowedField.text.trim()
    }

    override fun reset() {
        commandField.text = settings.claudeCommand
        modelCombo.selectedItem = modelToCombo(settings.defaultModel)
        permissionCombo.selectedItem = settings.permissionMode
        allowedField.text = settings.allowedTools
        disallowedField.text = settings.disallowedTools
    }
}
