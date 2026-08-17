package com.claudecode.chatplugin.permissions

import com.claudecode.chatplugin.model.ClaudeSession
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Lets the CLI ask before it acts, by being the thing it asks.
 *
 * The CLI will consult an MCP tool for permission and wait for the answer
 * (`--permission-prompt-tool`), but it insists that tool be an MCP tool. Rather
 * than ship a helper process to be one, the IDE is one: a small HTTP server on
 * loopback, named to the CLI through `--mcp-config`. Nothing to install,
 * nothing to keep in step with the plugin's own version, and the answer comes
 * from the same JVM the buttons are in.
 *
 * One endpoint per chat, because the question has to reach the right tab and
 * the CLI's request says nothing about which one it came from. The path carries
 * a random token, so nothing else on the machine can drive this or read what is
 * being asked.
 */
@Service(Service.Level.PROJECT)
class ApprovalService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(ApprovalService::class.java)

    private class Endpoint(
        val session: ClaudeSession,
        val onRequest: (ApprovalRequest) -> Unit
    ) {
        val pending: MutableSet<ApprovalRequest> = ConcurrentHashMap.newKeySet()
    }

    private val endpoints = ConcurrentHashMap<String, Endpoint>()

    private val http = ApprovalHttpServer { token ->
        endpoints[token]?.let { endpoint ->
            McpApprovalProtocol { toolName, input, toolUseId -> askUser(endpoint, toolName, input, toolUseId) }
        }
    }

    /**
     * Opens an endpoint for [session] and returns its URL, or null if no server
     * could be started — in which case the caller runs without asking, exactly
     * as it did before this existed.
     */
    fun endpointFor(session: ClaudeSession, parent: Disposable, onRequest: (ApprovalRequest) -> Unit): String? {
        if (!http.start()) return null
        val token = ApprovalHttpServer.newToken()
        val endpoint = Endpoint(session, onRequest)
        endpoints[token] = endpoint
        Disposer.register(parent) {
            endpoints.remove(token)
            cancelAll(endpoint, "The chat was closed.")
        }
        return http.urlFor(token)
    }

    /**
     * The `--mcp-config` file for [endpointUrl], written once and reused.
     *
     * On disk rather than on the command line because the CLI cannot be given
     * this JSON as an argument on Windows — see [McpApprovalProtocol.mcpConfigJson].
     * The file holds a loopback URL and a token, and lives in the IDE's own
     * temp area with everything else of that kind.
     */
    fun mcpConfigPath(endpointUrl: String): String? = configFiles.computeIfAbsent(endpointUrl) {
        runCatching {
            java.io.File.createTempFile("claude-brains-approvals", ".json").apply {
                deleteOnExit()
                writeText(McpApprovalProtocol.mcpConfigJson(endpointUrl), Charsets.UTF_8)
            }.absolutePath
        }.getOrElse {
            log.warn("Could not write the approval MCP config; this chat will fall back to refusals", it)
            ""
        }
    }.takeIf { it.isNotEmpty() }

    private val configFiles = ConcurrentHashMap<String, String>()

    /**
     * Answers every question still open for [session] with a refusal.
     *
     * Called when a turn is stopped or its process dies: the CLI is no longer
     * there to receive the answer, and a card left asking is a card that will
     * never be answered.
     */
    fun cancelPending(session: ClaudeSession, reason: String) {
        endpoints.values.filter { it.session === session }.forEach { cancelAll(it, reason) }
    }

    private fun cancelAll(endpoint: Endpoint, reason: String) {
        endpoint.pending.toList().forEach { it.decide(ApprovalDecision.Deny(reason)) }
        endpoint.pending.clear()
    }

    private fun askUser(
        endpoint: Endpoint,
        toolName: String,
        input: JsonObject,
        toolUseId: String?
    ): ApprovalDecision {
        // Already answered once, for this program, in this chat. Asking again
        // would not be a safeguard — it would be the same answer, typed again.
        AutoApproval.key(toolName, input)
            ?.takeIf { endpoint.session.autoApproved.contains(it) }
            ?.let { return ApprovalDecision.Allow(input, remembered = true) }

        val request = ApprovalRequest(
            toolName = toolName,
            input = input,
            toolUseId = toolUseId,
            summary = ApprovalSummary.of(toolName, input, project.basePath)
        )
        endpoint.pending.add(request)
        try {
            endpoint.onRequest(request)
        } catch (e: Exception) {
            log.warn("Could not show the approval card", e)
            endpoint.pending.remove(request)
            return ApprovalDecision.Deny("The IDE could not show this request.")
        }

        return try {
            request.future.get(WAIT_MINUTES, TimeUnit.MINUTES)
        } catch (e: TimeoutException) {
            // Better a refusal than a turn that hangs until the IDE is closed.
            val timedOut = ApprovalDecision.Deny("Nobody answered in $WAIT_MINUTES minutes, so this was not run.")
            request.decide(timedOut)
            timedOut
        } catch (e: Exception) {
            ApprovalDecision.Deny("The IDE stopped waiting for an answer.")
        } finally {
            endpoint.pending.remove(request)
        }
    }

    override fun dispose() {
        endpoints.values.forEach { cancelAll(it, "The IDE is shutting down.") }
        endpoints.clear()
        configFiles.values.forEach { runCatching { java.io.File(it).delete() } }
        configFiles.clear()
        http.stop()
    }

    companion object {
        fun getInstance(project: Project): ApprovalService = project.getService(ApprovalService::class.java)

        /** How long a question may stand unanswered before it answers itself. */
        private const val WAIT_MINUTES = 10L
    }
}
