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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import net.prototux.remoteagents.api.Providers
import net.prototux.remoteagents.data.ServerConfig
import net.prototux.remoteagents.data.SessionKey

/** Agent, working directory (empty = the server's project directory) and the first prompt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionSheet(
    container: Container,
    server: ServerConfig,
    knownCwds: List<String>,
    onDismiss: () -> Unit,
    onStarted: (SessionKey, String, String) -> Unit,
    onError: (String) -> Unit,
) {
    var provider by rememberSaveable { mutableStateOf(server.defaultProvider) }
    var cwd by rememberSaveable { mutableStateOf(server.recentCwds.firstOrNull().orEmpty()) }
    var text by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val suggestions = remember(server.recentCwds, knownCwds) { (server.recentCwds + knownCwds).distinct().take(8) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).imePadding().navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("New session", style = MaterialTheme.typography.titleLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                providerOptions().forEachIndexed { i, p ->
                    SegmentedButton(provider == p, { provider = p }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(Providers.label(p)) }
                }
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
                Button(enabled = text.isNotBlank() && !sending, onClick = {
                    sending = true
                    scope.launch {
                        runCatching { container.hub.newSession(server.id, provider, cwd.trim(), text.trim()) }.fold(
                            { key -> container.settings.rememberPrompt(text.trim()); onStarted(key, provider, text.trim().lineSequence().first().take(80)) },
                            { sending = false; onError(it.describe()) },
                        )
                    }
                }) { Text("Start") }
                if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}
