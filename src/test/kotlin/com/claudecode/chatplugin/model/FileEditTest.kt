package com.claudecode.chatplugin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the race-free before/after reconstruction that powers the
 * diff/revert feature. These lock in the behaviour validated against the real
 * Claude Code CLI (a single Edit's reconstructed "before" equalled the original
 * file, and forward-replay equalled the on-disk result).
 */
class FileEditTest {

    private fun edit(path: String, old: String, new: String, replaceAll: Boolean = false, snapshot: String? = null) =
        FileEdit(path, "Edit", snapshot).apply { ops.add(EditOp(old, new, null, replaceAll)) }

    @Test
    fun `single Edit reconstructs original and is revertible`() {
        val before = "return \"Hello, \" + name;"
        val after = "return \"Hi there, \" + name;"
        val e = edit("a.kt", "Hello, ", "Hi there, ")

        e.resolve(after)

        assertEquals(before, e.beforeText)
        assertEquals(after, e.afterText)
        assertTrue(e.canRevert)
    }

    @Test
    fun `replace_all reverses every occurrence`() {
        val before = "foo(); foo(); foo();"
        val after = "bar(); bar(); bar();"
        val e = edit("a.kt", "foo", "bar", replaceAll = true)

        e.resolve(after)

        assertEquals(before, e.beforeText)
        assertTrue(e.canRevert)
    }

    @Test
    fun `MultiEdit applies ops in order and reverses them`() {
        val before = "let a = 1;\nlet b = 2;\n"
        val after = "const a = 10;\nconst b = 2;\n"
        val e = FileEdit("a.kt", "MultiEdit", null).apply {
            ops.add(EditOp("let a = 1;", "const a = 10;", null, false))
            ops.add(EditOp("let b", "const b", null, false))
        }

        e.resolve(after)

        assertEquals(before, e.beforeText)
        assertTrue(e.canRevert)
    }

    @Test
    fun `ambiguous reconstruction disables revert but still diffs`() {
        // new_string ("x") already exists earlier in the file, so reversing the
        // first occurrence yields the wrong "before" — revert must be refused.
        val after = "x = 1;\ny = x;"
        val e = edit("a.kt", "y", "x") // claims it turned some "y" into "x"

        e.resolve(after)

        assertTrue(e.isResolved)       // diff is still available
        assertFalse(e.canRevert)       // but reverting is not, because it can't be proven exact
    }

    @Test
    fun `Write uses the snapshot as before`() {
        val snapshot = "old file contents"
        val after = "brand new contents"
        val e = FileEdit("a.kt", "Write", snapshot).apply {
            ops.add(EditOp(null, null, after, false))
        }

        e.resolve(after)

        assertEquals(snapshot, e.beforeText)
        assertTrue(e.canRevert) // forward-replay of a Write always yields its content
    }

    @Test
    fun `missing after content falls back to snapshot and is not revertible`() {
        val e = edit("a.kt", "a", "b", snapshot = "snap")
        e.resolve(null)
        assertEquals("snap", e.beforeText)
        assertFalse(e.canRevert)
    }
}
