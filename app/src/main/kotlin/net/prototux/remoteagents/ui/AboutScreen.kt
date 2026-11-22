package net.prototux.remoteagents.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.prototux.remoteagents.R
import net.prototux.remoteagents.ui.theme.tokens

private data class Licence(val name: String, val licence: String, val raw: Int? = null)

private val LICENCES = listOf(
    Licence("Remote Agents", "GPL-3.0-or-later", R.raw.gpl3),
    Licence("OkHttp, okhttp-sse", "Apache-2.0"),
    Licence("kotlinx.coroutines, kotlinx.serialization", "Apache-2.0"),
    Licence("AndroidX, Jetpack Compose, Material 3", "Apache-2.0"),
    Licence("ZXing Android Embedded, ZXing", "Apache-2.0"),
    Licence("Doto font", "SIL Open Font License 1.1", R.raw.doto_ofl),
    Licence("Hanken Grotesk font", "SIL Open Font License 1.1", R.raw.hanken_grotesk_ofl),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<Pair<String, String>?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(shown?.first ?: "About") }, navigationIcon = {
            IconButton(onClick = { if (shown != null) shown = null else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = tokens.screenMargin)) {
            val text = shown
            if (text != null) {
                Text(text.second, style = MaterialTheme.typography.bodySmall, fontFamily = tokens.mono)
                return@Column
            }
            Text("Remote Agents", style = MaterialTheme.typography.headlineMedium)
            Text("Follow and drive your coding agents (Claude Code, Codex) running on your own machines, over your own VPN.",
                style = MaterialTheme.typography.bodyMedium)
            Text("A client of even-terminal's network API, written from its protocol only. Not affiliated with Even Realities, Anthropic or OpenAI.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionTitle("Licences")
            LICENCES.forEach { l ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(l.name, style = MaterialTheme.typography.bodyLarge)
                    Text(l.licence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    l.raw?.let { res ->
                        TextButton(onClick = { shown = l.name to context.resources.openRawResource(res).bufferedReader().use { it.readText() } }) { Text("Read licence") }
                    }
                }
            }
        }
    }
}

