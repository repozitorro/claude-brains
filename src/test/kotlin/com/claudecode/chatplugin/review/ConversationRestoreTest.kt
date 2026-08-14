package com.claudecode.chatplugin.review

import com.claudecode.chatplugin.model.ChatMessage
import com.claudecode.chatplugin.model.EditOp
import com.claudecode.chatplugin.model.FileEdit
import com.claudecode.chatplugin.model.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Going back to how things were several messages ago.
 *
 * The order is the whole problem. Later edits are written on top of earlier
 * ones, so undoing them in the order they happened would restore text that was
 * never on disk — the second undo would be reversing a change that the first
 * one had already taken away.
 */
class ConversationRestoreTest {

    /** A resolved, revertible edit: [old] became [new] in [name]. */
    private fun edit(name: String, old: String, new: String): FileEdit =
        FileEdit("/repo/$name", "Edit", null).apply {
            ops.add(EditOp(old, new, null, false))
            resolve(new)
            assertTrue("test setup: must be revertible", canRevert)
        }

    private fun turn(vararg edits: FileEdit) =
        ChatMessage(Role.ASSISTANT, "done", edits = edits.toMutableList())

    private val conversation = listOf(
        ChatMessage(Role.USER, "first"),
        turn(edit("A.kt", "1", "2")),
        ChatMessage(Role.USER, "second"),
        turn(edit("A.kt", "2", "3"), edit("B.kt", "x", "y"))
    )

    @Test
    fun `undoing runs newest first`() {
        val edits = ConversationRestore.editsToRevert(conversation, fromIndex = 1)

        // Three edits, and the one made last is undone first.
        assertEquals(3, edits.size)
        assertEquals("y", edits.first().ops.single().newString)
        assertEquals("2", edits.last().ops.single().newString)
    }

    @Test
    fun `restoring from a later point leaves earlier work alone`() {
        val edits = ConversationRestore.editsToRevert(conversation, fromIndex = 3)

        assertEquals("only the last turn", 2, edits.size)
        assertTrue(edits.none { it.ops.single().newString == "2" })
    }

    @Test
    fun `an index past the end has nothing to undo`() {
        assertTrue(ConversationRestore.editsToRevert(conversation, fromIndex = 99).isEmpty())
        assertTrue(ConversationRestore.editsToRevert(conversation, fromIndex = -1).isEmpty())
    }

    @Test
    fun `each file is named once, however many times it was edited`() {
        // The question asked before rewriting files should list files, not edits.
        val files = ConversationRestore.affectedFiles(ConversationRestore.editsToRevert(conversation, 1))

        assertEquals(listOf("B.kt", "A.kt"), files)
    }

    @Test
    fun `an edit that cannot be undone exactly is not counted as restorable`() {
        // new_string already present elsewhere, so reversing it is guesswork —
        // and guessing here would write something that was never in the file.
        val ambiguous = FileEdit("/repo/C.kt", "Edit", null).apply {
            ops.add(EditOp("y", "x", null, false))
            resolve("x = 1;\ny = x;")
            assertFalse("test setup: must not be revertible", canRevert)
        }
        val messages = listOf(turn(ambiguous))

        assertFalse(ConversationRestore.hasSomethingToRestore(messages, 0))
        assertTrue(ConversationRestore.affectedFiles(ConversationRestore.editsToRevert(messages, 0)).isEmpty())
    }
}
