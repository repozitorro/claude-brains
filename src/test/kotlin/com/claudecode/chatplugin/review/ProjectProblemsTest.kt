package com.claudecode.chatplugin.review

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Handing the IDE's own analysis back to Claude.
 *
 * The CLI reads files as text. It does not see what the IDE has already worked
 * out about them — so a turn can finish while the editor fills with red, and
 * the only thing that closes the loop is a person reading the errors out. This
 * reads them instead.
 */
class ProjectProblemsTest : BasePlatformTestCase() {

    /** Analysis is what produces highlights, so the fixture has to run it. */
    private fun analyse(name: String, text: String) = myFixture
        .configureByText(name, text)
        .virtualFile
        .also { myFixture.doHighlighting() }

    fun testErrorsInAChangedFileAreFound() {
        val file = analyse("broken.json", """{"a": }""")

        val problems = ProjectProblems.collect(project, listOf(file))

        assertFalse("the IDE knows this file is broken", problems.isEmpty())
        assertEquals("broken.json", problems.first().fileName)
        assertTrue("a line worth pointing at", problems.first().line >= 1)
        assertTrue("something to read", problems.first().description.isNotBlank())
    }

    fun testAFileWithNothingWrongContributesNothing() {
        val file = analyse("fine.json", """{"a": 1}""")

        assertTrue(ProjectProblems.collect(project, listOf(file)).isEmpty())
    }

    fun testTheListIsCappedRatherThanBecomingTheFile() {
        // Twenty errors is a summary; two hundred is the file pasted back, and
        // it would crowd out the conversation it is meant to help.
        val broken = (1..80).joinToString("\n") { """{"k$it": }""" }
        val file = analyse("many.json", broken)

        assertTrue(ProjectProblems.collect(project, listOf(file)).size <= ProjectProblems.MAX_PROBLEMS)
    }

    fun testTheMessageNamesFileAndLine() {
        val described = ProjectProblems.describe(
            listOf(
                ProjectProblems.Problem("App.kt", 12, "Unresolved reference: foo"),
                ProjectProblems.Problem("App.kt", 40, "Type mismatch")
            )
        )

        assertTrue(described, described.contains("**App.kt**"))
        assertTrue(described, described.contains("line 12: Unresolved reference: foo"))
        assertTrue(described, described.contains("line 40: Type mismatch"))
        // It asks for the fix without prescribing one.
        assertTrue(described, described.contains("Please fix them."))
    }
}
