package net.prototux.remoteagents.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.prototux.remoteagents.Container
import net.prototux.remoteagents.data.AppSettings
import net.prototux.remoteagents.data.DarkMode
import net.prototux.remoteagents.data.ThemeId
import net.prototux.remoteagents.data.VoiceMode
import net.prototux.remoteagents.ui.theme.tokens
import net.prototux.remoteagents.voice.SystemVoiceInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: Container, onBack: () -> Unit, onAbout: () -> Unit) {
    val s by container.settings.settings.collectAsStateWithLifecycle()
    val update: ((AppSettings) -> AppSettings) -> Unit = container.settings::update
    val context = LocalContext.current
    var sttKey by remember { mutableStateOf(container.settings.sttKey.orEmpty()) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = tokens.screenMargin)) {
            SectionTitle("Appearance")
            Choice("Theme", ThemeId.entries, s.theme, { if (it == ThemeId.Material) "Material" else "Nothing" }) { v -> update { it.copy(theme = v) } }
            Choice("Dark mode", DarkMode.entries, s.darkMode, { it.name }) { v -> update { it.copy(darkMode = v) } }
            if (s.theme == ThemeId.Material) Toggle("Wallpaper colours", "Material You dynamic colour", s.dynamicColor) { v -> update { it.copy(dynamicColor = v) } }

            SectionTitle("Notifications")
            Toggle("Approvals", "A tool needs your permission", s.notifyApproval) { v -> update { it.copy(notifyApproval = v) } }
            Toggle("Questions", "The agent asks you to choose", s.notifyQuestion) { v -> update { it.copy(notifyQuestion = v) } }
            Toggle("Finished turns", "With a reply action", s.notifyFinished) { v -> update { it.copy(notifyFinished = v) } }
            Toggle("Errors", null, s.notifyError) { v -> update { it.copy(notifyError = v) } }
            Toggle("Unlock to act from notifications", "Allow, deny and reply ask for your fingerprint or PIN first", s.actionsNeedUnlock) { v -> update { it.copy(actionsNeedUnlock = v) } }
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("System notification settings") }
            Text("Sessions you open or start are followed: while they work, a quiet notification keeps the connection open so requests reach you immediately. It goes away when they are idle.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SectionTitle("Session view")
            Toggle("Expand tool calls", "Show input and output without tapping", s.expandTools) { v -> update { it.copy(expandTools = v) } }
            Toggle("Show unsupported events", "Events this app does not know yet, as raw JSON", s.showUnsupported) { v -> update { it.copy(showUnsupported = v) } }
            Toggle("Keep screen on", "While a session is open", s.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } }

            SectionTitle("Voice input")
            Choice("Dictation", VoiceMode.entries, s.voice, { when (it) { VoiceMode.Off -> "Off"; VoiceMode.System -> "Phone"; VoiceMode.Server -> "My server" } }) { v -> update { it.copy(voice = v) } }
            when (s.voice) {
                VoiceMode.System -> {
                    val onDevice = remember { SystemVoiceInput(context, null).onDevice }
                    Text(
                        if (onDevice) "This phone has an on-device recogniser: speech stays on the phone."
                        else "No on-device recogniser found: the phone's speech service may send audio to its provider (often Google).",
                        style = MaterialTheme.typography.bodySmall, color = if (onDevice) tokens.success else MaterialTheme.colorScheme.error,
                    )
                }
                VoiceMode.Server -> {
                    OutlinedTextField(s.sttUrl, { v -> update { it.copy(sttUrl = v.trim()) } }, label = { Text("Server URL") }, singleLine = true,
                        placeholder = { Text("http://100.x.y.z:8000") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(s.sttModel, { v -> update { it.copy(sttModel = v.trim()) } }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(sttKey, { sttKey = it; container.settings.sttKey = it }, label = { Text("API key (optional)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Text("OpenAI-compatible /v1/audio/transcriptions (whisper.cpp server, faster-whisper, LocalAI…). Plain HTTP only to private or VPN addresses.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                VoiceMode.Off -> Unit
            }
            if (s.voice != VoiceMode.Off) OutlinedTextField(s.sttLanguage, { v -> update { it.copy(sttLanguage = v.trim()) } }, label = { Text("Language (optional, e.g. en-US)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())

            SectionTitle("About")
            Row(Modifier.fillMaxWidth().clickable(onClick = onAbout).padding(vertical = 12.dp)) { Text("About and licences") }
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(value, onChange)
    }
}

@Composable
private fun <T> Choice(title: String, options: List<T>, value: T, label: (T) -> String, onChange: (T) -> Unit) {
    Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o -> SegmentedButton(o == value, { onChange(o) }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label(o)) } }
    }
}
