package com.claudecode.chatplugin.cli

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spending a tool row's width on the part worth reading.
 *
 * From a screenshot of a real turn:
 * `Bash cd "D:\Work\lms-human-front" && npm run graphify -- query…` — sixty
 * characters, and the command it actually ran fell off the end. The CLI writes
 * that preamble constantly, having no other way to choose a directory, so it
 * is the one part of the line guaranteed to say nothing.
 */
class ToolRowLabelTest {

    private val parser = StreamParser()

    @Test
    fun `a leading cd is dropped in favour of what follows it`() {
        assertEquals(
            "npm run graphify -- query \"architecture\"",
            parser.dropDirectoryPreamble("""cd "D:\Work\lms-human-front" && npm run graphify -- query "architecture"""")
        )
    }

    @Test
    fun `an unquoted path works the same way`() {
        assertEquals("npm test", parser.dropDirectoryPreamble("cd /srv/app && npm test"))
        assertEquals("ls -la", parser.dropDirectoryPreamble("cd /tmp ; ls -la"))
    }

    @Test
    fun `several of them in a row are all dropped`() {
        assertEquals("make", parser.dropDirectoryPreamble("cd /a && cd b && make"))
    }

    @Test
    fun `a cd that is the whole command is left alone`() {
        // Dropping it would leave an empty row that says nothing at all.
        assertEquals("cd /tmp", parser.dropDirectoryPreamble("cd /tmp"))
        assertEquals("cd /tmp &&", parser.dropDirectoryPreamble("cd /tmp &&"))
    }

    @Test
    fun `a command that merely mentions cd is untouched`() {
        assertEquals("echo cd /tmp && ls", parser.dropDirectoryPreamble("echo cd /tmp && ls"))
        assertEquals("git add cd", parser.dropDirectoryPreamble("git add cd"))
    }

    @Test
    fun `a path with a space in it survives`() {
        assertEquals("npm test", parser.dropDirectoryPreamble("""cd "C:\My Things\app" && npm test"""))
    }
}
