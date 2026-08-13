package com.claudecode.chatplugin.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Reading the CLI's own transcripts.
 *
 * These accumulate for the life of the installation, and the rate-limit
 * readout asks about them once a minute — but only ever about the current
 * window. Opening every transcript ever written to answer that is the
 * difference between a stat() per file and hundreds of megabytes of parsing.
 */
class UsageStatsReaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun transcript(project: String, name: String, timestamp: String, output: Long): File {
        val dir = File(temp.root, project).apply { mkdirs() }
        val file = File(dir, "$name.jsonl")
        file.writeText(
            """{"type":"assistant","cwd":"/repo/$project","gitBranch":"main","timestamp":"$timestamp",""" +
                """"message":{"model":"claude-opus-5","usage":{"input_tokens":1,"output_tokens":$output}}}""" + "\n"
        )
        return file
    }

    @Test
    fun `every transcript is read when nothing is asked to be skipped`() {
        transcript("alpha", "one", "2026-08-01T10:00:00.000Z", output = 10)
        transcript("beta", "two", "2026-08-02T10:00:00.000Z", output = 20)

        val entries = UsageStatsReader.read(temp.root)

        assertEquals(2, entries.size)
        assertEquals(30L, entries.sumOf { it.output })
    }

    @Test
    fun `a transcript untouched since the cutoff is never opened`() {
        val old = transcript("alpha", "old", "2026-08-01T10:00:00.000Z", output = 10)
        val fresh = transcript("beta", "fresh", "2026-08-02T10:00:00.000Z", output = 20)

        val cutoff = 1_000_000L
        assertTrue("test setup", old.setLastModified(cutoff - 60_000))
        assertTrue("test setup", fresh.setLastModified(cutoff + 60_000))

        val entries = UsageStatsReader.read(temp.root, modifiedSince = cutoff)

        assertEquals("only the file that could hold recent usage", 1, entries.size)
        assertEquals(20L, entries.single().output)
    }

    @Test
    fun `a file written exactly at the cutoff is still read`() {
        // Skipping is only safe when the last write provably predates the
        // window; equal timestamps must count, or usage goes missing.
        val edge = transcript("alpha", "edge", "2026-08-02T10:00:00.000Z", output = 7)
        val cutoff = 1_000_000L
        assertTrue("test setup", edge.setLastModified(cutoff))

        assertEquals(1, UsageStatsReader.read(temp.root, modifiedSince = cutoff).size)
    }

    @Test
    fun `a root that does not exist is empty rather than a failure`() {
        assertTrue(UsageStatsReader.read(File(temp.root, "nope")).isEmpty())
    }
}
