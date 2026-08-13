package com.claudecode.chatplugin.cli

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Finding `claude` on Windows.
 *
 * npm installs it as `claude.cmd`, and CreateProcess only ever appends `.exe`
 * — so a bare "claude" is not found and the plugin reports the CLI as missing
 * on a machine where it is installed and works in every terminal.
 */
class ExecutableResolverTest {

    private val path = listOf("C:\\tools", "C:\\Users\\me\\AppData\\Roaming\\npm")

    private fun resolveOnWindows(command: String, vararg present: String): String =
        ExecutableResolver.resolve(
            command = command,
            isWindows = true,
            pathEntries = path,
            exists = { it in present.toSet() }
        )

    @Test
    fun `a bare name resolves to the cmd shim npm installs`() {
        val resolved = resolveOnWindows(
            "claude",
            "C:\\Users\\me\\AppData\\Roaming\\npm\\claude.cmd"
        )

        assertEquals("C:\\Users\\me\\AppData\\Roaming\\npm\\claude.cmd", resolved)
    }

    @Test
    fun `an exe earlier on the PATH wins over a shim later on it`() {
        // PATH order is the user's choice and has to be respected.
        val resolved = resolveOnWindows(
            "claude",
            "C:\\tools\\claude.exe",
            "C:\\Users\\me\\AppData\\Roaming\\npm\\claude.cmd"
        )

        assertEquals("C:\\tools\\claude.exe", resolved)
    }

    @Test
    fun `something the user spelled out is left exactly as given`() {
        // A full path or an explicit extension is the user having already said
        // what to run; second-guessing it would override the setting.
        assertEquals(
            "C:\\custom\\claude.cmd",
            resolveOnWindows("C:\\custom\\claude.cmd", "C:\\tools\\claude.exe")
        )
        assertEquals("claude.exe", resolveOnWindows("claude.exe", "C:\\tools\\claude.exe"))
        assertEquals("./claude", resolveOnWindows("./claude", "C:\\tools\\claude.exe"))
    }

    @Test
    fun `nothing found means nothing changed`() {
        // So the caller still fails with its own "is the CLI installed?"
        // message rather than something stranger about a path we invented.
        assertEquals("claude", resolveOnWindows("claude"))
    }

    @Test
    fun `everywhere else the name is already enough`() {
        // Linux and macOS resolve PATH themselves, extensions and all.
        val resolved = ExecutableResolver.resolve(
            command = "claude",
            isWindows = false,
            pathEntries = listOf("/usr/local/bin"),
            exists = { true }
        )

        assertEquals("claude", resolved)
    }
}
