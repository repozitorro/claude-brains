package com.claudecode.chatplugin.model

/**
 * What the model dropdown offers.
 *
 * `claude --model` takes either an alias for the newest model of a family
 * ("opus", "sonnet") or a pinned full name ("claude-opus-5"). Both are useful:
 * an alias follows upgrades, a pinned id keeps a conversation on one model.
 * [id] is passed to the CLI verbatim; a null id means "don't pass --model" and
 * let the CLI decide.
 */
data class ModelChoice(val label: String, val id: String?) {

    override fun toString(): String = label

    companion object {
        val DEFAULT = ModelChoice("Default", null)

        val ALL: List<ModelChoice> = listOf(
            DEFAULT,
            ModelChoice("Opus 5", "claude-opus-5"),
            ModelChoice("Opus 4.8", "claude-opus-4-8"),
            ModelChoice("Sonnet 5", "claude-sonnet-5"),
            ModelChoice("Sonnet 4.6", "claude-sonnet-4-6"),
            ModelChoice("Haiku 4.5", "claude-haiku-4-5-20251001"),
            ModelChoice("Fable 5", "claude-fable-5"),
            // Aliases track the newest model of a family as it changes.
            ModelChoice("Opus (latest)", "opus"),
            ModelChoice("Sonnet (latest)", "sonnet"),
            ModelChoice("Haiku (latest)", "haiku")
        )

        /**
         * The entry for [id], or a synthetic one so a model saved by an older
         * build (or typed into settings by hand) still shows its real name
         * instead of silently reading as "Default".
         */
        fun forId(id: String?): ModelChoice =
            if (id.isNullOrBlank()) DEFAULT
            else ALL.firstOrNull { it.id == id } ?: ModelChoice(id, id)
    }
}

/**
 * Permission modes accepted by `claude --permission-mode`, verified against CLI
 * 2.1.205 (`claude --help`). [id] is null for "inherit", which passes no flag so
 * the CLI applies its own configured default.
 */
data class PermissionChoice(val label: String, val id: String?, val hint: String) {

    override fun toString(): String = label

    companion object {
        val INHERIT = PermissionChoice("Ask (default)", null, "Claude asks before acting, per the CLI's own settings")

        val ALL: List<PermissionChoice> = listOf(
            INHERIT,
            PermissionChoice("Accept edits", "acceptEdits", "File edits are applied without asking"),
            PermissionChoice("Plan", "plan", "Read-only: Claude plans but changes nothing"),
            // Modes the CLI accepts whose exact behaviour isn't documented in
            // `claude --help`; offered as-is rather than described inaccurately.
            PermissionChoice("Auto", "auto", ""),
            PermissionChoice("Manual", "manual", ""),
            PermissionChoice("Don't ask", "dontAsk", ""),
            PermissionChoice("Bypass permissions", "bypassPermissions", "Everything runs unprompted — use with care")
        )

        fun forId(id: String?): PermissionChoice =
            if (id.isNullOrBlank()) INHERIT
            else ALL.firstOrNull { it.id == id } ?: INHERIT
    }
}
