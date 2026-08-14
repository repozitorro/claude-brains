package com.claudecode.chatplugin.ui

import java.io.File

/**
 * The project's standing instructions to Claude.
 *
 * `CLAUDE.md` at the project root is read by the CLI itself — it advertises
 * "CLAUDE.md auto-discovery" and needs no flag from us. So the plugin's only
 * job is to make the file visible: it shapes every answer in the project and
 * was, until now, something you had to already know about to use.
 */
object ProjectRules {

    const val FILE_NAME = "CLAUDE.md"

    fun fileIn(basePath: String): File = File(basePath, FILE_NAME)

    /**
     * What a new file starts as.
     *
     * Deliberately written as prompts to the reader rather than as rules: an
     * empty file teaches nothing, and a file full of invented conventions would
     * be worse — it would be obeyed.
     */
    fun template(projectName: String): String = """
        # $projectName

        Notes for Claude Code, read automatically at the start of every
        conversation in this project. Keep it short — everything here is sent
        with each turn.

        ## What this project is

        <!-- One or two sentences. What it does, who runs it. -->

        ## How to build and test

        <!-- The exact commands. Claude will use these rather than guessing. -->

        ## Conventions worth keeping

        <!-- Only the ones that are not obvious from reading the code:
             a naming rule, a directory that is generated, a library you have
             deliberately not adopted. -->

        ## Things to leave alone

        <!-- Generated files, vendored code, anything a well-meaning change
             would break. -->
    """.trimIndent() + "\n"
}
