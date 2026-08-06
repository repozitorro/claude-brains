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
        return requireNotNull(PendingEdit.create(edit(before, after, *ops), file, doc)) {
            "expected a reviewable edit"
        }
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

    fun testUnrelatedLaterEditsDoNotBlockReview() {
        // Someone appended a line after Claude's change. Claude's edit still
        // reconstructs exactly, so it stays reviewable — and only its own lines
        // are marked up.
        val e = edit("a\nb\n", "a\nB\n", "b" to "B")
        val file = myFixture.configureByText("App.kt", "x").virtualFile
        val moved = document("a\nB\nsomething the user added\n")

        val review = PendingEdit.create(e, file, moved)!!

        assertEquals(1, review.hunks.size)
        assertEquals(1, review.hunks.single().startLine())

        review.rejectAll(project)
        assertEquals("the user's own line must survive", "a\nb\nsomething the user added\n", moved.text)
    }

    fun testAcceptingOrRejectingTheWholeFileClearsIt() {
        // What the floating toolbar's two buttons do.
        val before = "a\nb\nc\nd\n"
        val after = "a\nB\nc\nD\n"

        val accepted = pending(before, after, "b" to "B", "d" to "D")
        accepted.acceptAll()
        assertTrue(accepted.isFinished)
        assertEquals(after, accepted.document.text)

        val rejected = pending(before, after, "b" to "B", "d" to "D")
        rejected.rejectAll(project)
        assertTrue(rejected.isFinished)
        assertEquals(before, rejected.document.text)
    }

    fun testHunksAreOrderedForStepThroughNavigation() {
        // The toolbar's next/previous buttons walk these in line order.
        val review = pending(
            "a\nb\nc\nd\ne\nf\n",
            "a\nB\nc\nD\ne\nF\n",
            "b" to "B", "d" to "D", "f" to "F"
        )

        val lines = review.pendingHunks.map { it.startLine() }
        assertEquals(listOf(1, 3, 5), lines.sorted())
        assertEquals(3, review.pendingHunks.size)
    }

    fun testADocumentTheEditNoLongerDescribesIsDeclined() {
        // The change was undone (or never landed here): reverse-applying finds
        // nothing to undo, so there is nothing trustworthy to mark up.
        val e = edit("a\nb\n", "a\nB\n", "b" to "B")
        val file = myFixture.configureByText("App.kt", "x").virtualFile

        assertNull(PendingEdit.create(e, file, document("a\nb\n")))
    }
}
