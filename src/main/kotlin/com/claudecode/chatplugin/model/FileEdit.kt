package com.claudecode.chatplugin.model

/**
 * One file-mutating tool call Claude made (`Edit` / `MultiEdit` / `Write`),
 * tracked so the change can be shown in IntelliJ's diff viewer and reverted.
 *
 * The CLI applies the edit to disk itself, so by the time a turn finishes the
 * file already holds the *after* content. We recover the *before* content
 * race-free by reverse-applying the recorded [ops] to the final on-disk text
 * (see [resolve]). `Write` can't be reversed from the result alone, so for it
 * we fall back to [snapshotBefore] — the on-disk content captured (best effort)
 * the moment the edit was first observed.
 */
class FileEdit(
    val filePath: String,
    val toolName: String,
    val snapshotBefore: String?
) {
    val ops: MutableList<EditOp> = mutableListOf()

    /**
     * Filled in once the turn completes and the file's final state is known.
     *
     * Always newline-separated, whatever the file uses on disk — see
     * [lineSeparator].
     */
    var beforeText: String? = null
    var afterText: String? = null

    /**
     * The line ending the file actually uses.
     *
     * The CLI sends `old_string` / `new_string` with plain newlines even for a
     * CRLF file, so matching them against the raw file text never succeeds and
     * every multi-line edit on Windows looked unreconstructable. The text is
     * therefore normalised before any matching, and this records what to
     * restore when writing back.
     */
    var lineSeparator: String = "\n"
        private set

    /** Converts internal newline-separated text back to the file's own endings. */
    fun toFileText(text: String): String =
        if (lineSeparator == "\r\n") text.replace("\n", "\r\n") else text

    /**
     * True only when replaying [ops] forward over the reconstructed [beforeText]
     * exactly reproduces the on-disk [afterText]. Reverting is offered only then,
     * so a mis-reconstructed "before" can never overwrite the file with garbage.
     */
    var canRevert: Boolean = false
        private set

    val fileName: String get() = filePath.replace('\\', '/').substringAfterLast('/')

    /** True once [resolve] has produced content usable for a diff. */
    val isResolved: Boolean get() = beforeText != null && afterText != null

    /**
     * Given the file's [afterOnDisk] content (read after the turn ended),
     * reconstruct [beforeText] by undoing each op in reverse order.
     */
    fun resolve(afterOnDisk: String?) {
        lineSeparator = detectSeparator(afterOnDisk ?: snapshotBefore)
        val snapshot = snapshotBefore?.let(::toNewlines)
        val after = afterOnDisk?.let(::toNewlines)
        afterText = after
        if (after == null) {
            beforeText = snapshot
            return
        }
        val reconstructed = reconstructBefore(after, snapshot)
        beforeText = reconstructed ?: after
        canRevert = reconstructed != null
    }

    /**
     * Reverse-applies the ops to [text], returning the pre-edit content only
     * when the result is provably exact — otherwise null.
     *
     * Exposed so the same reasoning can run against an editor's document rather
     * than a separately-read disk snapshot: comparing those two for equality was
     * fragile, and any mismatch (a reload that hadn't landed, a charset quirk)
     * silently disabled inline review.
     */
    fun reconstructBefore(text: String, snapshot: String? = snapshotBefore?.let(::toNewlines)): String? {
        val after = toNewlines(text)
        var before: String = after
        // Track whether every reversal step was provably unambiguous. A plain
        // forward-replay check is NOT enough on its own: if new_string already
        // occurs earlier in the file, replaceFirst reverses the wrong spot yet
        // the result can still forward-replay back to `after`. So we additionally
        // require new_string to be unique at each single-occurrence reversal.
        var unambiguous = true
        for (op in ops.asReversed()) {
            val content = op.content
            val oldS = op.oldString
            val newS = op.newString
            if (content != null) {
                // A full-file Write discards prior content; the pre-turn state is
                // only knowable from the snapshot, and earlier ops don't matter.
                before = snapshot ?: ""
                break
            } else if (oldS != null && newS != null) {
                if (op.replaceAll) {
                    before = before.replace(newS, oldS)
                } else {
                    if (countOccurrences(before, newS) != 1) unambiguous = false
                    before = before.replaceFirst(newS, oldS)
                }
            }
        }
        return if (unambiguous && applyForward(before) == after) before else null
    }

    /** Whatever the file uses; a CRLF majority means the file is CRLF. */
    private fun detectSeparator(sample: String?): String {
        if (sample == null) return LF
        val crlfCount = sample.split(CRLF).size - 1
        val newlineCount = sample.count { it == NEWLINE }
        return if (crlfCount > 0 && crlfCount * 2 >= newlineCount) CRLF else LF
    }

    private fun toNewlines(text: String): String = text.replace(CRLF, LF)

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            count++
            i = haystack.indexOf(needle, i + needle.length)
        }
        return count
    }

    /** Replays [ops] forward over [start], mirroring what the CLI did to the file. */
    private fun applyForward(start: String): String {
        var s = start
        for (op in ops) {
            val content = op.content
            val oldS = op.oldString
            val newS = op.newString
            s = when {
                content != null -> content
                oldS != null && newS != null ->
                    if (op.replaceAll) s.replace(oldS, newS) else s.replaceFirst(oldS, newS)
                else -> s
            }
        }
        return s
    }

    private companion object {
        const val NEWLINE = '\n'
        const val LF = "\n"
        const val CRLF = "\r\n"
    }
}

/**
 * A single edit operation: either an (`oldString` → `newString`) replacement
 * (Edit / MultiEdit) or a full-file [content] overwrite (Write).
 */
data class EditOp(
    val oldString: String?,
    val newString: String?,
    val content: String?,
    val replaceAll: Boolean
)
