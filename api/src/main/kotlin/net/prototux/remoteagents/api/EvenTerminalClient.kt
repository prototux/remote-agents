package net.prototux.remoteagents.api

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The even-terminal HTTP API (reference: even-terminal-api.md, v0.10.4). All calls are main-safe. */
class EvenTerminalClient(val endpoint: Endpoint, base: OkHttpClient = OkHttpClient()) {
    internal val http: OkHttpClient = endpoint.buildClient(base)

    /** For the SSE stream: the 15 s heartbeat keeps bytes flowing, so 45 s of silence means the stream is dead. */
    internal val sseHttp: OkHttpClient = http.newBuilder().readTimeout(45, TimeUnit.SECONDS).build()

    suspend fun info(provider: String? = null): ServerInfo = get("info", "provider" to provider)
    suspend fun updateCheck(): UpdateCheck = get("update-check")

    suspend fun sessions(limit: Int = 30, provider: String? = null, cwd: String? = null): List<SessionSummary> {
        val r: SessionsResponse = get("sessions", "limit" to limit.toString(), "provider" to provider, "cwd" to cwd)
        if (r.error != null && r.sessions.isEmpty()) throw ApiException(200, null, r.error)
        return r.sessions
    }

    suspend fun status(sessionId: String, provider: String? = null): StatusResponse =
        get("status", "sessionId" to sessionId, "provider" to provider)

    suspend fun messages(sessionId: String, after: Long = 0, provider: String? = null): MessagesPage {
        val json: JsonObject = get("messages", "sessionId" to sessionId, "after" to after.toString(), "provider" to provider)
        val list = (json["messages"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { o -> AgentMessage.parse(o) } }
        return MessagesPage(list, json.str("state"))
    }

    suspend fun history(sessionId: String, limit: Int = 10, provider: String? = null): List<HistoryEntry> {
        val r: HistoryResponse = get(listOf("sessions", sessionId, "history"), "limit" to limit.toString(), "provider" to provider)
        return r.history
    }

    /**
     * Sends a prompt. Without [sessionId] this starts a new session; the answer carries its id.
     *
     * agentd extensions, sent only when set: [fork] continues [sessionId] in a new session (the answer
     * carries the new id) and never touches the original; [force] resumes a session whose liveness agentd
     * cannot tell ("unknown"), at the risk of a second process on its transcript. A session live in a terminal
     * is never hosted, force or not (decision S8): without [force] agentd delivers to it where it can (its
     * tmux pane; for Claude also the inbox or the Stop hook), else answers [SessionLiveException]; with
     * [force] it always answers [SessionLiveException]. Retrying with [force] does not help, [fork] does.
     * even-terminal ignores both fields.
     */
    suspend fun prompt(
        text: String,
        sessionId: String? = null,
        provider: String? = null,
        cwd: String? = null,
        fork: Boolean = false,
        force: Boolean = false,
    ): PromptAccepted =
        post("prompt", buildJsonObject {
            put("text", text)
            sessionId?.let { put("sessionId", it) }
            provider?.let { put("provider", it) }
            cwd?.let { put("cwd", it) }
            if (fork) put("fork", true)
            if (force) put("force", true)
        })

    /**
     * [toolUseId] names the request shown to the user (agentd extension): agentd then answers that one or
     * refuses with 409 when it is no longer pending, so a tap never approves a different tool call.
     */
    suspend fun permissionResponse(sessionId: String, decision: String, provider: String? = null, toolUseId: String? = null) {
        post<JsonObject>("permission-response", buildJsonObject {
            put("sessionId", sessionId); put("decision", decision); provider?.let { put("provider", it) }
            toolUseId?.let { put("toolUseId", it) }
        })
    }

    /** [answers] maps each question's key to its answer text (several selections joined with ", "). */
    suspend fun questionResponse(sessionId: String, answers: Map<String, String>, provider: String? = null, toolUseId: String? = null) {
        val answer = ApiJson.encodeToString(JsonObject.serializer(), buildJsonObject { answers.forEach { (k, v) -> put(k, v) } })
        post<JsonObject>("question-response", buildJsonObject {
            put("sessionId", sessionId); put("answer", answer); provider?.let { put("provider", it) }
            toolUseId?.let { put("toolUseId", it) }
        })
    }

    suspend fun interrupt(sessionId: String, provider: String? = null) {
        post<JsonObject>("interrupt", buildJsonObject { put("sessionId", sessionId); provider?.let { put("provider", it) } })
    }

    internal fun url(path: List<String>, vararg query: Pair<String, String?>): HttpUrl =
        endpoint.baseUrl.newBuilder().apply {
            path.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
        }.build()

    private suspend inline fun <reified T> get(path: String, vararg query: Pair<String, String?>): T = get(listOf(path), *query)

    private suspend inline fun <reified T> get(path: List<String>, vararg query: Pair<String, String?>): T =
        decode(execute(Request.Builder().url(url(path, *query)).get().build()))

    private suspend inline fun <reified T> post(path: String, body: JsonObject): T =
        decode(execute(Request.Builder().url(url(listOf(path))).post(body.toString().toRequestBody(JSON)).build()))

    private inline fun <reified T> decode(body: String): T = ApiJson.decodeFromString(body.ifBlank { "{}" })

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                val result = response.use { r ->
                    runCatching {
                        val text = r.body.string()
                        if (!r.isSuccessful) throw ApiException.from(r.code, text)
                        text
                    }
                }
                result.fold(cont::resume, cont::resumeWithException)
            }
        })
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
