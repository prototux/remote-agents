package net.prototux.remoteagents.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.prototux.remoteagents.api.AgentState
import net.prototux.remoteagents.api.AgentSummary
import net.prototux.remoteagents.api.AgentsState
import net.prototux.remoteagents.api.Format
import net.prototux.remoteagents.api.WorkflowRun
import net.prototux.remoteagents.api.WorkflowStatus
import net.prototux.remoteagents.ui.theme.tokens

/** A clock for elapsed times: ticks every second while [running], else stays put. */
@Composable
fun rememberNow(running: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) { now = System.currentTimeMillis(); while (running) { delay(1000); now = System.currentTimeMillis() } }
    return now
}

/** The session's workflow runs with their agents, then its Agent tool subagents (agentd S11). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsSheet(agents: AgentsState, onOpen: (AgentSummary) -> Unit, onDismiss: () -> Unit) {
    val now = rememberNow(agents.anyRunning)
    val groups = agents.groups
    val standalone = agents.standalone
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { Text("Agents", Modifier.padding(horizontal = tokens.screenMargin), style = MaterialTheme.typography.headlineSmall) }
            if (agents.isEmpty) item {
                Text("No subagents in this session.", Modifier.padding(tokens.screenMargin), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            groups.forEach { g ->
                item(key = "w" + g.run.runId) { WorkflowHeader(g.run, now) }
                items(g.agents, key = { "a" + it.id }) { AgentRow(it, now, indent = true) { onOpen(it) } }
            }
            if (standalone.isNotEmpty()) {
                item(key = "subagents") { SectionTitle("Subagents", Modifier.padding(horizontal = tokens.screenMargin)) }
                items(standalone, key = { "a" + it.id }) { AgentRow(it, now, indent = false) { onOpen(it) } }
            }
        }
    }
}

@Composable
private fun WorkflowHeader(run: WorkflowRun, now: Long) {
    Column(Modifier.fillMaxWidth().padding(horizontal = tokens.screenMargin).padding(top = 16.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(run.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            StatusLabel(run.status, workflowColor(run.status))
        }
        val counts = listOfNotNull(
            "workflow",
            run.currentPhase,
            "${run.agentsDone}/${run.agentsTotal} agents",
            run.agentsFailed.takeIf { it > 0 }?.let { "$it failed" },
            run.elapsedMs(now)?.let(Format::duration),
        ).joinToString(" · ")
        Text(counts, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (run.agentsTotal > 0) LinearProgressIndicator({ run.agentsDone.toFloat() / run.agentsTotal }, Modifier.fillMaxWidth().padding(vertical = 2.dp))
        run.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

/** Label, type and model, state, latest action and elapsed time; tapping opens its transcript. */
@Composable
fun AgentRow(agent: AgentSummary, now: Long, indent: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = tokens.screenMargin + if (indent) 12.dp else 0.dp, end = tokens.screenMargin, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(agentColor(agent.state))
            Text(agent.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            agent.elapsedMs(now)?.let { Text(Format.duration(it), style = MaterialTheme.typography.labelMedium, fontFamily = tokens.mono, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(agentFacts(agent), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        agent.lastAction?.takeIf { it.isNotBlank() }?.let {
            Text(Format.oneLine(it), style = MaterialTheme.typography.bodySmall, fontFamily = tokens.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "general-purpose · opus-5-5 · running · 14 tools · 95.8k tokens". */
fun agentFacts(a: AgentSummary): String = listOfNotNull(
    a.agentType,
    a.model?.removePrefix("claude-"),
    a.state,
    a.phase?.takeIf { a.runId == null },
    "${a.toolCount} " + if (a.toolCount == 1) "tool" else "tools",
    a.tokens?.let { Format.tokens(it) + " tokens" },
).joinToString(" · ")

@Composable
private fun StatusLabel(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Dot(color)
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun agentColor(state: String): Color = when (state) {
    AgentState.RUNNING -> MaterialTheme.colorScheme.primary
    AgentState.DONE -> tokens.success
    AgentState.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun workflowColor(status: String): Color = when (status) {
    WorkflowStatus.RUNNING -> MaterialTheme.colorScheme.primary
    WorkflowStatus.COMPLETED -> tokens.success
    WorkflowStatus.FAILED, WorkflowStatus.INTERRUPTED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}
