package com.claudecode.chatplugin.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Action links have to work the moment they are written into the page.
 *
 * They did not. The page wired each `a[data-cb]` in a pass that runs only for
 * content which has finished streaming — fine for diff and revert links, which
 * appear after a turn, and wrong for a permission card, which appears *during*
 * one, on the message being written. Its buttons were never wired, so the
 * anchor navigated: clicking **Run** replaced the entire conversation with
 * `ERR_UNKNOWN_URL_SCHEME` for `claudebrains:5:askrun:4`, with the turn still
 * parked waiting for the answer that would now never come.
 *
 * A delegated listener cannot have that bug — it exists before the markup does
 * — so this pins the page to that approach rather than to the state of any
 * particular render pass.
 */
class ChatPageLinkWiringTest {

    private val page: String =
        checkNotNull(javaClass.getResourceAsStream("/webview/chat.html")) { "chat.html is not on the classpath" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `clicks are caught by delegation, not by wiring each link`() {
        assertTrue(
            "the page must listen on the document for a[data-cb]",
            page.contains("document.addEventListener('click'") && page.contains("closest('a[data-cb]')")
        )
    }

    @Test
    fun `no link is left to a per-element pass`() {
        // The old approach marked each anchor as it wired it. If that mark is
        // back, so is the streaming hole it came with.
        assertFalse("per-element wiring has returned", page.contains("__cbWired"))
    }

    @Test
    fun `the default navigation is always cancelled`() {
        // Without this the anchor is followed and the whole view is gone —
        // there is no back button in a chat panel.
        val handler = page.substringAfter("document.addEventListener('click'")
        assertTrue(handler.substringBefore("});").contains("e.preventDefault()"))
    }
}
