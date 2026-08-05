package com.claudecode.chatplugin.actions

import com.claudecode.chatplugin.stats.UsageStatsDialog
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class ShowUsageStatsAction : AnAction(
    "Usage Statistics",
    "Token usage across every project and branch you have used Claude Code in",
    com.intellij.icons.AllIcons.General.InspectionsEye
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        UsageStatsDialog.show(project)
    }
}
