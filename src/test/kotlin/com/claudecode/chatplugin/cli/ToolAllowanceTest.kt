package com.claudecode.chatplugin.cli

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Letting a command through without having to know the syntax.
 *
 * "Allow git" is the thought. `Bash(git *) PowerShell(git *)` is what the CLI
 * needs, and nobody arrives at the second half of that until the same command
 * has been refused twice — once as Bash and once as PowerShell.
 */
class ToolAllowanceTest {

    @Test
    fun `a bare command is allowed through whichever shell runs it`() {
        assertEquals("Bash(git *) PowerShell(git *)", ToolAllowance.expand("git"))
    }

    @Test
    fun `the CLI's own form is left exactly as written`() {
        // Someone who wrote a precise pattern meant it precisely.
        assertEquals("Bash(git commit *)", ToolAllowance.expand("Bash(git commit *)"))
    }

    @Test
    fun `a capitalised name means the whole tool`() {
        // Tools are written capitalised and programs are not, which is the only
        // signal available — and it happens to be the one people already use.
        assertEquals("Edit Write", ToolAllowance.expand("Edit Write"))
    }

    @Test
    fun `spaces and commas both separate, as the CLI's help says`() {
        assertEquals(
            "Bash(git *) PowerShell(git *) Bash(npm *) PowerShell(npm *)",
            ToolAllowance.expand("git, npm")
        )
    }

    @Test
    fun `punctuation inside a pattern is not a separator`() {
        // `Bash(git add, -A)` is one entry however it is punctuated.
        assertEquals(listOf("Bash(git add, -A)", "npm"), ToolAllowance.split("Bash(git add, -A) npm"))
    }

    @Test
    fun `a mixture keeps each part in its own form`() {
        assertEquals(
            "Bash(git *) PowerShell(git *) Edit Bash(npm test)",
            ToolAllowance.expand("git Edit Bash(npm test)")
        )
    }

    @Test
    fun `the same allowance twice is stated once`() {
        assertEquals("Bash(git *) PowerShell(git *)", ToolAllowance.expand("git git"))
    }

    @Test
    fun `nothing in means nothing out, so the flag is omitted entirely`() {
        // Blank and absent are different things to the CLI: an empty value is
        // an error, while no flag leaves it on its own rules.
        assertEquals("", ToolAllowance.expand(""))
        assertEquals("", ToolAllowance.expand("   "))
    }
}
