package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.model.FileEdit
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil

/**
 * Shows a Claude file edit in IntelliJ's native diff viewer, and can revert it.
 *
 * "Before" content is reconstructed by [FileEdit.resolve] (race-free for
 * Edit/MultiEdit; best-effort snapshot for Write). "After" is the content the
 * CLI already wrote to disk. Both are passed as plain text so no assumptions
 * are made about the file being open in an editor.
 */
object DiffReviewer {

    /**
     * Opens a Before/After diff for [edit]. Must be called on the EDT.
     *
     * When the edit is safely revertible, a **Revert This Edit** button is added
     * to the diff window's own toolbar, so the change can be rejected without
     * going back to the chat.
     */
    fun showDiff(project: Project, edit: FileEdit) {
        if (!edit.isResolved) return
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(edit.fileName)
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "Claude edit — ${edit.fileName}",
            factory.create(project, edit.beforeText ?: "", fileType),
            factory.create(project, edit.afterText ?: "", fileType),
            "Before",
            "After (on disk)"
        )
        if (edit.canRevert) {
            request.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf(RevertAction(project, edit)))
        }
        DiffManager.getInstance().showDiff(project, request)
    }

    private class RevertAction(
        private val project: Project,
        private val edit: FileEdit
    ) : AnAction("Revert This Edit", "Restore ${edit.fileName} to its pre-edit content", AllIcons.Actions.Rollback) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = edit.canRevert
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (revert(project, edit)) {
                Messages.showInfoMessage(project, "Reverted ${edit.fileName}.", "Claude Brains")
            } else {
                Messages.showErrorDialog(project, "Could not revert ${edit.fileName}.", "Claude Brains")
            }
        }
    }

    /**
     * Reverts the file to its reconstructed pre-edit content. Returns true on
     * success. Uses a write command so open editors and undo history stay in sync.
     */
    fun revert(project: Project, edit: FileEdit): Boolean {
        if (!edit.canRevert) return false
        val before = edit.beforeText ?: return false
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(edit.filePath) ?: return false
        WriteCommandAction.runWriteCommandAction(project, "Revert Claude Edit", null, {
            VfsUtil.saveText(vFile, before)
        })
        return true
    }
}
