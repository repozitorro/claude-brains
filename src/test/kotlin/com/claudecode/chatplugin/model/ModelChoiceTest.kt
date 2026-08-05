package com.claudecode.chatplugin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelChoiceTest {

    @Test
    fun `specific versions are offered, not only family aliases`() {
        val ids = ModelChoice.ALL.mapNotNull { it.id }

        assertTrue(ids.containsAll(listOf("claude-opus-5", "claude-opus-4-8")))
        assertTrue(ids.containsAll(listOf("opus", "sonnet", "haiku"))) // aliases still available
    }

    @Test
    fun `the default choice passes no model flag`() {
        assertNull(ModelChoice.DEFAULT.id)
        assertSame(ModelChoice.DEFAULT, ModelChoice.forId(null))
        assertSame(ModelChoice.DEFAULT, ModelChoice.forId(""))
    }

    @Test
    fun `a known id maps back to its labelled entry`() {
        assertEquals("Opus 4.8", ModelChoice.forId("claude-opus-4-8").label)
        assertEquals("Opus (latest)", ModelChoice.forId("opus").label)
    }

    @Test
    fun `an unknown id survives instead of silently reading as default`() {
        // e.g. a model pinned by an older build, or typed into settings by hand
        val choice = ModelChoice.forId("claude-some-future-model")

        assertEquals("claude-some-future-model", choice.id)
        assertEquals("claude-some-future-model", choice.label)
    }

    @Test
    fun `permission modes match what the CLI accepts`() {
        // From `claude --help` (2.1.205): acceptEdits, auto, bypassPermissions,
        // manual, dontAsk, plan. Anything else would make the CLI reject the turn.
        val accepted = setOf("acceptEdits", "auto", "bypassPermissions", "manual", "dontAsk", "plan")

        val ids = PermissionChoice.ALL.mapNotNull { it.id }
        assertEquals(accepted, ids.toSet())
    }

    @Test
    fun `inheriting passes no permission flag`() {
        assertNull(PermissionChoice.INHERIT.id)
        assertSame(PermissionChoice.INHERIT, PermissionChoice.forId(null))
        assertSame(PermissionChoice.INHERIT, PermissionChoice.forId(""))
        // A mode the CLI no longer accepts falls back to inheriting rather than
        // being sent through and failing the turn.
        assertSame(PermissionChoice.INHERIT, PermissionChoice.forId("default"))
    }
}
