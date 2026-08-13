package com.claudecode.chatplugin.review

import com.claudecode.chatplugin.review.ReviewNavigation.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Stepping through a turn's changes.
 *
 * A turn usually touches several files, so the boundaries are what matter: the
 * last change in a file, the last file, and the file that is the only one left.
 * These are the cases you hit constantly in real use and never think to try by
 * hand.
 */
class ReviewNavigationTest {

    private fun next(lines: List<Int>, caret: Int) = ReviewNavigation.step(lines, caret, back = false)
    private fun previous(lines: List<Int>, caret: Int) = ReviewNavigation.step(lines, caret, back = true)

    // --- Inside one file ---

    @Test
    fun `next goes to the first change below the caret`() {
        assertEquals(Step.ToLine(10), next(listOf(10, 25, 40), caret = 0))
        assertEquals(Step.ToLine(25), next(listOf(10, 25, 40), caret = 10))
        assertEquals(Step.ToLine(40), next(listOf(10, 25, 40), caret = 30))
    }

    @Test
    fun `previous goes to the first change above the caret`() {
        assertEquals(Step.ToLine(25), previous(listOf(10, 25, 40), caret = 40))
        assertEquals(Step.ToLine(10), previous(listOf(10, 25, 40), caret = 20))
    }

    @Test
    fun `running out of changes in this direction leaves the file`() {
        // The whole point: the turn is the unit of review, not the file.
        assertEquals(Step.ToAnotherFile, next(listOf(10, 25, 40), caret = 40))
        assertEquals(Step.ToAnotherFile, next(listOf(10, 25, 40), caret = 999))
        assertEquals(Step.ToAnotherFile, previous(listOf(10, 25, 40), caret = 10))
        assertEquals(Step.ToAnotherFile, previous(listOf(10, 25, 40), caret = 0))
    }

    @Test
    fun `a file with nothing left always sends you elsewhere`() {
        assertEquals(Step.ToAnotherFile, next(emptyList(), caret = 3))
        assertEquals(Step.ToAnotherFile, previous(emptyList(), caret = 3))
    }

    // --- Across files ---

    private val files = listOf("A.kt", "B.kt", "C.kt")

    @Test
    fun `the next file is the one after this in review order`() {
        assertEquals("B.kt", ReviewNavigation.neighbour(files, "A.kt", back = false))
        assertEquals("C.kt", ReviewNavigation.neighbour(files, "B.kt", back = false))
    }

    @Test
    fun `past the last file it comes back round to the first`() {
        assertEquals("A.kt", ReviewNavigation.neighbour(files, "C.kt", back = false))
        assertEquals("C.kt", ReviewNavigation.neighbour(files, "A.kt", back = true))
    }

    @Test
    fun `the only file left has no neighbour, so the caller wraps inside it`() {
        // Returning the file itself would make it open itself on every press.
        assertNull(ReviewNavigation.neighbour(listOf("A.kt"), "A.kt", back = false))
        assertNull(ReviewNavigation.neighbour(emptyList<String>(), "A.kt", back = false))
    }

    @Test
    fun `a file already finished and dropped still steps somewhere sensible`() {
        // Its changes can be accepted from the chat while its editor is open, so
        // the file the strip belongs to may no longer be in the list at all.
        assertEquals("A.kt", ReviewNavigation.neighbour(files, "Gone.kt", back = false))
        assertEquals("C.kt", ReviewNavigation.neighbour(files, "Gone.kt", back = true))
    }

    // --- Arriving in a file from elsewhere ---

    @Test
    fun `arriving forwards lands on the first change, backwards on the last`() {
        assertEquals(10, ReviewNavigation.entryLine(listOf(40, 10, 25), back = false))
        assertEquals(40, ReviewNavigation.entryLine(listOf(40, 10, 25), back = true))
        assertNull(ReviewNavigation.entryLine(emptyList(), back = false))
    }
}
