package net.prototux.remoteagents.api

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A scripted even-terminal for tests, written from the API reference only. A prompt starts a turn that
 * stops at a permission request; the permission answer runs to a question; the question answer finishes
 * the turn. Each `/events` connection replays the whole buffer and then ends, which also exercises the
 * client's reconnect and de-duplication on every step.
 *
 * [reach] turns it into agentd for the sessions it names: `/sessions` and `/status` carry `liveness`,
 * `drive` and `canApprove`, a prompt to a watch-only terminal session answers 409 `session_live` unless
 * `fork` or `force`, and `fork` answers with a new session id. Sessions not in [reach] stay plain.
 */
class FakeEvenTerminal(val token: String = "0123456789abcdef0123456789abcdef") : AutoCloseable {
    val server = MockWebServer()
    /** Requests that carried the token in the URL: must stay empty. */
    val tokenInUrl = CopyOnWriteArrayList<String>()
    private val sessions = LinkedHashMap<String, MutableList<JsonObject>>()
    /** agentd's view of each session (see the class comment). */
    val reach = java.util.concurrent.ConcurrentHashMap<String, Reach>()
    /** Bodies of every `/prompt` call, in order. */
    val prompts = CopyOnWriteArrayList<JsonObject>()
    private var nextId = 1L
    private var nextSession = 1

