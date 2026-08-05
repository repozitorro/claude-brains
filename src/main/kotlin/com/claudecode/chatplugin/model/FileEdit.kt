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

    /** Filled in once the turn completes and the file's final state is known. */
    var beforeText: String? = null
    var afterText: String? = null

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
        afterText = afterOnDisk
        val after = afterOnDisk
        if (after == null) {
            beforeText = snapshotBefore
            return
        }
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
                before = snapshotBefore ?: ""
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
        beforeText = before
        // Revert is offered only when reconstruction was unambiguous AND replaying
        // the ops forward over `before` exactly reproduces what's on disk.
        canRevert = unambiguous && applyForward(before) == after
    }

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
