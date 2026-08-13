package com.claudecode.chatplugin.cli

import com.claudecode.chatplugin.model.ClaudeSession
import com.claudecode.chatplugin.model.FileEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A turn captured from a real CLI, replayed through the parser.
 *
 * `StreamParserTest` uses a hand-built fixture to reach cases a natural turn
 * will not produce on demand — a failed MCP server, a permission denial, an
 * error result. This one is the opposite: nothing was arranged, it is simply
 * what **2.1.223** actually emitted when asked to read a file and edit it. Its
 * job is to notice the day the CLI's output stops looking like what we parse.
 *
 * Paths in it are anonymised; nothing else was touched. To re-record after an
 * upgrade, see the command in the README.
 */
class RecordedTurnTest {

    private class Recorder : StreamListener {
        val text = StringBuilder()
        val thinking = StringBuilder()
        val toolUses = mutableListOf<String>()
        val toolResults = mutableListOf<Boolean>()
        val edits = mutableListOf<FileEdit>()
        val sessionIds = mutableSetOf<String>()
        var rateLimit: ClaudeSession.RateLimit? = null
        var error: String? = null

        override fun onTextChunk(chunk: String) { text.append(chunk) }
        override fun onThinkingChunk(chunk: String) { thinking.append(chunk) }
        override fun onToolUse(id: String?, display: String) { toolUses += display }
        override fun onToolResult(toolUseId: String?, isError: Boolean, output: String?) { toolResults += isError }
        override fun onFileEdit(edit: FileEdit) { edits += edit }
        override fun onRateLimit(rateLimit: ClaudeSession.RateLimit) { this.rateLimit = rateLimit }
        override fun onSessionId(cliSessionId: String) { sessionIds += cliSessionId }
        override fun onComplete(result: TurnResult) = Unit
        override fun onError(message: String) { error = message }


    }

    private lateinit var recorder: Recorder
    private var result: TurnResult? = null

    @Before
    fun replay() {
        recorder = Recorder()
        result = null
        val parser = StreamParser(snapshotReader = { null })
        val fixture = checkNotNull(javaClass.getResourceAsStream("/protocol/turn-2.1.223.jsonl")) {
            "recorded turn is missing"
        }
        // The terminal event comes back as a return value; onComplete is the
        // service's to call once the process has also exited.
        fixture.bufferedReader().forEachLine { line ->
            if (line.isNotBlank()) parser.parse(line, recorder)?.let { result = it }
        }
    }

    @Test
    fun `the reply reads as one answer, not three copies of it`() {
        // The single property most likely to break silently: the same text is
        // emitted as deltas, again in the assistant block, and again in the
        // result. Reading more than one source doubles every answer.
        assertEquals("Done. Updated version from 1.0 to 1.1 in build.txt.", recorder.text.toString().trim())
    }

    @Test
    fun `reasoning arrives separately from the answer`() {
        assertTrue("this turn did think", recorder.thinking.isNotEmpty())
        assertFalse(
            "reasoning must not leak into the reply",
            recorder.text.contains(recorder.thinking.toString().take(30))
        )
    }

    @Test
    fun `a read shows as activity and an edit shows as an edit`() {
        // Two tool calls happened. Only one of them changes a file, and the two
        // are presented completely differently in the chat.
        assertTrue("the Read should be listed", recorder.toolUses.any { it.startsWith("Read ") })
        assertTrue("an Edit must not appear as plain activity", recorder.toolUses.none { it.startsWith("Edit ") })

        val edit = recorder.edits.single()
        assertEquals("Edit", edit.toolName)
        assertEquals("build.txt", edit.fileName)
        assertEquals("version = \"1.0\"", edit.ops.single().oldString)
        assertEquals("version = \"1.1\"", edit.ops.single().newString)
    }

    @Test
    fun `a tool that failed is marked failed, and the turn still succeeds`() {
        // This really happened in the recording: the first Read missed the file
        // and Claude tried again with the right path. Worth keeping — a failed
        // tool is shown in red with its output open, while the turn around it
        // is a perfectly ordinary success, and conflating the two would either
        // hide the failure or invent one.
        assertEquals("one tool call failed", 1, recorder.toolResults.count { it })
        assertTrue("and others did not", recorder.toolResults.any { !it })

        assertNull(recorder.error)
        val result = checkNotNull(result)
        assertFalse("the turn itself did not fail", result.isError)
        assertNull(result.errorMessage)
    }

    @Test
    fun `the turn carries the numbers the panel shows`() {
        val result = checkNotNull(result)

        assertNotNull("cost", result.costUsd)
        assertNotNull("input tokens", result.inputTokens)
        assertNotNull("output tokens", result.outputTokens)
        assertEquals("context window", 200_000L, result.contextWindow)
        assertTrue("context used", (result.contextTokens ?: 0) > 0)
        assertNotNull("the window this turn belongs to", recorder.rateLimit)
    }

    @Test
    fun `one session id runs through the whole turn`() {
        // It is what the next turn resumes; more than one would mean the parser
        // is reading the wrong field somewhere.
        assertEquals(1, recorder.sessionIds.size)
    }

    private fun assertNull(value: Any?) = assertEquals(null, value)
    private fun assertNull(message: String, value: Any?) = assertEquals(message, null, value)
}
