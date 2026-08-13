package com.claudecode.chatplugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Index of project-relative file paths behind the `@` autocomplete.
 *
 * Built once in the background and then filtered in memory on each keystroke.
 *
 * The background part is the point. This walks every file in the project — up
 * to [MAX_FILES] of them — under a read lock, and it used to do that on the EDT,
 * from a key listener, the first time anyone typed `@`. On a large repository
 * that is a visible freeze in the middle of typing. It is now a non-blocking
 * read action, which also yields to writes instead of holding the whole IDE up.
 */
class ProjectFileSearch(private val project: Project, private val parent: Disposable) {

    @Volatile
    private var cache: List<String>? = null

    private val building = AtomicBoolean(false)

    /**
     * Starts building now, so the list is usually ready by the time someone
     * reaches for it. Called when the panel is created.
     */
    fun warmUp() = startBuild()

    /**
     * Best matches for [query], ranked so filename hits beat directory-only ones.
     *
     * Returns nothing while the index is still being built rather than waiting
     * for it: the caller is a keystroke handler, and an empty popup for one
     * character is not worth a stalled editor.
     */
    fun search(query: String, limit: Int = 12): List<String> {
        val all = cache
        if (all == null) {
            startBuild()
            return emptyList()
        }
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all.take(limit)
        return all.asSequence()
            .filter { it.lowercase().contains(q) }
            .sortedBy { path ->
                val name = path.substringAfterLast('/').lowercase()
                when {
                    name.startsWith(q) -> 0
                    name.contains(q) -> 1
                    else -> 2
                }
            }
            .take(limit)
            .toList()
    }

    private fun startBuild() {
        if (cache != null) return
        if (!building.compareAndSet(false, true)) return

        ReadAction.nonBlocking<List<String>> { collect() }
            .expireWith(parent)
            .finishOnUiThread(com.intellij.openapi.application.ModalityState.any()) { paths ->
                cache = paths
                building.set(false)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun collect(): List<String> {
        val base = project.basePath?.replace('\\', '/')
        val result = ArrayList<String>()
        ProjectFileIndex.getInstance(project).iterateContent { vf ->
            if (!vf.isDirectory) {
                val p = vf.path
                result.add(if (base != null && p.startsWith(base)) p.removePrefix(base).trimStart('/') else p)
            }
            result.size < MAX_FILES // stop iterating once the cap is hit
        }
        return result
    }

    private companion object {
        const val MAX_FILES = 20_000
    }
}
