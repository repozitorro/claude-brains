package com.claudecode.chatplugin.stats

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One assistant reply's token usage, as recorded in a CLI session transcript. */
data class UsageEntry(
    val project: String,
    val branch: String,
    val model: String,
    val timestampMs: Long,
    val input: Long,
    val output: Long,
    val cacheWrite: Long,
    val cacheRead: Long
) {
    val total: Long get() = input + output + cacheWrite + cacheRead
}

/** Aggregated counters for any grouping (a project, a branch, a model, a day). */
data class Bucket(
    var messages: Long = 0,
    var input: Long = 0,
    var output: Long = 0,
    var cacheWrite: Long = 0,
    var cacheRead: Long = 0
) {
    val total: Long get() = input + output + cacheWrite + cacheRead

    fun add(e: UsageEntry) {
        messages++
        input += e.input
        output += e.output
        cacheWrite += e.cacheWrite
        cacheRead += e.cacheRead
    }
}

data class ProjectUsage(
    val project: String,
    val bucket: Bucket,
    /** Branch name → usage, biggest first. */
    val branches: List<Pair<String, Bucket>>
)

data class UsageReport(
    val overall: Bucket,
    val projects: List<ProjectUsage>,
    val models: List<Pair<String, Bucket>>,
    /** Day (yyyy-MM-dd) → usage, oldest first. */
    val days: List<Pair<String, Bucket>>,
    /** Usage inside the current rate-limit window, when its start is known. */
    val window: Bucket?,
    val windowStartMs: Long?,
    val windowResetMs: Long?,
    val entryCount: Int
)

/**
 * Turns raw [UsageEntry] rows into the report the statistics page renders.
 *
 * Deliberately free of IO and IDE types so the grouping rules can be tested
 * directly — see `UsageStatsTest`.
 */
object UsageStats {

    /**
     * Working directories nest (a session started in `repo/apps/web` belongs to
     * `repo`), so each entry is attributed to the shortest observed ancestor.
     * Without this, one repository shows up as a dozen unrelated "projects".
     */
    fun rootFor(project: String, roots: Collection<String>): String {
        val normalised = project.replace('\\', '/').trimEnd('/')
        return roots.asSequence()
            .map { it.replace('\\', '/').trimEnd('/') }
            .filter { normalised == it || normalised.startsWith("$it/") }
            .minByOrNull { it.length }
            ?: normalised
    }

    fun aggregate(
        entries: List<UsageEntry>,
        zone: ZoneId = ZoneId.systemDefault(),
        maxDays: Int = 14,
        windowStartMs: Long? = null,
        windowResetMs: Long? = null
    ): UsageReport {
        val roots = entries.map { it.project.replace('\\', '/').trimEnd('/') }.toSet()

        val overall = Bucket()
        val byProject = LinkedHashMap<String, Bucket>()
        val byProjectBranch = LinkedHashMap<String, LinkedHashMap<String, Bucket>>()
        val byModel = LinkedHashMap<String, Bucket>()
        val byDay = LinkedHashMap<String, Bucket>()
        val window = windowStartMs?.let { Bucket() }

        val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone)

        for (e in entries) {
            overall.add(e)
            val root = rootFor(e.project, roots)
            byProject.getOrPut(root) { Bucket() }.add(e)
            byProjectBranch.getOrPut(root) { LinkedHashMap() }
                .getOrPut(e.branch.ifBlank { "—" }) { Bucket() }.add(e)
            byModel.getOrPut(e.model) { Bucket() }.add(e)
            byDay.getOrPut(dayFormat.format(Instant.ofEpochMilli(e.timestampMs))) { Bucket() }.add(e)
            if (window != null && e.timestampMs >= windowStartMs) window.add(e)
        }

        val projects = byProject.entries
            .sortedByDescending { it.value.total }
            .map { (name, bucket) ->
                ProjectUsage(
                    project = name,
                    bucket = bucket,
                    branches = byProjectBranch[name].orEmpty().entries
                        .sortedByDescending { it.value.total }
                        .map { it.key to it.value }
                )
            }

        return UsageReport(
            overall = overall,
            projects = projects,
            models = byModel.entries.sortedByDescending { it.value.total }.map { it.key to it.value },
            days = byDay.entries.sortedBy { it.key }.takeLast(maxDays).map { it.key to it.value },
            window = window,
            windowStartMs = windowStartMs,
            windowResetMs = windowResetMs,
            entryCount = entries.size
        )
    }

    /** Compact token count for display: 1_530_000 → "1.5M". */
    fun formatTokens(n: Long): String = when {
        n >= 1_000_000_000 -> String.format(java.util.Locale.ROOT, "%.1fB", n / 1_000_000_000.0)
        n >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(java.util.Locale.ROOT, "%.1fk", n / 1_000.0)
        else -> n.toString()
    }
}
