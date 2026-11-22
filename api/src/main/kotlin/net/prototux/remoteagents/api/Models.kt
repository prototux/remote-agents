package net.prototux.remoteagents.api

import kotlinx.serialization.Serializable

@Serializable
data class SessionSummary(
    val id: String,
    val title: String? = null,
    /** ISO-8601 instant of the last activity, as the server sends it. */
    val timestamp: String? = null,
    val cwd: String? = null,
    val provider: String? = null,
    /** `idle | busy | awaiting`; the server back-fills it for the first 10 sessions only. */
    val status: String? = null,
)

@Serializable
internal data class SessionsResponse(val sessions: List<SessionSummary> = emptyList(), val error: String? = null)

@Serializable
data class Account(val email: String? = null, val organization: String? = null, val subscriptionType: String? = null)

@Serializable
data class InfoExtra(val expose: String? = null)

@Serializable
data class ServerInfo(
    val account: Account? = null,
    val model: String? = null,
    val version: String? = null,
    val provider: String? = null,
    val extra: InfoExtra? = null,
)

@Serializable
data class UpdateCheck(
    val currentVersion: String? = null,
    val newestVersion: String? = null,
    val updateAvailable: Boolean? = null,
)

@Serializable
data class PromptAccepted(val ok: Boolean = false, val sessionId: String, val provider: String? = null)

@Serializable
data class StatusResponse(val state: String, val sessionId: String? = null, val provider: String? = null)

@Serializable
data class HistoryEntry(val role: String, val text: String = "")

@Serializable
internal data class HistoryResponse(val history: List<HistoryEntry> = emptyList())

/** `/messages` answer: the ring buffer after a given id, plus the session state. */
data class MessagesPage(val messages: List<AgentMessage>, val state: String?)

/** Session states as `/status` and `/sessions` report them. */
object SessionState {
    const val IDLE = "idle"
    const val BUSY = "busy"
    const val AWAITING = "awaiting"
}
