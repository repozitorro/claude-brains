package com.claudecode.chatplugin.review

import com.claudecode.chatplugin.model.FileEdit
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks the edits Claude has made but the user hasn't accepted or rejected yet.
 *
 * One instance per project owns every [PendingEdit]; the editor decorations and
 * the bar above the prompt are both views onto this state, so accepting from
 * either place updates the other.
 */
@Service(Service.Level.PROJECT)
class EditReviewService(private val project: Project) : Disposable {

    private val pending = LinkedHashMap<VirtualFile, PendingEdit>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun fireChanged() = listeners.forEach { runCatching { it() } }

    val pendingHunkCount: Int get() = pending.values.sumOf { it.pendingHunks.size }
    val pendingFileCount: Int get() = pending.values.count { !it.isFinished }
    val hasPending: Boolean get() = pendingHunkCount > 0

    fun editFor(file: VirtualFile): PendingEdit? = pending[file]?.takeIf { !it.isFinished }

    /**
     * Takes the edits from a finished turn into review and opens them.
     *
     * Returns the files it could not review — an edit whose "before" text
     * couldn't be proven exact stays with the chat's whole-file diff link.
     */
    fun submit(edits: List<FileEdit>): List<FileEdit> {
        val unreviewable = mutableListOf<FileEdit>()
        val opened = mutableListOf<VirtualFile>()

        for (edit in edits) {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(edit.filePath)
            if (file == null) {
                unreviewable.add(edit)
                continue
            }
            // The CLI wrote this file behind the IDE's back; without reloading,
            // the document still holds the pre-edit text and every marker would
            // land on the wrong line.
            FileDocumentManager.getInstance().reloadFiles(file)
            val document = FileDocumentManager.getInstance().getDocument(file)
            if (document == null) {
                unreviewable.add(edit)
                continue
            }

            val review = PendingEdit.create(edit, file, document)
            if (review == null) {
                unreviewable.add(edit)
                continue
            }
            pending.remove(file)?.dispose()
            pending[file] = review
            opened.add(file)
        }

        if (opened.isNotEmpty()) openForReview(opened)
        if (opened.isNotEmpty() || unreviewable.isNotEmpty()) fireChanged()
        return unreviewable
    }

    /** Opens each reviewed file, focusing the first on its first change. */
    private fun openForReview(files: List<VirtualFile>) {
        val manager = FileEditorManager.getInstance(project)
        files.take(MAX_AUTO_OPEN).forEachIndexed { index, file ->
            val line = pending[file]?.pendingHunks?.minOfOrNull { it.startLine() }?.coerceAtLeast(0) ?: 0
            OpenFileDescriptor(project, file, line, 0).let { descriptor ->
                // Only the first one takes focus, so a multi-file turn doesn't
                // yank the caret around while the user is reading.
                manager.openTextEditor(descriptor, index == 0)
            }
        }
    }

    fun accept(file: VirtualFile, hunk: Hunk) {
        pending[file]?.accept(hunk)
        cleanUp(file)
    }

    fun reject(file: VirtualFile, hunk: Hunk) {
        pending[file]?.reject(project, hunk)
        cleanUp(file)
    }

    fun acceptFile(file: VirtualFile) {
        pending[file]?.acceptAll()
        cleanUp(file)
    }

    fun rejectFile(file: VirtualFile) {
        pending[file]?.rejectAll(project)
        cleanUp(file)
    }

    fun acceptAll() {
        pending.values.toList().forEach { it.acceptAll() }
        cleanUpAll()
    }

    fun rejectAll() {
        pending.values.toList().forEach { it.rejectAll(project) }
        cleanUpAll()
    }

    /** Files still under review, for decorating editors as they open. */
    fun reviewedFiles(): List<VirtualFile> = pending.filterValues { !it.isFinished }.keys.toList()

    fun documentOf(file: VirtualFile): Document? = pending[file]?.document

    private fun cleanUp(file: VirtualFile) {
        pending[file]?.takeIf { it.isFinished }?.let {
            it.dispose()
            pending.remove(file)
        }
        fireChanged()
    }

    private fun cleanUpAll() {
        pending.entries.filter { it.value.isFinished }.map { it.key }.forEach {
            pending.remove(it)?.dispose()
        }
        fireChanged()
    }

    override fun dispose() {
        pending.values.forEach { it.dispose() }
        pending.clear()
    }

    companion object {
        /** A sweeping refactor shouldn't open a hundred tabs. */
        const val MAX_AUTO_OPEN = 20

        fun getInstance(project: Project): EditReviewService =
            project.getService(EditReviewService::class.java)
    }
}
