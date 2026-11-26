package net.prototux.remoteagents.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** What a session looks like right now, built only from its messages (see [reduce]). Immutable. */
data class Transcript(
    val items: List<TranscriptItem> = emptyList(),
    /** `idle | busy | awaiting`. */
    val state: String = SessionState.IDLE,
    val phase: Phase = Phase.None,
    val progress: AgentMessage.TaskProgress? = null,
    /** The latest TodoWrite list, as Claude Code shows it. */
    val todos: List<Todo> = emptyList(),
    val stats: AgentMessage.RunningStats? = null,
    /** The request to show: the newest still open. */
    val pendingPermission: AgentMessage.PermissionRequest? = null,
    val pendingQuestion: AgentMessage.UserQuestion? = null,
    /** Every open request, oldest first: agentd may hold several (parallel subagents) and names the one it resolves. */
    val openPermissions: List<AgentMessage.PermissionRequest> = emptyList(),
    val openQuestions: List<AgentMessage.UserQuestion> = emptyList(),
    val totalCostUsd: Double = 0.0,
    val turns: Int = 0,
    /** Ids already applied, with their fingerprints, so a later stream can skip them. */
    val knownIds: Map<Long, Int> = emptyMap(),
) {
    enum class Phase { None, Thinking, Responding }

    val busy: Boolean get() = state != SessionState.IDLE
    val awaitingInput: Boolean get() = pendingPermission != null || pendingQuestion != null

    /** Applies one message. Pure: the same messages always give the same transcript. */
    fun reduce(m: AgentMessage): Transcript {
        val ids = if (m.id != null) knownIds + (m.id!! to m.fingerprint) else knownIds
        val t = copy(knownIds = ids)
        return when (m) {
            is AgentMessage.Status -> when (m.state) {
                "busy" -> t.copy(state = if (t.awaitingInput) SessionState.AWAITING else SessionState.BUSY)
                "idle" -> t.copy(state = SessionState.IDLE, phase = Phase.None, stats = null, pendingPermission = null, pendingQuestion = null, openPermissions = emptyList(), openQuestions = emptyList(),
                    items = t.items.map { if (it is TranscriptItem.Tool && it.running) it.copy(running = false) else it })
                "think_start" -> t.copy(phase = Phase.Thinking, state = t.busyState())
                "text_start" -> t.copy(phase = Phase.Responding, state = t.busyState())
                "think_end", "text_end" -> t.copy(phase = Phase.None)
                else -> t
            }
            is AgentMessage.UserPrompt -> {
                // The server echoes prompts; a local echo of the same text is confirmed rather than repeated.
                val i = t.items.indexOfLast { it is TranscriptItem.Prompt && it.pending && it.text == m.text }
                if (i >= 0) t.copy(items = t.items.replace(i, TranscriptItem.Prompt(m.text, pending = false, key = t.items[i].key)), state = t.busyState())
                else t.copy(items = t.items + TranscriptItem.Prompt(m.text, pending = false, key = "p${m.id ?: t.items.size}"), state = t.busyState())
            }
            is AgentMessage.TextDelta -> {
                val last = t.items.lastOrNull()
                if (last is TranscriptItem.Text) t.copy(items = t.items.replace(t.items.lastIndex, last.copy(text = last.text + m.text)))
                else t.copy(items = t.items + TranscriptItem.Text(m.text, key = "t${m.id ?: t.items.size}"))
            }
            is AgentMessage.ToolStart -> t.copy(
                items = t.items + TranscriptItem.Tool(m.toolId, m.name, null, null, null, running = true, key = "x${m.toolId ?: m.id ?: t.items.size}"),
                state = t.busyState(),
            )
            is AgentMessage.ToolEnd -> {
                val i = t.items.indexOfLast { it is TranscriptItem.Tool && it.running && (m.toolId == null || it.toolId == m.toolId) && (m.toolId != null || it.name == m.name) }
                val todos = if (m.name == "TodoWrite") Todo.parse(m.input) ?: t.todos else t.todos
                // autoAllowed carries over from the running row: the policy's permission_result arrives before tool_end.
                val done = TranscriptItem.Tool(m.toolId, m.name, m.summary, m.input, m.output, running = false,
                    autoAllowed = i >= 0 && (t.items[i] as TranscriptItem.Tool).autoAllowed,
                    key = if (i >= 0) t.items[i].key else "x${m.toolId ?: m.id ?: t.items.size}")
                t.copy(items = if (i >= 0) t.items.replace(i, done) else t.items + done, todos = todos)
            }
            is AgentMessage.TaskProgress -> t.copy(progress = m)
            is AgentMessage.PermissionRequest -> t.copy(
                items = t.items + TranscriptItem.Permission(m, null, key = "a${m.id ?: t.items.size}"),
                pendingPermission = m, openPermissions = t.openPermissions + m, state = SessionState.AWAITING,
            )
            is AgentMessage.PermissionResult -> {
                // agentd names the request it resolves; older servers resolve the latest one, and only one is open.
                val id = m.toolUseId
                val i = t.items.indexOfLast { it is TranscriptItem.Permission && it.result == null && (id == null || it.request.toolUseId == id) }
                var items = if (i >= 0) t.items.replace(i, (t.items[i] as TranscriptItem.Permission).copy(result = m)) else t.items
                // agentd's bash auto-allow (owner decision 2026-11-25): no permission_request precedes this
                // result, so there is no card to fill in; the tool row it allowed gets a label instead.
                if (m.auto && id != null) {
                    val ti = items.indexOfLast { it is TranscriptItem.Tool && it.toolId == id }
                    if (ti >= 0) items = items.replace(ti, (items[ti] as TranscriptItem.Tool).copy(autoAllowed = true))
                }
                val open = if (id == null) emptyList() else t.openPermissions.filter { it.toolUseId != id }
                val rest = t.copy(items = items, openPermissions = open, pendingPermission = open.lastOrNull())
                rest.copy(state = if (rest.awaitingInput) SessionState.AWAITING else SessionState.BUSY)
            }
            is AgentMessage.UserQuestion -> t.copy(
                items = t.items + TranscriptItem.Ask(m, null, key = "q${m.id ?: t.items.size}"),
                pendingQuestion = m, openQuestions = t.openQuestions + m, state = SessionState.AWAITING,
            )
            is AgentMessage.QuestionAnswer -> {
                val id = m.toolUseId
                val i = t.items.indexOfLast { it is TranscriptItem.Ask && it.answers == null && (id == null || it.question.toolUseId == id) }
                val items = if (i >= 0) t.items.replace(i, (t.items[i] as TranscriptItem.Ask).copy(answers = m.answers)) else t.items
                val open = if (id == null) emptyList() else t.openQuestions.filter { it.toolUseId != id }
                val rest = t.copy(items = items, openQuestions = open, pendingQuestion = open.lastOrNull())
                rest.copy(state = if (rest.awaitingInput) SessionState.AWAITING else SessionState.BUSY)
            }
            is AgentMessage.Notification -> t.copy(items = t.items + TranscriptItem.Notice(m.title, m.message, key = "n${m.id ?: t.items.size}"))
            is AgentMessage.RunningStats -> t.copy(stats = m)
            is AgentMessage.Result -> t.copy(
                items = t.items.map { if (it is TranscriptItem.Tool && it.running) it.copy(running = false) else it } +
                    TranscriptItem.Outcome(m, key = "r${m.id ?: t.items.size}"),
                phase = Phase.None, stats = null, pendingPermission = null, pendingQuestion = null, openPermissions = emptyList(), openQuestions = emptyList(),
                totalCostUsd = t.totalCostUsd + (m.costUsd ?: 0.0), turns = t.turns + 1,
            )
            is AgentMessage.Error -> t.copy(items = t.items + TranscriptItem.Failure(m.message, key = "e${m.id ?: t.items.size}"))
            // Agent state is not part of the conversation (see AgentsState).
            is AgentMessage.AgentUpdate, is AgentMessage.WorkflowUpdate -> t
            is AgentMessage.Unsupported -> t.copy(items = t.items + TranscriptItem.Unknown(m.raw, key = "u${m.id ?: t.items.size}"))
        }
    }

    fun reduceAll(messages: Iterable<AgentMessage>) = messages.fold(this) { t, m -> t.reduce(m) }

    /**
     * Applies only the messages not applied yet (a backlog read again after a pause). If the backlog shows
     * the server restarted, the transcript starts over from it.
     */
    fun reduceNew(messages: Iterable<AgentMessage>): Transcript {
        val dedup = Deduplicator(knownIds)
        var t = this
        for (m in messages) when (dedup.check(m.id, m.fingerprint)) {
            Deduplicator.Verdict.New -> t = t.reduce(m)
            Deduplicator.Verdict.Duplicate -> Unit
            Deduplicator.Verdict.Reset -> t = Transcript().reduce(m)
        }
        return t
    }

    /** The state the server reports (`/messages`, `/status`) wins over what the backlog implies. */
    fun withServerState(serverState: String): Transcript = when (serverState) {
        SessionState.IDLE -> copy(state = SessionState.IDLE, phase = Phase.None, stats = null, pendingPermission = null, pendingQuestion = null, openPermissions = emptyList(), openQuestions = emptyList())
        SessionState.BUSY, SessionState.AWAITING -> copy(state = serverState)
        else -> this
    }

    /** Shows a prompt immediately, before the server echoes it back. */
    fun withLocalPrompt(text: String) = copy(items = items + TranscriptItem.Prompt(text, pending = true, key = "l${items.size}"))

    /** Older turns from `/sessions/:id/history`, shown above the live messages when the buffer is empty. */
    fun withHistory(history: List<HistoryEntry>) =
        copy(items = history.mapIndexed { i, h -> TranscriptItem.Past(h.role, h.text, key = "h$i") } + items)

    private fun busyState() = if (awaitingInput) SessionState.AWAITING else SessionState.BUSY
}

