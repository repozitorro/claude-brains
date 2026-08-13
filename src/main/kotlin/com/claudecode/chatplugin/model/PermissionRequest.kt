package com.claudecode.chatplugin.model

import com.claudecode.chatplugin.cli.PermissionDenial

/**
 * A refusal turned back into a question the user can answer.
 *
 * The CLI cannot ask: in print mode there is no terminal to answer in, and it
 * offers nothing for a host to answer on its behalf — no prompt tool, and no
 * request on the stream (checked against 2.1.223, not assumed). It decides
 * alone and reports what it refused.
 *
 * So the question is put afterwards instead. The turn has ended, the refusal is
 * known, and answering "yes" means granting the tool and asking again — which
 * from where the user sits is the same conversation carrying on.
 */
data class PermissionRequest(
    /** What the CLI refused. */
    val denials: List<PermissionDenial>,
    /** The narrowest allowance that would let it through, e.g. `Bash(git *)`. */
    val pattern: String,
    /** The message to send again once permission is given. */
    val prompt: String,
    /** Set once answered, so the buttons stop offering a decision already made. */
    var answer: Answer? = null
) {
    enum class Answer { ALLOWED_HERE, ALLOWED_ALWAYS, DENIED }
}
