package com.claudecode.chatplugin.limits

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RateLimitWindowTest {

    private val now = Instant.parse("2026-08-06T12:00:00Z")

    private fun window(type: String, resetsInSeconds: Long, status: String = "allowed") =
        RateLimitWindow(type, status, now.epochSecond + resetsInSeconds)

    @Test
    fun `counts down to the reset`() {
        assertEquals("in 2h 13m", window("five_hour", 2 * 3600 + 13 * 60).countdown(now))
        assertEquals("in 14m", window("five_hour", 14 * 60).countdown(now))
        assertEquals("in 3d 4h", window("seven_day", 3 * 86400 + 4 * 3600).countdown(now))
    }

    @Test
    fun `a window that already reset reads as now, never as negative time`() {
        assertEquals("now", window("five_hour", -60).countdown(now))
        assertEquals("now", window("five_hour", 0).countdown(now))
    }

    @Test
    fun `window start is derived from its reset time and length`() {
        val fiveHour = window("five_hour", 3600) // resets in an hour
        // Started four hours ago: a five-hour window ending in one hour.
        assertEquals((now.epochSecond - 4 * 3600) * 1000, fiveHour.startedAtMillis())

        val weekly = window("seven_day", 86400)
        assertEquals((now.epochSecond - 6 * 86400) * 1000, weekly.startedAtMillis())
    }

    @Test
    fun `an unknown window type yields no invented start`() {
        // Guessing a length would silently attribute the wrong tokens to the
        // window, which is worse than showing nothing.
        val unknown = window("some_new_window", 3600)

        assertNull(unknown.length())
        assertNull(unknown.startedAtMillis())
        assertEquals("in 1h 0m", unknown.countdown(now)) // the reset is still known
    }

    @Test
    fun `missing reset time degrades quietly`() {
        val noReset = RateLimitWindow("five_hour", "allowed", null)

        assertNull(noReset.countdown(now))
        assertNull(noReset.startedAtMillis())
        assertNull(noReset.resetsAtMillis())
    }

    @Test
    fun `status other than allowed is not healthy`() {
        assertTrue(window("five_hour", 60).isHealthy)
        assertFalse(window("five_hour", 60, status = "rejected").isHealthy)
    }

    @Test
    fun `window types read as names, unknown ones pass through`() {
        assertEquals("5-hour", window("five_hour", 60).displayName())
        assertEquals("7-day", window("seven_day", 60).displayName())
        assertEquals("some new window", window("some_new_window", 60).displayName())
    }
}
