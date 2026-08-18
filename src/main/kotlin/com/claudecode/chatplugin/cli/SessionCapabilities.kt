package com.claudecode.chatplugin.cli

import com.google.gson.JsonObject

/**
 * What this CLI can actually do, as it says so itself.
 *
 * The `init` event names the slash commands, skills and agents available to the
 * session that just started — including everything the user has installed,
 * which no list written here could know about. The plugin used to carry a
 * hardcoded list of seventeen commands with a comment admitting it would drift.
 * It had: it knew nothing of the eighteen skills on this machine.
 *
 * Read once per turn and kept on the session, so the "/" popup and the agent
 * selector describe the CLI in front of the user rather than the one that was
 * current when the list was typed.
 */
data class SessionCapabilities(
    val slashCommands: List<String> = emptyList(),
    /** Skills are invoked as slash commands too, and listed separately by the CLI. */
    val skills: List<String> = emptyList(),
    val agents: List<String> = emptyList()
) {

    val isEmpty: Boolean get() = slashCommands.isEmpty() && skills.isEmpty() && agents.isEmpty()

    /**
     * Every name the "/" popup should offer, skills included, without repeats.
     *
     * Skills and commands share one namespace at the prompt, and a skill that
     * is also listed as a command should appear once.
     */
    fun allSlashNames(): List<String> = (slashCommands + skills)
        .map { it.removePrefix("/") }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()

    companion object {

        /** Reads what the `init` event carries; missing fields simply stay empty. */
        fun from(init: JsonObject): SessionCapabilities = SessionCapabilities(
            slashCommands = strings(init, "slash_commands"),
            skills = strings(init, "skills"),
            agents = strings(init, "agents")
        )

        /**
         * Strings only, and `isJsonPrimitive` is not that test: Gson counts a
         * number as one, and `asString` turns 42 into "42", which would then be
         * offered at the prompt as `/42`.
         */
        private fun strings(json: JsonObject, key: String): List<String> =
            json.getAsJsonArray(key)
                ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive && e.asJsonPrimitive.isString }?.asString }
                ?.filter { it.isNotBlank() }
                .orEmpty()
    }
}
