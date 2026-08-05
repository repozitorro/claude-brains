package com.claudecode.chatplugin.actions

import com.claudecode.chatplugin.ClaudeSessionManager
import com.claudecode.chatplugin.ui.ChatPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Editor-popup action: takes the current selection (with a small file/line
 * header for context) and drops it into the active chat tab's input box
 * instead of requiring manual copy-paste.
 */
class SendSelectionToClaudeAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)

        val selection = editor.selectionModel
        val text = selection.selectedText ?: return
        val startLine = editor.document.getLineNumber(selection.selectionStart) + 1
        val endLine = editor.document.getLineNumber(selection.selectionEnd) + 1

        val header = if (file != null) "From ${file.name} (lines $startLine-$endLine):" else "Selected code:"
        val formatted = "$header\n```\n$text\n```\n"

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude Brains") ?: return
        toolWindow.show {
            val sessionManager = project.getService(ClaudeSessionManager::class.java)
            sessionManager.getOrCreateDefault() // ensure at least one session/tab exists
            val cm = toolWindow.contentManager
            val content = cm.selectedContent ?: cm.contents.firstOrNull()
            (content?.component as? ChatPanel)?.prefillInput(formatted)
        }
    }
}
