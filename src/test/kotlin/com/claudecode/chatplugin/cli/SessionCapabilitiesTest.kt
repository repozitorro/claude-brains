package com.claudecode.chatplugin.cli

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Letting the CLI say what it can do, instead of guessing.
 *
 * The plugin carried a hand-written list of seventeen slash commands, with a
 * comment admitting it would drift from whatever CLI was installed. It had: on
 * one machine the CLI reported eighteen skills, none of which were in it, and
 * any command the user had written themselves was invisible.
 *
 * The `init` event names all of them, every turn.
 */
class SessionCapabilitiesTest {

    private fun init(json: String) = SessionCapabilities.from(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `commands, skills and agents are read from the init event`() {
        val capabilities = init(
            """
            {"type":"system","subtype":"init",
             "slash_commands":["doctor","loop"],
             "skills":["code-review","simplify"],
             "agents":["Explore","Plan"]}
            """
        )
        assertEquals(listOf("doctor", "loop"), capabilities.slashCommands)
        assertEquals(listOf("code-review", "simplify"), capabilities.skills)
        assertEquals(listOf("Explore", "Plan"), capabilities.agents)
    }

    @Test
    fun `skills are offered at the prompt alongside commands`() {
        // They are typed the same way, so the popup should not care which list
        // a name came from.
        val capabilities = init("""{"slash_commands":["doctor"],"skills":["code-review"]}""")
        assertEquals(listOf("code-review", "doctor"), capabilities.allSlashNames())
    }

    @Test
    fun `a name in both lists is offered once`() {
        val capabilities = init("""{"slash_commands":["debug","doctor"],"skills":["debug"]}""")
        assertEquals(listOf("debug", "doctor"), capabilities.allSlashNames())
    }

    @Test
    fun `a leading slash is not doubled`() {
        // Reported without one here, but the field is the CLI's to change.
        assertEquals(listOf("doctor"), init("""{"slash_commands":["/doctor"]}""").allSlashNames())
    }

    @Test
    fun `an init event without any of this says nothing`() {
        val capabilities = init("""{"type":"system","subtype":"init","session_id":"abc"}""")
        assertTrue(capabilities.isEmpty)
        assertTrue(capabilities.allSlashNames().isEmpty())
    }

    @Test
    fun `entries that are not strings are skipped rather than crashing`() {
        val capabilities = init("""{"slash_commands":["doctor",null,42,""],"agents":[]}""")
        assertEquals(listOf("doctor"), capabilities.slashCommands)
    }
}
