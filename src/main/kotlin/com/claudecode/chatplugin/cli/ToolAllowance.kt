package com.claudecode.chatplugin.cli

/**
 * Turns what a person would write into what the CLI accepts.
 *
 * The CLI wants `Bash(git *)`. What anybody actually wants to say is `git` —
 * and on Windows the same command arrives as PowerShell half the time, so the
 * honest answer to "let git through" is two patterns, neither of which is
 * obvious until you have been refused twice and read the syntax.
 *
 * So a bare word is expanded here, and anything already written in the CLI's
 * own terms is passed through untouched.
 */
object ToolAllowance {

    /**
     * Tools that run a command, and therefore the ones a bare program name has
     * to be allowed through.
     */
    private val COMMAND_TOOLS = listOf("Bash", "PowerShell")

    /**
     * Tool names as the CLI spells them. Not exhaustive on purpose — anything
     * capitalised is treated as a tool name too, since that is how they are
     * written and new ones keep appearing.
     */
    private val KNOWN_TOOLS = setOf(
        "Bash", "PowerShell", "Edit", "MultiEdit", "Write", "Read", "Glob", "Grep",
        "WebFetch", "WebSearch", "Task", "TodoWrite", "NotebookEdit"
    )

    /**
     * Expands [raw] into a space-separated list the CLI understands.
     *
     * Entries are separated by spaces or commas, as the CLI's own help
     * describes — except inside brackets, where a pattern may contain both.
     */
    fun expand(raw: String): String {
        if (raw.isBlank()) return ""
        return split(raw)
            .flatMap { entry -> expandOne(entry) }
            .distinct()
            .joinToString(" ")
    }

    private fun expandOne(entry: String): List<String> = when {
        // Already in the CLI's terms: `Bash(git *)`.
        entry.contains('(') -> listOf(entry)
        // A whole tool, allowed outright.
        entry in KNOWN_TOOLS || entry.first().isUpperCase() -> listOf(entry)
        // A program: allow it through whichever shell tool runs it.
        else -> COMMAND_TOOLS.map { "$it($entry *)" }
    }

    /**
     * Splits on spaces and commas, but not inside brackets — `Bash(git add, -A)`
     * is one entry however it is punctuated.
     */
    internal fun split(raw: String): List<String> {
        val entries = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (ch in raw) {
            when {
                ch == '(' -> { depth++; current.append(ch) }
                ch == ')' -> { depth--; current.append(ch) }
                (ch.isWhitespace() || ch == ',') && depth == 0 -> {
                    if (current.isNotEmpty()) { entries.add(current.toString()); current.clear() }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) entries.add(current.toString())
        return entries
    }
}
