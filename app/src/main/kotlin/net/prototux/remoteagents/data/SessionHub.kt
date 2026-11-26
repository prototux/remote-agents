package net.prototux.remoteagents.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import net.prototux.remoteagents.api.AgentMessage
import net.prototux.remoteagents.api.AgentsState
import net.prototux.remoteagents.api.ApiException
import net.prototux.remoteagents.api.AuthFailedException
import net.prototux.remoteagents.api.EvenTerminalClient
import net.prototux.remoteagents.api.MessagesPage
import net.prototux.remoteagents.api.Reach
import net.prototux.remoteagents.api.SessionLiveException
import net.prototux.remoteagents.api.SessionPolicyState
import net.prototux.remoteagents.api.SessionState
import net.prototux.remoteagents.api.StreamEvent
import net.prototux.remoteagents.api.Transcript
import net.prototux.remoteagents.api.TranscriptItem
import net.prototux.remoteagents.api.events
import net.prototux.remoteagents.api.findPinMismatch
import java.util.WeakHashMap

/** The connection of one live session, as the UI shows it. */
sealed interface Link {
    data object Connecting : Link
    data object Live : Link
    data class Retrying(val inMs: Long, val reason: String?) : Link
    data object AuthFailed : Link
    data object CertificateChanged : Link
    data object Stopped : Link
}

/** [reach] is null until the list or `/status` said; `Reach()` (all fields null) means plain even-terminal. */
data class SessionMeta(val title: String? = null, val cwd: String? = null, val provider: String? = null, val reach: Reach? = null)

data class LiveState(
    val key: SessionKey,
    val meta: SessionMeta,
    val transcript: Transcript = Transcript(),
    val link: Link = Link.Connecting,
    val loading: Boolean = true,
    /** Subagents and workflow runs (agentd S11); always empty on even-terminal. */
    val agents: AgentsState = AgentsState(),
)

/** One subagent's transcript, read-only (agentd S11). [problem] says why it cannot be shown. */
data class AgentView(
    val transcript: Transcript = Transcript(),
    val link: Link = Link.Connecting,
    val loading: Boolean = true,
    val problem: String? = null,
    /** agentd no longer holds the start of this agent's transcript (a very long agent). */
    val truncated: Boolean = false,
)

/**
 * Owns the live sessions: their streams, transcripts and the actions on them. Everything runs on the
 * main thread ([scope] is main-bound); network calls are asynchronous in OkHttp.
 *
 * Streams stay open per [AttentionRules.keepOpen]; [onServiceNeeded] is told whenever the foreground
 * service should start or stop, and [onAlert] receives notifications to show or clear.
 */
