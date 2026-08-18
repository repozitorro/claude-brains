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
        var disallowedTools: String = "",   // passed to --disallowedTools, empty = don't pass

        /**
         * Directories added to the front of PATH for the CLI.
         *
         * For tools installed for the current user only — pip's `--user`, npm's
         * prefix — whose executables land somewhere nothing ever added to PATH.
         * Fixing that on the machine works too, but means editing system
         * settings and restarting the IDE to pick them up; this takes effect on
         * the next turn.
         */
        var extraPath: String = "",

        /** `KEY=VALUE` per line, for the CLI's environment. */
        var extraEnv: String = "",

        /**
         * Whether the CLI stops and asks before doing something it would
         * otherwise have to refuse.
         *
         * On by default, because a question you can answer beats a refusal you
         * have to work around. It rests on `--permission-prompt-tool`, which is
         * absent from `--help` and could go the same way, so it can be switched
         * off — and then the plugin behaves exactly as it did before: the CLI
         * decides alone and reports what it refused.
         */
        var askBeforeActing: Boolean = true,

        /**
         * A ceiling on what one turn may spend, in dollars. Blank = none.
         *
         * Empty by default on purpose: a cap that stops a turn half-way has its
         * own cost, and nobody should discover one they did not set.
         */
        var maxBudgetUsd: String = "",

        /**
         * Directories outside the project the CLI may read and write, one per
         * line — the repository next door that the work genuinely spans.
         */
        var extraDirs: String = "",

        /** Default effort for new chats: low…max. Blank = the CLI's own. */
        var defaultEffort: String = ""
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

    var extraPath: String
        get() = state.extraPath
        set(value) { state.extraPath = value }

    var extraEnv: String
        get() = state.extraEnv
        set(value) { state.extraEnv = value }

    var askBeforeActing: Boolean
        get() = state.askBeforeActing
        set(value) { state.askBeforeActing = value }

    var maxBudgetUsd: String
        get() = state.maxBudgetUsd
        set(value) { state.maxBudgetUsd = value }

    var extraDirs: String
        get() = state.extraDirs
        set(value) { state.extraDirs = value }

    /** One directory per line, blanks dropped. */
    fun extraDirList(): List<String> =
        state.extraDirs.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    var defaultEffort: String
        get() = state.defaultEffort
        set(value) { state.defaultEffort = value }

    companion object {
        fun getInstance(project: Project): ClaudeCodeSettings =
            project.getService(ClaudeCodeSettings::class.java)
    }
}
