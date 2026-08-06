package com.claudecode.chatplugin.limits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageLimitsTest {

    /** Captured verbatim from `/usage` on CLI 2.1.205. */
    private val realReport = """
        You are currently using your subscription to power your Claude Code usage

        Current session: 54% used · resets Aug 6, 2:30pm (Europe/Kiev)
        Current week (all models): 40% used · resets Aug 9, 6am (Europe/Kiev)

        What's contributing to your limits usage?
        Approximate, based on local sessions on this machine — does not include other devices or claude.ai.

        Last 24h · 569 requests · 7 sessions
          100% of your usage was at >150k context
          Top skills: /dataviz 4%, /artifact-design 2%
    """.trimIndent()

    @Test
    fun `reads both bars out of the real report`() {
        val bars = UsageLimits.parse(realReport)

        assertEquals(2, bars.size)
        assertEquals("Current session", bars[0].label)
        assertEquals(54, bars[0].percentUsed)
        assertEquals("Aug 6, 2:30pm (Europe/Kiev)", bars[0].resetsAt)
        assertEquals("Current week (all models)", bars[1].label)
        assertEquals(40, bars[1].percentUsed)
    }

    @Test
    fun `the noisy lines around them are ignored`() {
        val bars = UsageLimits.parse(realReport)

        // "100% of your usage was at >150k context" and "Top skills: /dataviz 4%"
        // are percentages too, and must not be mistaken for limit bars.
        assertTrue(bars.none { it.percentUsed == 100 })
        assertTrue(bars.none { it.label.contains("skills", ignoreCase = true) })
    }

    @Test
    fun `labels shorten for a cramped toolbar`() {
        val bars = UsageLimits.parse(realReport)

        assertEquals("Session", bars[0].shortLabel())
        assertEquals("Week", bars[1].shortLabel())
    }

    @Test
    fun `wording it doesn't recognise yields nothing rather than a guess`() {
        // If the CLI rewords this screen, showing a wrong number would be worse
        // than showing none: the fallback readout takes over instead.
        listOf(
            "",
            "Current session: some used, resets later",
            "You have plenty of usage left",
            "Session usage: high"
        ).forEach { assertTrue(it, UsageLimits.parse(it).isEmpty()) }
    }

    @Test
    fun `percentages are clamped to a sane range`() {
        val bars = UsageLimits.parse("Current session: 250% used · resets tomorrow")

        assertEquals(100, bars.single().percentUsed)
    }
}
