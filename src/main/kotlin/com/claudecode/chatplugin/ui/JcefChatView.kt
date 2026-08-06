package com.claudecode.chatplugin.ui

import com.claudecode.chatplugin.model.ChatMessage
import com.claudecode.chatplugin.model.Role
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.Color
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.swing.JComponent

/**
 * Rich [ChatView] backed by an embedded Chromium browser (JCEF). Renders the
 * whole conversation as one HTML page (see `webview/chat.html`) with real
 * streaming markdown, highlight.js syntax highlighting and copy-code buttons.
 *
 * Diff/revert link clicks are forwarded back to Kotlin via a [JBCefJSQuery].
 */
class JcefChatView(parent: Disposable, private val onLink: (String) -> Unit) : ChatView {

    private val browser = JBCefBrowser()
    private val linkQuery = JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)

    @Volatile
    private var loaded = false
    private val pending = ArrayList<String>()

    init {
        Disposer.register(parent, browser)
        Disposer.register(parent, linkQuery)

        linkQuery.addHandler { request ->
            onLink(request)
            JBCefJSQuery.Response(null)
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                synchronized(pending) {
                    loaded = true
                    pending.forEach { exec(it) }
                    pending.clear()
                }
            }
        }, browser.cefBrowser)

        browser.loadHTML(buildPage())
    }

    override val component: JComponent get() = browser.component

    override fun clear() = run("window.cbClear();")

    override fun render(index: Int, message: ChatMessage) {
        val role = when (message.role) {
            Role.USER -> "user"
            Role.SYSTEM -> "system"
            else -> "assistant"
        }
        val html = MessageRenderer.fragment(message, index, message.isStreaming)
        val b64 = Base64.getEncoder().encodeToString(html.toByteArray(StandardCharsets.UTF_8))
        run("window.cbRender($index, '$role', '$b64', ${message.isStreaming});")
    }

    override fun scrollToBottom() { /* the page auto-scrolls after each render */ }

    override fun dispose() {
        Disposer.dispose(browser)
    }

    private fun run(js: String) {
        synchronized(pending) {
            if (loaded) exec(js) else pending.add(js)
        }
    }

    private fun exec(js: String) {
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url ?: "about:blank", 0)
    }

    // --- Page assembly ---

    private fun buildPage(): String {
        val dark = isDark(UIUtil.getPanelBackground())
        val theme = resource(if (dark) "/webview/hljs-dark.css" else "/webview/hljs-light.css")
        val hljs = resource("/webview/highlight.min.js")
        val font = UIUtil.getLabelFont()

        return resource("/webview/chat.html")
            .replace("__HLJS_THEME__", theme)
            .replace("__HLJS_JS__", hljs)
            .replace("__QUERY_INJECT__", linkQuery.inject("payload"))
            .replace("__BG__", hex(UIUtil.getPanelBackground()))
            .replace("__FG__", hex(UIUtil.getLabelForeground()))
            .replace("__MUTED__", hex(UIUtil.getInactiveTextColor()))
            .replace("__USER_BG__", hex(blend(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.08)))
            .replace("__BORDER__", hex(blend(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.20)))
            // Claude's own clay tone, lightened on dark grounds so it stays legible.
            .replace("__ACCENT__", if (dark) "#E08B68" else "#C4643F")
            .replace("__FONT__", "'${font.family}'")
            .replace("__FONTSIZE__", font.size.toString())
    }

    private fun resource(path: String): String =
        javaClass.getResourceAsStream(path)?.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            ?: error("Missing bundled resource: $path")

    private fun hex(c: Color): String = "#%02x%02x%02x".format(c.red, c.green, c.blue)

    private fun isDark(c: Color): Boolean =
        (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue) < 128

    private fun blend(a: Color, b: Color, ratio: Double): Color = Color(
        (a.red * (1 - ratio) + b.red * ratio).toInt().coerceIn(0, 255),
        (a.green * (1 - ratio) + b.green * ratio).toInt().coerceIn(0, 255),
        (a.blue * (1 - ratio) + b.blue * ratio).toInt().coerceIn(0, 255)
    )

    companion object {
        /** @see JcefSupport.isAvailable */
        fun isAvailable(): Boolean = JcefSupport.isAvailable()
    }
}
