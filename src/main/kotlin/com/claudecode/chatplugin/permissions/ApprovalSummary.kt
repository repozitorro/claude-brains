package com.claudecode.chatplugin.permissions

import com.google.gson.JsonObject

/**
 * The last segment of a path, whichever separator wrote it.
 *
 * Deliberately not `java.io.File`, which knows only the separator of the
 * machine it is running on: `File("C:\\tools\\node.exe").name` is `node.exe` on
 * Windows and the whole string on Linux. These paths come from the CLI
 * describing a command, not from the local filesystem, so they have to keep
 * their meaning wherever the code happens to run — including a CI runner that
 * is not the platform the path was written on.
 */
internal fun lastPathSegment(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

/**
 * What a tool call looks like when it is put to a person as a question.
 *
 * The CLI hands over a tool name and a bag of arguments. That is enough to
 * answer with, but not enough to answer *quickly*: `{"command":"npm run
 * graphify -- query \"…\"","description":"…"}` is a shape to be decoded, and
 * the decision is being made in the half-second before someone clicks.
 *
 * So it is split in two. [title] says what kind of thing this is and where it
 * happens — the part that decides most answers on its own. [detail] is the
 * thing itself, verbatim, for the answers that need to read it.
 */
data class ApprovalSummary(val title: String, val detail: String?) {

    companion object {

        /** Tools whose argument is a command line rather than a file. */
        private val COMMAND_TOOLS = setOf("Bash", "PowerShell", "Shell", "Terminal")

        private val FILE_TOOLS = mapOf(
            "Write" to "Write", "Edit" to "Edit", "MultiEdit" to "Edit",
            "NotebookEdit" to "Edit", "Read" to "Read"
        )

        fun of(toolName: String, input: JsonObject?, projectDir: String?): ApprovalSummary {
            val where = location(projectDir)
            return when {
                toolName in COMMAND_TOOLS -> {
                    val command = input.string("command")
                    ApprovalSummary(
                        title = listOfNotNull("Command", program(command), where).joinToString(" "),
                        detail = command
                    )
                }

                FILE_TOOLS.containsKey(toolName) -> {
                    val path = input.string("file_path") ?: input.string("path") ?: input.string("notebook_path")
                    ApprovalSummary(
                        title = "${FILE_TOOLS[toolName]} ${path?.let { lastPathSegment(it) } ?: "file"}",
                        detail = path
                    )
                }

                toolName == "WebFetch" -> {
                    val url = input.string("url")
                    ApprovalSummary("Fetch ${url?.let { host(it) } ?: "a page"}", url)
                }

                toolName == "WebSearch" -> ApprovalSummary("Web search", input.string("query"))

                // mcp__<server>__<tool> — the middle is the part that says whose
                // tool this is, which is the part worth reading.
                toolName.startsWith("mcp__") -> {
                    val parts = toolName.removePrefix("mcp__").split("__", limit = 2)
                    val server = parts.getOrNull(0).orEmpty()
                    val tool = parts.getOrNull(1) ?: server
                    ApprovalSummary("$tool from $server", compact(input))
                }

                else -> ApprovalSummary(toolName, compact(input))
            }
        }

        /**
         * `npm run graphify -- query "…"` is a request to run **npm**. The rest
         * is in [detail]; the name of the program is what the title is for.
         */
        private fun program(command: String?): String? {
            if (command == null) return null
            // `cd "D:\Work\project" && npm run graphify` is a request to run
            // **npm**. Titling it "Command cd" describes the least interesting
            // thing on the line — and it is the line the CLI writes most often,
            // because it has no other way to choose a directory.
            val segment = command.split("&&", ";", "||")
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && it.substringBefore(' ') !in PREAMBLE }
                ?: command
            val first = segment.trim().substringBefore(' ').takeIf { it.isNotEmpty() } ?: return null
            // A full path names the same program as its last segment does, and
            // the segment is what someone recognises.
            return lastPathSegment(first.trim('"', '\''))
        }

        /** Commands that only set the stage for the one after them. */
        private val PREAMBLE = setOf("cd", "pushd", "set", "export", "chdir")

        /** Written as the shell prompt would write it: the last segment, elided. */
        private fun location(projectDir: String?): String? =
            projectDir?.let { "in …/${lastPathSegment(it)}" }

        private fun host(url: String): String =
            runCatching { java.net.URI(url).host }.getOrNull() ?: url

        /** Everything else: the arguments, on one line, without the braces. */
        private fun compact(input: JsonObject?): String? = input
            ?.entrySet()
            ?.joinToString(", ") { (k, v) -> "$k=${v.asStringOrJson().take(120)}" }
            ?.takeIf { it.isNotEmpty() }

        private fun JsonObject?.string(key: String): String? =
            this?.get(key)?.takeIf { it.isJsonPrimitive }?.asString

        private fun com.google.gson.JsonElement.asStringOrJson(): String =
            if (isJsonPrimitive) asString else toString()
    }
}
