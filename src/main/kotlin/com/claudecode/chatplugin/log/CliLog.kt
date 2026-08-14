package com.claudecode.chatplugin.log

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the CLI was actually asked, and what it actually said.
 *
 * The chat shows the answer. When there is no answer — a turn that ends blank,
 * a tool that refuses for no stated reason, a launch that fails — the only way
 * to find out why has been to run the CLI by hand outside the IDE and compare.
 * This keeps the same information where the person having the problem can read
 * it.
 *
 * Records are kept whether or not anyone is looking: the console is usually
 * opened *after* something has gone wrong.
 */
@Service(Service.Level.PROJECT)
class CliLog {

    enum class Kind {
        /** The command line a turn was launched with. */
        COMMAND,

        /** A line the CLI wrote to stdout. */
        OUTPUT,

        /** Anything on stderr, or a failure to launch at all. */
        ERROR,

        /** The plugin's own note about what it did. */
        INFO
    }

    data class Entry(val kind: Kind, val text: String, val at: Long = System.currentTimeMillis())

    private val entries = ArrayDeque<Entry>()
    private val listeners = CopyOnWriteArrayList<(Entry) -> Unit>()

    /**
     * Subscribes for as long as [parent] lives.
     *
     * The console comes and goes with its tool window while this service lives
     * as long as the project — the same shape as every other listener here, and
     * for the same reason.
     */
    fun addListener(parent: Disposable, listener: (Entry) -> Unit) {
        listeners.add(listener)
        Disposer.register(parent, Disposable { listeners.remove(listener) })
    }

    fun snapshot(): List<Entry> = synchronized(entries) { entries.toList() }

    fun record(kind: Kind, text: String) {
        if (text.isBlank()) return
        val entry = Entry(kind, clamp(text))
        synchronized(entries) {
            entries.addLast(entry)
            // A ring rather than a growing list: a long session produces tens of
            // thousands of lines, and none of the old ones help diagnose what
            // just happened.
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        listeners.forEach { runCatching { it(entry) } }
    }

    /** Throws the history away — the console's own clear, and a clean slate per test. */
    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    /** A single tool result can be an entire file; the log is for reading. */
    private fun clamp(text: String): String =
        if (text.length <= MAX_LINE) text else text.take(MAX_LINE) + "… (${text.length} chars)"

    internal val size: Int get() = synchronized(entries) { entries.size }

    companion object {
        internal const val MAX_ENTRIES = 2_000
        internal const val MAX_LINE = 2_000

        fun getInstance(project: Project): CliLog = project.getService(CliLog::class.java)
    }
}
