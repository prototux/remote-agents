package net.prototux.remoteagents.api

import kotlinx.serialization.Serializable

/**
 * One subagent of a session (agentd S11): an Agent tool subagent or an agent of a workflow run. What
 * `GET /sessions/:id/agents` lists and `agent_update` carries. Times are ISO-8601, null when unknown.
 * even-terminal has none of this.
 */
@Serializable
data class AgentSummary(
    val id: String,
    /** [AgentKind]. */
    val kind: String = AgentKind.SUBAGENT,
    val runId: String? = null,
    val label: String = "",
    val description: String? = null,
    val agentType: String? = null,
    val phase: String? = null,
    val model: String? = null,
    /** [AgentState]. */
    val state: String = AgentState.RUNNING,
    val startedAt: String? = null,
    val endedAt: String? = null,
    /** The agent's latest record: tells a long tool call from a stalled agent. */
    val updatedAt: String? = null,
    val lastAction: String? = null,
    val toolCount: Int = 0,
    /** Context size of the agent's latest model call (or the run summary's figure). */
    val tokens: Long? = null,
    /** The parent conversation's Agent tool call (its `tool_start.toolId`). */
    val toolUseId: String? = null,
) {
    val running: Boolean get() = state == AgentState.RUNNING
    val title: String get() = label.ifBlank { description ?: agentType ?: id }

    /** Time from start to end (or to [nowMs] while running); null without a start time. */
    fun elapsedMs(nowMs: Long): Long? {
        val start = instant(startedAt) ?: return null
        val end = if (running) nowMs else instant(endedAt) ?: instant(updatedAt) ?: return null
        return (end - start).coerceAtLeast(0)
    }
}

@Serializable
data class WorkflowPhase(val title: String, val detail: String? = null)

/** One workflow run of a session (agentd S11). */
@Serializable
data class WorkflowRun(
    val runId: String,
    val name: String? = null,
    val summary: String? = null,
    val phases: List<WorkflowPhase> = emptyList(),
    val currentPhase: String? = null,
    val agentsDone: Int = 0,
    val agentsFailed: Int = 0,
    /** Agents started so far; the run's own count once it wrote its summary. */
    val agentsTotal: Int = 0,
    /** [WorkflowStatus]: the run's own once it wrote one, else running. */
    val status: String = WorkflowStatus.RUNNING,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val durationMs: Long? = null,
    val tokens: Long? = null,
    val toolCalls: Int? = null,
) {
    val running: Boolean get() = status == WorkflowStatus.RUNNING
    val title: String get() = name?.ifBlank { null } ?: runId

    fun elapsedMs(nowMs: Long): Long? {
        if (!running) durationMs?.let { return it }
        val start = instant(startedAt) ?: return null
        val end = if (running) nowMs else instant(endedAt) ?: return null
        return (end - start).coerceAtLeast(0)
    }
}

/** `GET /api/sessions/:id/agents`. */
@Serializable
data class AgentsList(
    val sessionId: String? = null,
    val provider: String? = null,
    val agents: List<AgentSummary> = emptyList(),
    val workflows: List<WorkflowRun> = emptyList(),
)

object AgentKind {
    const val SUBAGENT = "subagent"
    const val WORKFLOW = "workflow"
}

object AgentState {
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
}

/** The values agentd sends; a run's own summary may use others, shown as they are. */
object WorkflowStatus {
    const val RUNNING = "running"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val INTERRUPTED = "interrupted"
}

/** A workflow run with its agents, in start order. */
data class WorkflowGroup(val run: WorkflowRun, val agents: List<AgentSummary>)

/**
 * The subagents and workflow runs of one session, from `/agents` and the `agent_update` / `workflow_update`
 * events (state, not messages: the latest of each wins). Immutable; empty on even-terminal.
 */