class SessionHub(
    private val scope: CoroutineScope,
    private val servers: ServerStore,
    private val clients: ClientRegistry,
    private val onAlert: (Alert, LiveState) -> Unit,
    private val onServiceNeeded: (Boolean) -> Unit,
) {
    private inner class Live(val key: SessionKey, meta: SessionMeta) {
        val state = MutableStateFlow(LiveState(key, meta))
        var onScreen = 0
        var job: Job? = null
        val t get() = state.value.transcript
        /** Agents and runs the stream updated while a `/agents` refresh is in flight; null when none is. */
        var touched: MutableSet<String>? = null

        fun start() {
            if (job?.isActive == true) return
            val client = clients.get(key.serverId) ?: run { publish(link = Link.AuthFailed, loading = false); return }
            job = scope.launch { run(client) }
        }

        private suspend fun run(client: EvenTerminalClient) {
            val provider = state.value.meta.provider
            publish(link = Link.Connecting)
            refreshReach(client)
            val page = try { client.messages(key.sessionId, provider = provider) } catch (e: AuthFailedException) {
                authFailed(); return
            } catch (e: Exception) { if (e.findPinMismatch() != null) { certificateChanged(); return }; null }
            var t = t.reduceNew(page?.messages.orEmpty())
            page?.state?.let { t = t.withServerState(it) }
            if (page != null && page.messages.isEmpty() && t.items.isEmpty()) {
                runCatching { client.history(key.sessionId, provider = provider) }.getOrNull()?.let { t = t.withHistory(it) }
            }
            publish(transcript = t, loading = false)
            AttentionRules.onSnapshot(key, t).forEach { onAlert(it, state.value) }

            // Subagents and workflows exist only on agentd; even-terminal never gets agents=1 or /agents.
            // Whether the server is agentd is asked again before each reconnect: the owner may have rolled
            // back to even-terminal behind the same address.
            var recheck = false
            val agentd = isAgentd(client)
            if (agentd) runCatching { client.agents(key.sessionId, provider) }.getOrNull()?.let { publishAgents(AgentsState.of(it)) }
            val agentsParam: suspend () -> Boolean = {
                isAgentd(client, recheck).also { on ->
                    recheck = false
                    if (!on) publishAgents(AgentsState()) // what an earlier agentd sent is no longer followed
                }
            }

            client.events(key.sessionId, provider, t.knownIds, agents = agentsParam).collect { ev ->
                when (ev) {
                    is StreamEvent.Message -> if (ev.message is AgentMessage.AgentUpdate || ev.message is AgentMessage.WorkflowUpdate) {
                        AgentsState.touchedKey(ev.message)?.let { touched?.add(it) }
                        publishAgents(state.value.agents.reduce(ev.message))
                    } else {
                        val next = this.t.reduce(ev.message)
                        publish(transcript = next, link = Link.Live)
                        AttentionRules.onLive(key, ev.message, next).forEach { onAlert(it, state.value) }
                    }
                    is StreamEvent.Connected -> publish(link = Link.Live)
                    is StreamEvent.Disconnected -> {
                        if (ev.error?.findPinMismatch() != null) { certificateChanged(); job?.cancel(); return@collect }
                        recheck = true
                        publish(link = Link.Retrying(ev.retryInMs, ev.error?.message))
                    }
                    StreamEvent.Reset -> publish(transcript = Transcript())
                    StreamEvent.AuthFailed -> authFailed()
                }
            }
        }

        /** Reads liveness and drive from `/status` (agentd); even-terminal answers without them or 404. */
        suspend fun refreshReach(client: EvenTerminalClient) {
            val status = runCatching { client.status(key.sessionId, state.value.meta.provider) }.getOrNull() ?: return
            state.value = state.value.copy(meta = state.value.meta.copy(reach = Reach.of(status)))
        }

        private fun authFailed() {
            servers.update(key.serverId) { it.copy(needsRepair = true) }
            publish(link = Link.AuthFailed, loading = false)
        }

        private fun certificateChanged() = publish(link = Link.CertificateChanged, loading = false)

        fun publish(transcript: Transcript = t, link: Link = state.value.link, loading: Boolean = state.value.loading) {
            state.value = state.value.copy(transcript = transcript, link = link, loading = loading)
            evaluate()
        }

        fun publishAgents(agents: AgentsState) {
            if (agents == state.value.agents) return
            state.value = state.value.copy(agents = agents)
            evaluate()
        }

        fun stop() { job?.cancel(); job = null; state.value = state.value.copy(link = Link.Stopped) }
    }

    private val live = HashMap<SessionKey, Live>()
    /** Which clients talk to agentd (from `/info`), per client: a server edited to point elsewhere gets a new one. */
    private val agentdClients = WeakHashMap<EvenTerminalClient, Boolean>()
    private val watched = HashSet<SessionKey>()
    /** Sessions this app started or already sent to (after the owner's go-ahead) in this process. */
    private val prompted = HashSet<SessionKey>()
    private var serviceOn = false

    private val _working = MutableStateFlow<List<LiveState>>(emptyList())
    /** Watched sessions that are busy or awaiting: what the service's ongoing notification lists. */
    val working: StateFlow<List<LiveState>> = _working.asStateFlow()

    /** The session currently on screen with the app in the foreground; it does not notify. */
    var visible: SessionKey? = null

    /** Drafts of the prompt box per session, kept while the app runs. */
    val drafts = HashMap<SessionKey, String>()

    private fun obtain(key: SessionKey, meta: SessionMeta?): Live =
        live.getOrPut(key) { Live(key, meta ?: SessionMeta()) }.also { l ->
            if (meta != null) l.state.value = l.state.value.copy(meta = merge(l.state.value.meta, meta))
        }

    private fun merge(old: SessionMeta, new: SessionMeta) =
        SessionMeta(new.title ?: old.title, new.cwd ?: old.cwd, new.provider ?: old.provider, new.reach ?: old.reach)

    fun state(key: SessionKey, meta: SessionMeta? = null): StateFlow<LiveState> = obtain(key, meta).state.asStateFlow()

    /** What the sessions list knows about a session, before it is opened. */
    fun note(key: SessionKey, meta: SessionMeta) { obtain(key, meta) }

    /**
     * The warning to confirm before the first prompt from this app to an existing session, or null. A prompt
     * that resumes a session in a new process forks it if a terminal still runs it, and even-terminal cannot
     * tell; so it is asked once, unless this app started the session or sent to it already.
     */
    fun resumeWarning(key: SessionKey): String? =
        if (key in prompted) null else (live[key]?.state?.value?.meta?.reach ?: Reach()).resumeWarning

    /** The session screen is shown (app started). Opening a session also watches it. */
    fun attach(key: SessionKey, meta: SessionMeta? = null) {
        val l = obtain(key, meta)
        l.onScreen++
        watched += key
        if (l.state.value.link is Link.Stopped || l.state.value.link is Link.AuthFailed && servers.get(key.serverId)?.needsRepair == false) {
            l.state.value = l.state.value.copy(link = Link.Connecting)
        }
        l.start()
    }

    fun detach(key: SessionKey) {
        live[key]?.let { it.onScreen = (it.onScreen - 1).coerceAtLeast(0) }
        evaluate()
    }

    fun isWatched(key: SessionKey) = key in watched
    fun setWatched(key: SessionKey, on: Boolean) { if (on) watched += key else watched -= key; evaluate() }

    /** Reloads a session from scratch (backlog, history, stream). */
    fun reload(key: SessionKey) {
        val l = live[key] ?: return
        l.stop()
        l.state.value = l.state.value.copy(transcript = Transcript(), loading = true, link = Link.Connecting)
        l.start()
    }

    private fun client(key: SessionKey) = clients.get(key.serverId) ?: throw AuthFailedException("Server not paired")
    private fun provider(key: SessionKey) = live[key]?.state?.value?.meta?.provider

    suspend fun prompt(key: SessionKey, text: String) {
        val l = obtain(key, null)
        watched += key
        val before = l.t
        l.publish(transcript = before.withLocalPrompt(text).copy(state = SessionState.BUSY))
        val client = client(key)
        try {
            client.prompt(text, sessionId = key.sessionId, provider = provider(key))
        } catch (e: Exception) {
            l.publish(transcript = l.t.copy(items = l.t.items.filterNot { it is TranscriptItem.Prompt && it.pending && it.text == text }, state = before.state))
            // agentd lost its way into the terminal (pane closed, dialog open): show the session as it is now.
            if (e is SessionLiveException) l.refreshReach(client)
            throw e
        }
        prompted += key
        l.start()
        // An ended session is now hosted: the banner and the next prompt follow that.
        scope.launch { l.refreshReach(client) }
    }

    /**
     * Continues [key] in a new session (agentd `fork`); the original is never touched, even when it runs in a
     * terminal. The new session opens with its first prompt shown.
     */
    suspend fun fork(key: SessionKey, text: String): SessionKey {
        val from = obtain(key, null).state.value.meta
        val accepted = client(key).prompt(text, sessionId = key.sessionId, provider = from.provider, fork = true)
        val forked = SessionKey(key.serverId, accepted.sessionId)
        val l = obtain(forked, SessionMeta(title = "Fork: " + (from.title ?: text.lineSequence().first().take(80)), cwd = from.cwd, provider = accepted.provider ?: from.provider))
        watched += forked
        prompted += forked
        l.publish(transcript = l.t.withLocalPrompt(text).copy(state = SessionState.BUSY), loading = false)
        l.start()
        return forked
    }

    /** Starts a session; its first prompt shows immediately. */
    suspend fun newSession(serverId: String, provider: String, cwd: String?, text: String): SessionKey {
        val client = clients.get(serverId) ?: throw AuthFailedException("Server not paired")
        val accepted = client.prompt(text, provider = provider, cwd = cwd?.ifBlank { null })
        cwd?.takeIf { it.isNotBlank() }?.let { servers.rememberCwd(serverId, it) }
        val key = SessionKey(serverId, accepted.sessionId)
        prompted += key
        val l = obtain(key, SessionMeta(title = text.lineSequence().first().take(80), cwd = cwd, provider = accepted.provider ?: provider))
        watched += key
        l.publish(transcript = l.t.withLocalPrompt(text).copy(state = SessionState.BUSY), loading = false)
        l.start()
        return key
    }

    // [provider] comes from a notification when the process was restarted and the session is not live.
    // [toolUseId] is the request the user saw, so the decision cannot reach another one.
    suspend fun respondPermission(key: SessionKey, decision: String, provider: String? = provider(key), toolUseId: String? = null) =
        client(key).permissionResponse(key.sessionId, decision, provider, toolUseId)

    suspend fun answer(key: SessionKey, answers: Map<String, String>, provider: String? = provider(key), toolUseId: String? = null) =
        client(key).questionResponse(key.sessionId, answers, provider, toolUseId)

    suspend fun interrupt(key: SessionKey) = client(key).interrupt(key.sessionId, provider(key))

    /**
     * Sets or clears (null) this session's own override of the "always allow Bash" policy (agentd). The
     * reply is the new truth for [Reach.bashAutoAllow]/[Reach.bashAutoAllowSession], so the menu and any
     * banner update from it at once, without waiting for the next `/status`.
     */
    suspend fun setSessionPolicy(key: SessionKey, bashAutoAllow: Boolean?): SessionPolicyState {
        val result = client(key).setSessionPolicy(key.sessionId, bashAutoAllow, provider(key))
        val l = live[key] ?: return result
        val reach = (l.state.value.meta.reach ?: Reach()).copy(bashAutoAllow = result.effective, bashAutoAllowSession = result.session)
        l.state.value = l.state.value.copy(meta = l.state.value.meta.copy(reach = reach))
        return result
    }

    /** A prompt from a notification reply: the session may not be live in this process. */
    suspend fun reply(key: SessionKey, text: String, provider: String?) {
        if (live[key] == null) obtain(key, SessionMeta(provider = provider))
        prompt(key, text)
    }

    fun unwatchAll() { watched.clear(); evaluate() }

    /**
     * Whether [client]'s server is agentd, from `/info`. Remembered per client; [recheck] asks again (after a
     * failed connection). An unreachable server keeps the last answer, or counts as not agentd, unremembered.
     */
    private suspend fun isAgentd(client: EvenTerminalClient, recheck: Boolean = false): Boolean {
        val known = agentdClients[client]
        if (known != null && !recheck) return known
        val info = runCatching { client.info() }.getOrNull() ?: return known ?: false
        return info.isAgentd.also { agentdClients[client] = it }
    }

    /**
     * Reads the agents list again (the panel opening); the stream keeps it current after that. What the stream
     * updates while the request is in flight wins over the answer, which may be older.
     */
    suspend fun refreshAgents(key: SessionKey) {
        val l = live[key] ?: return
        val client = clients.get(key.serverId) ?: return
        if (!isAgentd(client)) return
        val touched = HashSet<String>().also { l.touched = it }
        try {
            val list = client.agents(key.sessionId, provider(key))
            l.publishAgents(l.state.value.agents.refreshedWith(list, touched))
        } finally {
            if (l.touched === touched) l.touched = null
        }
    }

    /**
     * One subagent's transcript (agentd S11): its backlog, then its stream, for as long as it is collected (the
     * agent screen). Read-only, and it never alerts: the agent's requests reach the session's own stream.
     */
    fun followAgent(key: SessionKey, agentId: String): Flow<AgentView> = flow {
        val client = clients.get(key.serverId) ?: run { emit(AgentView(link = Link.AuthFailed, loading = false)); return@flow }
        val provider = provider(key)
        var v = AgentView()
        emit(v)
        val noAgents = "This server can't show agent transcripts."
        if (!isAgentd(client, recheck = true)) { emit(v.copy(link = Link.Stopped, loading = false, problem = noAgents)); return@flow }
        // The first page must come from agentd, echoing the agent: a failed one is asked again, never skipped,
        // as /events?agentId= on a server without S11 would stream the session's own transcript.
        var attempt = 0
        var page: MessagesPage? = null
        while (page == null) {
            try {
                page = client.messages(key.sessionId, provider = provider, agentId = agentId)
            } catch (e: AuthFailedException) {
                servers.update(key.serverId) { it.copy(needsRepair = true) }
                emit(v.copy(link = Link.AuthFailed, loading = false)); return@flow
            } catch (e: ApiException) {
                if (e.httpStatus == 404) { emit(v.copy(link = Link.Stopped, loading = false, problem = "The server no longer knows this agent.")); return@flow }
                v = v.copy(link = Link.Retrying(retryMs(attempt), e.message))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e.findPinMismatch() != null) { emit(v.copy(link = Link.CertificateChanged, loading = false)); return@flow }
                v = v.copy(link = Link.Retrying(retryMs(attempt), e.message))
            }
            if (page == null) { emit(v); delay(retryMs(attempt++)) }
        }
        val first = page ?: return@flow // never null: the loop ends with a page
        // A server without S11 ignores agentId and pages the session itself: never show that as the agent.
        if (first.agentId != agentId) {
            emit(v.copy(link = Link.Stopped, loading = false, problem = noAgents)); return@flow
        }
        var t = Transcript().reduceNew(first.messages)
        first.state?.let { t = t.withServerState(it) }
        v = v.copy(transcript = t, loading = false, link = Link.Connecting, truncated = first.truncated)
        emit(v)
        var pinChanged = false
        client.events(key.sessionId, provider, t.knownIds, agentId = agentId)
            .takeWhile { ev -> !(ev is StreamEvent.Disconnected && ev.error?.findPinMismatch() != null).also { pinChanged = it } }
            .collect { ev ->
                v = when (ev) {
                    is StreamEvent.Message -> v.copy(transcript = v.transcript.reduce(ev.message), link = Link.Live)
                    is StreamEvent.Connected -> v.copy(link = Link.Live)
                    is StreamEvent.Disconnected -> v.copy(link = Link.Retrying(ev.retryInMs, ev.error?.message))
                    StreamEvent.Reset -> v.copy(transcript = Transcript())
                    StreamEvent.AuthFailed -> v.copy(link = Link.AuthFailed)
                }
                emit(v)
            }
        if (pinChanged) emit(v.copy(link = Link.CertificateChanged))
    }

    /** Backoff for the first page of an agent: 1 s doubling up to 30 s, as the stream's. */
    private fun retryMs(attempt: Int): Long = (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)

    private fun evaluate() {
        val stop = live.values.filter { l ->
            l.job != null && !AttentionRules.keepOpen(l.onScreen > 0, l.key in watched, l.t, l.state.value.agents)
        }
        stop.forEach { it.stop() }
        val working = live.values.filter { AttentionRules.needsService(it.key in watched, it.t, it.state.value.agents) && it.job != null }.map { it.state.value }
        if (working != _working.value) _working.value = working
        val need = working.isNotEmpty()
        if (need != serviceOn) { serviceOn = need; onServiceNeeded(need) }
    }
}
