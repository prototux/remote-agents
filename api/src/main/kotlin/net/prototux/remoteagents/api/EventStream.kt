package net.prototux.remoteagents.api

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/** What the session stream yields: messages, plus connection state so the UI can show it. */
sealed interface StreamEvent {
    data class Message(val message: AgentMessage) : StreamEvent
    /** The stream is open; [reconnect] is true after the first connection. */
    data class Connected(val reconnect: Boolean) : StreamEvent
    /** The stream dropped; the next attempt starts in [retryInMs]. */
    data class Disconnected(val error: Throwable?, val retryInMs: Long) : StreamEvent
    /** Message ids went backwards for ids never seen: the server restarted. Drop the transcript and rebuild. */
    data object Reset : StreamEvent
    /** 401: terminal, the flow ends. The owner must re-pair. */
    data object AuthFailed : StreamEvent
}

/**
 * Remembers which messages were delivered (id and content fingerprint), so replayed messages are dropped
 * and a server restart is detected: a restarted server reuses ids from 1, so an id that was seen with
 * different content, or an unseen id below the highest one, can only come from a new server process.
 */
class Deduplicator(initial: Map<Long, Int> = emptyMap()) {
    private val seen = LinkedHashMap<Long, Int>()
    var lastId: Long? = null
        private set

    init { initial.forEach(::remember) }

    enum class Verdict { New, Duplicate, Reset }

    fun check(id: Long?, fingerprint: Int): Verdict {
        if (id == null) return Verdict.New
        val last = lastId
        val verdict = when {
            last == null || id > last -> Verdict.New
            seen[id] == fingerprint -> Verdict.Duplicate
            else -> Verdict.Reset
        }
        when (verdict) {
            Verdict.New -> remember(id, fingerprint)
            Verdict.Reset -> { seen.clear(); lastId = null; remember(id, fingerprint) }
            Verdict.Duplicate -> Unit
        }
        return verdict
    }

    private fun remember(id: Long, fingerprint: Int) {
        seen[id] = fingerprint
        if (lastId == null || id > lastId!!) lastId = id
        // The server keeps 500 messages per session; remembering a few times that is enough to recognise any replay.
        if (seen.size > 4000) seen.remove(seen.keys.first())
    }
}

/** Backoff for reconnects: 1 s doubling up to 30 s. */
internal fun backoffMs(attempt: Int): Long = (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)

/**
 * The live stream of one session (`GET /events?needReplay=true`), reconnecting forever with backoff until
 * the collector cancels or the token is refused. Replayed messages already in [known] (id to
 * [AgentMessage.fingerprint], usually [Transcript.knownIds]) or delivered earlier on this flow are dropped.
 */
fun EvenTerminalClient.events(
    sessionId: String,
    provider: String? = null,
    known: Map<Long, Int> = emptyMap(),
    backoff: (attempt: Int) -> Long = ::backoffMs,
): Flow<StreamEvent> = flow {
    val dedup = Deduplicator(known)
    var attempt = 0
    var connectedOnce = false
    while (true) {
        var authFailed = false
        var failure: Throwable? = null
        var delivered = false
        connectOnce(sessionId, provider).collect { item ->
            when (item) {
                is RawSse.Open -> { emit(StreamEvent.Connected(connectedOnce)); connectedOnce = true }
                is RawSse.Data -> {
                    val message = AgentMessage.parse(item.data, item.id?.toLongOrNull())
                    when (dedup.check(message.id, message.fingerprint)) {
                        Deduplicator.Verdict.Duplicate -> Unit
                        Deduplicator.Verdict.Reset -> { emit(StreamEvent.Reset); emit(StreamEvent.Message(message)) }
                        Deduplicator.Verdict.New -> emit(StreamEvent.Message(message))
                    }
                    delivered = true
                }
                is RawSse.Failed -> {
                    failure = item.error
                    if (item.error is AuthFailedException) authFailed = true
                }
            }
        }
        if (authFailed) { emit(StreamEvent.AuthFailed); return@flow }
        attempt = if (delivered) 0 else attempt + 1
        val wait = backoff(attempt)
        emit(StreamEvent.Disconnected(failure, wait))
        delay(wait)
    }
}

private sealed interface RawSse {
    data object Open : RawSse
    data class Data(val id: String?, val data: String) : RawSse
    data class Failed(val error: Throwable?) : RawSse
}

/** One SSE connection; completes when the connection ends for any reason. */
private fun EvenTerminalClient.connectOnce(sessionId: String, provider: String?): Flow<RawSse> = callbackFlow {
    val request = Request.Builder()
        .url(url(listOf("events"), "sessionId" to sessionId, "needReplay" to "true", "provider" to provider))
        .header("Accept", "text/event-stream")
        .build()
    val listener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) { trySend(RawSse.Open) }
        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) { trySend(RawSse.Data(id, data)) }
        override fun onClosed(eventSource: EventSource) { channel.close() }
        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            val error = when {
                response != null && !response.isSuccessful -> ApiException.from(response.code, runCatching { response.body.string() }.getOrNull())
                else -> t
            }
            trySend(RawSse.Failed(error))
            channel.close()
        }
    }
    val source = EventSources.createFactory(sseHttp).newEventSource(request, listener)
    awaitClose { source.cancel() }
}
