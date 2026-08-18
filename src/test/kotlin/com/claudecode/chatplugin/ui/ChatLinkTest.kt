package com.claudecode.chatplugin.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one string that both rendering surfaces drive clicks through.
 *
 * The Swing fallback has only an `href` and a `HyperlinkListener`, so every
 * action in the transcript is addressed by text. That text used to be written
 * by the renderer and taken apart by the panel with nothing holding the two
 * together: a field added at one end and not the other would have silently
 * stopped a button from working, with no failure anywhere to notice.
 */
class ChatLinkTest {

    @Test
    fun `what the renderer writes is what the panel reads`() {
        val link = ChatLink(messageIndex = 5, action = "askrun", itemIndex = 4)
        assertEquals(link, ChatLink.parse(link.token()))
    }

    @Test
    fun `the token has the shape the page has always emitted`() {
        // Pinned rather than derived: pages already rendered in saved
        // transcripts carry this exact form.
        assertEquals("claudebrains:5:askrun:4", ChatLink(5, "askrun", 4).token())
    }

    @Test
    fun `an ordinary link is not one of ours`() {
        assertNull(ChatLink.parse("https://example.com/a/b"))
        assertNull(ChatLink.parse("file:///c:/tmp/x.kt"))
        assertNull(ChatLink.parse(""))
    }

    @Test
    fun `a malformed token is refused rather than half-read`() {
        // Half-reading one means acting on the wrong message, which for
        // `revert` means writing over a file nobody pointed at.
        assertNull(ChatLink.parse("claudebrains:5:askrun"))
        assertNull(ChatLink.parse("claudebrains:x:askrun:4"))
        assertNull(ChatLink.parse("claudebrains:5:askrun:y"))
        assertNull(ChatLink.parse("claudebrains:5::4"))
        assertNull(ChatLink.parse("somethingelse:5:askrun:4"))
    }

    @Test
    fun `an index that addresses the whole message survives the round trip`() {
        // `revertall` uses -1, meaning "not one item, all of them".
        val link = ChatLink(0, "revertall", -1)
        assertEquals(link, ChatLink.parse(link.token()))
    }
}
