package net.prototux.remoteagents.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.prototux.remoteagents.Container
import net.prototux.remoteagents.data.VoiceMode
import net.prototux.remoteagents.voice.ServerVoiceInput
import net.prototux.remoteagents.voice.SystemVoiceInput
import net.prototux.remoteagents.voice.VoiceInput

/** Mic state for a text field: tap to talk, tap again to stop; the result is appended to the text. */
@Stable
class VoiceController internal constructor(private val input: VoiceInput, private val requestPermission: (() -> Unit) -> Unit) {
    var listening by mutableStateOf(false); private set
    var partial by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set

    fun toggle(onText: (String) -> Unit) {
        if (listening) { input.stop(); return }
        error = null
        requestPermission {
            input.start(object : VoiceInput.Listener {
                override fun onListening(active: Boolean) { listening = active }
                override fun onPartial(text: String) { partial = text }
                override fun onResult(text: String) { partial = null; if (text.isNotBlank()) onText(text) }
                override fun onError(message: String) { partial = null; listening = false; error = message }
            })
        }
    }

    internal fun dispose() = input.cancel()
}

/** Null when voice input is off in Settings. */
@Composable
fun rememberVoice(container: Container): VoiceController? {
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) pending?.invoke(); pending = null }
    val controller = remember(settings.voice, settings.sttUrl, settings.sttModel, settings.sttLanguage) {
        val input: VoiceInput = when (settings.voice) {
            VoiceMode.Off -> return@remember null
            VoiceMode.System -> SystemVoiceInput(context, settings.sttLanguage)
            VoiceMode.Server -> ServerVoiceInput(context, settings.sttUrl, settings.sttModel, settings.sttLanguage, container.settings.sttKey, scope)
        }
        VoiceController(input) { start ->
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
            else { pending = start; launcher.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }
    DisposableEffect(controller) { onDispose { controller?.dispose() } }
    return controller
}

@Composable
fun MicButton(voice: VoiceController, onText: (String) -> Unit) {
    IconButton(onClick = { voice.toggle(onText) }) {
        Icon(
            if (voice.listening) AppIcons.Stop else AppIcons.Mic,
            if (voice.listening) "Stop dictation" else "Dictate",
            tint = if (voice.listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun appendText(current: String, addition: String) =
    if (current.isBlank()) addition else current.trimEnd() + " " + addition

/** A text field with a mic button when voice input is on. */
@Composable
fun VoiceTextField(container: Container, value: String, onValueChange: (String) -> Unit, label: String, minLines: Int = 1, modifier: Modifier = Modifier) {
    val voice = rememberVoice(container)
    OutlinedTextField(
        value, onValueChange, label = { Text(label) }, minLines = minLines, maxLines = 8, modifier = modifier,
        trailingIcon = voice?.let { v -> { MicButton(v) { onValueChange(appendText(value, it)) } } },
        supportingText = voice?.let { v -> (v.partial ?: v.error)?.let { t -> { Text(t) } } },
    )
}
