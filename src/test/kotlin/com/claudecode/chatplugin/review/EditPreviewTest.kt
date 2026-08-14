package com.claudecode.chatplugin.review

import com.claudecode.chatplugin.model.DiffLine
import com.claudecode.chatplugin.model.EditOp
import com.claudecode.chatplugin.model.FileEdit
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The change, shown where it was made.
 *
 * A line saying a file was edited says nothing about what was done to it. This
 * is what turns "Edit App.kt" into something you can agree or disagree with
 * without opening anything.
 */
class EditPreviewTest : BasePlatformTestCase() {

    private fun edit(before: String, after: String, old: String, new: String): FileEdit =
        FileEdit("/repo/App.kt", "Edit", null).apply {
            ops.add(EditOp(old, new, null, false))
            resolve(after)
            assertEquals("test setup", before, beforeText)
        }

    fun testAReplacementShowsBothSides() {
        val preview = EditPreview.of(edit("a\nb\nc\n", "a\nB\nc\n", "b", "B"))

        assertEquals(
            listOf(DiffLine.Kind.REMOVED to "b", DiffLine.Kind.ADDED to "B"),
            preview.map { it.kind to it.text }
        )
    }

    fun testUntouchedLinesAreLeftOut() {
        // Context is what the diff window is for; this is for a glance.
        val before = (1..40).joinToString("\n") { "line $it" } + "\n"
        val after = before.replace("line 20", "LINE 20")

        val preview = EditPreview.of(edit(before, after, "line 20", "LINE 20"))

        assertEquals(2, preview.size)
        assertTrue(preview.none { it.text.contains("line 19") })
    }

    fun testSeparateChangesAreSeparated() {
        val preview = EditPreview.of(
            FileEdit("/repo/App.kt", "MultiEdit", null).apply {
                ops.add(EditOp("b", "B", null, false))
                ops.add(EditOp("e", "E", null, false))
                resolve("a\nB\nc\nd\nE\n")
            }
        )

        // Two changes, with a marker between them rather than run together.
        assertTrue(preview.any { it.kind == DiffLine.Kind.GAP })
        assertEquals(2, preview.count { it.kind == DiffLine.Kind.ADDED })
    }

    fun testAHugeRewriteIsCutRatherThanPasted() {
        val before = (1..200).joinToString("\n") { "old $it" } + "\n"
        val after = (1..200).joinToString("\n") { "new $it" } + "\n"
        val rewrite = FileEdit("/repo/App.kt", "Write", null).apply {
            ops.add(EditOp(null, null, after, false))
            resolve(after)
        }
        rewrite.beforeText = before

        val preview = EditPreview.of(rewrite)

        assertTrue("capped", preview.size <= EditPreview.MAX_LINES + 1)
        assertEquals("and says it was cut", DiffLine.Kind.GAP, preview.last().kind)
    }

    fun testAnUnresolvedEditPreviewsNothing() {
        // Nothing to show before the turn has finished writing the file.
        val unresolved = FileEdit("/repo/App.kt", "Edit", null).apply {
            ops.add(EditOp("a", "b", null, false))
        }

        assertTrue(EditPreview.of(unresolved).isEmpty())
    }
}
