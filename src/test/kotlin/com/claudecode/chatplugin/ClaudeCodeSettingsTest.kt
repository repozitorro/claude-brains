package com.claudecode.chatplugin

import com.claudecode.chatplugin.cli.ClaudeCommandBuilder
import com.claudecode.chatplugin.cli.TurnRequest
import com.claudecode.chatplugin.model.PermissionChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * What a chat runs as before anyone touches a setting.
 *
 * The permission mode is the one default that decides whether the plugin can do
 * its job at all: with no flag the CLI falls back to asking, and asking needs a
 * terminal this chat does not have — so the tool is refused and nothing gets
 * edited. Out of the box that looked like Claude simply not working.
 */
class ClaudeCodeSettingsTest {

    private val defaults = ClaudeCodeSettings.State()

    @Test
    fun `a fresh install accepts edits rather than being unable to make them`() {
        assertEquals("acceptEdits", defaults.permissionMode)
    }

    @Test
    fun `the default reaches the command line`() {
        // The chat has no opinion until someone picks one, so the project
        // setting is what actually gets passed.
        val command = ClaudeCommandBuilder.build(
            TurnRequest(
                claudeCommand = "claude",
                sessionPermissionMode = null,
                projectPermissionMode = defaults.permissionMode
            )
        )

        val index = command.indexOf("--permission-mode")
        assertEquals("acceptEdits", command.getOrNull(index + 1))
    }

    @Test
    fun `a chat that picks the CLI's own rules is not overruled by the project default`() {
        // The regression this default introduces if the two "no mode" answers
        // are stored the same way: "hasn't chosen" falls back to the project,
        // and "chose the CLI's own rules" must not.
        val command = ClaudeCommandBuilder.build(
            TurnRequest(
                claudeCommand = "claude",
                sessionPermissionMode = PermissionChoice.INHERIT.id,
                projectPermissionMode = defaults.permissionMode
            )
        )

        assertFalse("the chat's own choice must win", command.contains("--permission-mode"))
        assertSame(PermissionChoice.INHERIT, PermissionChoice.forId(PermissionChoice.INHERIT.id))
    }

    @Test
    fun `the accept-edits entry is the one the default names`() {
        // A default pointing at an id the dropdown doesn't list would show as
        // something else entirely once a chat was restored.
        assertSame(PermissionChoice.ACCEPT_EDITS, PermissionChoice.forId(defaults.permissionMode))
        assertEquals("acceptEdits", PermissionChoice.ACCEPT_EDITS.id)
    }

    @Test
    fun `the other defaults still leave the CLI alone`() {
        // Model and tool policy are genuinely "no opinion" — an empty value
        // means the flag is omitted, which is not the same as passing a blank.
        assertEquals("claude", defaults.claudeCommand)
        assertEquals("", defaults.defaultModel)
        assertEquals("", defaults.allowedTools)
        assertEquals("", defaults.disallowedTools)
    }
}
