package com.claudecode.chatplugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the stale-`--resume` detection. This decides when a turn is silently
 * retried without the stored session id, so it must match the CLI's real
 * "unknown session" message and nothing else — a looser pattern would throw
 * away conversation context on unrelated failures.
 */
class ClaudeCliServiceTest {

    @Test
    fun `recognises the CLI's unknown-session message`() {
        // Captured from Claude Code CLI 2.1.205 (exit 1, stderr).
        val stderr = "No conversation found with session ID: 00000000-dead-beef-0000-000000000000"
        assertTrue(ClaudeCliService.STALE_SESSION.containsMatchIn(stderr))
    }

    @Test
    fun `does not treat an expired login as a stale session`() {
        // Also captured live: retrying this without --resume would lose context
        // for nothing, since re-authentication is what's actually needed.
        val authError = "Failed to authenticate. API Error: 401 OAuth access token has expired. " +
            "Re-authenticate to continue."
        assertFalse(ClaudeCliService.STALE_SESSION.containsMatchIn(authError))
    }

    @Test
    fun `does not match unrelated failures`() {
        listOf(
            "claude: command not found",
            "Error: ENOENT: no such file or directory",
            "Permission denied",
            "No such session file"
        ).forEach { assertFalse(it, ClaudeCliService.STALE_SESSION.containsMatchIn(it)) }
    }
}
