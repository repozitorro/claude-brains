package com.claudecode.chatplugin.actions

import com.claudecode.chatplugin.ui.ChatPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

class SendFileToClaudeAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        // Reference the path instead of pasting the contents: Claude Code can read
        // the file itself, which keeps large files from blowing up the prompt (and
        // the token bill). Paths are relative to the project root where possible,
        // since the CLI runs with that as its working directory.
        val base = project.basePath?.replace('\\', '/')
        val path = file.path
        val shown = if (base != null && path.startsWith(base)) path.removePrefix(base).trimStart('/') else path

        val formatted = "Take a look at `$shown`: "

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude Brains") ?: return
        toolWindow.show {
            val cm = toolWindow.contentManager
            val content = cm.selectedContent ?: cm.contents.firstOrNull()
            (content?.component as? ChatPanel)?.prefillInput(formatted)
        }
    }
}
