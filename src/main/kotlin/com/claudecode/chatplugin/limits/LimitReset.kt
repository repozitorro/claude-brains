package com.claudecode.chatplugin.limits

/**
 * Noticing that a limit window has rolled over.
 *
 * Nothing announces this. The CLI reports a window's reset *time*, but a time
 * passing is not the same as a window having reset — the figure can be revised,
 * and a clock that is a few minutes out would announce a reset that has not
 * happened. So this waits for evidence instead: the next report says a smaller
 * percentage than the last one did, and names a different reset moment.
 *
 * Both halves are required. A percentage alone can fall for reasons of its own,
 * and a reset string alone changes whenever the wording does.
 */
object LimitReset {

    /**
     * How full a window has to have been for its reset to be worth saying out
     * loud.
     *
     * Below this it is not news — the window resets every few hours whether or
     * not anyone was waiting, and a notification that arrives when nothing was
     * blocked is one the user learns to dismiss unread.
     */
    const val TIGHT_PERCENT = 80

    /** One window that has rolled over since the previous report. */
    data class Event(val label: String, val wasPercent: Int, val nowPercent: Int, val resetsAt: String)

    /**
     * Windows that reset between [before] and [after], and were full enough
     * beforehand to be worth mentioning.
     */
    fun detect(before: List<LimitBar>, after: List<LimitBar>): List<Event> {
        if (before.isEmpty()) return emptyList() // nothing to compare against yet
        val previous = before.associateBy { it.label }
        return after.mapNotNull { now ->
            val was = previous[now.label] ?: return@mapNotNull null
            if (!rolledOver(was, now)) return@mapNotNull null
            if (was.percentUsed < TIGHT_PERCENT) return@mapNotNull null
            Event(now.shortLabel(), was.percentUsed, now.percentUsed, now.resetsAt)
        }
    }

    private fun rolledOver(was: LimitBar, now: LimitBar): Boolean =
        now.percentUsed < was.percentUsed && now.resetsAt != was.resetsAt

    /** What the notification says. */
    fun message(event: Event): String =
        "${event.label} limit has reset — ${event.nowPercent}% used, next reset ${event.resetsAt}."
}
