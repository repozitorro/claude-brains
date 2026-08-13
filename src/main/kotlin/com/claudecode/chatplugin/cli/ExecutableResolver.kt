package com.claudecode.chatplugin.cli

import com.intellij.openapi.util.SystemInfo
import java.io.File

/**
 * Finds what to actually launch for a bare command name on Windows.
 *
 * `npm install -g @anthropic-ai/claude-code` puts `claude.cmd` on the PATH, and
 * `CreateProcess` — which is what ProcessBuilder ends up calling — does not
 * apply `PATHEXT`. It appends `.exe` and nothing else, so plain "claude" is not
 * found and the plugin reports the CLI as missing on a machine where it is
 * installed and works in every terminal. It is the most common way this plugin
 * looks broken when it isn't.
 *
 * Anything already carrying a path or an extension is left alone: that is the
 * user having told us exactly what to run.
 */
object ExecutableResolver {

    /** Extensions Windows will actually execute, in the order npm-style shims use them. */
    private val WINDOWS_EXTENSIONS = listOf(".cmd", ".bat", ".exe", ".com")

    fun resolve(command: String): String = resolve(
        command = command,
        isWindows = SystemInfo.isWindows,
        pathEntries = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() },
        exists = { path -> File(path).isFile }
    )

    /**
     * The rule itself, with the platform, the PATH and the file system handed
     * in so it can be tested away from all three.
     *
     * Returns [command] unchanged when there is nothing to add — including when
     * no candidate exists, so the caller still fails with its own message about
     * the CLI not being installed rather than something more confusing.
     */
    internal fun resolve(
        command: String,
        isWindows: Boolean,
        pathEntries: List<String>,
        exists: (String) -> Boolean
    ): String {
        if (!isWindows) return command
        if (command.isBlank()) return command
        // A path, or a name that already says what kind of file it is.
        if (command.contains('/') || command.contains('\\')) return command
        if (File(command).extension.isNotEmpty()) return command

        for (entry in pathEntries) {
            for (extension in WINDOWS_EXTENSIONS) {
                // A literal backslash, not File.separator: this branch only ever
                // describes Windows paths, so the separator of whatever machine
                // is running the code is irrelevant — and wrong the moment the
                // rule is exercised from anywhere else, which is exactly what
                // the tests do.
                val candidate = entry.trimEnd('/', '\\') + '\\' + command + extension
                if (exists(candidate)) return candidate
            }
        }
        return command
    }
}
