package com.claudecode.chatplugin

import com.claudecode.chatplugin.cli.CliRunner
import com.claudecode.chatplugin.cli.ClaudeCommandBuilder
import com.claudecode.chatplugin.cli.StreamListener
import com.claudecode.chatplugin.cli.StreamParser
import com.claudecode.chatplugin.cli.TurnRequest
import com.claudecode.chatplugin.cli.TurnResult
import com.claudecode.chatplugin.model.ClaudeSession
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

/**
 * Runs the `claude` CLI for a chat turn and feeds its output to the UI.
 *
 * This class owns the *process*: starting it, streaming it, cancelling it, and
 * the one recovery it can make on its own (a `--resume` id the CLI has since
 * forgotten). What the command line looks like belongs to
 * [ClaudeCommandBuilder], and what the output means belongs to [StreamParser] —
 * both are plain classes so they can be tested without spawning anything.
 */
@Service(Service.Level.PROJECT)
class ClaudeCliService(private val project: Project) : com.intellij.openapi.Disposable {

    private val log = Logger.getInstance(ClaudeCliService::class.java)
    private val executor = Executors.newCachedThreadPool()
    private val parser = StreamParser()

    /** Shuts the worker pool down with the project, so its threads don't outlive it. */
    override fun dispose() {
        executor.shutdownNow()
    }

    private val settings get() = ClaudeCodeSettings.getInstance(project)

    /**
     * Sends [prompt] for the given [session]. Resumes the CLI's own session
     * (`--resume`) once this session has an id, so multi-turn context is
     * preserved independently per chat tab.
     */
    fun sendPrompt(session: ClaudeSession, prompt: String, listener: StreamListener) {
        if (session.isBusy) {
            listener.onError("This session is already waiting on a response.")
            return
        }
        session.isBusy = true

        executor.submit {
            try {
                runProcess(session, prompt, listener)
            } catch (e: Exception) {
                log.warn("Claude CLI invocation failed", e)
                listener.onError(e.message ?: e.toString())
            } finally {
                session.isBusy = false
                session.process = null
            }
        }
    }

    /** Terminates the running CLI process for [session], if any (Stop button). */
    fun cancel(session: ClaudeSession) {
        session.process?.destroy()
    }

    private fun runProcess(
        session: ClaudeSession,
        prompt: String,
        listener: StreamListener,
        allowResume: Boolean = true
    ) {
        val claudeCommand = settings.claudeCommand
        val request = TurnRequest(
            claudeCommand = claudeCommand,
            sessionPermissionMode = session.permissionMode,
            projectPermissionMode = settings.permissionMode,
            // What the project allows, plus anything granted from a blocked
            // message in this chat. The CLI takes a space-separated list, so the
            // two simply join.
            allowedTools = (listOf(settings.allowedTools) + session.grantedTools)
                .filter { it.isNotBlank() }
                .joinToString(" "),
            disallowedTools = settings.disallowedTools,
            model = session.selectedModel,
            resumeId = session.cliSessionId.takeIf { allowResume }
        )
        val command = ClaudeCommandBuilder.build(request).toMutableList()
        // On Windows a bare "claude" is really claude.cmd, which CreateProcess
        // will not find on its own.
        command[0] = com.claudecode.chatplugin.cli.ExecutableResolver.resolve(command[0])
        val usedResume = request.resumeId != null

        val workingDir = project.basePath?.let { java.io.File(it) }
        val process = try {
            ProcessBuilder(command)
                .apply { if (workingDir != null) directory(workingDir) }
                .redirectErrorStream(false)
                .start()
        } catch (e: java.io.IOException) {
            listener.onError(
                "Could not launch '$claudeCommand'. Is the Claude Code CLI installed " +
                    "and on your PATH? You can set a full path in Settings > Tools > " +
                    "Claude Brains. (${e.message})"
            )
            return
        }
        session.process = process

        // The prompt goes in here rather than on the command line, which has a
        // length limit the command line does not — see ClaudeCommandBuilder.
        //
        // Standard input is then closed at once, which is also what keeps a turn
        // needing confirmation from hanging: the CLI would ask, wait on input
        // this UI has no way to send, and never reach its result event. At EOF
        // it cannot wait, and reports the blocked tools in `permission_denials`
        // instead, which the panel shows.
        runCatching {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(prompt) }
        }.onFailure {
            log.warn("Could not send the prompt to the CLI", it)
        }

