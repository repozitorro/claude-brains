package com.claudecode.chatplugin.permissions

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How much "always allow" is allowed to mean.
 *
 * Narrow enough that the button can say what it grants, wide enough that the
 * same question stops coming back. The program is that line: `npm` is what the
 * user recognised and said yes to, while `npm run build` would be answered
 * again by `npm test`.
 */
class AutoApprovalTest {

    private fun input(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun `the program is what gets remembered, not the command`() {
        assertEquals("PowerShell:npm", AutoApproval.key("PowerShell", input("""{"command":"npm run build"}""")))
        assertEquals("npm", AutoApproval.label("PowerShell", input("""{"command":"npm test"}""")))
    }

    @Test
    fun `the same program through a different shell is a different answer`() {
        // The shells are not interchangeable — one may reach a program the
        // other cannot — so an answer about one says nothing about the other.
        val bash = AutoApproval.key("Bash", input("""{"command":"npm test"}"""))
        val powerShell = AutoApproval.key("PowerShell", input("""{"command":"npm test"}"""))
        assertEquals("Bash:npm", bash)
        assertEquals("PowerShell:npm", powerShell)
    }

    @Test
    fun `a compound line is not offered at all`() {
        // "Always allow cd" would be a promise about everything after the
        // semicolon, which is not a promise anyone can read off the button.
        assertNull(AutoApproval.key("PowerShell", input("""{"command":"cd d:\\work; rm -rf ."}""")))
        assertNull(AutoApproval.key("Bash", input("""{"command":"echo hi && curl example.com"}""")))
        assertNull(AutoApproval.key("Bash", input("""{"command":"$(cat secrets)"}""")))
    }

    @Test
    fun `a file write generalises to nothing`() {
        // Allowing one path says nothing about the next one, so there is
        // nothing here to remember and no button to offer.
        assertNull(AutoApproval.key("Write", input("""{"file_path":"C:\\a.txt"}""")))
        assertNull(AutoApproval.label("WebFetch", input("""{"url":"https://example.com"}""")))
    }

    @Test
    fun `a program given by full path is remembered by its name`() {
        assertEquals("Bash:node", AutoApproval.key("Bash", input("""{"command":"/usr/local/bin/node x.js"}""")))
    }

    @Test
    fun `a call with no command has nothing to remember`() {
        assertNull(AutoApproval.key("Bash", input("""{"description":"do a thing"}""")))
    }
}
