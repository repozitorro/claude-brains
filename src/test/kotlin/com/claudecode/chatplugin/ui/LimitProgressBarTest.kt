package com.claudecode.chatplugin.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitProgressBarTest {

    @Test
    fun `stays hidden until a figure is actually reported`() {
        val bar = LimitProgressBar()

        assertFalse("nothing reported yet, so nothing to imply", bar.isVisible)
        assertNull(bar.percent)

        bar.percent = 42

        assertTrue(bar.isVisible)
        assertEquals(42, bar.percent)
    }

    @Test
    fun `hides again when the figure goes away`() {
        val bar = LimitProgressBar().apply { percent = 42 }

        bar.percent = null

        assertFalse(bar.isVisible)
    }

    @Test
    fun `out-of-range values are clamped rather than drawn past the end`() {
        val bar = LimitProgressBar()

        bar.percent = 250
        assertEquals(100, bar.percent)

        bar.percent = -10
        assertEquals(0, bar.percent)
    }
}