        val stderr = StringBuffer()
        val stderrThread = Thread {
            BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).forEachLine { line ->
                log.info("claude stderr: $line")
                stderr.append(line).append('\n')
            }
        }.apply { isDaemon = true; start() }

        var completed: TurnResult? = null
        BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                parser.parse(line, listener)?.let { completed = it }
            }
        }

        val exitCode = process.waitFor()
        stderrThread.join(STDERR_JOIN_MS)

        val stderrText = stderr.toString().trim()
        val result = completed

        // The stored session id can be pruned by the CLI, in which case every
        // later turn in a restored tab would fail. Detect that one case and
        // retry once from a fresh context instead of leaving the tab broken.
        if (result != null && result.isError && allowResume && usedResume &&
            STALE_SESSION.containsMatchIn(stderrText)
        ) {
            log.info("Stored --resume id was stale; retrying with a fresh context")
            session.cliSessionId = null
            listener.onSessionExpired()
            runProcess(session, prompt, listener, allowResume = false)
            return
        }

        when {
            result != null -> listener.onComplete(
                // Some failures (a stale session id among them) report no text in
                // the result event and only explain themselves on stderr.
                if (result.isError && result.errorMessage.isNullOrBlank() && stderrText.isNotEmpty()) {
                    result.copy(errorMessage = stderrText)
                } else {
                    result
                }
            )
            exitCode != 0 -> listener.onError(
                "claude exited with code $exitCode" + if (stderrText.isNotEmpty()) "\n$stderrText" else ""
            )
            else -> listener.onComplete(
                TurnResult(isError = false, costUsd = null, inputTokens = null, outputTokens = null, durationMs = null)
            )
        }
    }

    /**
     * Runs `claude mcp list` and returns its raw output (stdout+stderr).
     *
     * Adding/removing servers stays a CLI concern (`claude mcp add/remove`) —
     * this only surfaces what's configured, so the plugin never rewrites the
     * user's MCP configuration behind their back. Blocking; call off the EDT.
     */
    fun listMcpServers(): String {
        val command = settings.claudeCommand
        val result = CliRunner.run(
            command = listOf(command, "mcp", "list"),
            workingDir = project.basePath?.let { java.io.File(it) },
            timeoutSeconds = MCP_LIST_TIMEOUT_SECONDS
        )
        return when {
            result.failure != null -> "Could not run '$command mcp list': ${result.failure.message}"
            result.timedOut -> "Timed out waiting for '$command mcp list'."
            else -> result.output.ifEmpty { "No output from '$command mcp list'." }
        }
    }

    fun openSettings() {
        ShowSettingsUtil.getInstance()
            .showSettingsDialog(project, com.claudecode.chatplugin.ui.ClaudeBrainsConfigurable::class.java)
    }

    companion object {
        fun getInstance(project: Project): ClaudeCliService = project.getService(ClaudeCliService::class.java)

        /**
         * How the CLI reports an unknown `--resume` id (verified against 2.1.205:
         * exit 1, `result.subtype == "error_during_execution"`, and this line on
         * stderr — the result event itself carries no explanatory text).
         */
        internal val STALE_SESSION = Regex("No conversation found with session ID", RegexOption.IGNORE_CASE)

        private const val MCP_LIST_TIMEOUT_SECONDS = 30L

        /** How long to wait for the stderr drain once the process is gone. */
        private const val STDERR_JOIN_MS = 500L
    }
}
