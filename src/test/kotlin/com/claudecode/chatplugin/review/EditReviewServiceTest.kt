package com.claudecode.chatplugin.review

import com.claudecode.chatplugin.model.EditOp
import com.claudecode.chatplugin.model.FileEdit
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The rule that protects work the plugin did not write.
 *
 * Taking a turn's edits into review means reloading the file, because the CLI
 * wrote it behind the IDE's back — and reloading throws away whatever the
 * editor is holding. When that is unsaved work of the user's, this is the only
 * place in the plugin that could destroy it without asking.
 *
 * Note the helpers: this is a JUnit 3 fixture, and a non-inline lambda written
 * inside a `testX` method compiles to a synthetic `testX$lambda$N`, which the
 * runner then picks up as a test of its own and fails as "not public". Keeping
 * lambdas out of the test methods themselves avoids it.
 */
class EditReviewServiceTest : BasePlatformTestCase() {

    private val service get() = EditReviewService.getInstance(project)

    private fun resolveTo(file: VirtualFile?) {
        service.fileResolver = { file }
    }

    private fun replaceWithoutSaving(document: Document, text: String) {
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
    }

    private fun saveEverything() {
        ApplicationManager.getApplication().runWriteAction { FileDocumentManager.getInstance().saveAllDocuments() }
    }

    private fun documentOf(file: VirtualFile): Document =
        FileDocumentManager.getInstance().getDocument(file)!!

    private fun isUnsaved(document: Document): Boolean =
        FileDocumentManager.getInstance().isDocumentUnsaved(document)

    /** An edit that provably turns "a\nb\nc\n" into "a\nB\nc\n". */
    private fun edit(path: String): FileEdit =
        FileEdit(path, "Edit", null).apply {
            ops.add(EditOp("b", "B", null, false))
            resolve("a\nB\nc\n")
            assertTrue("test setup: reconstruction must be provable", canRevert)
            assertEquals("test setup", "a\nb\nc\n", beforeText)
        }

    fun testAFileWithUnsavedChangesIsLeftAloneAndReported() {
        val file = myFixture.configureByText("App.kt", "a\nB\nc\n").virtualFile
        resolveTo(file)

        // The user has typed something and not saved it.
        val document = documentOf(file)
        replaceWithoutSaving(document, "mine, not saved\n")
        assertTrue("test setup: document must be unsaved", isUnsaved(document))

        val outcome = service.submit(listOf(edit(file.path)))

        assertEquals(1, outcome.conflicted.size)
        assertEquals("App.kt", outcome.conflicted.single().fileName)
        assertTrue("a conflict is not the same as unreviewable", outcome.unreviewable.isEmpty())
        // The point of the whole exercise.
        assertEquals("the user's text must survive", "mine, not saved\n", document.text)
        assertNull("nothing may be marked up over unsaved work", service.editFor(file))
    }

    fun testAFileWithNothingUnsavedIsTakenIntoReview() {
        // The ordinary path still has to work: this guard must not cost review
        // on every file just because one of them could be dirty.
        val file = myFixture.configureByText("Clean.kt", "a\nB\nc\n").virtualFile
        resolveTo(file)
        saveEverything()

        val outcome = service.submit(listOf(edit(file.path)))

        assertTrue(outcome.conflicted.isEmpty())
        assertTrue(outcome.unreviewable.isEmpty())
        assertNotNull("should be under review", service.editFor(file))
    }

    fun testAMissingFileIsUnreviewableRatherThanAConflict() {
        resolveTo(null)

        val outcome = service.submit(listOf(edit("/gone/App.kt")))

        assertEquals(1, outcome.unreviewable.size)
        assertTrue(outcome.conflicted.isEmpty())
    }
}
