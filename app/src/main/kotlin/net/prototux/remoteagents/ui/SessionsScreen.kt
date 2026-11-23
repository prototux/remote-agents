package net.prototux.remoteagents.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.prototux.remoteagents.Container
import net.prototux.remoteagents.api.Format
import net.prototux.remoteagents.api.Liveness
import net.prototux.remoteagents.api.ProviderPicker
import net.prototux.remoteagents.api.Providers
import net.prototux.remoteagents.api.Reach
import net.prototux.remoteagents.api.ServerInfo
import net.prototux.remoteagents.api.SessionState
import net.prototux.remoteagents.api.SessionSummary
import net.prototux.remoteagents.data.SessionKey
import net.prototux.remoteagents.data.SessionMeta
import net.prototux.remoteagents.ui.theme.tokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    container: Container,
    serverId: String,
    onBack: () -> Unit,
    onOpen: (SessionSummary) -> Unit,
    onOpenKey: (SessionKey, String?, String?) -> Unit,
    onEditServer: () -> Unit,
    onSettings: () -> Unit,
) {
    val servers by container.servers.servers.collectAsStateWithLifecycle()
    val server = servers.firstOrNull { it.id == serverId } ?: run { LaunchedEffect(Unit) { onBack() }; return }
    var sessions by remember { mutableStateOf<List<SessionSummary>?>(null) }
    var info by remember { mutableStateOf<ServerInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var newSession by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    suspend fun load() {
        val client = container.clients.get(serverId) ?: run { error = "Token missing: pair this server again"; return }
        runCatching { client.sessions(limit = 50) }.fold(
            { sessions = it.sortedByDescending { s -> s.timestamp }; error = null },
            { error = it.describe() },
        )
        if (info == null) info = runCatching { client.info() }.getOrNull()
    }

    // Refresh while on screen: states (working, needs you) change on the workstation.
    LifecycleResumeEffect(serverId) {
        val job = scope.launch { while (isActive) { load(); delay(10_000) } }
        onPauseOrDispose { job.cancel() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        info?.let { i ->
                            Text(listOfNotNull(i.model, i.account?.email).joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Servers") } },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Filled.Search, "Search") }
                    IconButton(onClick = onEditServer) { Icon(Icons.Filled.Edit, "Edit server") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { newSession = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New session") })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; load(); refreshing = false } },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val filters = remember(info, sessions) { ProviderPicker.filters(info, sessions.orEmpty()) }
            val shown = sessions.orEmpty().filter { s ->
                (filter == null || Providers.canonical(s.provider) == filter) &&
                    (query.isBlank() || listOfNotNull(s.title, s.cwd, s.id).any { it.contains(query, ignoreCase = true) })
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                if (searching) item {
                    OutlinedTextField(query, { query = it }, placeholder = { Text("Title, folder or id") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = tokens.screenMargin, vertical = 4.dp))
                }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = tokens.screenMargin), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(filter == null, { filter = null }, label = { Text("All") })
                        filters.forEach { p -> FilterChip(filter == p, { filter = p }, label = { Text(Providers.label(p)) }) }
                    }
                }
                if (server.needsRepair) item {
                    Banner("The server refused the token. Pair again with the current QR code.", MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer, Modifier.padding(tokens.screenMargin)) { TextButton(onClick = onEditServer) { Text("Re-pair") } }
                }
                error?.let { e -> item { Banner(e, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, Modifier.padding(tokens.screenMargin)) } }
                if (sessions != null && shown.isEmpty()) item {
                    Text(if (query.isBlank()) "No sessions yet. Start one with the button below." else "No session matches.",
                        Modifier.padding(tokens.screenMargin), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(shown, key = { it.id }) { s ->
                    SessionRow(s, container.hub.isWatched(SessionKey(serverId, s.id))) {
                        // The session screen knows the liveness at once, before its own /status answers.
                        container.hub.note(SessionKey(serverId, s.id), SessionMeta(s.title, s.cwd, s.provider, Reach.of(s)))
                        onOpen(s)
                    }
                    HorizontalDivider(Modifier.padding(start = tokens.screenMargin), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    if (newSession) {
        NewSessionSheet(
            container, server, info,
            knownCwds = sessions.orEmpty().mapNotNull { it.cwd }.distinct(),
            onDismiss = { newSession = false },
            onStarted = { key, provider, title -> newSession = false; onOpenKey(key, provider, title) },
            onError = { scope.launch { snackbar.showSnackbar(it) } },
        )
    }
}

@Composable
private fun SessionRow(s: SessionSummary, watched: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = tokens.screenMargin, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.title?.ifBlank { null } ?: "Untitled session", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProviderBadge(s.provider)
                ReachLabel(Reach.of(s))
                Icon(AppIcons.Folder, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Format.path(s.cwd), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Format.ago(s.timestamp).orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.status == SessionState.BUSY || s.status == SessionState.AWAITING) StateBadge(s.status)
            else if (watched) Text("followed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** agentd's liveness and drive ("in terminal · tmux", "hosted", "ended"); nothing on even-terminal. */
@Composable
private fun ReachLabel(reach: Reach) {
    val label = reach.label ?: return
    val color = when {
        reach.inTerminal && !reach.watchOnly -> MaterialTheme.colorScheme.primary
        reach.inTerminal || reach.liveness == Liveness.HOSTED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.outline
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
}
