package net.prototux.remoteagents.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Lenient JSON: the server evolves, so unknown keys are expected and never an error. */
val ApiJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; encodeDefaults = true }

/**
 * One message of a session's event stream (`/events` SSE `data:` or an item of `/messages`).
 *
 * Every variant keeps the [raw] JSON, so fields this client does not know yet are never lost and an
 * [Unsupported] message can still be shown. Parsing never throws on a well-formed JSON object: missing
 * fields get neutral defaults.
 */
sealed interface AgentMessage {
    /** Server-assigned monotonic id (the SSE `id:`), or null when the server sent none. */
    val id: Long?
    val raw: JsonObject
    val type: String get() = raw.str("type") ?: ""

    /**
     * Content hash without the id: the same message replayed (SSE, or `/messages` where the id is also in
     * the body) has the same fingerprint, a different message reusing an id after a server restart does not.
     */
    val fingerprint: Int get() = JsonObject(raw - "id").toString().hashCode()

    data class Status(override val id: Long?, override val raw: JsonObject, val state: String) : AgentMessage
    data class UserPrompt(override val id: Long?, override val raw: JsonObject, val text: String) : AgentMessage
    data class TextDelta(override val id: Long?, override val raw: JsonObject, val text: String) : AgentMessage
    data class ToolStart(override val id: Long?, override val raw: JsonObject, val name: String, val toolId: String?) : AgentMessage
    data class ToolEnd(
        override val id: Long?, override val raw: JsonObject,
        val name: String, val toolId: String?, val summary: String?,
        /** `detail.input`: usually the tool's JSON input object, sometimes a string. */
        val input: JsonElement?,
        /** `detail.output`: stringified tool output (stdout+stderr, or JSON). */
        val output: String?,
    ) : AgentMessage
    data class TaskProgress(override val id: Long?, override val raw: JsonObject, val completed: Int, val total: Int, val current: String?) : AgentMessage
    data class PermissionRequest(
        override val id: Long?, override val raw: JsonObject,
        val toolName: String, val description: String?, val detail: String?, val toolUseId: String?,
        val options: List<PermissionOption>,
    ) : AgentMessage
    /** [toolUseId] (agentd) names the request it resolves; [decision] `expired` means it ended unanswered. */
    data class PermissionResult(
        override val id: Long?, override val raw: JsonObject, val toolName: String, val summary: String?, val decision: String,
        val toolUseId: String? = null,
    ) : AgentMessage
    data class UserQuestion(override val id: Long?, override val raw: JsonObject, val questions: List<Question>, val toolUseId: String?) : AgentMessage
    /** [toolUseId] (agentd) names the question it resolves; no answers means it ended unanswered. */
    data class QuestionAnswer(override val id: Long?, override val raw: JsonObject, val answers: Map<String, String>, val toolUseId: String? = null) : AgentMessage
    data class Notification(override val id: Long?, override val raw: JsonObject, val title: String?, val message: String) : AgentMessage
    data class RunningStats(override val id: Long?, override val raw: JsonObject, val durationMs: Long, val inputTokens: Long, val outputTokens: Long) : AgentMessage
    data class Result(
        override val id: Long?, override val raw: JsonObject,
        val success: Boolean, val text: String?, val costUsd: Double?, val turns: Int?,
        val durationMs: Long?, val inputTokens: Long?, val outputTokens: Long?,
    ) : AgentMessage
    data class Error(override val id: Long?, override val raw: JsonObject, val message: String) : AgentMessage
    /** A type this client does not know; shown as "unsupported event" with its raw JSON. */
    data class Unsupported(override val id: Long?, override val raw: JsonObject) : AgentMessage

