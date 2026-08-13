package com.claudecode.chatplugin.review

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.toolbar.floating.AbstractFloatingToolbarProvider
import com.intellij.openapi.editor.toolbar.floating.FloatingToolbarComponent
import com.intellij.openapi.vfs.VirtualFile

/**
 * Puts the review controls on the platform's own floating editor toolbar.
 *
 * This replaces a panel added straight to the editor's content component and
 * positioned by hand on every scroll. That version had to re-implement things
 * the platform already does — placement, layering, showing and hiding — and got
 * one of them wrong in a way nothing could catch: its buttons were never laid
 * out, so they painted where you saw them but a click passed through to the
 * panel behind, and the navigation arrows looked dead.
 *
 * Here the platform owns all of that. What is left is the only part that is
 * genuinely ours: deciding when this file has something to review.
 */
class ReviewFloatingToolbarProvider :
    AbstractFloatingToolbarProvider(ACTION_GROUP_ID) {

    /**
     * The strip stays put while there are changes to decide on. Fading it out
     * on mouse-away would hide the count of what is left, which is the thing
     * that tells you the review is unfinished.
     */
    override val autoHideable: Boolean = false

    // `isApplicable` is deliberately not overridden: it is deprecated, and on
    // 2026.2 it is scheduled for removal. It isn't needed either — whether the
    // strip is on screen is decided below by showing and hiding it, which is
    // the same decision made in the place that can also change its mind when a
    // change is accepted somewhere else.

    override fun register(
        dataContext: DataContext,
        component: FloatingToolbarComponent,
        parentDisposable: Disposable
    ) {
        val project = dataContext.getData(CommonDataKeys.PROJECT) ?: return
        val file = dataContext.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        fun sync() {
            if (project.isDisposed) return
            if (EditReviewService.getInstance(project).editFor(file) != null) {
                component.scheduleShow()
            } else {
                component.scheduleHide()
            }
        }

        // Accepting or rejecting anywhere — the gutter, the chat, another
        // editor — changes whether this file still has anything to show.
        EditReviewService.getInstance(project).addChangeListener(parentDisposable) {
            ApplicationManager.getApplication().invokeLater(::sync)
        }
        sync()
    }

    private companion object {
        /** Must match the group declared in plugin.xml. */
        const val ACTION_GROUP_ID = "ClaudeCodeChat.ReviewToolbar"
    }
}
