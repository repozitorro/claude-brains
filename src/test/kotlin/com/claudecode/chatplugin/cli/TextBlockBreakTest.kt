package com.claudecode.chatplugin.cli

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two thoughts either side of a tool call, kept apart.
 *
 * A reply resumes in a fresh content block every time a tool runs, and the
 * plugin appended every text delta to one string — so the chat showed
 * "…не вигадувати свій.Тепер створю ініціативу.Heredoc з таким контентом…",
 * three separate sentences run into one.
 *
 * The obvious signal is the block index, and it does not work: it restarts
 * from 0 with each assistant message, so the text before a tool call and the
 * text after it are both index 0. The sequence below is exactly what CLI
 * 2.1.232 emitted for "say one, run a command, say three" — start 0, stop 0,
 * start 1 (the tool), stop 1, start 0 again.
 */
class TextBlockBreakTest {

    private class Collected : StreamListener {
        val text = StringBuilder()
        override fun onTextChunk(chunk: String) { text.append(chunk) }
        override fun onTextBlockStart() { if (text.isNotBlank()) text.append("\n\n") }
        override fun onSessionId(cliSessionId: String) {}
        override fun onComplete(result: TurnResult) {}
        override fun onError(message: String) {}
    }

    private fun start(index: Int) =
        """{"type":"stream_event","event":{"type":"content_block_start","index":$index}}"""

    private fun stop(index: Int) =
        """{"type":"stream_event","event":{"type":"content_block_stop","index":$index}}"""

    private fun text(index: Int, text: String) =
        """{"type":"stream_event","event":{"type":"content_block_delta","index":$index,""" +
            """"delta":{"type":"text_delta","text":"$text"}}}"""

    private fun run(vararg lines: String): String {
        val parser = StreamParser()
        val listener = Collected()
        lines.forEach { parser.parse(it, listener) }
        return listener.text.toString()
    }

    @Test
    fun `text either side of a tool call is two paragraphs`() {
        val out = run(
            start(0), text(0, "one"), stop(0),
            start(1), stop(1), // the tool_use block
            start(0), text(0, "three"), stop(0)
        )
        assertEquals("one\n\nthree", out)
    }

    @Test
    fun `one block streaming in pieces stays one paragraph`() {
        // Deltas arrive a few characters at a time; a break between each of
        // them would shred every sentence in the reply.
        val out = run(start(0), text(0, "one "), text(0, "two "), text(0, "three"), stop(0))
        assertEquals("one two three", out)
    }

    @Test
    fun `a reply that starts with a tool call has no leading blank line`() {
        val out = run(start(0), stop(0), start(0), text(0, "after"), stop(0))
        assertEquals("after", out)
    }

    @Test
    fun `thinking between two texts does not itself split them`() {
        // The break belongs to the visible reply. A thinking block consumes the
        // start it was given without announcing a paragraph of its own.
        val thinking = """{"type":"stream_event","event":{"type":"content_block_delta","index":0,""" +
            """"delta":{"type":"thinking_delta","thinking":"hmm"}}}"""
        val out = run(start(0), thinking, text(0, "visible"), stop(0))
        assertEquals("visible", out)
    }

    @Test
    fun `each turn starts clean`() {
        // The parser carries this state, which is why one is built per turn:
        // two chats streaming at once must not take each other's breaks.
        assertEquals("one", run(start(0), text(0, "one"), stop(0)))
        assertEquals("one", run(start(0), text(0, "one"), stop(0)))
    }
}
