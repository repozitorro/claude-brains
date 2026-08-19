package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.ClaudeSessionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextArea
import java.awt.Container
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * Shift+Enter puts in a line break instead of sending.
 *
 * Leaving it out of the send handler is not enough to make it work. The IDE
 * dispatches its own keymap before Swing sees a key at all, so a shortcut it
 * has an action for never reaches the text area — the same reason paste is
 * handled here as an action rather than as a paste. It has to be claimed on
 * the component, where it outranks whatever the keymap would have done.
 */
class ShiftEnterTest : BasePlatformTestCase() {

    override fun setUp() {
        System.setProperty("ide.browser.jcef.enabled", "false")
        super.setUp()
    }

    private val shiftEnter: KeyStroke =
        KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)

    private fun buildPanel(): ChatPanel {
        val manager = project.getService(ClaudeSessionManager::class.java)
        val panel = ChatPanel(project, manager.createSession("Keys"))
        Disposer.register(testRootDisposable, panel)
        return panel
    }

    private fun findInput(root: Container): JBTextArea? {
        for (child in root.components) {
            if (child is JBTextArea && child.isEditable) return child
            if (child is Container) findInput(child)?.let { return it }
        }
        return null
    }

    private fun shortcutsOn(component: JComponent): List<KeyStroke> =
        (component.getClientProperty(AnAction.ACTIONS_KEY) as? List<*>)
            .orEmpty()
            .filterIsInstance<AnAction>()
            .flatMap { it.shortcutSet.shortcuts.toList() }
            .filterIsInstance<KeyboardShortcut>()
            .map { it.firstKeyStroke }

    fun testShiftEnterIsClaimedOnTheInput() {
        val input = findInput(buildPanel()) ?: error("the prompt input was not built")
        assertTrue(
            "Shift+Enter must be claimed on the input, or the IDE's keymap takes it first",
            shortcutsOn(input).contains(shiftEnter)
        )
    }

    fun testPlainEnterIsNotClaimedAsAnAction() {
        // It stays with the key listener that sends. Claiming it here as well
        // would give one keystroke two owners.
        val input = findInput(buildPanel()) ?: error("the prompt input was not built")
        val plainEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        assertFalse(shortcutsOn(input).contains(plainEnter))
    }

    fun testTheInputSaysHowToBreakALine() {
        // A shortcut nobody is told about is a shortcut nobody uses.
        val input = findInput(buildPanel()) ?: error("the prompt input was not built")
        assertTrue(input.emptyText.text.contains("new line"))
    }
}