    // --- agentd's "always allow Bash" policy (api/policy.go, approval/policy.go), scripted for tests. ---
    private var policyGlobal = false
    private val policyProviders = LinkedHashMap<String, Boolean>()
    private val sessionPolicy = HashMap<String, Boolean>()
    /** Bodies of every `PUT /api/policy` call, in order. */
    val policyPuts = CopyOnWriteArrayList<JsonObject>()
    /** Bodies of every `POST /api/sessions/{id}/policy` call, in order. */
    val sessionPolicyPosts = CopyOnWriteArrayList<JsonObject>()
    private val stillAskLabels = listOf("sudo", "rm -rf", "git push --force", "git reset --hard", "git clean -f", "dd", "mkfs")

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@FakeEvenTerminal) { handle(request) }
        }
        server.start()
    }

    fun endpoint() = Endpoint(false, server.hostName, server.port, token)

    @Synchronized fun buffer(sessionId: String): List<JsonObject> = sessions[sessionId].orEmpty().toList()

    @Synchronized fun emit(sessionId: String, vararg json: String) {
        val list = sessions.getOrPut(sessionId) { mutableListOf() }
        json.forEach { list += JsonObject((ApiJson.parseToJsonElement(it) as JsonObject) + ("id" to JsonPrimitive(nextId++))) }
    }

    /** A session the server lists without a buffer yet, e.g. one started in a terminal. */
    @Synchronized fun addSession(id: String, reach: Reach? = null) {
        sessions.getOrPut(id) { mutableListOf() }
        reach?.let { this.reach[id] = it }
    }

    /** Simulates a server restart: buffers and ids start over. */
    @Synchronized fun restart() { sessions.values.forEach { it.clear() }; nextId = 1 }

    private fun handle(r: RecordedRequest): MockResponse {
        if (r.url.queryParameter("token") != null) tokenInUrl += r.url.encodedPath
        if (r.headers["Authorization"] != "Bearer $token") {
            return json(401, """{"error":{"code":"auth_failed","message":"Unauthorized"}}""")
        }
        val body = r.body?.utf8()?.takeIf { it.isNotBlank() }?.let { ApiJson.parseToJsonElement(it) as JsonObject }
        val sid = body?.str("sessionId") ?: r.url.queryParameter("sessionId")
        Regex("""/api/sessions/([^/]+)/policy""").matchEntire(r.url.encodedPath)?.let { m ->
            if (r.method != "POST") return json(404, """{"error":"Not found"}""")
            return sessionPolicyPost(m.groupValues[1], body)
        }
        return when ("${r.method} ${r.url.encodedPath}") {
            "GET /api/info" -> json(200, """{"account":{"email":"me@example.org","organization":"Org","subscriptionType":"max"},"model":"opus","version":"0.10.4","provider":"claude","extra":{"expose":"off"}}""")
            "GET /api/sessions" -> json(200, buildJsonObject {
                put("sessions", kotlinx.serialization.json.JsonArray(sessions.keys.map { id ->
                    buildJsonObject { put("id", id); put("title", "Session $id"); put("timestamp", "2026-11-22T13:45:20.147Z"); put("cwd", "/home/me/p"); put("provider", "claude"); put("status", state(id)); putReach(id) }
                }))
            }.toString())
            "GET /api/status" -> if (sid == null || sid !in sessions) json(404, """{"error":"Session not found"}""")
                else json(200, buildJsonObject { put("state", state(sid)); put("sessionId", sid); put("provider", "claude"); putReach(sid) }.toString())
            "GET /api/messages" -> {
                val after = r.url.queryParameter("after")?.toLong() ?: 0
                val list = sessions[sid].orEmpty().filter { (it.long("id") ?: 0) > after }
                json(200, """{"messages":[${list.joinToString(",")}],"state":"${sid?.let(::state) ?: "idle"}","sessionId":"$sid","provider":"claude"}""")
            }
            "GET /api/events" -> MockResponse.Builder().code(200).setHeader("Content-Type", "text/event-stream").body(
                ":ok\n\n" + sessions[sid].orEmpty().joinToString("") { "id: ${it.long("id")}\ndata: ${JsonObject(it - "id")}\n\n" } + ":heartbeat\n\n",
            ).build()
            "POST /api/prompt" -> {
                prompts += body ?: JsonObject(emptyMap())
                val text = body?.str("text") ?: return json(400, """{"error":"Missing 'text'"}""")
                if (sid != null && sid !in sessions) return json(404, """{"error":"Session not found"}""")
                val fork = sid != null && body.bool("fork") && reach[sid] != null // even-terminal ignores fork
                val r = sid?.let { reach[it] }
                if (r != null && r.watchOnly && !fork && !body.bool("force")) {
                    return json(409, """{"error":"Session is running in a terminal and cannot be driven","code":"session_live"}""")
                }
                val id = if (sid == null || fork) "s${nextSession++}" else sid
                if (fork) reach[id] = Reach(Liveness.HOSTED, Drive.HOSTED, true)
                emit(id, """{"type":"user_prompt","text":${JsonPrimitive(text)}}""", """{"type":"status","state":"busy","sessionId":"$id"}""",
                    """{"type":"text_delta","text":"Running tests."}""", """{"type":"tool_start","name":"Bash","toolId":"t1"}""",
                    """{"type":"permission_request","toolName":"Bash","description":"Run tests","detail":"npm test","toolUseId":"t1","options":[{"text":"Allow","key":"allow"},{"text":"Deny","key":"deny"}]}""")
                json(202, """{"ok":true,"sessionId":"$id","provider":"claude"}""")
            }
            "POST /api/permission-response" -> {
                if (sid == null) return json(400, """{"error":"Missing 'sessionId'"}""")
                val pending = pending(sid, "permission_request", "permission_result") ?: return json(400, """{"error":"No pending permission"}""")
                val decision = body?.str("decision")
                if (decision !in pending.toString().let { Regex("\"key\":\"(\\w+)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }) {
                    return json(400, """{"error":"Decision not offered"}""")
                }
                emit(sid, """{"type":"permission_result","toolName":"Bash","summary":"npm test","decision":"${if (decision == "deny") "denied" else "allowed"}"}""",
                    """{"type":"tool_end","name":"Bash","toolId":"t1","summary":"npm test","detail":{"input":{"command":"npm test"},"output":"1 failing"}}""",
                    """{"type":"user_question","toolUseId":"q1","questions":[{"question":"Which fix?","header":"Fix","multiSelect":false,"options":[{"label":"Patch"},{"label":"Rewrite"}]}]}""")
                json(200, """{"ok":true}""")
            }
            "POST /api/question-response" -> {
                if (sid == null) return json(400, """{"error":"Missing 'sessionId'"}""")
                pending(sid, "user_question", "question_answer") ?: return json(400, """{"error":"No pending question"}""")
                val answers = ApiJson.parseToJsonElement(body!!.str("answer")!!) as JsonObject
                emit(sid, """{"type":"question_answer","answers":$answers}""", """{"type":"text_delta","text":"Done."}""",
                    """{"type":"result","success":true,"text":"Done.","costUsd":0.01,"turns":2,"durationMs":5000,"inputTokens":100,"outputTokens":20}""",
                    """{"type":"status","state":"idle","sessionId":"$sid"}""")
                json(200, """{"ok":true}""")
            }
            "POST /api/interrupt" -> when {
                sid == null -> json(400, """{"error":"Missing 'sessionId'"}""")
                sid !in sessions -> json(404, """{"error":"Session not found"}""")
                else -> { emit(sid, """{"type":"result","success":false,"text":"Interrupted by user"}""", """{"type":"status","state":"idle"}"""); json(200, """{"ok":true}""") }
            }
            "GET /api/policy" -> json(200, policyJson())
            "PUT /api/policy" -> {
                val ba = body?.get("bashAutoAllow") as? JsonObject ?: return json(400, """{"error":"Missing 'bashAutoAllow'"}""")
                policyPuts += body
                val incoming = ba["providers"] as? JsonObject
                incoming?.keys?.firstOrNull { it !in setOf("claude", "codex", "pi", "opencode", "hermes") }?.let {
                    return json(400, """{"error":"Unknown provider \"$it\" in bashAutoAllow.providers","code":"unknown_provider"}""")
                }
                policyGlobal = ba.bool("global")
                policyProviders.clear()
                incoming?.forEach { (k, v) -> policyProviders[k] = (v as? JsonPrimitive)?.content == "true" }
                json(200, policyJson())
            }
            "GET /api/boom" -> json(500, """{"error":"internal"}""")
            else -> json(404, """{"error":"Not found"}""")
        }
    }

    private fun policyJson(): String = buildJsonObject {
        put("bashAutoAllow", buildJsonObject {
            put("global", policyGlobal)
            put("providers", buildJsonObject { policyProviders.forEach { (k, v) -> put(k, v) } })
        })
        put("stillAsk", kotlinx.serialization.json.JsonArray(stillAskLabels.map { JsonPrimitive(it) }))
    }.toString()

    /** `effective` falls back to the (fixed, in this fake) "claude" provider, else the global switch. */
    private fun sessionPolicyPost(sid: String, body: JsonObject?): MockResponse {
        if (sid !in sessions) return json(404, """{"error":"Session not found"}""")
        if (body == null || "bashAutoAllow" !in body) return json(400, """{"error":"Missing 'bashAutoAllow'"}""")
        sessionPolicyPosts += body
        when (val v = body["bashAutoAllow"]) {
            is JsonNull -> sessionPolicy.remove(sid)
            is JsonPrimitive -> sessionPolicy[sid] = v.content == "true"
            else -> return json(400, """{"error":"'bashAutoAllow' must be true, false or null"}""")
        }
        val effective = sessionPolicy[sid] ?: policyProviders["claude"] ?: policyGlobal
        return json(200, buildJsonObject {
            put("bashAutoAllow", buildJsonObject { put("effective", effective); sessionPolicy[sid]?.let { put("session", it) } })
        }.toString())
    }

    private fun JsonObject?.bool(key: String) = (this?.get(key) as? JsonPrimitive)?.content == "true"

    private fun kotlinx.serialization.json.JsonObjectBuilder.putReach(id: String) {
        val r = reach[id] ?: return
        r.liveness?.let { put("liveness", it) }
        r.drive?.let { put("drive", it) }
        r.canApprove?.let { put("canApprove", it) }
    }

    private fun pending(sid: String, request: String, answer: String): JsonObject? {
        val list = sessions[sid] ?: return null
        val last = list.indexOfLast { it.str("type") == request }
        return if (last >= 0 && list.drop(last).none { it.str("type") == answer }) list[last] else null
    }

    private fun state(sid: String): String {
        val list = sessions[sid].orEmpty()
        val lastStatus = list.lastOrNull { it.str("type") == "status" }?.str("state")
        return when {
            pending(sid, "permission_request", "permission_result") != null || pending(sid, "user_question", "question_answer") != null -> "awaiting"
            lastStatus == "busy" -> "busy"
            else -> "idle"
        }
    }

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code).setHeader("Content-Type", "application/json").body(body).build()

    override fun close() = server.close()
}
