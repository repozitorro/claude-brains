package com.claudecode.chatplugin.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * Hands a command to the IDE's own terminal.
 *
 * This exists because of where the plugin sits. The CLI runs its tools inside
 * its own process, so a command it was refused permission for never passes
 * through here and cannot be approved on its way past — there is nothing to
 * intercept. What can be done is give the command back to the user in a shell
 * that is already open, in the right directory, with nothing to retype.
 *
 * The terminal is a bundled plugin and can be switched off, so it is an
 * optional dependency: every reference to it is confined to the two methods
 * below, and losing it costs this one button rather than the chat.
 */
object TerminalRunner {

    private val LOG = Logger.getInstance(TerminalRunner::class.java)

    fun isAvailable(): Boolean = try {
        Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager")
        true
    } catch (e: Throwable) {
        LOG.info("Terminal plugin unavailable, not offering to run commands: $e")
        false
    }

    /**
     * Opens a shell in the project directory and runs [command] in it.
     *
     * Returns false when the terminal could not be used, so the caller can fall
     * back to something the user can still act on rather than a dead button.
     */
    fun run(project: Project, command: String, tabName: String = "Claude"): Boolean {
        if (!isAvailable()) return false
        return try {
            val manager = org.jetbrains.plugins.terminal.TerminalToolWindowManager.getInstance(project)
            // Deprecated as of 2026.2 and knowingly kept: whatever replaces it
            // arrived after 2024.1, which is the floor sinceBuild promises, so
            // reaching for it would compile here and fail there. Revisit when
            // the floor moves — the verifier will keep reporting it until then.
            @Suppress("DEPRECATION")
            val widget = manager.createShellWidget(project.basePath, tabName, true, true)
            val shell = org.jetbrains.plugins.terminal.ShellTerminalWidget.asShellJediTermWidget(widget)
            if (shell == null) {
                LOG.info("Terminal widget is not a shell widget; cannot run the command")
                return false
            }
            shell.executeCommand(command)
            true
        } catch (e: Throwable) {
            LOG.warn("Could not run the command in the IDE terminal", e)
            false
        }
    }
}
