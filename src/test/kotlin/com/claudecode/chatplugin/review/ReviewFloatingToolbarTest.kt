package com.claudecode.chatplugin.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The stepping rules behind the strip's next/previous buttons.
 *
 * Worth pinning because the interesting cases are the ones you hit constantly
 * in real use and never think to try by hand: the caret already sitting on a
 * change, and running off either end.
 */
class ReviewFloatingToolbarTest {

    private fun next(lines: List<Int>, current: Int) =
        ReviewFloatingToolbar.targetLine(lines, current, back = false)

    private fun previous(lines: List<Int>, current: Int) =
        ReviewFloatingToolbar.targetLine(lines, current, back = true)

    @Test
    fun `next goes to the first change below the caret`() {
        assertEquals(10, next(listOf(10, 25, 40), current = 0))
        assertEquals(25, next(listOf(10, 25, 40), current = 10))
        assertEquals(40, next(listOf(10, 25, 40), current = 30))
    }

    @Test
    fun `previous goes to the first change above the caret`() {
        assertEquals(25, previous(listOf(10, 25, 40), current = 40))
        assertEquals(10, previous(listOf(10, 25, 40), current = 25))
        assertEquals(10, previous(listOf(10, 25, 40), current = 20))
    }

    @Test
    fun `stepping past the last change wraps to the first`() {
        // Otherwise the button goes dead at the bottom of the file, which reads
        // as broken rather than as finished.
        assertEquals(10, next(listOf(10, 25, 40), current = 40))
        assertEquals(10, next(listOf(10, 25, 40), current = 999))
    }

    @Test
    fun `stepping back past the first change wraps to the last`() {
        assertEquals(40, previous(listOf(10, 25, 40), current = 10))
        assertEquals(40, previous(listOf(10, 25, 40), current = 0))
    }

    @Test
    fun `a single change is still reachable from either direction`() {
        // With one hunk both buttons must land on it, including when the caret
        // is already there — the wrap is what makes that work.
        assertEquals(7, next(listOf(7), current = 7))
        assertEquals(7, previous(listOf(7), current = 7))
        assertEquals(7, next(listOf(7), current = 0))
    }

    @Test
    fun `nothing to step to is not a jump to nowhere`() {
        assertNull(next(emptyList(), current = 3))
        assertNull(previous(emptyList(), current = 3))
    }
}
