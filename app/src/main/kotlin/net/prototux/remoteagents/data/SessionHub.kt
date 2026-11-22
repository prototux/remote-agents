package net.prototux.remoteagents.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.prototux.remoteagents.api.AuthFailedException
import net.prototux.remoteagents.api.EvenTerminalClient
import net.prototux.remoteagents.api.SessionState
import net.prototux.remoteagents.api.StreamEvent
import net.prototux.remoteagents.api.Transcript
import net.prototux.remoteagents.api.events
import net.prototux.remoteagents.api.findPinMismatch

/** The connection of one live session, as the UI shows it. */
sealed interface Link {
    data object Connecting : Link
    data object Live : Link
    data class Retrying(val inMs: Long, val reason: String?) : Link
    data object AuthFailed : Link
    data object CertificateChanged : Link
    data object Stopped : Link
}

data class SessionMeta(val title: String? = null, val cwd: String? = null, val provider: String? = null)

data class LiveState(
    val key: SessionKey,
    val meta: SessionMeta,
    val transcript: Transcript = Transcript(),
    val link: Link = Link.Connecting,
    val loading: Boolean = true,
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

        fun start() {
            if (job?.isActive == true) return
            val client = clients.get(key.serverId) ?: run { publish(link = Link.AuthFailed, loading = false); return }
            job = scope.launch { run(client) }
        }

        private suspend fun run(client: EvenTerminalClient) {
            val provider = state.value.meta.provider
            publish(link = Link.Connecting)
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

            client.events(key.sessionId, provider, t.knownIds).collect { ev ->
                when (ev) {
                    is StreamEvent.Message -> {
                        val next = this.t.reduce(ev.message)
                        publish(transcript = next, link = Link.Live)
                        AttentionRules.onLive(key, ev.message, next).forEach { onAlert(it, state.value) }
                    }
                    is StreamEvent.Connected -> publish(link = Link.Live)
                    is StreamEvent.Disconnected -> {
                        if (ev.error?.findPinMismatch() != null) { certificateChanged(); job?.cancel(); return@collect }
                        publish(link = Link.Retrying(ev.retryInMs, ev.error?.message))
                    }
                    StreamEvent.Reset -> publish(transcript = Transcript())
                    StreamEvent.AuthFailed -> authFailed()
                }
            }
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

        fun stop() { job?.cancel(); job = null; state.value = state.value.copy(link = Link.Stopped) }
    }

    private val live = HashMap<SessionKey, Live>()
    private val watched = HashSet<SessionKey>()
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
        SessionMeta(new.title ?: old.title, new.cwd ?: old.cwd, new.provider ?: old.provider)

    fun state(key: SessionKey, meta: SessionMeta? = null): StateFlow<LiveState> = obtain(key, meta).state.asStateFlow()

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
        try {
            client(key).prompt(text, sessionId = key.sessionId, provider = provider(key))
        } catch (e: Exception) {
            l.publish(transcript = l.t.copy(items = l.t.items.filterNot { it is net.prototux.remoteagents.api.TranscriptItem.Prompt && it.pending && it.text == text }, state = before.state))
            throw e
        }
        l.start()
    }

    /** Starts a session; its first prompt shows immediately. */
    suspend fun newSession(serverId: String, provider: String, cwd: String?, text: String): SessionKey {
        val client = clients.get(serverId) ?: throw AuthFailedException("Server not paired")
        val accepted = client.prompt(text, provider = provider, cwd = cwd?.ifBlank { null })
        cwd?.takeIf { it.isNotBlank() }?.let { servers.rememberCwd(serverId, it) }
        val key = SessionKey(serverId, accepted.sessionId)
        val l = obtain(key, SessionMeta(title = text.lineSequence().first().take(80), cwd = cwd, provider = accepted.provider ?: provider))
        watched += key
        l.publish(transcript = l.t.withLocalPrompt(text).copy(state = SessionState.BUSY), loading = false)
        l.start()
        return key
    }

    // [provider] comes from a notification when the process was restarted and the session is not live.
    suspend fun respondPermission(key: SessionKey, decision: String, provider: String? = provider(key)) =
        client(key).permissionResponse(key.sessionId, decision, provider)

    suspend fun answer(key: SessionKey, answers: Map<String, String>, provider: String? = provider(key)) =
        client(key).questionResponse(key.sessionId, answers, provider)

    suspend fun interrupt(key: SessionKey) = client(key).interrupt(key.sessionId, provider(key))

    /** A prompt from a notification reply: the session may not be live in this process. */
    suspend fun reply(key: SessionKey, text: String, provider: String?) {
        if (live[key] == null) obtain(key, SessionMeta(provider = provider))
        prompt(key, text)
    }

    fun unwatchAll() { watched.clear(); evaluate() }

    private fun evaluate() {
        val stop = live.values.filter { l ->
            l.job != null && !AttentionRules.keepOpen(l.onScreen > 0, l.key in watched, l.t)
        }
        stop.forEach { it.stop() }
        val working = live.values.filter { AttentionRules.needsService(it.key in watched, it.t) && it.job != null }.map { it.state.value }
        if (working != _working.value) _working.value = working
        val need = working.isNotEmpty()
        if (need != serviceOn) { serviceOn = need; onServiceNeeded(need) }
    }
}
