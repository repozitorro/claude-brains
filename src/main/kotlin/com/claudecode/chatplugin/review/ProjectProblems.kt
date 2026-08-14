package com.claudecode.chatplugin.review

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * What the IDE already knows is wrong with the files Claude just changed.
 *
 * The CLI reads files with its own tools and sees text. It does not see the
 * type errors, unresolved imports and inspection failures that the IDE has
 * worked out and is drawing in red at that very moment — so a turn can end
 * "successfully" with the editor full of problems Claude has no idea about,
 * and the next thing that happens is a human reading them out loud.
 *
 * This collects them so they can be handed back instead.
 */
object ProjectProblems {

    /** One thing the IDE is complaining about. */
    data class Problem(val fileName: String, val line: Int, val description: String)

    /** Beyond this the list stops being a summary and starts being the file. */
    const val MAX_PROBLEMS = 20

    /**
     * Errors in [files], as the IDE currently sees them.
     *
     * Only errors: warnings are mostly style, and a turn that ends by dumping
     * forty of them teaches you to ignore the message. Nothing is triggered
     * here — this reads analysis the IDE has already done, so a file it has not
     * looked at yet simply contributes nothing.
     */
    fun collect(project: Project, files: List<VirtualFile>): List<Problem> {
        val documentManager = FileDocumentManager.getInstance()
        val problems = mutableListOf<Problem>()

        for (file in files) {
            val document = documentManager.getDocument(file) ?: continue

            // Read the markup the daemon has already put on the document rather
            // than calling DaemonCodeAnalyzerImpl.getHighlights, which is marked
            // @ApiStatus.Internal — "not supposed to be used in client code", and
            // so free to change or vanish without a deprecation to warn us.
            val highlighters = runCatching {
                DocumentMarkupModel.forDocument(document, project, false).allHighlighters
            }.getOrNull().orEmpty()

            for (highlighter in highlighters) {
                val info = highlighter.errorStripeTooltip as? HighlightInfo ?: continue
                if (info.severity.myVal < HighlightSeverity.ERROR.myVal) continue
                val description = info.description?.trim()?.ifEmpty { null } ?: continue
                val line = runCatching { document.getLineNumber(highlighter.startOffset) + 1 }.getOrDefault(0)
                problems += Problem(file.name, line, description)
                if (problems.size >= MAX_PROBLEMS) return problems
            }
        }
        return problems
    }

    /**
     * The problems as a message for Claude.
     *
     * Kept plain and factual: file, line, what the IDE said. Anything more
     * would be the plugin guessing at a fix, which is the model's job.
     */
    fun describe(problems: List<Problem>): String = buildString {
        append("The IDE reports these errors in the files you just changed:\n\n")
        problems.groupBy { it.fileName }.forEach { (file, inFile) ->
            append("**").append(file).append("**\n")
            inFile.forEach { append("- line ").append(it.line).append(": ").append(it.description).append('\n') }
            append('\n')
        }
        append("Please fix them.")
    }
}
