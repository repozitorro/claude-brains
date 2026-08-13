package com.claudecode.chatplugin

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@State(name = "ClaudeBrainsSettings", storages = [Storage("claude-brains.xml")])
@Service(Service.Level.PROJECT)
class ClaudeCodeSettings : PersistentStateComponent<ClaudeCodeSettings.State> {

    data class State(
        var claudeCommand: String = "claude",
        var defaultModel: String = "",      // empty = let the CLI pick its default
        /**
         * Empty would pass no `--permission-mode` flag and leave the CLI on its
         * own default — which is to ask. This chat has no terminal to answer in,
         * so the CLI cannot ask: it refuses instead, and the turn comes back
         * saying it needed permission to write the file (verified against CLI
         * 2.1.223). Out of the box that made the plugin unable to edit anything
         * until the mode was found and changed by hand.
         *
         * It also contradicted how the plugin is built: inline review, revert
         * and Accept/Reject all assume the edits are already on disk and you are
         * deciding about them afterwards. `acceptEdits` is that model — and it
         * covers file edits only, not commands.
         */
        var permissionMode: String = "acceptEdits",
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
        fun getInstance(project: Project): ClaudeCodeSettings =
            project.getService(ClaudeCodeSettings::class.java)
    }
}
