package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.cli.TurnResult
import com.claudecode.chatplugin.model.FileEdit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a turn says for itself when it ends.
 *
 * This is the half of the stream listener that can be wrong on its own. A
 * failed turn that says nothing shows an empty answer and no reason; a
 * conflict warning that omits "nothing of yours was lost" reads as data loss.
 * None of it was reachable by a test while it lived inside an anonymous
 * listener in a 1600-line panel.
 */
class TurnOutcomeTest {

    private fun result(
        isError: Boolean = false,
        message: String? = null,
        status: Int? = null
    ) = TurnResult(
        isError = isError,
        costUsd = null,
        inputTokens = null,
        outputTokens = null,
        durationMs = null,
        errorMessage = message,
        apiErrorStatus = status
    )

    private fun edit(name: String) =
        FileEdit(filePath = "D:\\p\\$name", toolName = "Edit", snapshotBefore = null)

    @Test
    fun `a turn that went well says nothing`() {
        assertNull(TurnOutcome.errorText(result(), replyIsEmpty = false))
    }

    @Test
    fun `a failure always gives a reason, even when it brought none`() {
        // The result event can be an error with no text at all, and "**Error:**"
        // followed by nothing is worse than a plain sentence.
        val text = TurnOutcome.errorText(result(isError = true), replyIsEmpty = true)!!
        assertTrue(text.contains("the turn ended with an error"))
    }

    @Test
    fun `the reason it gave is the one shown`() {
        val text = TurnOutcome.errorText(result(isError = true, message = "credit exhausted"), true)!!
        assertTrue(text.contains("credit exhausted"))
    }

    @Test
    fun `an error after a partial reply is separated from it`() {
        assertTrue(TurnOutcome.errorText(result(isError = true), replyIsEmpty = false)!!.startsWith("\n\n"))
        assertFalse(TurnOutcome.errorText(result(isError = true), replyIsEmpty = true)!!.startsWith("\n"))
    }

    @Test
    fun `being signed out points at the banner instead of the error`() {
        val signedOut = result(isError = true, message = "401 unauthorized", status = 401)
        assertTrue(TurnOutcome.needsSignIn(signedOut))
        assertTrue(TurnOutcome.errorText(signedOut, true)!!.contains("Sign in again"))

        // Any other failure has no such next step, and inventing one wastes the
        // one line the user reads.
        val other = result(isError = true, message = "overloaded", status = 529)
        assertFalse(TurnOutcome.needsSignIn(other))
        assertFalse(TurnOutcome.errorText(other, true)!!.contains("Sign in again"))
    }

    @Test
    fun `a success with a 401 on the record is not a sign-in problem`() {
        assertFalse(TurnOutcome.needsSignIn(result(isError = false, status = 401)))
    }

    @Test
    fun `the conflict warning says the user's work is safe`() {
        // This is the whole point of it. Named files, and then the reassurance,
        // because the first thing anyone wants to know is what they just lost.
        val text = TurnOutcome.conflictedMessage(listOf(edit("App.kt"), edit("Main.kt")))
        assertTrue(text.contains("App.kt"))
        assertTrue(text.contains("Main.kt"))
        assertTrue(text.contains("nothing of yours was lost"))
    }

    @Test
    fun `the unreviewable warning says what to do instead`() {
        val text = TurnOutcome.unreviewableMessage(listOf(edit("App.kt")))
        assertTrue(text.contains("App.kt"))
        assertTrue(text.contains("**diff**"))
    }
}
