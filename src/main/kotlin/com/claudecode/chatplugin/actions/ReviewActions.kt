package com.claudecode.chatplugin.actions

import com.claudecode.chatplugin.review.EditReviewService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Accept / reject every pending change, from anywhere in the IDE.
 *
 * The same operations exist on the bar above the prompt; registering them as
 * actions makes them reachable from Find Action and assignable to a shortcut,
 * which matters when you're reviewing in the editor rather than in the chat.
 */
class AcceptAllChangesAction : AnAction(
    "Accept All Claude Changes",
    "Keep every change Claude made in this project",
    AllIcons.Actions.Commit
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { EditReviewService.getInstance(it).hasPending } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { EditReviewService.getInstance(it).acceptAll() }
    }
}

class RejectAllChangesAction : AnAction(
    "Reject All Claude Changes",
    "Restore every file Claude changed to how it was before",
    AllIcons.Actions.Rollback
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { EditReviewService.getInstance(it).hasPending } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { EditReviewService.getInstance(it).rejectAll() }
    }
}
