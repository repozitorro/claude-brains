package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.cli.PermissionDenial

/**
 * What to say when the CLI refused to run something.
 *
 * The first version of this told everyone to "pick Accept edits" — including
 * the people already on it, watching Claude fail to run `git add` three times
 * in a row. Accept edits covers *file edits*; a command is a different
 * permission, and no amount of re-reading the message would have said so.
 *
 * So the advice is worked out from the mode actually in force and from what was
 * actually blocked, and it names a setting that would let exactly that through
 * rather than a mode that would not.
 */
object BlockedToolsMessage {

    /** How many distinct calls to quote before saying "and N more". */
    private const val MAX_SHOWN = 3

    fun format(denials: List<PermissionDenial>, effectiveMode: String?): String {
        if (denials.isEmpty()) return ""

        return buildString {
            append("⚠️ **Blocked").append(modeSuffix(effectiveMode)).append(":** ")
            append(summarise(denials))
            append("\n\n")
            append(explain(denials, effectiveMode))
            append("\n\n")
            append(remedy(denials, effectiveMode))
        }
    }

    private fun modeSuffix(mode: String?): String =
        if (mode.isNullOrBlank()) "" else " (permission mode: `$mode`)"

    /** `Bash × 3 — git add …`, one line per tool rather than the same name repeated. */
    private fun summarise(denials: List<PermissionDenial>): String =
        denials.groupBy { it.toolName }.entries.joinToString("; ") { (tool, calls) ->
            val count = if (calls.size > 1) " × ${calls.size}" else ""
            val details = calls.mapNotNull { it.detail }.distinct()
            val shown = details.take(MAX_SHOWN).joinToString(", ") { "`$it`" }
            val more = (details.size - MAX_SHOWN).takeIf { it > 0 }?.let { " and $it more" }.orEmpty()
            if (shown.isEmpty()) "$tool$count" else "$tool$count — $shown$more"
        }

    private fun explain(denials: List<PermissionDenial>, mode: String?): String = when {
        mode == "acceptEdits" && denials.any { !it.toolName.isFileEdit() } ->
            "**Accept edits** allows file edits only — commands and other tools still need permission, " +
                "and this chat has no terminal to answer a prompt in, so they are refused instead of asked."

        mode == "plan" ->
            "**Plan** is read-only by design: Claude works out what it would do without doing any of it."

        else ->
            "This chat has no terminal to answer a permission prompt in, so anything needing one is " +
                "refused rather than asked."
    }

    private fun remedy(denials: List<PermissionDenial>, mode: String?): String {
        if (mode == "plan") {
            return "Switch this chat out of Plan when you want it carried out."
        }

        val lines = mutableListOf<String>()
        suggestedPattern(denials)?.let {
            lines += "- Allow just this: put `$it` in **Settings → Tools → Claude Brains → Allowed tools**."
        }
        if (mode != "acceptEdits" && denials.any { it.toolName.isFileEdit() }) {
            lines += "- For file edits, switch this chat to **Accept edits** in the dropdown above."
        }
        lines += "- Or run it yourself in a terminal — the conversation carries on either way."
        lines += "- **Bypass permissions** lifts every restriction for this chat, which is worth " +
            "understanding before you pick it."
        return lines.joinToString("\n")
    }

    /**
     * A pattern narrow enough to be worth pasting.
     *
     * `Bash(git *)` beats `Bash`: it unblocks what was refused without handing
     * over every command there is. Derived from the first word of the blocked
     * command, which is the program being run.
     */
    internal fun suggestedPattern(denials: List<PermissionDenial>): String? {
        val bash = denials.filter { it.toolName == "Bash" }
        if (bash.isNotEmpty()) {
            val programs = bash.mapNotNull { it.detail?.trim()?.substringBefore(' ')?.ifBlank { null } }
                .distinct()
            if (programs.size == 1) return "Bash(${programs.single()} *)"
            if (programs.isNotEmpty()) return programs.joinToString(" ") { "Bash($it *)" }
        }
        val tools = denials.map { it.toolName }.distinct()
        return tools.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun String.isFileEdit(): Boolean = this in setOf("Edit", "MultiEdit", "Write", "NotebookEdit")
}
