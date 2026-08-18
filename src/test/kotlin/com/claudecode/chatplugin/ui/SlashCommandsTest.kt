package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.cli.SessionCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the "/" popup offers, and where it got it.
 *
 * The built-in list is a fallback for the first turn of a fresh chat. After
 * that the CLI has said what it actually has — including skills and anything
 * the user wrote themselves — and that is what should be on offer.
 */
class SlashCommandsTest {

    private val live = SessionCapabilities(
        slashCommands = listOf("doctor", "my-own-command"),
        skills = listOf("code-review")
    )

    @Test
    fun `before a turn has reported anything, the built-in list stands`() {
        val matches = SlashCommands.matching("/co", capabilities = null)
        assertTrue(matches.any { it.name == "/compact" })
        assertTrue(matches.any { it.name == "/cost" })
    }

    @Test
    fun `once the CLI has reported, its list is what is offered`() {
        val matches = SlashCommands.matching("/", live).map { it.name }
        assertEquals(listOf("/code-review", "/doctor", "/my-own-command"), matches)
    }

    @Test
    fun `a built-in description survives a live name`() {
        // The CLI sends names only, and "doctor" alone says less than the
        // sentence already written for it.
        val doctor = SlashCommands.matching("/doctor", live).single()
        assertEquals("Diagnose the Claude Code installation", doctor.description)
    }

    @Test
    fun `a name with nothing written about it still says what it is`() {
        assertEquals("Skill", SlashCommands.matching("/code-review", live).single().description)
        assertEquals("Command from your CLI", SlashCommands.matching("/my-own", live).single().description)
    }

    @Test
    fun `text that is not a command matches nothing`() {
        assertTrue(SlashCommands.matching("doctor", live).isEmpty())
        assertTrue(SlashCommands.matching("", live).isEmpty())
    }

    @Test
    fun `a command the CLI no longer has is not offered`() {
        // The old list had /vim. If this CLI does not report it, offering it
        // would be the drift this change exists to end.
        assertTrue(SlashCommands.matching("/vim", live).isEmpty())
    }
}
