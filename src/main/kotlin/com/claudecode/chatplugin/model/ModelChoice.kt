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
 * 2.1.205 (`claude --help`).
 *
 * [id] is the **empty string** for "CLI default", not null: a chat stores this
 * id as its own choice, and null there already means "hasn't chosen, use the
 * project's". Those are different answers now that the project default is a
 * real mode — without the distinction, picking "CLI default" for one chat would
 * silently fall through to the project setting instead.
 */
data class PermissionChoice(val label: String, val id: String?, val hint: String) {

    override fun toString(): String = label

    companion object {
        /**
         * Passes no flag at all, leaving the CLI on its own configured default.
         *
         * Not called "Ask", because in this chat it cannot: answering a
         * permission prompt needs a terminal, and there isn't one — the CLI
         * refuses the tool and says so instead. Kept because it is the honest
         * way to say "whatever my CLI is configured to do", which for someone
         * with their own settings may be exactly right.
         */
        val INHERIT = PermissionChoice(
            "CLI default", "",
            "Whatever your CLI is configured to do. Anything that would need a prompt is " +
                "refused here rather than asked, since this chat has no terminal to answer in."
        )

        val ACCEPT_EDITS = PermissionChoice(
            "Accept edits", "acceptEdits",
            "File edits are applied, then marked up in the editor for you to accept or reject. " +
                "Commands still follow your CLI's own rules."
        )

        // Accept edits leads: it is the default, and the one that matches how
        // review works here — decide after the edit, in the file.
        val ALL: List<PermissionChoice> = listOf(
            ACCEPT_EDITS,
            INHERIT,
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
