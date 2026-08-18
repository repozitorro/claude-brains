package com.claudecode.chatplugin.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the flags a turn is launched with.
 *
 * An omitted flag and an empty one mean different things to the CLI: omitting
 * `--permission-mode` leaves it on whatever the user configured, while passing
 * a blank one is an error. Same for `--model` and the tool lists. None of that
 * is visible from the chat, so it is pinned here.
 */
class ClaudeCommandBuilderTest {

    private fun build(request: TurnRequest) = ClaudeCommandBuilder.build(request)

    private val minimal = TurnRequest(claudeCommand = "claude")

    /** The value passed after [flag], or null when the flag is absent. */
    private fun List<String>.valueOf(flag: String): String? =
        indexOf(flag).takeIf { it >= 0 && it + 1 < size }?.let { this[it + 1] }

    @Test
    fun `a minimal turn asks for the streaming protocol and nothing else`() {
        val command = build(minimal)

        assertEquals("claude", command.first())
        assertTrue("print mode is what makes it non-interactive", command.contains("-p"))
        assertEquals("stream-json", command.valueOf("--output-format"))
        // Both are required for token-by-token deltas to arrive at all.
        assertTrue(command.contains("--verbose"))
        assertTrue(command.contains("--include-partial-messages"))

        listOf("--permission-mode", "--allowedTools", "--disallowedTools", "--resume", "--model")
            .forEach { assertFalse("$it should be omitted", command.contains(it)) }
    }

    @Test
    fun `the chat's own permission mode wins over the project's`() {
        val command = build(minimal.copy(sessionPermissionMode = "plan", projectPermissionMode = "acceptEdits"))
        assertEquals("plan", command.valueOf("--permission-mode"))
    }

    @Test
    fun `the project setting applies when the chat has no opinion`() {
        val command = build(minimal.copy(sessionPermissionMode = null, projectPermissionMode = "acceptEdits"))
        assertEquals("acceptEdits", command.valueOf("--permission-mode"))
    }

    @Test
    fun `neither set means no flag at all, so the CLI keeps its own default`() {
        assertNull(ClaudeCommandBuilder.permissionMode(minimal))
        assertFalse(build(minimal.copy(projectPermissionMode = "   ")).contains("--permission-mode"))
    }

    @Test
    fun `tool policies are passed only when non-blank, and trimmed`() {
        val command = build(minimal.copy(allowedTools = "  Read Edit  ", disallowedTools = "   "))

        assertEquals("Read Edit", command.valueOf("--allowedTools"))
        assertFalse(command.contains("--disallowedTools"))
    }

    @Test
    fun `a stored session is resumed, and a fresh turn is not`() {
        assertEquals("abc-123", build(minimal.copy(resumeId = "abc-123")).valueOf("--resume"))
        assertFalse(build(minimal.copy(resumeId = null)).contains("--resume"))
    }

    @Test
    fun `the model id is passed verbatim, alias or pinned`() {
        assertEquals("claude-opus-5", build(minimal.copy(model = "claude-opus-5")).valueOf("--model"))
        assertEquals("opus", build(minimal.copy(model = "opus")).valueOf("--model"))
    }

    @Test
    fun `an approval endpoint is named as an MCP server and as the prompt tool`() {
        // Both halves or neither: --permission-prompt-tool is refused unless it
        // names a tool the CLI can actually see, which means the server has to
        // be configured in the same breath.
        val command = build(minimal.copy(approvalConfigPath = "C:\\tmp\\approvals.json"))
        assertEquals("C:\\tmp\\approvals.json", command.valueOf("--mcp-config"))
        assertEquals(
            com.claudecode.chatplugin.permissions.McpApprovalProtocol.QUALIFIED_TOOL_NAME,
            command.valueOf("--permission-prompt-tool")
        )
    }

