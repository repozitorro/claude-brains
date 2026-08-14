package com.claudecode.chatplugin.ui

import com.intellij.ide.util.gotoByName.GotoSymbolModel2
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.Computable
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Classes and functions by name, for the `@` autocomplete.
 *
 * You remember what the class is called; you do not remember which of four
 * directories it lives in. The IDE has known both all along — this is the same
 * index Search Everywhere uses.
 *
 * What gets inserted is still the **file**, because a path is something Claude
 * can act on and a bare symbol name is not. The symbol is only how you find it.
 */
class ProjectSymbolSearch(private val project: Project, private val parent: Disposable) {

    @Volatile
    private var names: List<String>? = null
    private val building = AtomicBoolean(false)

    fun warmUp() = startBuild()

    /** Symbol names matching [query], or nothing while the index is still being built. */
    fun search(query: String, limit: Int = 6): List<String> {
        val all = names
        if (all == null) {
            startBuild()
            return emptyList()
        }
        val q = query.trim()
        if (q.length < MIN_QUERY) return emptyList()
        val lower = q.lowercase()
        return all.asSequence()
            .filter { it.lowercase().contains(lower) }
            .sortedBy { if (it.lowercase().startsWith(lower)) 0 else 1 }
            .take(limit)
            .toList()
    }

    /**
     * The project-relative file a symbol lives in, or null if it can't be
     * pinned down. Resolved only when one is chosen — this walks the index and
     * is far too slow to do per keystroke.
     */
    fun fileOf(name: String): String? = runCatching {
        // runReadAction rather than ReadAction.compute: the latter is deprecated
        // from 2026.2 on, while this has been the plain way to read for far
        // longer than the floor we support.
        ApplicationManager.getApplication().runReadAction(
            Computable {
                val element = model()
                    .getElementsByName(name, false, name)
                    .filterIsInstance<PsiElement>()
                    .firstOrNull()
                val path = element?.containingFile?.virtualFile?.path
                if (path == null) {
                    null
                } else {
                    val base = project.basePath?.replace('\\', '/')
                    val normalised = path.replace('\\', '/')
                    if (base != null && normalised.startsWith(base)) {
                        normalised.removePrefix(base).trimStart('/')
                    } else {
                        normalised
                    }
                }
            }
        )
    }.getOrNull()

    /**
     * The single-argument constructor is deprecated *and* scheduled for removal,
     * even at the 2024.1 floor. The two-argument one takes the disposable that
     * bounds the model's own caching, which is the thing that was missing.
     */
    private fun model() = GotoSymbolModel2(project, parent)

    private fun startBuild() {
        if (names != null) return
        if (!building.compareAndSet(false, true)) return

        // Off the EDT and cancellable, for the same reason the file index is:
        // this walks everything the project knows about.
        ReadAction.nonBlocking<List<String>> {
            model().getNames(false).filterNotNull().take(MAX_SYMBOLS)
        }
            .expireWith(parent)
            .finishOnUiThread(com.intellij.openapi.application.ModalityState.any()) { collected ->
                names = collected
                building.set(false)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private companion object {
        /** A one- or two-letter query matches half the project and helps nobody. */
        const val MIN_QUERY = 3
        const val MAX_SYMBOLS = 50_000
    }
}
