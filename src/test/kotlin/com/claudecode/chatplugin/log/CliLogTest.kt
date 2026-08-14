package com.claudecode.chatplugin.log

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The record of what the CLI was asked and what it said.
 *
 * Its whole purpose is to be readable *after* something has gone wrong, which
 * puts two demands on it: it has to keep recording when nobody is watching,
 * and it must not grow without limit while doing so.
 */
class CliLogTest : BasePlatformTestCase() {

    private val log get() = CliLog.getInstance(project)

    /**
     * The fixture reuses one project across the methods in this class, and the
     * log is a project service — so without this each test would be reading the
     * one before it.
     */
    override fun setUp() {
        super.setUp()
        log.clear()
    }

    fun testItRecordsWithNobodyListening() {
        // The console is opened after the problem, never before it.
        log.record(CliLog.Kind.COMMAND, "claude -p --output-format stream-json")
        log.record(CliLog.Kind.ERROR, "boom")

        val entries = log.snapshot()
        assertEquals(2, entries.size)
        assertEquals(CliLog.Kind.COMMAND, entries.first().kind)
        assertEquals("boom", entries.last().text)
    }

    fun testOldLinesFallOffRatherThanAccumulating() {
        repeat(CliLog.MAX_ENTRIES + 50) { log.record(CliLog.Kind.OUTPUT, "line $it") }

        assertEquals(CliLog.MAX_ENTRIES, log.size)
        // The ones kept are the recent ones: the old lines explain nothing about
        // what just happened.
        assertTrue(log.snapshot().last().text.endsWith("${CliLog.MAX_ENTRIES + 49}"))
    }

    fun testASingleEnormousLineIsClamped() {
        // One tool result can be an entire file, and the log is for reading.
        log.record(CliLog.Kind.OUTPUT, "x".repeat(CliLog.MAX_LINE * 3))

        val text = log.snapshot().single().text
        assertTrue("clamped", text.length < CliLog.MAX_LINE * 2)
        assertTrue("and says so", text.contains("chars"))
    }

    fun testBlankLinesAreNotRecorded() {
        log.record(CliLog.Kind.OUTPUT, "")
        log.record(CliLog.Kind.OUTPUT, "   ")

        assertTrue(log.snapshot().isEmpty())
    }

    fun testAListenerSeesWhatArrivesAfterItSubscribes() {
        val seen = mutableListOf<String>()
        log.addListener(testRootDisposable) { seen += it.text }

        log.record(CliLog.Kind.INFO, "exited with code 0")

        assertEquals(listOf("exited with code 0"), seen)
    }
}