sealed interface TranscriptItem {
    /** Stable identity for list diffing. */
    val key: String

    data class Prompt(val text: String, val pending: Boolean, override val key: String) : TranscriptItem
    data class Text(val text: String, override val key: String) : TranscriptItem
    data class Tool(
        val toolId: String?, val name: String, val summary: String?, val input: JsonElement?, val output: String?,
        val running: Boolean, override val key: String,
        /** agentd's "always allow Bash" policy allowed this call by itself, with no permission card shown. */
        val autoAllowed: Boolean = false,
    ) : TranscriptItem
    data class Permission(val request: AgentMessage.PermissionRequest, val result: AgentMessage.PermissionResult?, override val key: String) : TranscriptItem
    data class Ask(val question: AgentMessage.UserQuestion, val answers: Map<String, String>?, override val key: String) : TranscriptItem
    data class Notice(val title: String?, val message: String, override val key: String) : TranscriptItem
    data class Outcome(val result: AgentMessage.Result, override val key: String) : TranscriptItem
    data class Failure(val message: String, override val key: String) : TranscriptItem
    data class Unknown(val raw: JsonObject, override val key: String) : TranscriptItem
    /** A turn from `/history` (role `user` or `assistant`). */
    data class Past(val role: String, val text: String, override val key: String) : TranscriptItem
}

data class Todo(val content: String, val status: String, val activeForm: String?) {
    companion object {
        /** Reads TodoWrite's `{todos:[{content,status,activeForm}]}` input; null when it is not that shape. */
        fun parse(input: JsonElement?): List<Todo>? {
            val todos = (input as? JsonObject)?.get("todos") as? JsonArray ?: return null
            return todos.mapNotNull { e ->
                (e as? JsonObject)?.let { Todo(it.str("content") ?: return@let null, it.str("status") ?: "pending", it.str("activeForm")) }
            }
        }
    }
}

private fun <T> List<T>.replace(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }
