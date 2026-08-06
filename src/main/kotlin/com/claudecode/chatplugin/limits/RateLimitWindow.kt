package com.claudecode.chatplugin.limits

import java.time.Duration
import java.time.Instant

/**
 * One rate-limit window as the CLI reports it in its `rate_limit_event`.
 *
 * What the CLI publishes is the window's *type*, its *status*, and *when it
 * resets* — never a remaining amount, a quota or a percentage. Anything
 * presented as "N% left" would be invented, so this models only the real
 * fields, and the UI shows consumption instead of a remaining figure.
 */
data class RateLimitWindow(
    /** e.g. "five_hour", "seven_day". */
    val type: String,
    /** e.g. "allowed". Anything else is worth showing prominently. */
    val status: String,
    val resetsAtEpochSec: Long?,
    val isUsingOverage: Boolean = false,
    val overageStatus: String? = null
) {

    val isHealthy: Boolean get() = status.equals("allowed", ignoreCase = true)

    /** Human name for the window: "five_hour" → "5-hour". */
    fun displayName(): String = when (type) {
        "five_hour" -> "5-hour"
        "seven_day" -> "7-day"
        "" -> "usage"
        else -> type.replace('_', ' ')
    }

    /** How long the window lasts, when the type says so. */
    fun length(): Duration? = when (type) {
        "five_hour" -> Duration.ofHours(5)
        "seven_day" -> Duration.ofDays(7)
        else -> null
    }

    /**
     * When the current window opened, derived from its reset time and length.
     * Null when the type is unknown — better no number than a guessed one.
     */
    fun startedAtMillis(): Long? {
        val resets = resetsAtEpochSec ?: return null
        val length = length() ?: return null
        return resets * 1000 - length.toMillis()
    }

    fun resetsAtMillis(): Long? = resetsAtEpochSec?.times(1000)

    /** "in 2h 13m", "in 14m", "now". */
    fun countdown(now: Instant = Instant.now()): String? {
        val resets = resetsAtEpochSec ?: return null
        val left = Duration.between(now, Instant.ofEpochSecond(resets))
        if (left.isNegative || left.isZero) return "now"
        val h = left.toHours()
        val m = left.toMinutes() % 60
        return when {
            h >= 24 -> "in ${left.toDays()}d ${h % 24}h"
            h > 0 -> "in ${h}h ${m}m"
            else -> "in ${m}m"
        }
    }
}
