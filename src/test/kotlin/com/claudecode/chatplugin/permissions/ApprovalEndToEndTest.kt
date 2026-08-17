package com.claudecode.chatplugin.permissions

import com.claudecode.chatplugin.cli.ClaudeCommandBuilder
import com.claudecode.chatplugin.cli.TurnRequest
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The one test that talks to a real `claude`.
 *
 * Everything else here checks our side of a protocol we reverse-engineered,
 * which proves only that we are consistent with our own reading of it. This
 * runs the actual CLI against the actual server and watches it stop, ask, and
 * obey the answer — the only evidence that the reading was right.
 *
 * Off by default: it spawns a signed-in CLI, makes a model call, and costs real
 * money. Run it deliberately, after touching anything in this package:
 *
 *     CLAUDE_BRAINS_E2E=1 ./gradlew test --tests '*ApprovalEndToEndTest*'
 */
class ApprovalEndToEndTest {

    private val enabled = System.getenv("CLAUDE_BRAINS_E2E") == "1"

    @Test
    fun `the CLI asks before it writes, and does what it is told`() {
        if (!enabled) return

        val asked = AtomicReference<Pair<String, JsonObject>?>(null)
        val decision = AtomicReference<ApprovalDecision>(ApprovalDecision.Deny("Declined by the test."))

        val token = ApprovalHttpServer.newToken()
        val server = ApprovalHttpServer { requested ->
            if (requested != token) null
            else McpApprovalProtocol { toolName, input, _ ->
                asked.set(toolName to input)
                decision.get()
            }
        }
        assertTrue("the approval server should start", server.start())

        try {
            // Somewhere outside the working directory, which is what makes the
            // CLI stop and ask rather than deciding for itself.
            val target = File(System.getProperty("user.home"), "claude-brains-e2e-probe.txt")
            target.delete()

            // Refuse it first: the file must still not exist afterwards.
            runTurn(server.urlFor(token)!!, target)
            assertNotNull("the CLI should have asked before writing", asked.get())
            assertEquals("Write", asked.get()!!.first)
            assertTrue("a refused write must not happen", !target.exists())

            // Then allow it: the same request, answered the other way.
            asked.set(null)
            decision.set(ApprovalDecision.Allow(null))
            runTurn(server.urlFor(token)!!, target)
            assertNotNull("the CLI should have asked again", asked.get())
            assertTrue("an allowed write must happen", target.exists())
            target.delete()
        } finally {
            server.stop()
        }
    }

    private fun runTurn(endpoint: String, target: File) {
        val config = File.createTempFile("claude-brains-e2e", ".json").apply {
            deleteOnExit()
            writeText(McpApprovalProtocol.mcpConfigJson(endpoint), Charsets.UTF_8)
        }
        val command = ClaudeCommandBuilder.build(
            TurnRequest(
                claudeCommand = "claude",
                // The mode every chat runs in unless told otherwise. Worth
                // pinning: the prompt tool and the mode are two halves of the
                // same permission engine, and "it worked with no mode set" is
                // not evidence about the one the plugin actually passes.
                projectPermissionMode = "acceptEdits",
                approvalConfigPath = config.absolutePath
            )
        ).toMutableList()
        command[0] = com.claudecode.chatplugin.cli.ExecutableResolver.resolve(command[0])

        val process = ProcessBuilder(command)
            .directory(File(System.getProperty("java.io.tmpdir")))
            .redirectErrorStream(true)
            .start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use {
            it.write("Using the Write tool, create the file ${target.absolutePath} containing the word hello.")
        }
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        process.waitFor(TURN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        assertTrue("the CLI printed nothing at all", output.isNotEmpty())
        // Printed, not asserted on: when this test fails it is almost always
        // the CLI saying why on its way out, and that line is the whole answer.
        println("--- claude said ---\n${output.take(4000)}")
    }

    private companion object {
        const val TURN_TIMEOUT_SECONDS = 180L
    }
}
