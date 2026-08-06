package com.claudecode.chatplugin.limits

import com.claudecode.chatplugin.ClaudeCodeSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.TimeUnit

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
        return try {
            val process = ProcessBuilder(listOf(command))
                .apply { project.basePath?.let { directory(File(it)) } }
                .redirectErrorStream(true)
                .start()

            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write("/usage\n") }
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy()
                log.info("`$command` did not return a usage report in ${TIMEOUT_SECONDS}s")
                return emptyList()
            }
            UsageLimits.parse(output).also {
                if (it.isEmpty()) log.info("No usage percentages recognised in the CLI's report")
            }
        } catch (e: Exception) {
            log.warn("Could not read the usage report", e)
            emptyList()
        }
    }

    private const val TIMEOUT_SECONDS = 30L
}
