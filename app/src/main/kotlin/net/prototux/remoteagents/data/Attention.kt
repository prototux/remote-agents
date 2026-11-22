package net.prototux.remoteagents.data

import net.prototux.remoteagents.api.AgentMessage
import net.prototux.remoteagents.api.AgentsState
import net.prototux.remoteagents.api.Transcript
import net.prototux.remoteagents.api.TranscriptItem

data class SessionKey(val serverId: String, val sessionId: String)

/** Something the owner should learn about (or stop being told about). Pure data; the Notifier renders it. */
sealed interface Alert {
    val key: SessionKey

    data class Permission(override val key: SessionKey, val request: AgentMessage.PermissionRequest) : Alert
    data class Question(override val key: SessionKey, val question: AgentMessage.UserQuestion) : Alert
    data class Finished(override val key: SessionKey, val result: AgentMessage.Result, val lastText: String?) : Alert
    data class Failed(override val key: SessionKey, val message: String) : Alert
    data class Clear(override val key: SessionKey, val kind: Kind) : Alert

    enum class Kind { Permission, Question, Finished, Failed }
}

/**
 * When to alert. A snapshot (the backlog read on opening a session) only alerts for what is still
 * pending, so old, already-answered requests never notify; live messages alert as they come.
 */
object AttentionRules {
    fun onSnapshot(key: SessionKey, t: Transcript): List<Alert> = listOfNotNull(
        t.pendingPermission?.let { Alert.Permission(key, it) },
        t.pendingQuestion?.let { Alert.Question(key, it) },
    )

    fun onLive(key: SessionKey, m: AgentMessage, after: Transcript): List<Alert> = when (m) {
        is AgentMessage.PermissionRequest -> listOf(Alert.Permission(key, m))
        is AgentMessage.PermissionResult -> listOf(Alert.Clear(key, Alert.Kind.Permission))
        is AgentMessage.UserQuestion -> listOf(Alert.Question(key, m))
        is AgentMessage.QuestionAnswer -> listOf(Alert.Clear(key, Alert.Kind.Question))
        is AgentMessage.Result -> listOf(
            Alert.Clear(key, Alert.Kind.Permission), Alert.Clear(key, Alert.Kind.Question),
            Alert.Finished(key, m, after.items.lastOrNull { it is TranscriptItem.Text }?.let { (it as TranscriptItem.Text).text }),
        )
        is AgentMessage.Error -> listOf(Alert.Failed(key, m.message))
        is AgentMessage.UserPrompt -> listOf(Alert.Clear(key, Alert.Kind.Finished), Alert.Clear(key, Alert.Kind.Failed))
        is AgentMessage.Status -> if (m.state == "idle") listOf(Alert.Clear(key, Alert.Kind.Permission), Alert.Clear(key, Alert.Kind.Question)) else emptyList()
        else -> emptyList()
    }

    /**
     * Whether a session's stream stays open: while it is on screen, or while it is watched and working.
     * The foreground service runs exactly while some session is watched and working. Running subagents or
     * workflow runs count as working (agentd S11): a background workflow runs while the session itself is
     * idle, and its progress is what the owner follows. agentd ends an agent at its task notification, and a stale one after 45 min
     * (2 h while the session's process lives).
     */
    fun keepOpen(onScreen: Boolean, watched: Boolean, t: Transcript, agents: AgentsState = AgentsState()) =
        onScreen || needsService(watched, t, agents)
    fun needsService(watched: Boolean, t: Transcript, agents: AgentsState = AgentsState()) = watched && (t.busy || agents.anyRunning)
}
