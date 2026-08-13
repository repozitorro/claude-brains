package com.claudecode.chatplugin.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wording of the one question that stands between a click and several
 * rewritten files. It has to state the size of what is about to happen, and it
 * has to read like a sentence — "1 changes in 1 files" undermines the warning
 * it is trying to give.
 */
class ReviewConfirmationsTest {

    private fun message(changes: Int, files: Int) = ReviewConfirmations.message(changes, files)

    @Test
    fun `a single change in a single file reads as one of each`() {
        val text = message(changes = 1, files = 1)

        assertTrue(text, text.startsWith("1 change in 1 file "))
        assertTrue(text, !text.contains("1 changes"))
        assertTrue(text, !text.contains("1 files"))
    }

    @Test
    fun `several changes across several files are counted`() {
        assertTrue(message(7, 3).startsWith("7 changes in 3 files "))
    }

    @Test
    fun `several changes inside one file still say one file`() {
        // The common case: a single file rewritten in several places.
        assertTrue(message(4, 1).startsWith("4 changes in 1 file "))
    }

    @Test
    fun `it says the door swings both ways`() {
        // Rejecting is a write command per hunk, so Undo really does bring it
        // back. Leaving that out would make the dialog sound more final than it
        // is, and people would keep changes they meant to drop.
        val text = message(2, 1)

        assertTrue(text, text.contains("Undo"))
        assertTrue(text, text.contains("one step per change"))
    }
}
