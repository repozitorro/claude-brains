package com.claudecode.chatplugin.review

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

/**
 * Keeps every open editor's decorations in step with [EditReviewService].
 *
 * Editors come and go independently of the review — a file can be opened long
 * after the turn that changed it, or closed and reopened mid-review — so
 * decoration is driven by editor lifecycle events rather than done once at
 * submit time.
 */
@Service(Service.Level.PROJECT)
class EditReviewDecorations(private val project: Project) : Disposable {

    private val decorators = mutableMapOf<Editor, EditReviewDecorator>()

    init {
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project == project) decorate(event.editor)
            }

            override fun editorReleased(event: EditorFactoryEvent) {
                decorators.remove(event.editor)?.detach()
            }
        }, this)

        // Any accept/reject changes what should be drawn everywhere, so redraw
        // the lot rather than trying to work out which editors were affected.
        EditReviewService.getInstance(project).addChangeListener {
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) refreshAll()
            }
        }
    }

    fun refreshAll() {
        EditorFactory.getInstance().allEditors
            .filter { it.project == project }
            .forEach { decorate(it) }
    }

    private fun decorate(editor: Editor) {
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val hasPending = EditReviewService.getInstance(project).editFor(file) != null

        val existing = decorators[editor]
        when {
            !hasPending -> {
                existing?.detach()
                decorators.remove(editor)
            }
            existing != null -> existing.refresh()
            else -> EditReviewDecorator(project, editor, file).also {
                decorators[editor] = it
                it.attach()
            }
        }
    }

    override fun dispose() {
        decorators.values.forEach { it.detach() }
        decorators.clear()
    }

    companion object {
        fun getInstance(project: Project): EditReviewDecorations =
            project.getService(EditReviewDecorations::class.java)
    }
}
