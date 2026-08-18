package com.claudecode.chatplugin.ui

/**
 * What the "/" popup offers.
 *
 * The list below is a fallback, and used to be the whole story — with a comment
 * admitting it would drift from whatever CLI was installed. It had drifted: it
 * knew nothing of the eighteen skills on one user's machine, or of any command
 * they had written themselves.
 *
 * The CLI names all of them in its `init` event, so once a turn has run, that
 * is what the popup shows. The fallback covers the first turn of a fresh chat,
 * when nothing has been reported yet.
 */
object SlashCommands {
    data class Command(val name: String, val description: String)

    val ALL: List<Command> = listOf(
        Command("/help", "Show available commands"),
        Command("/clear", "Clear the current conversation"),
        Command("/compact", "Summarize and compact conversation history"),
        Command("/cost", "Show token usage / cost for this session"),
        Command("/doctor", "Diagnose the Claude Code installation"),
        Command("/ide", "Connect to the running IDE"),
        Command("/init", "Generate a CLAUDE.md for this project"),
        Command("/login", "Sign in to your Claude account"),
        Command("/logout", "Sign out of your Claude account"),
        Command("/mcp", "List/manage configured MCP servers"),
        Command("/memory", "Edit CLAUDE.md project memory"),
        Command("/model", "Switch the active model"),
        Command("/permissions", "View or change permission settings"),
        Command("/resume", "Resume a previous session"),
        Command("/review", "Review a diff or PR"),
        Command("/status", "Show session/connection status"),
        Command("/vim", "Toggle vim key bindings for the prompt")
    )

    fun matching(prefix: String): List<Command> = matching(prefix, null)

    /**
     * Matches against what [capabilities] reported, falling back to [ALL] until
     * a turn has told us otherwise.
     *
     * A live name keeps the built-in description when there is one — the CLI
     * sends names only, and "compact" alone says less than the sentence here.
     */
    fun matching(
        prefix: String,
        capabilities: com.claudecode.chatplugin.cli.SessionCapabilities?
    ): List<Command> {
        if (!prefix.startsWith("/")) return emptyList()
        val known = ALL.associateBy { it.name.removePrefix("/").lowercase() }
        val live = capabilities?.allSlashNames().orEmpty()
        val pool = if (live.isEmpty()) ALL else live.map { name ->
            known[name.lowercase()] ?: Command("/$name", "")
        }
        return pool.filter { it.name.startsWith(prefix, ignoreCase = true) }
    }
}
