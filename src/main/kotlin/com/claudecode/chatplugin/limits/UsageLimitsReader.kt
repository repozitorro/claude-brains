package com.claudecode.chatplugin.limits

import com.claudecode.chatplugin.ClaudeCodeSettings
import com.claudecode.chatplugin.cli.CliEnvironment
import com.claudecode.chatplugin.cli.CliRunner
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
 * It does leave a small session transcript behind each time, which is why the
 * caller polls on an interval rather than continuously.
 */
object UsageLimitsReader {

    private val log = Logger.getInstance(UsageLimitsReader::class.java)

    fun read(project: Project): List<LimitBar> {
        val command = ClaudeCodeSettings.getInstance(project).claudeCommand
        val result = CliRunner.run(
            command = listOf(command),
            workingDir = project.basePath?.let { File(it) },
            timeoutSeconds = TIMEOUT_SECONDS,
            stdin = "/usage\n",
            environment = CliEnvironment.forProject(project)
        )
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
