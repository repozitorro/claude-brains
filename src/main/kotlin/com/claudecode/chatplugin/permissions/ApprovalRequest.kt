package com.claudecode.chatplugin.permissions

import com.google.gson.JsonObject
import java.util.concurrent.CompletableFuture

/**
 * One tool call the CLI has stopped on, waiting to be told what to do.
 *
 * Unlike the after-the-fact offer this replaces, nothing has happened yet: the
 * CLI is holding the call open until [future] completes. That is the whole
 * point — and also the obligation. Every one of these must be completed, on
 * every path, or the turn waits forever.
 */
class ApprovalRequest(
    val toolName: String,
    val input: JsonObject,
    val toolUseId: String?,
    val summary: ApprovalSummary
) {
    val future: CompletableFuture<ApprovalDecision> = CompletableFuture()

    /** True once answered — by the user, by a timeout, or by the turn ending. */
    val isDecided: Boolean get() = future.isDone

    /** What was decided, for a card that is now a record rather than a question. */
    @Volatile
    var decision: ApprovalDecision? = null
        private set

    /**
     * Called once this is answered, however it was answered.
     *
     * Set by whoever put the card on screen. Most answers are clicks, and those
     * redraw themselves — but a turn that ends, a chat that closes and the
     * backstop timeout all decide this from somewhere else, and a card still
     * offering **Run** for a CLI that has stopped listening is worse than no
     * card at all.
     */
    @Volatile
    var onDecided: (() -> Unit)? = null

    /** First answer wins; later ones are ignored rather than racing. */
    fun decide(decision: ApprovalDecision): Boolean {
        if (future.isDone) return false
        this.decision = decision
        val completed = future.complete(decision)
        if (completed) runCatching { onDecided?.invoke() }
        return completed
    }
}

/**
 * The answer, in the shape the CLI asked for it:
 * `{behavior: 'allow', updatedInput?: object}` or
 * `{behavior: 'deny', message: string}` — verified against CLI 2.1.232.
 */
sealed class ApprovalDecision {

    /** Run it. [updatedInput] echoes the arguments back, unchanged. */
    data class Allow(val updatedInput: JsonObject?, val remembered: Boolean = false) : ApprovalDecision()

    /**
     * Don't. [message] is shown to the model, not to the user, so it says what
     * happened rather than telling it what to do instead — the user is right
     * there and can say that themselves.
     */
    data class Deny(val message: String) : ApprovalDecision()
}
