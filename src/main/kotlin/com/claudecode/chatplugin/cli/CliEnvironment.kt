package com.claudecode.chatplugin.cli

import com.claudecode.chatplugin.ClaudeCodeSettings
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import java.io.File

/**
 * The environment a spawned CLI sees.
 *
 * A process started from the IDE inherits the IDE's environment, and that is
 * not the environment a terminal has. Two ways it differs, both of which end in
 * "command not found" for something that plainly works when typed by hand:
 *
 *  - On macOS and Linux a GUI-launched IDE never reads a login shell, so
 *    everything a profile adds to PATH is missing. The platform works this out
 *    for us — [EnvironmentUtil] is exactly that answer — and using it costs
 *    nothing on Windows, where the question does not arise.
 *  - A tool installed for the current user only (pip's `--user`, npm's prefix)
 *    puts its executable somewhere that was never added to PATH at all. No
 *    amount of asking the system helps there: the entry genuinely isn't there,
 *    and the fix belongs to whoever installed it — or to a setting here, so it
 *    can be fixed without editing the machine or restarting the IDE.
 */
object CliEnvironment {

    fun forProject(project: Project): Map<String, String> {
        val settings = ClaudeCodeSettings.getInstance(project)
        return build(
            base = EnvironmentUtil.getEnvironmentMap(),
            extraPath = settings.extraPath,
            extraEnv = settings.extraEnv,
            appended = detectedUserBins()
        )
    }

    internal fun build(
        base: Map<String, String>,
        extraPath: String,
        extraEnv: String,
        separator: Char = File.pathSeparatorChar,
        appended: List<String> = emptyList()
    ): Map<String, String> {
        val env = LinkedHashMap(base)
        env.putAll(parseVars(extraEnv))

        val pathKey = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        val merged = mergePath(env[pathKey], extraPath, separator, appended)
        if (merged.isNotEmpty()) env[pathKey] = merged
        return env
    }

    /**
     * Ordering, and why it is what it is: settings entries first, the inherited
     * PATH next, guesses last. Naming a directory in settings is a deliberate
     * act and should win; a directory this object merely *found* is a guess, and
     * a guess must never shadow a program the machine already resolves.
     */
    internal fun mergePath(
        existing: String?,
        extra: String,
        separator: Char = File.pathSeparatorChar,
        appended: List<String> = emptyList()
    ): String {
        val added = extra.split('\n', ';', separator)
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }
        val current = existing.orEmpty().split(separator).filter { it.isNotBlank() }

        val seen = LinkedHashSet<String>()
        // Compared case-insensitively: Windows paths differ in case constantly
        // and the same directory listed twice helps nobody.
        val result = mutableListOf<String>()
        (added + current + appended).forEach { entry ->
            if (seen.add(entry.lowercase())) result.add(entry)
        }
        return result.joinToString(separator.toString())
    }

    /**
     * The handful of directories that per-user installers write executables to
     * and then leave off PATH — pip's `--user`, npm's prefix, cargo, bun, go.
     *
     * Only directories that actually exist are returned, and they go on the end
     * of PATH, so this can add reach without changing what any existing command
     * resolves to. It is a convenience, not a substitute for the setting: an
     * installer that chose somewhere else entirely still has to be named.
     */
    private fun detectedUserBins(): List<String> {
        val candidates = mutableListOf<File>()
        System.getProperty("user.home")?.let { home ->
            listOf(".local/bin", ".cargo/bin", ".bun/bin", "go/bin").forEach { candidates += File(home, it) }
        }
        System.getenv("APPDATA")?.let { appData ->
            candidates += File(appData, "npm")
            // pip's --user scripts live under a version-named directory, so the
            // name cannot be written down — it has to be looked up.
            File(appData, "Python").listFiles()?.forEach { candidates += File(it, "Scripts") }
        }
        return candidates.filter { runCatching { it.isDirectory }.getOrDefault(false) }.map { it.path }
    }

    /** `KEY=VALUE` per line. Blank lines and `#` comments are skipped. */
    internal fun parseVars(raw: String): Map<String, String> = raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .associate { line ->
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim().trim('"')
            key to value
        }
        .filterKeys { it.isNotEmpty() }
}
