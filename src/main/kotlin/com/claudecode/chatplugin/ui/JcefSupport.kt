package com.claudecode.chatplugin.ui

import com.intellij.openapi.diagnostic.Logger

/**
 * Whether an embedded browser can be created in this IDE/runtime.
 *
 * Every site that wants JCEF has to ask here first, and has to survive a "no".
 * The check catches [Throwable] rather than exceptions alone: in 2026.2 the
 * browser moved into its own bundled plugin, and merely touching `JBCefApp`
 * threw `NoClassDefFoundError` — which took the whole tool window down with it.
 * An optional capability going missing must cost the rich rendering, never the
 * feature.
 *
 * The reference to `JBCefApp` is deliberately confined to [isAvailable] so that
 * a caller which asks first never resolves the class on a platform that lacks
 * it. For the same reason callers must keep their own JCEF construction in a
 * separate method, not inline in a branch.
 */
object JcefSupport {

    private val LOG = Logger.getInstance(JcefSupport::class.java)

    fun isAvailable(): Boolean = try {
        com.intellij.ui.jcef.JBCefApp.isSupported()
    } catch (e: Throwable) {
        LOG.info("Embedded browser unavailable, falling back to the Swing rendering: $e")
        false
    }
}
