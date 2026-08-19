package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.ClaudeSessionManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * A price, to as many places as carry meaning.
 *
 * The status line read `$2.8111`. Two of those digits are a hundredth of a
 * cent, which changes no decision anyone makes, while they crowd the figure
 * that does. A first turn at `$0.0043` is the opposite case and needs them.
 */
class CostFormatTest : BasePlatformTestCase() {

    override fun setUp() {
        System.setProperty("ide.browser.jcef.enabled", "false")
        super.setUp()
    }

    private fun panel(): ChatPanel {
        val manager = project.getService(ClaudeSessionManager::class.java)
        val panel = ChatPanel(project, manager.createSession("Cost"))
        Disposer.register(testRootDisposable, panel)
        return panel
    }

    fun testDollarsGetTwoPlaces() {
        assertEquals("2.81", panel().formatCost(2.8111))
        assertEquals("12.00", panel().formatCost(12.0))
    }

    fun testCentsGetThree() {
        assertEquals("0.042", panel().formatCost(0.0424))
    }

    fun testFractionsOfACentKeepAllFour() {
        // The whole cost of a first turn can live here, and rounding it to
        // "0.00" would say the conversation was free.
        assertEquals("0.0043", panel().formatCost(0.00432))
    }

    fun testNothingSpentIsNotAnEmptyString() {
        assertEquals("0.0000", panel().formatCost(0.0))
    }
}
