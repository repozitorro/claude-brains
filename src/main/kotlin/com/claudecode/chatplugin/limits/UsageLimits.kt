package com.claudecode.chatplugin.limits

/** One limit bar as the CLI's own `/usage` screen reports it. */
data class LimitBar(
    /** "Current session", "Current week (all models)". */
    val label: String,
    /** 0–100, as the CLI states it. */
    val percentUsed: Int,
    /** The CLI's own wording, e.g. "Aug 6, 2:30pm (Europe/Kiev)". */
    val resetsAt: String
) {
    /** Short label for a cramped toolbar: "Current week (all models)" → "Week". */
    fun shortLabel(): String = when {
        label.contains("session", ignoreCase = true) -> "Session"
        label.contains("week", ignoreCase = true) -> "Week"
        label.contains("day", ignoreCase = true) -> "Day"
        else -> label.substringBefore(" (").removePrefix("Current ").replaceFirstChar { it.uppercase() }
    }
}

/**
 * Parses the CLI's `/usage` report.
 *
 * The percentages exist nowhere in the machine-readable stream — not in
 * `rate_limit_event`, not in the transcripts, not in the CLI's own state. They
 * are only produced by this human-facing screen, so this reads that. It is
 * therefore parsing a display: [parse] takes only lines it fully recognises and
 * returns nothing rather than guessing if the wording changes.
 */
object UsageLimits {

    /**
     * Matches, from CLI 2.1.205:
     *
     *     Current session: 54% used · resets Aug 6, 2:30pm (Europe/Kiev)
     *     Current week (all models): 40% used · resets Aug 9, 6am (Europe/Kiev)
     */
    private val LINE = Regex(
        """^\s*(.+?):\s*(\d{1,3})\s*%\s*used\s*[·|-]\s*resets\s+(.+?)\s*$""",
        RegexOption.IGNORE_CASE
    )

    fun parse(output: String): List<LimitBar> =
        output.lineSequence()
            .mapNotNull { line -> LINE.find(line) }
            .mapNotNull { match ->
                val percent = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                LimitBar(
                    label = match.groupValues[1].trim(),
                    percentUsed = percent.coerceIn(0, 100),
                    resetsAt = match.groupValues[3].trim()
                )
            }
            .toList()
}
