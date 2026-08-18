package com.claudecode.chatplugin.permissions

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Turning a tool call into something answerable at a glance.
 *
 * The decision gets about a second of attention, and it is made from the title
 * line most of the time. So the title says the kind of thing and the program
 * doing it, and the arguments wait below for the times that is not enough.
 */
class ApprovalSummaryTest {

    private val project = "D:\\Work\\lms-human-front"

    private fun input(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun `a command is named by its program and where it runs`() {
        val summary = ApprovalSummary.of(
            "PowerShell",
            input("""{"command":"npm run graphify -- query \"architecture\"","description":"Ask graphify"}"""),
            project
        )
        assertEquals("Command npm in …/lms-human-front", summary.title)
        assertEquals("npm run graphify -- query \"architecture\"", summary.detail)
    }

    @Test
    fun `a leading cd is not what the command is about`() {
        // The CLI writes this constantly — it has no other way to choose a
        // directory — and "Command cd" describes the least interesting thing
        // on the line.
        val summary = ApprovalSummary.of(
            "Bash",
            input("""{"command":"cd \"D:\\Work\\lms-human-front\" && npm run graphify -- query \"x\""}"""),
            project
        )
        assertEquals("Command npm in …/lms-human-front", summary.title)
        // The line itself is untouched: it is what will actually run.
        assertEquals(
            "cd \"D:\\Work\\lms-human-front\" && npm run graphify -- query \"x\"",
            summary.detail
        )
    }

    @Test
    fun `a line that is only a cd is still named by it`() {
        assertEquals("Command cd in …/lms-human-front", ApprovalSummary.of("Bash", input("""{"command":"cd /tmp"}"""), project).title)
    }

    @Test
    fun `a program given by full path is named by the program`() {
        // C:\tools\node.exe is node. The path is in the detail line for anyone
        // who needs it; the title is for recognising the thing.
        val summary = ApprovalSummary.of("Bash", input("""{"command":"C:\\tools\\node.exe build.js"}"""), project)
        assertEquals("Command node.exe in …/lms-human-front", summary.title)
    }

    @Test
    fun `a write is named by the file, not by the path`() {
        val summary = ApprovalSummary.of("Write", input("""{"file_path":"C:\\Users\\me\\notes.txt"}"""), project)
        assertEquals("Write notes.txt", summary.title)
        assertEquals("C:\\Users\\me\\notes.txt", summary.detail)
    }

    @Test
    fun `a fetch is named by the host`() {
        val summary = ApprovalSummary.of("WebFetch", input("""{"url":"https://example.com/a/b?c=d"}"""), project)
        assertEquals("Fetch example.com", summary.title)
        assertEquals("https://example.com/a/b?c=d", summary.detail)
    }

    @Test
    fun `an MCP tool says whose tool it is`() {
        val summary = ApprovalSummary.of("mcp__graphify__query", input("""{"q":"deps"}"""), project)
        assertEquals("query from graphify", summary.title)
        assertEquals("q=deps", summary.detail)
    }

    @Test
    fun `an unrecognised tool falls back to its arguments`() {
        val summary = ApprovalSummary.of("SomethingNew", input("""{"a":"1","b":"2"}"""), project)
        assertEquals("SomethingNew", summary.title)
        assertEquals("a=1, b=2", summary.detail)
    }

    @Test
    fun `a path keeps its meaning on a machine that did not write it`() {
        // The paths here come from the CLI, not from the local filesystem, so
        // both separators have to work everywhere. `java.io.File` knows only
        // the separator of the machine it runs on, which passed on Windows and
        // failed the release build on Linux.
        assertEquals("node.exe", lastPathSegment("C:\\tools\\node.exe"))
        assertEquals("node", lastPathSegment("/usr/local/bin/node"))
        assertEquals("lms-human-front", lastPathSegment("D:\\Work\\lms-human-front\\"))
        assertEquals("npm", lastPathSegment("npm"))
    }

    @Test
    fun `a linux project is named the same way as a windows one`() {
        val summary = ApprovalSummary.of("Write", input("""{"file_path":"/home/me/notes.txt"}"""), "/srv/lms")
        assertEquals("Write notes.txt", summary.title)
        assertEquals("Command npm in …/lms", ApprovalSummary.of("Bash", input("""{"command":"npm test"}"""), "/srv/lms").title)
    }

    @Test
    fun `a command with no arguments still has a title`() {
        val summary = ApprovalSummary.of("Bash", JsonObject(), null)
        assertEquals("Command", summary.title)
        assertNull(summary.detail)
    }
}
