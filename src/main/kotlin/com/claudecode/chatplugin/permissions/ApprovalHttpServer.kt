package com.claudecode.chatplugin.permissions

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * The transport half: an HTTP server on loopback that speaks MCP.
 *
 * Kept apart from [ApprovalService] so it needs no project and no UI, which is
 * what lets it be pointed at a real `claude` process in a test. Everything here
 * was shaped by watching one: the client opens a GET stream for
 * server-initiated messages, sends notifications that must not be answered, and
 * probes for methods this server does not have.
 */
class ApprovalHttpServer(private val protocolFor: (token: String) -> McpApprovalProtocol?) {

    private val log = Logger.getInstance(ApprovalHttpServer::class.java)

    @Volatile
    private var server: HttpServer? = null

    /** The port in use, or null if the server never started. */
    val port: Int? get() = server?.address?.port

    /** Starts on a free loopback port. Returns false if it could not start. */
    @Synchronized
    fun start(): Boolean {
        if (server != null) return true
        return try {
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).also { created ->
                created.createContext("/$PATH_PREFIX") { handle(it) }
                // Each call parks a thread for as long as someone takes to
                // answer, so this cannot be the single-threaded default.
                created.executor = Executors.newCachedThreadPool { r ->
                    Thread(r, "claude-brains-approvals").apply { isDaemon = true }
                }
                created.start()
                server = created
                log.info("Approval endpoint listening on 127.0.0.1:${created.address.port}")
            }
            true
        } catch (e: Exception) {
            log.warn("Could not start the approval endpoint", e)
            false
        }
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    fun urlFor(token: String): String? = port?.let { "http://127.0.0.1:$it/$PATH_PREFIX/$token" }

    private fun handle(exchange: HttpExchange) {
        try {
            val token = exchange.requestURI.path.removePrefix("/$PATH_PREFIX/").substringBefore('/')
            val protocol = protocolFor(token)
            when {
                protocol == null -> respond(exchange, 404, "")
                // The client opens a GET stream for server-initiated messages.
                // There are none, so saying so is the whole answer.
                exchange.requestMethod != "POST" -> respond(exchange, 405, "")
                else -> {
                    val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                    val request = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                    when (val response: JsonObject? = request?.let { protocol.handle(it) }) {
                        null -> respond(exchange, if (request == null) 400 else 202, "")
                        else -> respondRpc(exchange, response)
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Approval endpoint failed to answer", e)
            runCatching { respond(exchange, 500, "") }
        }
    }

    /**
     * Answers in whichever of the two shapes the client asked for.
     *
     * Streamable HTTP lets the server reply with a plain JSON body or with a
     * one-event SSE stream, and the client says which it will take in `Accept`.
     * This one sends `application/json, text/event-stream` and does mean it:
     * answering with JSON when it asked for a stream is what a working server
     * and a silent one differ by.
     */
    private fun respondRpc(exchange: HttpExchange, response: JsonObject) {
        val accept = exchange.requestHeaders.getFirst("Accept").orEmpty()
        if (!accept.contains("text/event-stream")) {
            respond(exchange, 200, response.toString())
            return
        }
        val body = "data: $response\n\n".toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
        exchange.close()
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    companion object {
        private const val PATH_PREFIX = "claude-brains-approvals"

        /** A path nothing else on the machine can guess. */
        fun newToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
