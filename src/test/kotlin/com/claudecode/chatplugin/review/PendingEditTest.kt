package com.claudecode.chatplugin.review

import com.claudecode.chatplugin.model.EditOp
import com.claudecode.chatplugin.model.FileEdit
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The rules that decide what lands in a user's file.
 *
 * Rejecting a hunk rewrites part of the document, which moves every hunk below
 * it — the reason hunks are held by RangeMarkers rather than offsets. These
 * tests pin that down, along with the guard that keeps unprovable
 * reconstructions out of inline review entirely.
 */
class PendingEditTest : BasePlatformTestCase() {

    private fun document(text: String): Document =
        EditorFactory.getInstance().createDocument(text)

    /** An edit whose ops provably reproduce [after] from [before]. */
    private fun edit(before: String, after: String, vararg ops: Pair<String, String>): FileEdit =
        FileEdit("/repo/App.kt", "Edit", null).apply {
            ops.forEach { (old, new) -> this.ops.add(EditOp(old, new, null, false)) }
            resolve(after)
            assertTrue("test setup: reconstruction must be provable", canRevert)
            assertEquals("test setup: before-text should match", before, beforeText)
        }

    private fun pending(before: String, after: String, vararg ops: Pair<String, String>): PendingEdit {
        val doc = document(after)
        val file = myFixture.configureByText("App.kt", after).virtualFile
        return PendingEdit.create(edit(before, after, *ops), file, doc)
            ?: fail("expected a reviewable edit") as Nothing
    }

    fun testSplitsIntoOneHunkPerChangedRegion() {
        val before = "a\nb\nc\nd\ne\n"
        val after = "a\nB\nc\nd\nE\n"

        val review = pending(before, after, "b" to "B", "e" to "E")

        assertEquals(2, review.hunks.size)
        assertEquals(listOf(1, 4), review.hunks.map { it.startLine() })
    }

    fun testRejectingOneHunkLeavesTheOthersIntact() {
        val before = "a\nb\nc\nd\ne\n"
        val after = "a\nB\nc\nd\nE\n"
        val review = pending(before, after, "b" to "B", "e" to "E")

        // Reject the FIRST hunk: everything below shifts, which is exactly the
        // case that breaks if hunks are tracked by plain offsets.
        review.reject(project, review.hunks.first())

        assertEquals("a\nb\nc\nd\nE\n", review.document.text)
        assertEquals(1, review.pendingHunks.size)
        assertEquals("the surviving hunk should still cover line 4", 4, review.pendingHunks.first().startLine())
    }

    fun testRejectingEveryHunkRestoresTheOriginal() {
        val before = "one\ntwo\nthree\nfour\n"
        val after = "one\nTWO\nthree\nFOUR\n"
        val review = pending(before, after, "two" to "TWO", "four" to "FOUR")

        review.rejectAll(project)

        assertEquals(before, review.document.text)
        assertTrue(review.isFinished)
    }

    fun testAcceptingLeavesTheFileAlone() {
        val after = "one\nTWO\nthree\n"
        val review = pending("one\ntwo\nthree\n", after, "two" to "TWO")

        review.acceptAll()

        assertEquals("accepting must not touch the text", after, review.document.text)
        assertTrue(review.isFinished)
    }

    fun testInsertionsAndDeletionsRoundTrip() {
        val before = "keep\ndrop\n"
        val after = "keep\nadded\n"
        val review = pending(before, after, "drop" to "added")

        review.rejectAll(project)

        assertEquals(before, review.document.text)
    }

    fun testUnprovableReconstructionIsNotOfferedInline() {
        // new_string already appears elsewhere, so reversing it is ambiguous and
        // canRevert stays false — line-by-line reject would be guesswork.
        val after = "x = 1;\ny = x;\n"
        val ambiguous = FileEdit("/repo/App.kt", "Edit", null).apply {
            ops.add(EditOp("y", "x", null, false))
            resolve(after)
        }
        assertFalse(ambiguous.canRevert)

        val file = myFixture.configureByText("App.kt", after).virtualFile
        assertNull(PendingEdit.create(ambiguous, file, document(after)))
    }

    fun testDocumentThatMovedOnIsNotReviewed() {
        val before = "a\nb\n"
        val after = "a\nB\n"
        val stale = edit(before, after, "b" to "B")
        val file = myFixture.configureByText("App.kt", after).virtualFile

        // The user (or a formatter) changed the file after Claude did.
        val moved = document("a\nB\nsomething else\n")

        assertNull(PendingEdit.create(stale, file, moved))
    }
}
