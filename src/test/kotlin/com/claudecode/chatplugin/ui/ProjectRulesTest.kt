package com.claudecode.chatplugin.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file that shapes every answer in a project.
 *
 * The CLI finds it on its own; the plugin's only contribution is making it
 * something you can see and edit without already knowing it exists.
 */
class ProjectRulesTest {

    @Test
    fun `it lives at the project root, where the CLI looks`() {
        // The CLI discovers CLAUDE.md from its working directory, which is the
        // project root — anywhere else and it would simply never be read.
        val file = ProjectRules.fileIn("/repo")

        assertEquals("CLAUDE.md", file.name)
        assertEquals("/repo", file.parentFile.path.replace('\\', '/'))
    }

    @Test
    fun `a new file prompts rather than prescribes`() {
        val template = ProjectRules.template("my-app")

        assertTrue("it should name the project", template.contains("# my-app"))
        // Section headings with empty comment prompts under them: the reader
        // fills these in. Inventing conventions here would be worse than an
        // empty file, because whatever is written gets obeyed.
        listOf("What this project is", "How to build and test", "Things to leave alone")
            .forEach { assertTrue(it, template.contains(it)) }
        assertTrue("prompts, not rules", template.contains("<!--"))
    }

    @Test
    fun `it warns that everything in it is sent every turn`() {
        // The failure mode is a file that grows into documentation, quietly
        // costing tokens on every single message.
        assertTrue(ProjectRules.template("x").contains("sent"))
    }

    @Test
    fun `the template is not itself full of instructions Claude would follow`() {
        val template = ProjectRules.template("x")

        // No imperative rules of our invention — the prompts are HTML comments,
        // which the model sees as placeholders rather than as orders.
        assertFalse(template.contains("Always "))
        assertFalse(template.contains("Never "))
    }
}
