package com.claudecode.chatplugin.review

import com.claudecode.chatplugin.model.FileEdit
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
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
    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(EditReviewService::class.java)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Subscribes for as long as [parent] lives — see `RateLimitService.addChangeListener`. */
    fun addChangeListener(parent: Disposable, listener: () -> Unit) {
        listeners.add(listener)
        Disposer.register(parent, Disposable { listeners.remove(listener) })
    }

    private fun fireChanged() = listeners.forEach { runCatching { it() } }

    val pendingHunkCount: Int get() = pending.values.sumOf { it.pendingHunks.size }
    val pendingFileCount: Int get() = pending.values.count { !it.isFinished }
    val hasPending: Boolean get() = pendingHunkCount > 0

    fun editFor(file: VirtualFile): PendingEdit? = pending[file]?.takeIf { !it.isFinished }

    /**
     * What a turn's edits could and could not be taken into review.
     *
     * [conflicted] is kept apart from [unreviewable] because it is not a
     * limitation to explain away — it means the file holds unsaved work of the
     * user's that Claude has now also changed on disk, and only they can decide
     * which one wins.
     */
    data class Outcome(
        val unreviewable: List<FileEdit> = emptyList(),
        val conflicted: List<FileEdit> = emptyList()
    )

    /**
     * Takes the edits from a finished turn into review and opens them.
     *
     * Nothing here may discard the user's own work: an edit whose "before" text
     * cannot be proven exact keeps the chat's whole-file diff link instead, and
     * a file with unsaved changes is left exactly as it is.
     */
    fun submit(edits: List<FileEdit>): Outcome {
        val unreviewable = mutableListOf<FileEdit>()
        val conflicted = mutableListOf<FileEdit>()
        val toOpen = mutableListOf<VirtualFile>()
        var reviewed = 0

        for (edit in edits) {
            val file = fileResolver(edit.filePath)
            if (file == null) {
                LOG.info("inline review: ${edit.fileName} — not found on disk")
                unreviewable.add(edit)
                continue
            }
            // Whether or not it can be marked up, the file was changed and should
            // be in front of the user.
            toOpen.add(file)

            val documentManager = FileDocumentManager.getInstance()
            val open = documentManager.getDocument(file)

            // The CLI wrote this file behind the IDE's back, so the document may
            // still hold the pre-edit text and has to be reloaded — but reloading
            // throws away whatever is in memory. If that memory holds edits the
            // user has not saved, this is the one place in the plugin that could
            // silently destroy their work, so it stops instead. The two versions
            // have genuinely diverged and only they can say which one wins.
            if (open != null && documentManager.isDocumentUnsaved(open)) {
                LOG.info("inline review: ${edit.fileName} — unsaved changes in the editor, leaving it alone")
                conflicted.add(edit)
                continue
            }

            // Refreshing needs a write action.
            ApplicationManager.getApplication().runWriteAction {
                file.refresh(false, false)
                documentManager.reloadFiles(file)
            }
            val document = documentManager.getDocument(file)
            if (document == null) {
                LOG.info("inline review: ${edit.fileName} — no document")
                unreviewable.add(edit)
                continue
            }

            val review = PendingEdit.create(edit, file, document)
            if (review == null) {
                // Logged with sizes because the usual cause is the document not
                // holding what the edit describes, and that is invisible from the
                // message the user sees.
                LOG.info(
                    "inline review declined for ${edit.fileName}: " +
                        "doc=${document.textLength} chars, recorded after=${edit.afterText?.length}, " +
                        "canRevert=${edit.canRevert}"
                )
                unreviewable.add(edit)
                continue
            }
            pending.remove(file)?.dispose()
            pending[file] = review
            reviewed++
        }

        if (toOpen.isNotEmpty()) openForReview(toOpen)
        if (reviewed > 0 || unreviewable.isNotEmpty() || conflicted.isNotEmpty()) fireChanged()
        return Outcome(unreviewable = unreviewable, conflicted = conflicted)
    }

    /**
     * How a recorded path becomes a file.
     *
     * Replaceable so the rule above — never reload over unsaved work — can be
     * tested, which otherwise needs the edited file to exist on the real disk.
     */
    internal var fileResolver: (String) -> VirtualFile? =
        { path -> LocalFileSystem.getInstance().refreshAndFindFileByPath(path) }

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

    /**
     * Files still under review, in the order their changes arrived — which is
     * the order stepping walks them in.
     */
    fun reviewedFiles(): List<VirtualFile> = pending.filterValues { !it.isFinished }.keys.toList()

    /** Lines still holding changes in [file], in file order. */
    fun pendingLines(file: VirtualFile): List<Int> =
        editFor(file)?.pendingHunks?.map { it.startLine() }?.filter { it >= 0 }?.distinct()?.sorted().orEmpty()

    /**
     * Opens [file] and puts the caret on the change to carry on from — its
     * first, or its last when stepping backwards.
     *
     * Takes focus: arriving in a different file with the caret left behind in
     * the previous one makes the next press of the button go somewhere
     * unexpected.
     */
    fun openAtChange(file: VirtualFile, back: Boolean): Boolean {
        val line = ReviewNavigation.entryLine(pendingLines(file), back) ?: return false
        FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, file, line, 0), true)
        return true
    }

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
