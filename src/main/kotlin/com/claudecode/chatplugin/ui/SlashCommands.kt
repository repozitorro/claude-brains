package com.claudecode.chatplugin.ui

/**
 * Known Claude Code CLI slash commands, used to power the "/" autocomplete
 * popup in the prompt input. This list can drift from what your installed
 * CLI version actually supports - run `claude` and type `/` to check the
 * live list, then update this if needed. Grouped with short descriptions so
 * the popup is self-explanatory.
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

    fun matching(prefix: String): List<Command> =
        if (!prefix.startsWith("/")) emptyList()
        else ALL.filter { it.name.startsWith(prefix, ignoreCase = true) }
}