data class AgentsState(
    val agents: Map<String, AgentSummary> = emptyMap(),
    val workflows: Map<String, WorkflowRun> = emptyMap(),
) {
    val isEmpty: Boolean get() = agents.isEmpty() && workflows.isEmpty()
    val runningCount: Int get() = agents.values.count { it.running }
    val anyRunning: Boolean get() = runningCount > 0 || workflows.values.any { it.running }

    /** Applies an `agent_update` or `workflow_update`; other messages leave it unchanged. */
    fun reduce(m: AgentMessage): AgentsState = when (m) {
        is AgentMessage.AgentUpdate -> copy(agents = agents + (m.agent.id to m.agent))
        is AgentMessage.WorkflowUpdate -> copy(workflows = workflows + (m.workflow.runId to m.workflow))
        else -> this
    }

    /**
     * Workflow runs with their agents: running runs first, then the newest. An agent whose run has not been
     * reported yet gets a placeholder run (updates for one agent and its run may arrive in either order).
     */
    val groups: List<WorkflowGroup>
        get() {
            val byRun = agents.values.filter { it.runId != null }.groupBy { it.runId!! }
            val runs = workflows + (byRun.keys - workflows.keys).associateWith { WorkflowRun(it, agentsTotal = byRun[it]!!.size) }
            return runs.values
                .sortedWith(compareByDescending<WorkflowRun> { it.running }.thenByDescending { it.startedAt.orEmpty() })
                .map { r -> WorkflowGroup(r, byRun[r.runId].orEmpty().sortedBy { it.startedAt.orEmpty() }) }
        }

    /** Agent tool subagents (not part of a run): running first, then the newest. */
    val standalone: List<AgentSummary>
        get() = agents.values.filter { it.runId == null }
            .sortedWith(compareByDescending<AgentSummary> { it.running }.thenByDescending { it.startedAt.orEmpty() })

    /**
     * One line for the status strip and the ongoing notification, null when nothing runs:
     * "workflow agentd-build · Review · 5/8 agents", plus other running runs and subagents.
     */
    val statusLine: String?
        get() {
            val runs = groups.filter { it.run.running }
            val loose = standalone.filter { it.running }
            val parts = mutableListOf<String>()
            runs.firstOrNull()?.run?.let { r ->
                parts += listOfNotNull(
                    "workflow ${r.title}", r.currentPhase, "${r.agentsDone}/${r.agentsTotal} agents",
                    r.agentsFailed.takeIf { it > 0 }?.let { "$it failed" },
                ).joinToString(" · ")
            }
            if (runs.size > 1) parts += "+${runs.size - 1} more " + if (runs.size == 2) "workflow" else "workflows"
            when (loose.size) {
                0 -> Unit
                1 -> parts += "subagent " + loose.single().title
                else -> parts += "${loose.size} subagents"
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }

    /**
     * The state from a `/agents` answer read while the stream stayed open: the agents and runs the stream
     * updated meanwhile ([touched]: agent ids, and run ids prefixed with [RUN_KEY]) keep the stream's
     * version, as the answer may predate it and agentd sends an update only when something changes.
     */
    fun refreshedWith(list: AgentsList, touched: Set<String>): AgentsState {
        val fresh = of(list)
        return AgentsState(
            fresh.agents.filterKeys { it !in touched } + agents.filterKeys { it in touched },
            fresh.workflows.filterKeys { RUN_KEY + it !in touched } + workflows.filterKeys { RUN_KEY + it in touched },
        )
    }

    companion object {
        /** Prefix of a run id in [refreshedWith]'s touched set (agent and run ids could collide). */
        const val RUN_KEY = "run:"

        fun of(list: AgentsList) = AgentsState(list.agents.associateBy { it.id }, list.workflows.associateBy { it.runId })

        /** The key an update touches, for [refreshedWith]; null for other messages. */
        fun touchedKey(m: AgentMessage): String? = when (m) {
            is AgentMessage.AgentUpdate -> m.agent.id
            is AgentMessage.WorkflowUpdate -> RUN_KEY + m.workflow.runId
            else -> null
        }
    }
}

private fun instant(iso: String?): Long? = iso?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
