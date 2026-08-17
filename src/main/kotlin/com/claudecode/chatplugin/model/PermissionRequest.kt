package com.claudecode.chatplugin.model

import com.claudecode.chatplugin.cli.PermissionDenial

/**
 * A refusal turned back into a question the user can answer, after the fact.
 *
 * This is the fallback now, not the main road. The CLI *can* ask — see
 * [com.claudecode.chatplugin.permissions.ApprovalService], which is the thing
 * it asks — but only when an endpoint was opened for the chat: the setting can
 * turn it off, the endpoint can fail to start, and `--permission-prompt-tool`
 * is undocumented enough that it may one day stop working. In any of those
 * cases the CLI goes back to deciding alone and reporting what it refused, and
 * this is how that refusal is put to the user.
 *
 * The turn has ended by then, so answering "yes" means granting the tool and
 * asking again — which from where the user sits is the same conversation
 * carrying on.
 */
data class PermissionRequest(
    /** What the CLI refused. */
    val denials: List<PermissionDenial>,
    /** The narrowest allowance that would let it through, e.g. `Bash(git *)`. */
    val pattern: String,
    /** The message to send again once permission is given. */
    val prompt: String,
    /**
     * The refused command, when one of the calls was a command at all.
     *
     * Offered separately from allowing it, because handing it to a shell is not
     * a permission at all: it is the user doing the thing themselves, which
     * needs no grant and changes no setting.
     */
    val command: String? = null,
    /** Set once answered, so the buttons stop offering a decision already made. */
    var answer: Answer? = null
) {
    enum class Answer { ALLOWED_HERE, ALLOWED_ALWAYS, DENIED }
}
