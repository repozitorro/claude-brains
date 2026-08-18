package com.claudecode.chatplugin.permissions

import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The transport, exercised over real HTTP.
 *
 * Every rule below was read off a live CLI rather than a specification, and
 * each one was a thing that had to be right before it would run anything: the
 * client opens a GET stream, sends notifications that must not be answered,
 * probes for methods this server has never heard of, and asks for
 * `text/event-stream` while meaning it. Until now all of that rested on a
 * single end-to-end run that costs money to repeat; here it costs nothing, and
 * a regression in any of it shows up as a named failure rather than as
 * "permissions stopped working".
 */
class ApprovalHttpServerTest {

    private val token = "test-token"
    private lateinit var server: ApprovalHttpServer

    /** What the server will answer with; swapped per test. */
    @Volatile
    private var decision: ApprovalDecision = ApprovalDecision.Allow(null)

    /** Released once a tool call has reached the decision function. */
    private val asked = CountDownLatch(1)

    @Before
    fun start() {
        server = ApprovalHttpServer { requested ->
            if (requested != token) null
            else McpApprovalProtocol { _, _, _ ->
                asked.countDown()
                decision
            }
        }
        assertTrue("the server should start on loopback", server.start())
    }

    @After
    fun stop() {
        server.stop()
    }

    private fun post(
        body: String,
        path: String = token,
        accept: String = "application/json, text/event-stream",
        method: String = "POST"
    ): Pair<Int, String> {
        val url = URL(server.urlFor(path)!!)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Accept", accept)
            setRequestProperty("Content-Type", "application/json")
            if (method == "POST") {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = connection.responseCode
        val text = runCatching {
            (if (code >= 400) connection.errorStream else connection.inputStream)
                ?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
        }.getOrDefault("")
        connection.disconnect()
        return code to text
    }

    @Test
    fun `an unknown token is not served at all`() {
        // The path is the only thing keeping other processes on this machine
        // out, so a wrong one has to be a closed door, not a quiet failure.
        val (code, _) = post("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""", path = "not-the-token")
        assertEquals(404, code)
    }

    @Test
    fun `the stream the client opens for server messages is declined`() {
        // It opens a GET expecting server-initiated messages. There are none,
        // and saying so is the whole answer.
        val (code, _) = post("", method = "GET")
        assertEquals(405, code)
    }

    @Test
    fun `a notification is accepted and not answered`() {
        val (code, body) = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, code)
        assertTrue(body.isEmpty())
    }

    @Test
    fun `something that is not JSON is refused`() {
        val (code, _) = post("this is not json")
        assertEquals(400, code)
    }

    @Test
    fun `a client asking for a stream is answered as one`() {
        // Answering with plain JSON here is the difference between a working
        // server and a silent one.
        val (code, body) = post("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
        assertEquals(200, code)
        assertTrue("expected SSE framing, got: $body", body.startsWith("data: "))
        val payload = JsonParser.parseString(body.removePrefix("data: ").trim()).asJsonObject
        assertEquals("2.0", payload.get("jsonrpc").asString)
    }

    @Test
    fun `a client that only takes JSON is answered with JSON`() {
        val (code, body) = post("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""", accept = "application/json")
        assertEquals(200, code)
        assertTrue(body.startsWith("{"))
    }

    @Test
    fun `a tool call carries the decision back`() {
        decision = ApprovalDecision.Deny("The user declined this in the IDE.")
        val (code, body) = post(
            """
            {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
              "name":"approval_prompt",
              "arguments":{"tool_name":"Bash","input":{"command":"npm test"},"tool_use_id":"toolu_01"}
            }}
            """
        )
        assertEquals(200, code)
        assertTrue("the request should have reached the asker", asked.await(5, TimeUnit.SECONDS))

        val result = JsonParser.parseString(body.removePrefix("data: ").trim())
            .asJsonObject.getAsJsonObject("result")
        val text = result.getAsJsonArray("content")[0].asJsonObject.get("text").asString
        val answer = JsonParser.parseString(text).asJsonObject
        assertEquals("deny", answer.get("behavior").asString)
    }

    @Test
    fun `a stopped server keeps nothing open`() {
        server.stop()
        val failed = runCatching { post("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""") }.isFailure
        assertTrue("the port should be closed once stopped", failed)
    }

    @Test
    fun `each endpoint gets a token nothing else would guess`() {
        val first = ApprovalHttpServer.newToken()
        val second = ApprovalHttpServer.newToken()
        assertTrue(first.length >= 32)
        assertTrue("two tokens must not match", first != second)
    }
}
