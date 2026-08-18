package com.claudecode.chatplugin.limits

import com.claudecode.chatplugin.ClaudeCodeSettings
import com.claudecode.chatplugin.cli.CliEnvironment
import com.claudecode.chatplugin.cli.CliRunner
import com.claudecode.chatplugin.cli.CliTranscript
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Asks the CLI for its usage report by piping `/usage` into it.
 *
 * The slash command is the only place the percentages appear, and it works when
 * fed on standard input (in `-p` mode the CLI reads `/usage` as a file path
 * instead). Costs nothing: the run makes no model call — verified against CLI
 * 2.1.205, where the session it records contains zero tokens.
 *
 * Every run leaves a transcript behind, and this one runs every minute the
 * panel is open: 4835 of them had accumulated in one user's `~/.claude`,
 * sitting among their real conversations where `claude --resume` looks. So the
 * session id is chosen here rather than by the CLI, which makes the file's name
 * known in advance — and the run clears up after itself. See [CliTranscript].
 */
object UsageLimitsReader {

    private val log = Logger.getInstance(UsageLimitsReader::class.java)

    fun read(project: Project): List<LimitBar> {
        val command = ClaudeCodeSettings.getInstance(project).claudeCommand
        val workingDir = project.basePath?.let { File(it) }
        // Ours, so it can be cleaned up afterwards without guessing which file
        // belongs to this run.
        val sessionId = java.util.UUID.randomUUID().toString()

        val result = CliRunner.run(
            command = listOf(command, "--session-id", sessionId),
            workingDir = workingDir,
            timeoutSeconds = TIMEOUT_SECONDS,
            stdin = "/usage\n",
            environment = CliEnvironment.forProject(project)
        )
        // Whatever the run did, the record of it is not a conversation anyone
        // asked for.
        CliTranscript.discard(CliTranscript.fileFor(sessionId, workingDir))
        if (result.failure != null) {
            log.warn("Could not read the usage report: ${result.failure.message}")
            return emptyList()
        }
        // A timeout is not fatal here: the report is printed before the session
        // would end, so parse whatever arrived rather than discarding it.
        if (result.timedOut) log.info("`$command` did not exit within ${TIMEOUT_SECONDS}s; using what it printed")

        return UsageLimits.parse(result.output).also {
            if (it.isEmpty()) log.info("No usage percentages recognised in the CLI's report")
        }
    }

    private const val TIMEOUT_SECONDS = 30L
}
