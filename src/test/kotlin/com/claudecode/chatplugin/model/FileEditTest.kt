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
    fun `a CRLF file edited with newline-separated ops is still reconstructable`() {
        // The real-world case this guards: Windows projects are CRLF, but the
        // CLI always sends old_string / new_string with plain newlines. Matching
        // them against the raw file text never succeeds, which silently turned
        // off both inline review and revert for practically every Windows file.
        // What the CLI left on disk: CRLF throughout, comment added.
        val afterOnDisk = "class A {\r\n  /** does b */\r\n  fun b() {}\r\n}\r\n"
        // What the CLI reported doing: the same edit, with plain newlines.
        val e = FileEdit("a.kt", "Edit", null).apply {
            ops.add(EditOp("fun b()", "/** does b */\n  fun b()", null, false))
        }

        e.resolve(afterOnDisk)

        assertTrue("a CRLF file must not disable review", e.canRevert)
        assertEquals("class A {\n  fun b() {}\n}\n", e.beforeText)
        assertEquals("\r\n", e.lineSeparator)
    }

    @Test
    fun `reverting a CRLF file writes CRLF back`() {
        val e = FileEdit("a.kt", "Edit", null).apply {
            ops.add(EditOp("old", "new", null, false))
        }
        e.resolve("a\r\nnew\r\nb\r\n")

        // Writing the internal text as-is would rewrite every line of the file.
        assertEquals("a\r\nold\r\nb\r\n", e.toFileText(e.beforeText!!))
    }

    @Test
    fun `a newline-separated file is left alone`() {
        val e = FileEdit("a.kt", "Edit", null).apply {
            ops.add(EditOp("old", "new", null, false))
        }
        e.resolve("a\nnew\nb\n")

        assertEquals("\n", e.lineSeparator)
        assertEquals("a\nold\nb\n", e.toFileText(e.beforeText!!))
    }

    @Test
    fun `missing after content falls back to snapshot and is not revertible`() {
        val e = edit("a.kt", "a", "b", snapshot = "snap")
        e.resolve(null)
        assertEquals("snap", e.beforeText)
        assertFalse(e.canRevert)
    }
}
