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
    /**
     * This chat's own choice.
     *
     * Null means it has never made one, so the project's applies. Blank is a
     * choice — "run on the CLI's own rules" — and stops the fallback, which is
     * why the two are not the same value.
     */
    val sessionPermissionMode: String? = null,
    /** The project setting. Blank means pass no flag. */
    val projectPermissionMode: String = "",
    val allowedTools: String = "",
    val disallowedTools: String = "",
    /** Model id or family alias passed verbatim; null leaves the CLI on its default. */
    val model: String? = null,
    /** The CLI session to resume; null starts a fresh context. */
    val resumeId: String? = null,
    /**
     * Path to the MCP config naming the endpoint that answers permission
     * questions for this chat.
     *
     * A file rather than the JSON itself, which `--mcp-config` also accepts:
     * passed inline on Windows it arrives at the CLI with its quotes eaten and
     * its slashes reversed, and the CLI reads the wreckage as a filename.
     * Null means don't ask — the mode makes the question moot, or no endpoint
     * could be opened — and the CLI goes back to deciding alone.
     */
    val approvalConfigPath: String? = null
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

    /**
     * The prompt is **not** here — it goes to the process on standard input.
     *
     * As an argument it was subject to the command line's own limits, which on
     * Windows means roughly 32k characters for everything put together: a
     * pasted log or a few file references could push a turn over it and the
     * launch would fail or be truncated, with nothing to show why. `claude -p`
     * with no argument reads the prompt from stdin instead (verified against
     * CLI 2.1.223), which has no such ceiling and needs no quoting.
     */
    fun build(request: TurnRequest): List<String> = buildList {
        add(request.claudeCommand)
        add("-p")
        add("--output-format")
        add("stream-json")
        add("--verbose")                 // required alongside stream-json
        add("--include-partial-messages") // enables token-by-token text/thinking deltas

        permissionMode(request)?.let {
            add("--permission-mode")
            add(it)
        }
        // Expanded here rather than stored expanded: the setting keeps what the
        // user wrote, and `git` becomes the CLI's spelling on the way out.
        ToolAllowance.expand(request.allowedTools).takeIf { it.isNotEmpty() }?.let {
            add("--allowedTools")
            add(it)
        }
        ToolAllowance.expand(request.disallowedTools).takeIf { it.isNotEmpty() }?.let {
            add("--disallowedTools")
            add(it)
        }
        // Turns "refused, here's what I refused" into "may I?". The flag is
        // absent from `--help` but present and honoured (2.1.232): the CLI
        // calls this MCP tool for anything it would otherwise have to ask
        // about, and waits for the answer.
        request.approvalConfigPath?.takeIf { asksBeforeActing(permissionMode(request)) }?.let {
            add("--mcp-config")
            add(it)
            add("--permission-prompt-tool")
            add(com.claudecode.chatplugin.permissions.McpApprovalProtocol.QUALIFIED_TOOL_NAME)
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
        permissionMode(request.sessionPermissionMode, request.projectPermissionMode)

    /** The same rule, for callers deciding something before they have a request. */
    fun permissionMode(sessionMode: String?, projectMode: String): String? =
        (sessionMode ?: projectMode).takeIf { it.isNotBlank() }

    /**
     * Whether a mode should stop and ask.
     *
     * Some modes exist precisely so that nobody is asked anything, and handing
     * one of those a prompt tool contradicts the choice the user just made:
     * picking **Auto** and then being stopped mid-turn is the mode failing to
     * do the one thing it is for.
     *
     * The rest ask. What they refuse and what they wave through still differs
     * between them — that part is the CLI's own permission engine, and none of
     * it is ours to decide; this only settles whether there is anyone to ask.
     */
    fun asksBeforeActing(mode: String?): Boolean = mode !in DECIDES_FOR_ITSELF

    /**
     * `auto` runs the CLI's classifier, `dontAsk` says so in its name, and
     * `bypassPermissions` has nothing left to ask about.
     */
    private val DECIDES_FOR_ITSELF = setOf("auto", "dontAsk", "bypassPermissions")
}
