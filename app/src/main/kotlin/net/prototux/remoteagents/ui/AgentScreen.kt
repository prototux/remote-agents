package net.prototux.remoteagents.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.prototux.remoteagents.Container
import net.prototux.remoteagents.api.Format
import net.prototux.remoteagents.api.TranscriptItem
import net.prototux.remoteagents.data.AgentView
import net.prototux.remoteagents.data.Link
import net.prototux.remoteagents.data.SessionKey
import net.prototux.remoteagents.ui.theme.tokens

/**
 * One subagent's transcript, read-only, live while the screen is started (agentd S11). Its summary comes from
 * the session's agents state, so the session stream stays attached too; its requests are answered there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    container: Container,
    serverId: String,
    sessionId: String,
    agentId: String,
    label: String?,
    onBack: () -> Unit,
) {
    val key = remember(serverId, sessionId) { SessionKey(serverId, sessionId) }
    val hub = container.hub
    val session by remember(key) { hub.state(key) }.collectAsStateWithLifecycle()
    val view by remember(key, agentId) { hub.followAgent(key, agentId) }.collectAsStateWithLifecycle(AgentView())
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val agent = session.agents.agents[agentId]
    val now = rememberNow(agent?.running == true)

    LifecycleStartEffect(key) {
        hub.attach(key)
        onStopOrDispose { hub.detach(key) }
    }

    val items = remember(view.transcript.items, settings.showUnsupported) {
        if (settings.showUnsupported) view.transcript.items else view.transcript.items.filterNot { it is TranscriptItem.Unknown }
    }
    val list = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (scrolling, canForward) -> if (scrolling) follow = !canForward }
    }
    val lastSize = (items.lastOrNull() as? TranscriptItem.Text)?.text?.length ?: 0
    LaunchedEffect(items.size, lastSize, follow) {
        if (follow && items.isNotEmpty()) list.scrollToItem(items.lastIndex, Int.MAX_VALUE / 2)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(agent?.title ?: label ?: "Agent", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull("read only", session.meta.title?.let { "in " + it }, linkLabel(view.link)).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                if (session.transcript.awaitingInput) {
                    Banner("The session waits for your answer", tokens.attention, tokens.onAttention, Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        TextButton(onClick = onBack) { Text("Open", color = tokens.onAttention) }
                    }
                }
                agent?.let { a ->
                    if (a.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(horizontal = tokens.screenMargin, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(agentColor(a.state))
                        Text(agentFacts(a), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        a.elapsedMs(now)?.let { Text(Format.duration(it), style = MaterialTheme.typography.labelMedium, fontFamily = tokens.mono) }
                    }
                }
                view.problem?.let { Text(it, Modifier.padding(tokens.screenMargin), color = MaterialTheme.colorScheme.error) }
                if (view.truncated) Text("The start of this long transcript is no longer on the server.", Modifier.padding(tokens.screenMargin), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (view.loading && items.isEmpty()) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else if (items.isEmpty() && view.problem == null) Text("Nothing in this agent's transcript yet.", Modifier.padding(tokens.screenMargin), color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxSize().navigationBarsPadding(), state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(items, key = { it.key }) { item -> TranscriptRow(item, settings.expandTools, onAnswer = onBack) }
                }
            }
            if (!follow) SmallFloatingActionButton(
                onClick = { follow = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).navigationBarsPadding(),
            ) { Icon(Icons.Filled.KeyboardArrowDown, "Jump to latest") }
        }
    }
}

private fun linkLabel(link: Link): String? = when (link) {
    Link.Live, Link.Stopped -> null
    Link.Connecting -> "connecting"
    is Link.Retrying -> "reconnecting"
    Link.AuthFailed -> "token refused"
    Link.CertificateChanged -> "certificate changed"
}