    @Test
    fun `a mode that exists to not ask is not given something to ask with`() {
        // Picking Auto and then being stopped mid-turn is the mode failing at
        // the one thing it is for. Same for Don't ask, and bypass has nothing
        // left to ask about.
        listOf("auto", "dontAsk", "bypassPermissions").forEach { mode ->
            val command = build(
                minimal.copy(sessionPermissionMode = mode, approvalConfigPath = "C:\\tmp\\approvals.json")
            )
            assertFalse("$mode should not be handed a prompt tool", command.contains("--permission-prompt-tool"))
            assertFalse("$mode should not be handed the server either", command.contains("--mcp-config"))
            // The mode itself still reaches the CLI — this is about who answers.
            assertEquals(mode, command.valueOf("--permission-mode"))
        }
    }

    @Test
    fun `the modes that ask are given something to ask with`() {
        listOf("acceptEdits", "plan", "manual", "").forEach { mode ->
            val command = build(
                minimal.copy(sessionPermissionMode = mode, approvalConfigPath = "C:\\tmp\\approvals.json")
            )
            assertTrue("$mode should be able to ask", command.contains("--permission-prompt-tool"))
        }
    }

    @Test
    fun `the project setting decides it when the chat has not chosen`() {
        val command = build(
            minimal.copy(
                sessionPermissionMode = null,
                projectPermissionMode = "auto",
                approvalConfigPath = "C:\\tmp\\approvals.json"
            )
        )
        assertFalse(command.contains("--permission-prompt-tool"))
    }

    @Test
    fun `no endpoint means the CLI goes back to deciding alone`() {
        val command = build(minimal.copy(approvalConfigPath = null))
        assertFalse(command.contains("--permission-prompt-tool"))
        assertFalse(command.contains("--mcp-config"))
    }

    @Test
    fun `agent and effort are passed when chosen, and omitted when not`() {
        val chosen = build(minimal.copy(agent = "Explore", effort = "high"))
        assertEquals("Explore", chosen.valueOf("--agent"))
        assertEquals("high", chosen.valueOf("--effort"))

        // Blank is not a choice the CLI can act on, and an omitted flag leaves
        // it on its own default — which is the point of the "Default" entry.
        val neither = build(minimal.copy(agent = "", effort = null))
        assertFalse(neither.contains("--agent"))
        assertFalse(neither.contains("--effort"))
    }

    @Test
    fun `a spending cap is passed only when one is set`() {
        assertEquals("2.50", build(minimal.copy(maxBudgetUsd = "2.50")).valueOf("--max-budget-usd"))
        assertFalse(build(minimal.copy(maxBudgetUsd = "")).contains("--max-budget-usd"))
    }

    @Test
    fun `each extra directory gets its own flag`() {
        // Joined into one argument, a path containing a space would arrive as
        // two directories, neither of which exists.
        val command = build(minimal.copy(extraDirs = listOf("D:\\Work\\other", "C:\\My Things\\lib", "")))
        val dirs = command.withIndex().filter { it.value == "--add-dir" }.map { command[it.index + 1] }
        assertEquals(listOf("D:\\Work\\other", "C:\\My Things\\lib"), dirs)
    }

    @Test
    fun `forking is only said where it means something`() {
        // The flag is documented for use with --resume; on a fresh turn there
        // is nothing to fork from and it would just be noise.
        assertTrue(build(minimal.copy(resumeId = "abc", forkSession = true)).contains("--fork-session"))
        assertFalse(build(minimal.copy(resumeId = null, forkSession = true)).contains("--fork-session"))
        assertFalse(build(minimal.copy(resumeId = "abc", forkSession = false)).contains("--fork-session"))
    }

    @Test
    fun `the prompt never reaches the command line at all`() {
        // It goes to the process on stdin. As an argument it was bounded by the
        // command line's own limit — about 32k for everything together on
        // Windows — which a pasted log can exceed, and it had to survive
        // quoting on the way. `-p` is passed bare, with no value after it.
        val command = build(minimal)

        val printIndex = command.indexOf("-p")
        assertEquals("--output-format", command.getOrNull(printIndex + 1))
    }
}
