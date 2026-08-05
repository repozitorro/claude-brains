package com.claudecode.chatplugin

import com.claudecode.chatplugin.model.ChatMessage
import com.claudecode.chatplugin.model.ClaudeSession
import com.claudecode.chatplugin.model.Role
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Holds every chat session for a project so multiple tasks can run as
 * independent, parallel conversations (each with its own CLI --resume id).
 *
 * Sessions are persisted per project ([Storage]), so conversations, their
 * `--resume` ids, model choice and cumulative usage survive an IDE restart.
 * Only the transcript text + reasoning is stored — per-turn tool/edit
 * annotations are ephemeral and not reconstructed on reload.
 */
@State(name = "ClaudeBrainsSessions", storages = [Storage("claude-brains-sessions.xml")])
@Service(Service.Level.PROJECT)
class ClaudeSessionManager(private val project: Project) :
    PersistentStateComponent<ClaudeSessionManager.State> {

    private val counter = AtomicInteger(0)
    val sessions: MutableList<ClaudeSession> = CopyOnWriteArrayList()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun fireChanged() = listeners.forEach { it() }

    fun createSession(name: String? = null): ClaudeSession {
        val displayName = name ?: "Chat ${counter.incrementAndGet()}"
        val session = ClaudeSession(displayName)
        sessions.add(session)
        fireChanged()
        return session
    }

    fun closeSession(session: ClaudeSession) {
        sessions.remove(session)
        fireChanged()
    }

    fun renameSession(session: ClaudeSession, newName: String) {
        session.displayName = newName
        fireChanged()
    }

    /** Returns the first session, creating one if none exist yet. */
    fun getOrCreateDefault(): ClaudeSession =
        sessions.firstOrNull() ?: createSession("Chat 1")

    // --- Persistence ---

    override fun getState(): State {
        val state = State()
        for (s in sessions) {
            val ps = PersistedSession()
            ps.displayName = s.displayName
            ps.cliSessionId = s.cliSessionId
            ps.selectedModel = s.selectedModel
            ps.totalCostUsd = s.totalCostUsd
            ps.totalInputTokens = s.totalInputTokens
            ps.totalOutputTokens = s.totalOutputTokens
            ps.turnCount = s.turnCount
            // Persisted state lands in a project XML file, so the transcript is
            // capped: only the most recent messages are kept, long ones are
            // truncated, and reasoning is dropped entirely (it's scratch work,
            // and by far the bulkiest part).
            s.messages.asSequence()
                .filter { !it.isStreaming }
                .toList()
                .takeLast(MAX_PERSISTED_MESSAGES)
                .forEach { m ->
                    val pm = PersistedMessage()
                    pm.role = m.role.name
                    pm.text = if (m.text.length > MAX_PERSISTED_TEXT) {
                        m.text.take(MAX_PERSISTED_TEXT) + "\n\n_(truncated when saved)_"
                    } else {
                        m.text
                    }
                    ps.messages.add(pm)
                }
            state.sessions.add(ps)
        }
        return state
    }

    override fun loadState(state: State) {
        sessions.clear()
        // Resume auto-numbering above the highest restored "Chat N", so a new
        // session can't collide with a persisted one's name.
        val highest = state.sessions.mapNotNull { AUTO_NAME.find(it.displayName)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        counter.set(highest)

        state.sessions.forEach { ps ->
            val session = ClaudeSession(ps.displayName.ifBlank { "Chat ${counter.incrementAndGet()}" })
            session.cliSessionId = ps.cliSessionId
            session.selectedModel = ps.selectedModel
            session.totalCostUsd = ps.totalCostUsd
            session.totalInputTokens = ps.totalInputTokens
            session.totalOutputTokens = ps.totalOutputTokens
            session.turnCount = ps.turnCount
            ps.messages.forEach { pm ->
                val role = runCatching { Role.valueOf(pm.role) }.getOrDefault(Role.ASSISTANT)
                session.messages.add(ChatMessage(role, pm.text, pm.thinking))
            }
            sessions.add(session)
        }
    }

    class State {
        var sessions: MutableList<PersistedSession> = mutableListOf()
    }

    class PersistedSession {
        var displayName: String = ""
        var cliSessionId: String? = null
        var selectedModel: String? = null
        var totalCostUsd: Double = 0.0
        var totalInputTokens: Long = 0
        var totalOutputTokens: Long = 0
        var turnCount: Int = 0
        var messages: MutableList<PersistedMessage> = mutableListOf()
    }

    class PersistedMessage {
        var role: String = Role.ASSISTANT.name
        var text: String = ""
        var thinking: String = ""
    }

    private companion object {
        val AUTO_NAME = Regex("""^Chat (\d+)$""")
        const val MAX_PERSISTED_MESSAGES = 100
        const val MAX_PERSISTED_TEXT = 20_000
    }
}
