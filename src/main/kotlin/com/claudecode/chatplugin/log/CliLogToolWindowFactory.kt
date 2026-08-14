package com.claudecode.chatplugin.log

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * A window showing what the CLI was asked and what it replied.
 *
 * Deliberately a console rather than a text area: it comes with the searching,
 * scrolling and copying people already expect from the IDE's own output
 * windows, and none of that is worth reimplementing.
 *
 * It opens with the history already in it. Nobody opens a log before the thing
 * they are trying to explain has happened.
 */
class CliLogToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val content = ContentFactory.getInstance().createContent(console.component, null, false)
        content.setDisposer(console)
        toolWindow.contentManager.addContent(content)

        val log = CliLog.getInstance(project)
        log.snapshot().forEach { console.print(it) }
        log.addListener(console) { entry ->
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) console.print(entry)
            }
        }
    }

    /** Colour carries the kind, so the eye can skip to the red without reading. */
    private fun ConsoleView.print(entry: CliLog.Entry) {
        val type = when (entry.kind) {
            CliLog.Kind.COMMAND -> ConsoleViewContentType.USER_INPUT
            CliLog.Kind.ERROR -> ConsoleViewContentType.ERROR_OUTPUT
            CliLog.Kind.INFO -> ConsoleViewContentType.SYSTEM_OUTPUT
            CliLog.Kind.OUTPUT -> ConsoleViewContentType.NORMAL_OUTPUT
        }
        val prefix = when (entry.kind) {
            CliLog.Kind.COMMAND -> "\n$ "
            CliLog.Kind.ERROR -> "! "
            CliLog.Kind.INFO -> "· "
            CliLog.Kind.OUTPUT -> ""
        }
        print(prefix + entry.text + "\n", type)
    }
}
