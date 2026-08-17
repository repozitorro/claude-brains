package com.claudecode.chatplugin.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Making a user-installed tool reachable.
 *
 * The case this exists for: `graphify.exe` sits in a per-user Scripts directory
 * that no installer ever added to PATH, so the CLI reports "command not found"
 * for something the user can see on disk. Naming that directory here fixes it
 * for the CLI without touching the machine.
 */
class CliEnvironmentTest {

    private val sep = ';'

    @Test
    fun `added directories are searched before the inherited ones`() {
        // Whoever named a directory meant that copy of the program, not
        // whichever one PATH happened to reach first.
        val merged = CliEnvironment.mergePath("C:\\Windows;C:\\tools", "C:\\Users\\me\\Scripts", sep)
        assertEquals("C:\\Users\\me\\Scripts;C:\\Windows;C:\\tools", merged)
    }

    @Test
    fun `one directory per line`() {
        val merged = CliEnvironment.mergePath("C:\\Windows", "C:\\a\nC:\\b\n\n  C:\\c  ", sep)
        assertEquals("C:\\a;C:\\b;C:\\c;C:\\Windows", merged)
    }

    @Test
    fun `a directory already on PATH is not listed twice`() {
        // Windows paths differ in case constantly; the same directory in two
        // spellings is still the same directory.
        val merged = CliEnvironment.mergePath("C:\\Windows;C:\\tools", "c:\\windows", sep)
        assertEquals("c:\\windows;C:\\tools", merged)
    }

    @Test
    fun `an empty setting leaves PATH alone`() {
        assertEquals("C:\\Windows;C:\\tools", CliEnvironment.mergePath("C:\\Windows;C:\\tools", "", sep))
    }

    @Test
    fun `quotes pasted from a shell are stripped`() {
        assertEquals("C:\\a;C:\\Windows", CliEnvironment.mergePath("C:\\Windows", "\"C:\\a\"", sep))
    }

    @Test
    fun `variables are read one per line`() {
        val vars = CliEnvironment.parseVars("FOO=1\nBAR=two words\n")
        assertEquals(mapOf("FOO" to "1", "BAR" to "two words"), vars)
    }

    @Test
    fun `blank lines and comments are skipped`() {
        val vars = CliEnvironment.parseVars("\n# a note\nFOO=1\n   \nnot a setting\n")
        assertEquals(mapOf("FOO" to "1"), vars)
    }

    @Test
    fun `a value containing equals signs is kept whole`() {
        // Tokens and connection strings are full of them.
        assertEquals(mapOf("TOKEN" to "a=b=c"), CliEnvironment.parseVars("TOKEN=a=b=c"))
    }

    @Test
    fun `the built environment keeps what it inherited`() {
        val env = CliEnvironment.build(
            base = mapOf("HOME" to "C:\\Users\\me", "Path" to "C:\\Windows"),
            extraPath = "C:\\Users\\me\\Scripts",
            extraEnv = "FOO=1",
            separator = sep
        )
        assertEquals("C:\\Users\\me", env["HOME"])
        assertEquals("1", env["FOO"])
        // PATH is whatever the inherited environment called it — replacing
        // `Path` with `PATH` on Windows would leave the child with both.
        assertEquals("C:\\Users\\me\\Scripts;C:\\Windows", env["Path"])
        assertFalse(env.containsKey("PATH"))
    }

    @Test
    fun `a found directory goes last, so it cannot shadow anything`() {
        // ~/.local/bin is a guess this object made, not something the user
        // named. If a program exists in both places, PATH's answer stands.
        val merged = CliEnvironment.mergePath("C:\\Windows", "C:\\named", sep, listOf("C:\\found"))
        assertEquals("C:\\named;C:\\Windows;C:\\found", merged)
    }

    @Test
    fun `a found directory already on PATH is not appended again`() {
        val merged = CliEnvironment.mergePath("C:\\Windows;C:\\tools", "", sep, listOf("C:\\TOOLS"))
        assertEquals("C:\\Windows;C:\\tools", merged)
    }

    @Test
    fun `an environment with no PATH at all still gets one`() {
        val env = CliEnvironment.build(mapOf("HOME" to "/home/me"), "/opt/bin", "", sep)
        assertTrue(env["PATH"] == "/opt/bin")
    }
}
