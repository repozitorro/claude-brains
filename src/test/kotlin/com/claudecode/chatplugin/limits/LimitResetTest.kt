package com.claudecode.chatplugin.limits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling a reset from everything else that makes a percentage move.
 *
 * Nothing announces a reset, and the reset *time* passing is not proof one
 * happened — clocks drift and the CLI can revise the figure. So the evidence
 * required is a report that says both a smaller percentage and a different
 * reset moment than the last one did. Either alone is something else.
 */
class LimitResetTest {

    private fun bar(label: String, percent: Int, resets: String) = LimitBar(label, percent, resets)

    private val fullSession = listOf(bar("Current session", 96, "Aug 18, 2:30pm (Europe/Kiev)"))

    @Test
    fun `a smaller percentage and a new reset time is a reset`() {
        val events = LimitReset.detect(
            fullSession,
            listOf(bar("Current session", 2, "Aug 18, 7:30pm (Europe/Kiev)"))
        )
        assertEquals(1, events.size)
        assertEquals("Session", events[0].label)
        assertEquals(96, events[0].wasPercent)
        assertEquals(2, events[0].nowPercent)
    }

    @Test
    fun `a percentage that only fell is not a reset`() {
        // Same window, revised figure. Announcing this would be announcing noise.
        val events = LimitReset.detect(
            fullSession,
            listOf(bar("Current session", 90, "Aug 18, 2:30pm (Europe/Kiev)"))
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a new reset time alone is not a reset`() {
        // The wording of that field is the CLI's to change.
        val events = LimitReset.detect(
            fullSession,
            listOf(bar("Current session", 96, "Aug 18, 2:30pm (UTC)"))
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a window nobody was waiting on is not worth saying`() {
        // It resets every few hours whether or not anyone noticed. A balloon
        // for that is one the user learns to dismiss unread.
        val events = LimitReset.detect(
            listOf(bar("Current session", 12, "Aug 18, 2:30pm (Europe/Kiev)")),
            listOf(bar("Current session", 0, "Aug 18, 7:30pm (Europe/Kiev)"))
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `the first report announces nothing`() {
        // Starting the IDE mid-window is not a reset, and there is nothing to
        // compare it against anyway.
        assertTrue(LimitReset.detect(emptyList(), fullSession).isEmpty())
    }

    @Test
    fun `each window is judged on its own`() {
        val before = listOf(
            bar("Current session", 98, "Aug 18, 2:30pm"),
            bar("Current week (all models)", 30, "Aug 22, 6am")
        )
        val after = listOf(
            bar("Current session", 0, "Aug 18, 7:30pm"),
            bar("Current week (all models)", 31, "Aug 22, 6am")
        )
        val events = LimitReset.detect(before, after)
        assertEquals(1, events.size)
        assertEquals("Session", events[0].label)
    }

    @Test
    fun `a window that disappears from the report says nothing`() {
        assertTrue(LimitReset.detect(fullSession, listOf(bar("Current week (all models)", 4, "Aug 22, 6am"))).isEmpty())
    }

    @Test
    fun `the message says what reset and when the next one is`() {
        val event = LimitReset.Event("Session", 96, 2, "Aug 18, 7:30pm (Europe/Kiev)")
        assertEquals(
            "Session limit has reset — 2% used, next reset Aug 18, 7:30pm (Europe/Kiev).",
            LimitReset.message(event)
        )
    }
}
