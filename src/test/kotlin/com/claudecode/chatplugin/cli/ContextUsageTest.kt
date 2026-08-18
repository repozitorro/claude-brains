package com.claudecode.chatplugin.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How full the context is, rather than how much went through it.
 *
 * The panel showed `context 1.3M / 1.0M (128%)`, which is not a thing that can
 * happen: it read the turn's totals, and a turn that stops to run tools is
 * several requests, each re-reading the cached prefix. The totals count the
 * same tokens again and again — traffic, not occupancy — and pass the window
 * as soon as a turn runs long enough.
 *
 * One iteration is one request, and that is what the window bounds.
 */
class ContextUsageTest {

    private object Ignore : StreamListener {
        override fun onTextChunk(chunk: String) {}
        override fun onSessionId(cliSessionId: String) {}
        override fun onComplete(result: TurnResult) {}
        override fun onError(message: String) {}
    }

    /** `parse` hands the finished turn back rather than announcing it. */
    private fun contextOf(resultJson: String): Long? =
        StreamParser().parse(resultJson, Ignore)?.contextTokens

    @Test
    fun `a turn with tool iterations reports the last request, not the sum`() {
        // Shaped from a real result event: the totals aggregate every
        // iteration, the last iteration is the request that ended the turn.
        val context = contextOf(
            """
            {"type":"result","subtype":"success","is_error":false,
             "usage":{"input_tokens":4,"cache_creation_input_tokens":26149,"cache_read_input_tokens":25919,
               "iterations":[
                 {"input_tokens":2,"cache_read_input_tokens":10000,"cache_creation_input_tokens":50},
                 {"input_tokens":2,"cache_read_input_tokens":25919,"cache_creation_input_tokens":95}]}}
            """
        )
        assertEquals(2L + 25919L + 95L, context)
    }

    @Test
    fun `a turn that never called a tool uses its totals`() {
        // One request, so the totals are that request. Nothing to correct.
        val context = contextOf(
            """
            {"type":"result","subtype":"success","is_error":false,
             "usage":{"input_tokens":10,"cache_creation_input_tokens":200,"cache_read_input_tokens":3000}}
            """
        )
        assertEquals(3210L, context)
    }

    @Test
    fun `the figure stays inside the window it is measured against`() {
        // The bug in one assertion: totals of 1.3M against a 1M window, where
        // the last request was well inside it.
        val context = contextOf(
            """
            {"type":"result","subtype":"success","is_error":false,
             "usage":{"input_tokens":0,"cache_read_input_tokens":1300000,
               "iterations":[{"input_tokens":0,"cache_read_input_tokens":180000,"cache_creation_input_tokens":2000}]}}
            """
        )!!
        assertTrue("expected a figure inside a 1M window, got $context", context < 1_000_000)
    }

    @Test
    fun `a result with no usage reports nothing rather than zero`() {
        assertNull(contextOf("""{"type":"result","subtype":"success","is_error":false}"""))
    }

    @Test
    fun `an empty iteration list falls back to the totals`() {
        val context = contextOf(
            """
            {"type":"result","subtype":"success","is_error":false,
             "usage":{"input_tokens":1,"cache_read_input_tokens":9,"iterations":[]}}
            """
        )
        assertEquals(10L, context)
    }
}