    companion object {
        /** Parses one message. [sseId] (the SSE `id:` line) wins over an `id` inside the JSON. */
        fun parse(json: JsonObject, sseId: Long? = null): AgentMessage {
            val id = sseId ?: json.long("id")
            return when (json.str("type")) {
                "status" -> Status(id, json, json.str("state") ?: "")
                "user_prompt" -> UserPrompt(id, json, json.str("text") ?: "")
                "text_delta" -> TextDelta(id, json, json.str("text") ?: "")
                "tool_start" -> ToolStart(id, json, json.str("name") ?: "tool", json.str("toolId"))
                "tool_end" -> {
                    val detail = json["detail"] as? JsonObject
                    ToolEnd(
                        id, json, json.str("name") ?: "tool", json.str("toolId"), json.str("summary"),
                        detail?.get("input")?.takeUnless { it is JsonNull },
                        detail?.get("output")?.let { it.asText() },
                    )
                }
                "task_progress" -> TaskProgress(id, json, json.long("completed")?.toInt() ?: 0, json.long("total")?.toInt() ?: 0, json.str("current"))
                "permission_request" -> PermissionRequest(
                    id, json, json.str("toolName") ?: "tool", json.str("description"), json["detail"]?.asText(),
                    json.str("toolUseId"),
                    (json["options"] as? JsonArray).orEmpty().mapNotNull { o ->
                        (o as? JsonObject)?.let { PermissionOption(it.str("key") ?: return@let null, it.str("text") ?: it.str("key")!!) }
                    },
                )
                "permission_result" -> PermissionResult(id, json, json.str("toolName") ?: "tool", json.str("summary"), json.str("decision") ?: "", json.str("toolUseId"))
                "user_question" -> UserQuestion(
                    id, json,
                    (json["questions"] as? JsonArray).orEmpty().mapNotNull { q -> (q as? JsonObject)?.let(Question::parse) },
                    json.str("toolUseId"),
                )
                "question_answer" -> QuestionAnswer(
                    id, json, (json["answers"] as? JsonObject).orEmpty().mapValues { it.value.asText() }, json.str("toolUseId"),
                )
                "notification" -> Notification(id, json, json.str("title"), json.str("message") ?: "")
                "running_stats" -> RunningStats(id, json, json.long("durationMs") ?: 0, json.long("inputTokens") ?: 0, json.long("outputTokens") ?: 0)
                "result" -> Result(
                    id, json, json.bool("success") ?: false, json.str("text"), json.double("costUsd"),
                    json.long("turns")?.toInt(), json.long("durationMs"), json.long("inputTokens"), json.long("outputTokens"),
                )
                "error" -> Error(id, json, json.str("message") ?: "Unknown error")
                else -> Unsupported(id, json)
            }
        }

        /** Parses a raw SSE `data:` payload; anything that is not a JSON object becomes [Unsupported]. */
        fun parse(data: String, sseId: Long? = null): AgentMessage {
            val element = runCatching { ApiJson.parseToJsonElement(data) }.getOrNull()
            return if (element is JsonObject) parse(element, sseId)
            else Unsupported(sseId, JsonObject(mapOf("type" to JsonPrimitive("(unparsed)"), "data" to JsonPrimitive(data))))
        }
    }
}

data class PermissionOption(val key: String, val text: String)

data class Question(
    val question: String,
    val header: String?,
    val multiSelect: Boolean,
    val options: List<QuestionOption>,
) {
    /** The key of this question in the `/question-response` answer map: Claude Code keys answers by question text. */
    val key: String get() = question

    companion object {
        fun parse(o: JsonObject) = Question(
            question = o.str("question") ?: "",
            header = o.str("header"),
            multiSelect = o.bool("multiSelect") ?: false,
            options = (o["options"] as? JsonArray).orEmpty().mapNotNull { e ->
                (e as? JsonObject)?.let { QuestionOption(it.str("label") ?: "", it.str("description"), it.str("preview")) }
            },
        )
    }
}

data class QuestionOption(val label: String, val description: String?, val preview: String?)

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?: (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** A JSON value as display text: strings unquoted, everything else as compact JSON. */
fun JsonElement.asText(): String = when {
    this is JsonNull -> ""
    this is JsonPrimitive && isString -> content
    this is JsonPrimitive -> jsonPrimitive.content
    else -> toString()
}
