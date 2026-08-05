package com.claudecode.chatplugin.actions

import com.claudecode.chatplugin.ClaudeSessionManager
import com.claudecode.chatplugin.ui.ChatPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JLabel

/**
 * Alt+Shift+C: opens a single-line prompt box right next to the caret so you
 * can fire off a quick question without leaving the keyboard or hunting for
 * the tool window. Submitting forwards the text to the main chat panel
 * (creating a session if none exists) and opens the tool window so you can
 * watch the streamed reply.
 */
class FloatingPromptAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val field = JBTextField(30)
        val panel = JPanel(BorderLayout()).apply {
            add(JLabel("Ask Claude: "), BorderLayout.WEST)
            add(field, BorderLayout.CENTER)
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, field)
            .setRequestFocus(true)
            .setResizable(false)
            .setMovable(true)
            .createPopup()

        field.addActionListener {
            val prompt = field.text.trim()
            popup.closeOk(null)
            if (prompt.isEmpty()) return@addActionListener

            val sessionManager = project.getService(ClaudeSessionManager::class.java)
            sessionManager.getOrCreateDefault()

            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Claude Brains") ?: return@addActionListener
            toolWindow.show {
                val cm = toolWindow.contentManager
                val content = cm.selectedContent ?: cm.contents.firstOrNull()
                (content?.component as? ChatPanel)?.prefillInput(prompt)
            }
        }

        popup.showInBestPositionFor(editor)
    }
}
