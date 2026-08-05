package com.claudecode.chatplugin

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@State(name = "ClaudeBrainsSettings", storages = [Storage("claude-brains.xml")])
@Service(Service.Level.PROJECT)
class ClaudeCodeSettings : PersistentStateComponent<ClaudeCodeSettings.State> {

    data class State(
        var claudeCommand: String = "claude",
        var defaultModel: String = "",      // empty = let the CLI pick its default
        var permissionMode: String = "default", // default | acceptEdits | plan | bypassPermissions
        var allowedTools: String = "",      // space/comma list passed to --allowedTools, empty = don't pass
        var disallowedTools: String = ""    // passed to --disallowedTools, empty = don't pass
    )

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    var claudeCommand: String
        get() = state.claudeCommand
        set(value) { state.claudeCommand = value }

    var defaultModel: String
        get() = state.defaultModel
        set(value) { state.defaultModel = value }

    var permissionMode: String
        get() = state.permissionMode
        set(value) { state.permissionMode = value }

    var allowedTools: String
        get() = state.allowedTools
        set(value) { state.allowedTools = value }

    var disallowedTools: String
        get() = state.disallowedTools
        set(value) { state.disallowedTools = value }

    companion object {
        val PERMISSION_MODES = listOf("default", "acceptEdits", "plan", "bypassPermissions")

        fun getInstance(project: Project): ClaudeCodeSettings =
            project.getService(ClaudeCodeSettings::class.java)
    }
}
