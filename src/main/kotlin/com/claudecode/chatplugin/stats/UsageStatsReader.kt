package com.claudecode.chatplugin.stats

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.time.Instant

/**
 * Reads token usage out of the Claude Code CLI's own session transcripts
 * (`~/.claude/projects/<encoded-cwd>/<session>.jsonl`).
 *
 * Each assistant entry carries `cwd`, `gitBranch`, `timestamp`, the model and a
 * `message.usage` block, which is everything the statistics page needs. Verified
 * against CLI 2.1.205.
 *
 * Note what is deliberately absent: transcripts record **no cost and no
 * rate-limit figures**, so the page reports tokens — the numbers that actually
 * exist — rather than inventing prices or a percentage of a limit.
 */
object UsageStatsReader {

    private val log = Logger.getInstance(UsageStatsReader::class.java)

    fun defaultRoot(): File = File(System.getProperty("user.home"), ".claude/projects")

    /**
     * Parses transcripts under [root]. Blocking; call off the EDT.
     *
     * [modifiedSince] skips files the file system says cannot contain anything
     * newer than that instant. The rate-limit readout only ever asks about the
     * current window, and it asks once a minute — without this it opened and
     * parsed every transcript the user has ever produced, every time, which for
     * a heavy user is hundreds of megabytes a minute to answer a question about
     * the last few hours. A file's timestamp answers it for free.
     *
     * Deliberately conservative: a file is skipped only when its *last* write
     * predates the cutoff, so nothing inside the window can be missed.
     */
    fun read(root: File = defaultRoot(), modifiedSince: Long? = null): List<UsageEntry> {
        if (!root.isDirectory) return emptyList()
        val entries = ArrayList<UsageEntry>()
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".jsonl") }?.forEach { file ->
                if (modifiedSince != null && file.lastModified() < modifiedSince) return@forEach
                try {
                    file.forEachLine { line -> parseLine(line)?.let(entries::add) }
                } catch (e: Exception) {
                    log.warn("Could not read transcript ${file.name}", e)
                }
            }
        }
        return entries
    }

    internal fun parseLine(line: String): UsageEntry? {
        if (line.isBlank() || !line.contains("\"usage\"")) return null
        val json = try {
            JsonParser.parseString(line).asJsonObject
        } catch (e: Exception) {
            return null
        }
        if (json.get("type")?.asString != "assistant") return null

        val message = json.getAsJsonObject("message") ?: return null
        val usage = message.getAsJsonObject("usage") ?: return null

        // Synthetic entries are local placeholders (interrupts, replayed context)
        // and always carry zero usage; counting them would inflate message counts.
        val model = message.get("model")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        if (model == "<synthetic>") return null

        val timestamp = json.get("timestamp")?.takeIf { it.isJsonPrimitive }?.asString
        val millis = timestamp?.let {
            runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
        } ?: return null

        return UsageEntry(
            project = json.get("cwd")?.takeIf { it.isJsonPrimitive }?.asString ?: return null,
            branch = json.get("gitBranch")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
            model = model,
            timestampMs = millis,
            input = num(usage, "input_tokens"),
            output = num(usage, "output_tokens"),
            cacheWrite = num(usage, "cache_creation_input_tokens"),
            cacheRead = num(usage, "cache_read_input_tokens")
        )
    }

    private fun num(obj: JsonObject, key: String): Long =
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
}
