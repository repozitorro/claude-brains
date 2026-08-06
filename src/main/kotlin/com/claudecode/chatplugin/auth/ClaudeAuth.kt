package com.claudecode.chatplugin.auth

import com.claudecode.chatplugin.ClaudeCodeSettings
import com.claudecode.chatplugin.cli.CliRunner
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo

/**
 * Reads the CLI's sign-in state and hands off signing in to the CLI itself.
 *
 * Deliberately narrow: this never asks for, reads, or stores a password, token
 * or authorisation code. `claude auth login` runs in a terminal the user
 * controls, completes its own browser flow there, and stores its own
 * credentials — the plugin only asks afterwards whether it worked.
 */
@Service(Service.Level.PROJECT)
class ClaudeAuth(private val project: Project) {

    private val log = Logger.getInstance(ClaudeAuth::class.java)

    private val command: String
        get() = ClaudeCodeSettings.getInstance(project).claudeCommand

    /** Runs `claude auth status`. Blocking (a few seconds at most); call off the EDT. */
    fun status(): AuthStatus {
        val exe = command
        val result = CliRunner.run(
            command = listOf(exe, "auth", "status"),
            workingDir = project.basePath?.let { java.io.File(it) },
            timeoutSeconds = STATUS_TIMEOUT_SECONDS
        )
        return when {
            result.failure != null -> AuthStatus.Unavailable(
                "Could not run '$exe'. Install the Claude Code CLI, or set its full path in " +
                    "Settings → Tools → Claude Brains. (${result.failure.message})"
            )
            result.timedOut ->
                AuthStatus.Unavailable("`$exe auth status` did not finish in ${STATUS_TIMEOUT_SECONDS}s")
            else -> AuthStatus.parse(result.output)
        }
    }

    /** The command a user runs themselves to sign in. */
    fun signInCommand(): String = "$command auth login"

    /**
     * Opens a terminal window running the sign-in command, so the OAuth flow
     * happens in the user's own shell. Returns false if no terminal could be
     * launched, in which case the UI falls back to showing the command to copy.
     */
    fun launchSignIn(): Boolean {
        val exe = command
        val commandLine = when {
            SystemInfo.isWindows ->
                GeneralCommandLine("cmd.exe", "/c", "start", "Claude Code sign-in", "cmd", "/k", exe, "auth", "login")
            SystemInfo.isMac ->
                GeneralCommandLine(
                    "osascript", "-e",
                    """tell application "Terminal" to do script "$exe auth login"""",
                    "-e", """tell application "Terminal" to activate"""
                )
            else -> linuxTerminal(exe) ?: return false
        }
        return try {
            commandLine.withWorkDirectory(project.basePath).createProcess()
            true
        } catch (e: Exception) {
            log.warn("Could not open a terminal for sign-in", e)
            false
        }
    }

    /** Picks whichever terminal emulator is actually installed. */
    private fun linuxTerminal(exe: String): GeneralCommandLine? {
        val candidates = listOf(
            listOf("x-terminal-emulator", "-e", exe, "auth", "login"),
            listOf("gnome-terminal", "--", exe, "auth", "login"),
            listOf("konsole", "-e", exe, "auth", "login"),
            listOf("xterm", "-e", "$exe auth login")
        )
        return candidates.firstOrNull { which(it.first()) }?.let { GeneralCommandLine(it) }
    }

    // `succeeded` is exit code 0 *and* not timed out — a lookup that had to be
    // killed must not read as "this terminal exists".
    private fun which(tool: String): Boolean =
        CliRunner.run(listOf("which", tool), timeoutSeconds = WHICH_TIMEOUT_SECONDS).succeeded

    companion object {
        private const val STATUS_TIMEOUT_SECONDS = 20L
        private const val WHICH_TIMEOUT_SECONDS = 3L

        fun getInstance(project: Project): ClaudeAuth = project.getService(ClaudeAuth::class.java)
    }
}
