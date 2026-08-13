package com.claudecode.chatplugin.cli

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs a short-lived CLI command and returns what it printed.
 *
 * Every caller used to spell this out for itself, and every copy shared the
 * same defect: `readText()` was called *before* `waitFor(timeout)`. Reading to
 * end-of-stream blocks until the process exits, so the timeout below it could
 * never be reached — a command that never returned pinned a pooled thread
 * forever, and the one polled every minute (`/usage`) could pile those up.
 *
 * Here the output is drained on its own thread, so [timeoutSeconds] is real:
 * the process is asked to stop, then killed, and whatever it printed up to that
 * point is still returned.
 *
 * For the streaming chat turn see `ClaudeCliService.runProcess` — that one is
 * long-lived by design and deliberately not routed through here.
 */
object CliRunner {

    private val LOG = Logger.getInstance(CliRunner::class.java)

    /**
     * The outcome of one run. [output] is stdout and stderr interleaved, and is
     * populated even when the run timed out or failed part-way.
     */
    data class Result(
        val exitCode: Int?,
        val output: String,
        val timedOut: Boolean = false,
        /** Set when the process could not be started at all (missing binary, …). */
        val failure: Exception? = null
    ) {
        val started: Boolean get() = failure == null
        val succeeded: Boolean get() = exitCode == 0 && !timedOut
    }

    /**
     * Runs [command] in [workingDir], optionally writing [stdin] to it first.
     *
     * Standard input is always closed afterwards: a CLI that reads it (the
     * `/usage` probe drives an otherwise interactive session) needs the EOF to
     * know the input is finished, and one that ignores it is unaffected.
     */
    fun run(
        command: List<String>,
        workingDir: File? = null,
        timeoutSeconds: Long,
        stdin: String? = null
    ): Result {
        // On Windows a bare "claude" is really claude.cmd, which CreateProcess
        // will not find on its own.
        val launched = listOf(ExecutableResolver.resolve(command.first())) + command.drop(1)
        val process = try {
            ProcessBuilder(launched)
                .apply { if (workingDir != null) directory(workingDir) }
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            LOG.info("Could not start ${command.firstOrNull()}: ${e.message}")
            return Result(exitCode = null, output = "", failure = e)
        }

        // StringBuffer, not StringBuilder: the reader thread writes it and this
        // thread reads it, including on the timeout path where the join below
        // may return before the thread has finished.
        val output = StringBuffer()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { output.append(it).append('\n') }
            }
        }.apply { isDaemon = true; name = "claude-brains-cli-reader"; start() }

        runCatching {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer -> stdin?.let(writer::write) }
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            LOG.info("`${command.joinToString(" ")}` did not finish in ${timeoutSeconds}s; terminating it")
            process.destroy()
            if (!process.waitFor(GRACE_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        reader.join(READER_JOIN_MS)

        return Result(
            exitCode = if (finished) process.exitValue() else null,
            output = output.toString().trim(),
            timedOut = !finished
        )
    }

    /** How long a process gets to exit on its own after being asked to stop. */
    private const val GRACE_SECONDS = 2L

    /** How long to wait for the drain thread once the process is gone. */
    private const val READER_JOIN_MS = 1_000L
}
