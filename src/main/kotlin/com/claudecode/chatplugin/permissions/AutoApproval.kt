package com.claudecode.chatplugin.permissions

import com.google.gson.JsonObject

/**
 * "Yes, and stop asking me about this."
 *
 * Being asked once is a safeguard; being asked the same thing eleven times is
 * an obstacle, and the eleventh answer is not a decision any more. So an
 * approval can be remembered — but only as narrowly as it can still be
 * described in the button that grants it.
 *
 * The unit is the *program*, not the command: allowing `npm run build` and then
 * being asked again about `npm test` teaches nothing, while a blanket "allow
 * everything this tool does" is not a decision anyone should make from a
 * one-line card. `npm` is the thing the user recognised and said yes to.
 *
 * Remembered per chat only. Anything meant to outlive the chat goes to Settings,
 * which is a different act in a different place.
 */
object AutoApproval {

    private val COMMAND_TOOLS = setOf("Bash", "PowerShell", "Shell", "Terminal")

    /**
     * The key an approval is remembered under, or null when this call is not
     * the kind of thing that can be generalised — a write to one path says
     * nothing about a write to the next one.
     */
    fun key(toolName: String, input: JsonObject?): String? {
        if (toolName !in COMMAND_TOOLS) return null
        val command = input?.get("command")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val program = program(command) ?: return null
        return "$toolName:$program"
    }

    /** What the button says it will do, e.g. `npm`. */
    fun label(toolName: String, input: JsonObject?): String? =
        key(toolName, input)?.substringAfter(':')

    /**
     * The first word, minus any path around it.
     *
     * A shell operator **anywhere** in the line disqualifies it: `cd d:\work;
     * rm -rf .` starts with `cd`, and remembering that as "always allow cd"
     * would be a promise about everything after the semicolon. The whole line
     * has to be one program's arguments for the button to be able to say what
     * it grants.
     */
    private fun program(command: String): String? {
        if (command.any { it in FORBIDDEN }) return null
        val first = command.trim().substringBefore(' ').trim('"', '\'')
        if (first.isEmpty()) return null
        return lastPathSegment(first).takeIf { it.isNotEmpty() }
    }

    private const val FORBIDDEN = "|&;<>(){}$`"
}
