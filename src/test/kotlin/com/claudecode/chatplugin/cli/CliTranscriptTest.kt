package com.claudecode.chatplugin.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Clearing up after a run nobody asked for.
 *
 * Reading the usage percentages means running the CLI, and every run of the CLI
 * files away a transcript. Once a minute, for as long as the panel is open,
 * that had left 4835 of them in one user's `~/.claude/projects` — among their
 * real conversations, which is exactly where `claude --resume` goes looking.
 *
 * Deleting "the newest file" would be a guess, and a guess here deletes
 * somebody's conversation. Choosing the session id instead makes the name known
 * before the run starts, so what gets removed is only ever what this plugin
 * itself created. The encoding below is the CLI's, read off a real
 * `~/.claude/projects` rather than assumed.
 */
class CliTranscriptTest {

    @Test
    fun `a windows path loses its colon and its separators`() {
        // D:\Projects\claude-brains is filed as D--Projects-claude-brains: the
        // colon contributes one dash and the backslash the next.
        assertEquals("D--Projects-claude-brains", CliTranscript.encode("D:\\Projects\\claude-brains"))
    }

    @Test
    fun `a posix path loses its separators too`() {
        assertEquals("-home-me-work-thing", CliTranscript.encode("/home/me/work/thing"))
    }

    @Test
    fun `forward slashes on windows encode the same as backslashes`() {
        // The IDE hands out `D:/Projects/...` while the process ends up with
        // `D:\Projects\...`; both have to name the same folder.
        assertEquals(CliTranscript.encode("D:\\Projects\\x"), CliTranscript.encode("D:/Projects/x"))
    }

    @Test
    fun `the file is named after the session that wrote it`() {
        val file = CliTranscript.fileFor(
            sessionId = "11111111-2222-3333-4444-555555555555",
            workingDir = File("/home/me/work"),
            configDir = File("/home/me/.claude")
        )!!
        assertEquals("11111111-2222-3333-4444-555555555555.jsonl", file.name)
        assertEquals(CliTranscript.encode(File("/home/me/work").absolutePath), file.parentFile.name)
        assertEquals("projects", file.parentFile.parentFile.name)
    }

    @Test
    fun `without a working directory there is nothing to name`() {
        assertNull(CliTranscript.fileFor("abc", workingDir = null, configDir = File("/home/me/.claude")))
    }

    @Test
    fun `discarding a file that exists removes it`() {
        val file = File.createTempFile("claude-brains-transcript", ".jsonl")
        assertTrue(file.exists())
        assertTrue(CliTranscript.discard(file))
        assertFalse(file.exists())
    }

    @Test
    fun `discarding something that is not there is not a failure`() {
        // A reading of the usage percentages must not fail over its own
        // housekeeping.
        assertFalse(CliTranscript.discard(File("no-such-file-anywhere.jsonl")))
        assertFalse(CliTranscript.discard(null))
    }
}
