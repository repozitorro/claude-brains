package com.claudecode.chatplugin.cli

import java.io.File

/**
 * Where the CLI files away the record of a session.
 *
 * Every run leaves one, including the throwaway runs this plugin makes to read
 * the usage percentages. Those had piled up: 4835 of them in one user's
 * `~/.claude/projects`, mixed in with real conversations, which is where
 * `claude --resume` goes looking. None of it was asked for and none of it was
 * visible.
 *
 * The way out is to know the name in advance. The CLI will accept a session id
 * rather than inventing one (`--session-id`), and it writes the transcript to a
 * path derived entirely from that id and the working directory — so a run that
 * chooses its own id can remove exactly its own file afterwards, with no
 * guessing at "the newest one" and no chance of touching a real conversation.
 *
 * Verified against CLI 2.1.232. `--no-session-persistence` does **not** do this
 * — it is documented as working only with `--print`, and the usage probe has to
 * run without it (in print mode `/usage` is read as a file path).
 */
object CliTranscript {

    /**
     * The transcript for [sessionId] run in [workingDir], or null if the config
     * directory cannot be located.
     */
    fun fileFor(sessionId: String, workingDir: File?, configDir: File? = defaultConfigDir()): File? {
        val config = configDir ?: return null
        val dir = workingDir?.absolutePath ?: return null
        return File(File(config, "projects"), encode(dir)).resolve("$sessionId.jsonl")
    }

    /**
     * How a working directory becomes a folder name: every separator, and the
     * drive-letter colon with it, turns into a dash.
     *
     * `D:\Projects\claude-brains` → `D--Projects-claude-brains`, the colon and
     * the backslash each contributing one.
     */
    internal fun encode(path: String): String = path.map { if (it in SEPARATORS) '-' else it }.joinToString("")

    /** Honours `CLAUDE_CONFIG_DIR`, which is where the CLI itself looks first. */
    private fun defaultConfigDir(): File? {
        System.getenv("CLAUDE_CONFIG_DIR")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        return System.getProperty("user.home")?.let { File(it, ".claude") }
    }

    /**
     * Removes a transcript this plugin created. Best effort by design: a file
     * that is already gone, or that cannot be deleted, is not worth failing a
     * usage reading over.
     */
    fun discard(file: File?): Boolean = file != null && runCatching { file.delete() }.getOrDefault(false)

    private val SEPARATORS = setOf(':', '/', '\\')
}
