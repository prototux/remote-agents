package net.prototux.remoteagents.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.prototux.remoteagents.Container
import net.prototux.remoteagents.api.Format
import net.prototux.remoteagents.api.ProviderOption
import net.prototux.remoteagents.api.ProviderPicker
import net.prototux.remoteagents.api.Providers
import net.prototux.remoteagents.api.ServerInfo
import net.prototux.remoteagents.data.ServerConfig
import net.prototux.remoteagents.data.SessionKey

/**
 * Agent, working directory (empty = the server's project directory) and the first prompt. The agents are the ones
 * [info] says are installed (agentd), else Claude Code and Codex (even-terminal, or `/info` not answered yet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionSheet(
    container: Container,
    server: ServerConfig,
    info: ServerInfo?,
    knownCwds: List<String>,
    onDismiss: () -> Unit,
    onStarted: (SessionKey, String, String) -> Unit,
    onError: (String) -> Unit,
) {
    val options = remember(info) { ProviderPicker.options(info) }
    var picked by rememberSaveable { mutableStateOf(server.defaultProvider) }
    // What was picked while it is offered; the server's default or the first option otherwise (/info may arrive later).
    val provider = ProviderPicker.select(options, picked)
    var cwd by rememberSaveable { mutableStateOf(server.recentCwds.firstOrNull().orEmpty()) }
    var text by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val suggestions = remember(server.recentCwds, knownCwds) { (server.recentCwds + knownCwds).distinct().take(8) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // Scrolls: the sheet pads its content by the keyboard (safeDrawing), so without scrolling the prompt and
        // Start end up under the keyboard; a focused field scrolls itself into view when the viewport shrinks.
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).imePadding().navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("New session", style = MaterialTheme.typography.titleLarge)
            if (options.isEmpty()) {
                Text("This server runs no agent that can start a session.", color = MaterialTheme.colorScheme.error)
            } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { o -> FilterChip(provider == o.name, { picked = o.name }, label = { ProviderChipLabel(o) }) }
            }
            OutlinedTextField(
                cwd, { cwd = it }, label = { Text("Working directory") }, singleLine = true,
                placeholder = { Text("Server default") }, modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(AppIcons.Folder, null) },
            )
            if (suggestions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { s -> AssistChip(onClick = { cwd = s }, label = { Text(Format.path(s, keep = 2)) }) }
            }
            VoiceTextField(container, text, { text = it }, label = "What should the agent do?", minLines = 3, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = text.isNotBlank() && !sending && provider != null, onClick = {
                    val chosen = provider ?: return@Button
                    sending = true
                    scope.launch {
                        runCatching { container.hub.newSession(server.id, chosen, cwd.trim(), text.trim()) }.fold(
                            { key -> container.settings.rememberPrompt(text.trim()); onStarted(key, chosen, text.trim().lineSequence().first().take(80)) },
                            { sending = false; onError(it.describe()) },
                        )
                    }
                }) { Text("Start") }
                if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun ProviderChipLabel(o: ProviderOption) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(o.label)
    if (o.experimental) Text(Providers.EXPERIMENTAL_TAG, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
}
