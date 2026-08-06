package com.claudecode.chatplugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex

/**
 * Lazily-built index of project-relative file paths, used by the `@` file
 * autocomplete in the prompt input.
 *
 * The list is collected once per panel (capped, so huge monorepos can't stall
 * the EDT) and filtered in memory on each keystroke.
 */
class ProjectFileSearch(private val project: Project) {

    private var cache: List<String>? = null

    private fun paths(): List<String> = cache ?: collect().also { cache = it }

    private fun collect(): List<String> {
        val base = project.basePath?.replace('\\', '/')
        val result = ArrayList<String>()
        ApplicationManager.getApplication().runReadAction {
            ProjectFileIndex.getInstance(project).iterateContent { vf ->
                if (!vf.isDirectory) {
                    val p = vf.path
                    result.add(if (base != null && p.startsWith(base)) p.removePrefix(base).trimStart('/') else p)
                }
                result.size < MAX_FILES // stop iterating once the cap is hit
            }
        }
        return result
    }

    /** Best matches for [query], ranked so filename hits beat directory-only hits. */
    fun search(query: String, limit: Int = 12): List<String> {
        val q = query.trim().lowercase()
        val all = paths()
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

    private companion object {
        const val MAX_FILES = 20_000
    }
}
