package com.claudecode.chatplugin.cli

import com.claudecode.chatplugin.model.ClaudeSession
import com.claudecode.chatplugin.model.FileEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Replays a whole turn through the parser.
 *
 * The event shapes are the plugin's most exposed surface: they are an
 * implementation detail of a CLI that upgrades itself, and when they drift the
 * symptom is a chat that renders nothing rather than an error. Pinning them to
 * a fixture means drift shows up here first.
 *
 * To refresh the fixture against a newer CLI:
 *
 *     claude -p "..." --output-format stream-json --verbose \
 *       --include-partial-messages > src/test/resources/protocol/turn-<version>.jsonl
 */
class StreamParserTest {

    /** Records every callback, so a turn can be asserted on as a whole. */
    private class Recorder : StreamListener {
        val text = StringBuilder()
        val thinking = StringBuilder()
        val toolUses = mutableListOf<Pair<String?, String>>()
        val toolResults = mutableListOf<Triple<String?, Boolean, String?>>()
        val edits = mutableListOf<FileEdit>()
        val mcpFailures = mutableListOf<String>()
        val sessionIds = mutableListOf<String>()
        var rateLimit: ClaudeSession.RateLimit? = null
        var completed: TurnResult? = null
        var error: String? = null

        override fun onTextChunk(chunk: String) { text.append(chunk) }
        override fun onThinkingChunk(chunk: String) { thinking.append(chunk) }
        override fun onToolUse(id: String?, display: String) { toolUses += id to display }
        override fun onToolResult(toolUseId: String?, isError: Boolean, output: String?) {
            toolResults += Triple(toolUseId, isError, output)
        }
        override fun onFileEdit(edit: FileEdit) { edits += edit }
        override fun onRateLimit(rateLimit: ClaudeSession.RateLimit) { this.rateLimit = rateLimit }
        override fun onMcpFailures(failed: List<String>) { mcpFailures += failed }
        override fun onSessionId(cliSessionId: String) { sessionIds += cliSessionId }
        override fun onComplete(result: TurnResult) { completed = result }
        override fun onError(message: String) { error = message }
    }

    private lateinit var recorder: Recorder
    private var result: TurnResult? = null

    @Before
    fun replayTheRecordedTurn() {
        recorder = Recorder()
        // No file system: the snapshot reader is what a Write would consult.
        val parser = StreamParser(snapshotReader = { path -> "snapshot of $path" })

        val fixture = checkNotNull(javaClass.getResourceAsStream("/protocol/turn-2.1.205.jsonl")) {
            "protocol fixture is missing"
        }
        fixture.bufferedReader().forEachLine { line ->
            if (line.isNotBlank()) parser.parse(line, recorder)?.let { result = it }
        }
    }

    @Test
    fun `the reply is assembled from the deltas and counted once`() {
        // The full text arrives three times per turn — as deltas, in the final
        // assistant block, and again in result.result. Reading more than one of
        // them would silently double every answer.
        assertEquals("I read the build file and bumped the version.", recorder.text.toString())
        assertEquals("Let me look at the file.", recorder.thinking.toString())
    }

    @Test
    fun `a line that is not JSON is ignored rather than fatal`() {
        // The fixture carries an npm warning in the middle of the stream.
        assertNull(recorder.error)
        assertNotNull("the turn still completed", result)
    }

    @Test
    fun `tool calls are summarised and their results correlated`() {
        assertEquals(listOf("toolu_read1" to "Read build.gradle.kts"), recorder.toolUses)

        assertEquals(2, recorder.toolResults.size)
        val (id, isError, output) = recorder.toolResults.first()
        assertEquals("toolu_read1", id)
        assertFalse(isError)
        assertEquals("version = \"1.0\"", output)
        // The second result's content is a bare string, not an array of blocks.
        assertEquals("The file has been updated.", recorder.toolResults[1].third)
    }

    @Test
    fun `a file-mutating call becomes an edit, not an activity line`() {
        assertEquals(1, recorder.edits.size)
        val edit = recorder.edits.single()
        assertEquals("/repo/build.gradle.kts", edit.filePath)
        assertEquals("Edit", edit.toolName)
        assertEquals(1, edit.ops.size)
        assertEquals("version = \"1.0\"", edit.ops.single().oldString)
        assertEquals("version = \"1.1\"", edit.ops.single().newString)
        // It must not also show up as a plain tool call, or the chat lists it twice.
        assertTrue(recorder.toolUses.none { it.first == "toolu_edit1" })
    }

    @Test
    fun `the session id is reported so the next turn can resume`() {
        assertTrue(recorder.sessionIds.isNotEmpty())
        assertTrue(recorder.sessionIds.all { it == "11111111-2222-3333-4444-555555555555" })
    }

    @Test
    fun `only MCP servers that failed are reported`() {
        assertEquals(listOf("sentry (failed)"), recorder.mcpFailures)
    }

    @Test
    fun `the rate-limit window is read from its own event`() {
        val limit = checkNotNull(recorder.rateLimit)
        assertEquals("five_hour", limit.type)
        assertEquals("allowed", limit.status)
        assertEquals(1754500000L, limit.resetsAtEpochSec)
        assertFalse(limit.isUsingOverage)
    }

    @Test
    fun `the terminal event carries cost, usage and denials`() {
        val turn = checkNotNull(result)
        assertFalse(turn.isError)
        assertEquals(0.0123, turn.costUsd!!, 1e-9)
        assertEquals(15, turn.inputTokens)
        assertEquals(42, turn.outputTokens)
        assertEquals(4321L, turn.durationMs)
        assertEquals(listOf("Bash"), turn.permissionDenials)
        // A successful turn must not surface result.result as an error message.
        assertNull(turn.errorMessage)
    }

    @Test
    fun `context is everything the request was built from, against the largest window`() {
        val turn = checkNotNull(result)
        // input + cache read + cache creation — not just the fresh input.
        assertEquals(15L + 1000L + 200L, turn.contextTokens)
        // A turn can touch several models; the conversation is bounded by the biggest.
        assertEquals(200_000L, turn.contextWindow)
    }

    @Test
    fun `a failed turn explains itself instead of reporting a blank reply`() {
        val recorder = Recorder()
        val turn = StreamParser().parse(
            """{"type":"result","subtype":"error_during_execution","is_error":true,""" +
                """"result":"Failed to authenticate.","api_error_status":401}""",
            recorder
        )

        val failure = checkNotNull(turn)
        assertTrue(failure.isError)
        assertEquals("Failed to authenticate.", failure.errorMessage)
        assertEquals(401, failure.apiErrorStatus)
    }
}
