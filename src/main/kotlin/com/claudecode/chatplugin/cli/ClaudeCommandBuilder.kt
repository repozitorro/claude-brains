package com.claudecode.chatplugin.cli

/**
 * Everything that decides what a turn's command line looks like.
 *
 * Both permission modes are carried rather than a single resolved one, because
 * choosing between them is part of what [ClaudeCommandBuilder] is responsible
 * for — and the rule ("the chat's own choice wins, else the project's, else
 * pass no flag at all") is exactly the kind of thing that should be pinned by a
 * test rather than read out of a builder expression.
 */
data class TurnRequest(
    val claudeCommand: String,
    val prompt: String,
    /** This chat's override. Null means "no opinion". */
    val sessionPermissionMode: String? = null,
    /** The project setting. Blank means "no opinion". */
    val projectPermissionMode: String = "",
    val allowedTools: String = "",
    val disallowedTools: String = "",
    /** Model id or family alias passed verbatim; null leaves the CLI on its default. */
    val model: String? = null,
    /** The CLI session to resume; null starts a fresh context. */
    val resumeId: String? = null
)

/**
 * Builds the argument list for one streamed turn.
 *
 * Split out of the service so the flag rules can be tested without spawning
 * anything: which flags appear, which are omitted entirely (an omitted flag and
 * an empty one mean different things to the CLI — omitted leaves it on its own
 * configured default), and how the two permission modes resolve.
 */
object ClaudeCommandBuilder {

    fun build(request: TurnRequest): List<String> = buildList {
        add(request.claudeCommand)
        add("-p")
        add(request.prompt)
        add("--output-format")
        add("stream-json")
        add("--verbose")                 // required alongside stream-json
        add("--include-partial-messages") // enables token-by-token text/thinking deltas

        permissionMode(request)?.let {
            add("--permission-mode")
            add(it)
        }
        request.allowedTools.trim().takeIf { it.isNotEmpty() }?.let {
            add("--allowedTools")
            add(it)
        }
        request.disallowedTools.trim().takeIf { it.isNotEmpty() }?.let {
            add("--disallowedTools")
            add(it)
        }
        request.resumeId?.let {
            add("--resume")
            add(it)
        }
        request.model?.let {
            add("--model")
            add(it)
        }
    }

    /**
     * Per-chat mode wins; otherwise the project setting; otherwise nothing,
     * which leaves the CLI on its own configured default.
     */
    fun permissionMode(request: TurnRequest): String? =
        (request.sessionPermissionMode ?: request.projectPermissionMode).takeIf { it.isNotBlank() }
}
