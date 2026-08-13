package com.claudecode.chatplugin.review

import com.claudecode.chatplugin.ui.ReviewActionButton
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/**
 * The controls on the strip that floats over an editor holding unreviewed
 * changes.
 *
 * They are actions rather than buttons on a panel we position ourselves. The
 * platform owns where the strip sits, when it appears and how it layers over
 * the editor — all of which we previously did by hand, and got wrong: the
 * buttons were painted where you could see them but never laid out, so clicks
 * passed straight through and the arrows appeared dead.
 *
 * Each action reads its editor and file from the action system's own context,
 * so nothing has to be wired to a particular editor at construction.
 */
abstract class ReviewToolbarAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    protected fun context(e: AnActionEvent): Triple<Project, VirtualFile, Editor>? {
        val project = e.project ?: return null
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        return Triple(project, file, editor)
    }

    protected fun service(project: Project) = EditReviewService.getInstance(project)

    /** Only meaningful while this file still has changes waiting on a decision. */
    override fun update(e: AnActionEvent) {
        val (project, file, _) = context(e) ?: run {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isEnabledAndVisible = service(project).editFor(file) != null
    }
}

/**
 * A coloured button inside the toolbar.
 *
 * An action normally renders as an icon, and accept and reject are opposite
 * decisions that should not have to be read to be told apart. The button routes
 * its click back through [actionPerformed] so the behaviour still lives in the
 * action, with the context the action system resolves for it.
 */
abstract class ReviewToolbarButtonAction(
    private val label: String,
    private val accept: Boolean
) : ReviewToolbarAction(), CustomComponentAction {

    /** The work itself, reachable from the button and from the action alike. */
    protected abstract fun perform(project: Project, file: VirtualFile)

    final override fun actionPerformed(e: AnActionEvent) {
        val (project, file, _) = context(e) ?: return
        perform(project, file)
    }

    /**
     * The button does the work directly rather than synthesising an event and
     * calling [actionPerformed].
     *
     * `AnAction.actionPerformed` is override-only — invoking it is an API
     * violation the verifier reports — and `AnActionEvent.createFromAnAction`
     * is deprecated. Both exist only to route a click into an action, and this
     * button already knows what the click means.
     */
    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        lateinit var button: JComponent
        val onClick: () -> Unit = {
            val context = DataManager.getInstance().getDataContext(button)
            val project = context.getData(CommonDataKeys.PROJECT)
            val file = context.getData(CommonDataKeys.VIRTUAL_FILE)
            if (project != null && file != null) perform(project, file)
        }
        val tooltip = presentation.description.orEmpty()
        button = if (accept) {
            ReviewActionButton.accept(label, tooltip, onClick)
        } else {
            ReviewActionButton.reject(label, tooltip, onClick)
        }
        return button
    }

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
        component.isVisible = presentation.isVisible
        component.isEnabled = presentation.isEnabled
    }
}

class AcceptFileChangesAction : ReviewToolbarButtonAction("Accept file", accept = true) {
    override fun perform(project: Project, file: VirtualFile) = service(project).acceptFile(file)
}

class RejectFileChangesAction : ReviewToolbarButtonAction("Reject file", accept = false) {
    override fun perform(project: Project, file: VirtualFile) = service(project).rejectFile(file)
}

/** Steps through the turn's changes, continuing into the other files it touched. */
abstract class StepChangeAction(private val back: Boolean) : ReviewToolbarAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val (project, file, editor) = context(e) ?: return
        val service = service(project)
        val lines = service.pendingLines(file)

        when (val step = ReviewNavigation.step(lines, editor.caretModel.logicalPosition.line, back)) {
            is ReviewNavigation.Step.ToLine -> moveTo(editor, step.line)
            ReviewNavigation.Step.ToAnotherFile -> {
                val next = ReviewNavigation.neighbour(service.reviewedFiles(), file, back)
                if (next == null || !service.openAtChange(next, back)) {
                    // The only file left, so wrap inside it rather than going
                    // dead at the last change.
                    ReviewNavigation.entryLine(lines, back)?.let { moveTo(editor, it) }
                }
            }
        }
    }

    private fun moveTo(editor: Editor, line: Int) {
        editor.caretModel.moveToLogicalPosition(LogicalPosition(line, 0))
        editor.selectionModel.removeSelection()
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        // The caret is invisible in an unfocused editor, so without this the
        // view scrolls and nothing shows where it landed.
        editor.contentComponent.requestFocusInWindow()
    }
}

class NextChangeAction : StepChangeAction(back = false)

class PreviousChangeAction : StepChangeAction(back = true)

/**
 * How much is left, here and elsewhere.
 *
 * The "more files" part matters: stepping leaves this file once it is done, and
 * arriving somewhere else should read as the button working rather than as it
 * misfiring.
 */
class ReviewCounterAction : ReviewToolbarAction(), CustomComponentAction {

    override fun update(e: AnActionEvent) {
        val (project, file, _) = context(e) ?: run {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val service = service(project)
        val here = service.editFor(file)?.pendingHunks?.size ?: 0
        e.presentation.isEnabledAndVisible = here > 0
        e.presentation.text = buildString {
            append(if (here == 1) "1 change" else "$here changes")
            val elsewhere = service.reviewedFiles().count { it != file }
            if (elsewhere > 0) append(if (elsewhere == 1) " · 1 more file" else " · $elsewhere more files")
        }
    }

    override fun actionPerformed(e: AnActionEvent) = Unit

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
        JBLabel(presentation.text.orEmpty()).apply { border = JBUI.Borders.empty(0, 6, 0, 2) }

    override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
        (component as? JBLabel)?.text = presentation.text.orEmpty()
        component.isVisible = presentation.isVisible
    }
}
