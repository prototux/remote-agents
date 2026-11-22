package net.prototux.remoteagents.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.prototux.remoteagents.Container
import net.prototux.remoteagents.api.Providers
import net.prototux.remoteagents.api.ServerInfo
import net.prototux.remoteagents.data.ServerConfig
import net.prototux.remoteagents.ui.theme.tokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    container: Container,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    autoOpenSingle: () -> Boolean,
) {
    val servers by container.servers.servers.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (servers.size == 1 && autoOpenSingle()) onOpen(servers.single().id) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Remote Agents") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") } },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            if (servers.isNotEmpty()) ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Pair server") })
        },
    ) { padding ->
        if (servers.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(AppIcons.Terminal, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Text("No server yet", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "On your workstation, run even-terminal bound to your VPN (for example even-terminal --tailscale), then scan the QR code it prints.",
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onAdd) { Icon(AppIcons.QrCode, null); Spacer(Modifier.size(8.dp)); Text("Pair a server") }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = tokens.screenMargin, end = tokens.screenMargin, top = padding.calculateTopPadding(), bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(tokens.cardGap),
            ) {
                items(servers, key = { it.id }) { s -> ServerCard(container, s, onOpen = { onOpen(s.id) }, onEdit = { onEdit(s.id) }) }
            }
        }
    }
}

private sealed interface Probe {
    data object Checking : Probe
    data class Online(val info: ServerInfo) : Probe
    data class Offline(val reason: String) : Probe
}

@Composable
private fun ServerCard(container: Container, server: ServerConfig, onOpen: () -> Unit, onEdit: () -> Unit) {
    var probe by remember(server) { mutableStateOf<Probe>(Probe.Checking) }
    LaunchedEffect(server) {
        val client = container.clients.get(server.id)
        probe = if (client == null) Probe.Offline("Token missing: pair again")
        else runCatching { client.info() }.fold({ Probe.Online(it) }, { Probe.Offline(it.describe()) })
    }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium)
                    if (server.https) Icon(Icons.Filled.Lock, "HTTPS", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    ProviderBadge(server.defaultProvider)
                }
                Text("${server.host}:${server.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (val p = probe) {
                        Probe.Checking -> { Dot(MaterialTheme.colorScheme.outline); Text("Checking…", style = MaterialTheme.typography.labelMedium) }
                        is Probe.Online -> {
                            Dot(tokens.success)
                            Text(
                                listOfNotNull("Online", p.info.version?.let { "v$it" }, p.info.model, p.info.account?.subscriptionType).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        is Probe.Offline -> {
                            Dot(MaterialTheme.colorScheme.error)
                            Text(if (server.needsRepair) "Re-pair needed" else p.reason, style = MaterialTheme.typography.labelMedium, maxLines = 2)
                        }
                    }
                }
                (probe as? Probe.Online)?.info?.extra?.expose?.takeIf { it !in setOf("off", "tailscale", null) }?.let {
                    Text("Warning: the server exposes a public tunnel ($it)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit server") }
        }
    }
}

internal fun providerOptions() = listOf(Providers.CLAUDE, Providers.CODEX)
