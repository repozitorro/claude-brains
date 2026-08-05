package com.claudecode.chatplugin.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class UsageStatsTest {

    private var clock = 1_700_000_000_000L

    private fun entry(
        project: String,
        branch: String = "main",
        model: String = "claude-opus-4-8",
        input: Long = 10,
        output: Long = 100,
        cacheWrite: Long = 0,
        cacheRead: Long = 0,
        at: Long = clock
    ) = UsageEntry(project, branch, model, at, input, output, cacheWrite, cacheRead)

    @Test
    fun `sessions started in a subdirectory count towards the repository`() {
        val roots = setOf("D:/Work/app", "D:/Work/app/src/ui", "D:/Other")

        assertEquals("D:/Work/app", UsageStats.rootFor("D:/Work/app/src/ui", roots))
        assertEquals("D:/Work/app", UsageStats.rootFor("D:\\Work\\app\\src\\ui", roots)) // windows path
        assertEquals("D:/Other", UsageStats.rootFor("D:/Other", roots))
    }

    @Test
    fun `an unrelated path is its own project, not a sibling's child`() {
        val roots = setOf("D:/Work/app", "D:/Work/app-tools")

        // "app-tools" starts with "app" as a string but is not inside that directory.
        assertEquals("D:/Work/app-tools", UsageStats.rootFor("D:/Work/app-tools", roots))
    }

    @Test
    fun `usage rolls up per project and splits per branch`() {
        val report = UsageStats.aggregate(
            listOf(
                entry("D:/Work/app", branch = "main", output = 100),
                entry("D:/Work/app/src", branch = "main", output = 50),
                entry("D:/Work/app", branch = "feature/x", output = 25),
                entry("D:/Other", branch = "dev", output = 400)
            )
        )

        assertEquals(2, report.projects.size)
        // Ranked by volume: the busier project leads.
        assertEquals("D:/Other", report.projects[0].project)

        val app = report.projects.first { it.project == "D:/Work/app" }
        assertEquals(175, app.bucket.output)
        assertEquals(3, app.bucket.messages)
        assertEquals(listOf("main", "feature/x"), app.branches.map { it.first })
        assertEquals(150, app.branches.first { it.first == "main" }.second.output)
    }

    @Test
    fun `totals count every token kind`() {
        val report = UsageStats.aggregate(
            listOf(entry("p", input = 1, output = 2, cacheWrite = 4, cacheRead = 8))
        )

        assertEquals(15, report.overall.total)
        assertEquals(1, report.entryCount)
    }

    @Test
    fun `the window bucket counts only entries after it opened`() {
        val start = clock
        val report = UsageStats.aggregate(
            listOf(
                entry("p", output = 5, at = start - 1),   // just before the window
                entry("p", output = 7, at = start),       // exactly at the boundary
                entry("p", output = 9, at = start + 1000)
            ),
            windowStartMs = start
        )

        assertEquals(16, report.window!!.output)
        assertEquals(21, report.overall.output)
    }

    @Test
    fun `without a known window there is nothing to report`() {
        val report = UsageStats.aggregate(listOf(entry("p")))

        assertNull(report.window)
        assertNull(report.windowStartMs)
    }

    @Test
    fun `days are grouped chronologically and capped`() {
        val day = 24 * 60 * 60 * 1000L
        val entries = (0 until 20).map { entry("p", at = clock + it * day, output = 1) }

        val report = UsageStats.aggregate(entries, zone = ZoneId.of("UTC"), maxDays = 14)

        assertEquals(14, report.days.size)
        assertEquals(report.days.map { it.first }.sorted(), report.days.map { it.first })
        assertTrue(report.days.all { it.second.output == 1L })
    }

    @Test
    fun `models are ranked by volume`() {
        val report = UsageStats.aggregate(
            listOf(
                entry("p", model = "haiku", output = 10),
                entry("p", model = "opus", output = 90)
            )
        )

        assertEquals(listOf("opus", "haiku"), report.models.map { it.first })
    }

    @Test
    fun `token counts are formatted compactly`() {
        assertEquals("512", UsageStats.formatTokens(512))
        assertEquals("1.5k", UsageStats.formatTokens(1_530))
        assertEquals("2.0M", UsageStats.formatTokens(2_000_000))
        assertEquals("1.6B", UsageStats.formatTokens(1_561_254_560))
    }
}
