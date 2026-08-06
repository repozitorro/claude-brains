package com.claudecode.chatplugin.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the property the four CLI call sites depend on: **the timeout is
 * reachable**.
 *
 * Every one of them used to read the process output to end-of-stream before
 * asking whether it had finished, which meant a command that never returned
 * pinned a thread forever — and the `/usage` probe runs once a minute.
 *
 * The subject is a real subprocess, launched from this test's own JVM so the
 * test stays portable: no shell, no `sleep`, nothing platform-specific.
 */
class CliRunnerTest {

    private fun javaCommand(vararg args: String): List<String> {
        val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
        return listOf(java) + args
    }

    /** Runs [SleepForever] from the test classpath, so it can be timed out. */
    private fun sleepForeverCommand(): List<String> =
        javaCommand("-cp", System.getProperty("java.class.path"), SleepForever::class.java.name)

    @Test
    fun `a process that never exits is timed out and killed`() {
        val startedAt = System.currentTimeMillis()

        val result = CliRunner.run(sleepForeverCommand(), timeoutSeconds = 1)

        val elapsed = System.currentTimeMillis() - startedAt
        assertTrue("should have given up", result.timedOut)
        assertFalse("a timed-out run has not succeeded", result.succeeded)
        assertEquals("no exit code is known for a killed process", null, result.exitCode)
        // Generous, but far below "forever": before the fix this never returned.
        assertTrue("took ${elapsed}ms, should be seconds not minutes", elapsed < 30_000)
    }

    @Test
    fun `output is captured and the exit code reported`() {
        val result = CliRunner.run(javaCommand("-version"), timeoutSeconds = 30)

        assertTrue(result.succeeded)
        assertFalse(result.timedOut)
        assertEquals(0, result.exitCode)
        // `java -version` prints to stderr; the runner interleaves both streams,
        // which is what every caller here parses.
        assertTrue("expected version output, got: ${result.output}", result.output.contains("version"))
    }

    @Test
    fun `a missing executable is reported as a failure, not a timeout`() {
        // ClaudeAuth tells these apart: one means "install the CLI", the other
        // means "the CLI is wedged".
        val result = CliRunner.run(listOf("claude-brains-no-such-binary"), timeoutSeconds = 5)

        assertNotNull(result.failure)
        assertFalse(result.started)
        assertFalse(result.timedOut)
        assertFalse(result.succeeded)
    }

    @Test
    fun `standard input is delivered and then closed`() {
        // The `/usage` probe depends on both halves: the CLI has to receive the
        // command, and has to see EOF to act on it.
        val result = CliRunner.run(
            javaCommand("-cp", System.getProperty("java.class.path"), EchoStdin::class.java.name),
            timeoutSeconds = 30,
            stdin = "/usage\n"
        )

        assertTrue("stdin was not delivered: ${result.output}", result.output.contains("read:/usage"))
        assertTrue("stdin was not closed", result.output.contains("eof"))
        assertEquals(0, result.exitCode)
    }
}

/** Test subject: a process that will not exit on its own. */
object SleepForever {
    @JvmStatic
    fun main(args: Array<String>) {
        Thread.sleep(10 * 60 * 1000L)
    }
}

/** Test subject: echoes what it reads, then reports that the stream ended. */
object EchoStdin {
    @JvmStatic
    fun main(args: Array<String>) {
        System.`in`.bufferedReader().forEachLine { println("read:$it") }
        println("eof")
    }
}
