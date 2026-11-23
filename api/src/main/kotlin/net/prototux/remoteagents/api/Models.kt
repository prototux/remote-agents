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
    /** agentd extension ([Liveness]); absent on even-terminal. */
    val liveness: String? = null,
    /** agentd extension ([Drive]): how a prompt reaches the session; absent on even-terminal. */
    val drive: String? = null,
    /** agentd extension: the phone can answer this session's permission requests. */
    val canApprove: Boolean? = null,
)

@Serializable
internal data class SessionsResponse(val sessions: List<SessionSummary> = emptyList(), val error: String? = null)

@Serializable
data class Account(val email: String? = null, val organization: String? = null, val subscriptionType: String? = null)

/** `/info` `server` (agentd): which server answers, and the providers it knows. even-terminal sends none. */
@Serializable
data class ServerIdent(val name: String? = null, val version: String? = null, val providers: List<ProviderInfo>? = null)

/** agentd `/info` `server.providers` item: a provider this server knows, whether it runs here and what it supports. */
@Serializable
data class ProviderInfo(
    val name: String = "",
    val label: String? = null,
    /** The agent was found on the server's machine; otherwise the name is accepted but answers 501. */
    val installed: Boolean = false,
    /** Written from the agent's documentation only, never run against it (Codex, opencode, Hermes). */
    val experimental: Boolean = false,
    val canFollow: Boolean = false,
    val canHost: Boolean = false,
    val canDriveTerminal: Boolean = false,
    val canApprove: Boolean = false,
)

@Serializable
data class InfoExtra(val expose: String? = null, val server: ServerIdent? = null)

@Serializable
data class ServerInfo(
    val account: Account? = null,
    val model: String? = null,
    val version: String? = null,
    val provider: String? = null,
    val extra: InfoExtra? = null,
    val server: ServerIdent? = null,
) {
    /**
     * agentd puts `server` at the top level of `/info`; `extra.server` is read too, so either place counts.
     * Only agentd gets `agents=1`, `/agents` and `agentId=`; even-terminal never does.
     */
    val isAgentd: Boolean get() = (server ?: extra?.server)?.name == "agentd"

    /** agentd's `server.providers`; null when the server does not list them (even-terminal, older agentd). */
    val providers: List<ProviderInfo>? get() = (server ?: extra?.server)?.providers
}

@Serializable
data class UpdateCheck(
    val currentVersion: String? = null,
    val newestVersion: String? = null,
    val updateAvailable: Boolean? = null,
)

@Serializable
data class PromptAccepted(val ok: Boolean = false, val sessionId: String, val provider: String? = null)

@Serializable
data class StatusResponse(
    val state: String,
    val sessionId: String? = null,
    val provider: String? = null,
    // agentd extensions, as on [SessionSummary].
    val liveness: String? = null,
    val drive: String? = null,
    val canApprove: Boolean? = null,
)

@Serializable
data class HistoryEntry(val role: String, val text: String = "")

@Serializable
internal data class HistoryResponse(val history: List<HistoryEntry> = emptyList())

/**
 * `/messages` answer: the ring buffer after a given id, plus the session state. [agentId] is agentd's echo
 * of an agent transcript request; a server that does not echo it ignored `agentId` and sent the session's.
 */
/** [truncated] (agentd, agent transcripts): the server no longer holds the start of what was asked for. */
data class MessagesPage(val messages: List<AgentMessage>, val state: String?, val agentId: String? = null, val truncated: Boolean = false)

/** Session states as `/status` and `/sessions` report them. */
object SessionState {
    const val IDLE = "idle"
    const val BUSY = "busy"
    const val AWAITING = "awaiting"
}

/** agentd's `liveness` values. */
object Liveness {
    const val HOSTED = "hosted"
    const val TERMINAL = "terminal"
    const val ENDED = "ended"
    const val UNKNOWN = "unknown"
}

/** agentd's `drive` values: how a prompt reaches the session. */
object Drive {
    const val HOSTED = "hosted"
    const val TMUX = "tmux"
    const val INBOX = "inbox"
    const val STOPHOOK = "stophook"
    const val NONE = "none"
}
