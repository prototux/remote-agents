package net.prototux.remoteagents.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.prototux.remoteagents.api.Format
import net.prototux.remoteagents.api.SlashCommands

/**
 * The prompt box: multi-line text, slash-command suggestions (sent as text), recent prompts, quick replies,
 * dictation, send, and Interrupt while the agent works (like Esc in Claude Code). With [disabledHint] set
 * the session cannot take prompts (watch only): the box shows the hint and sends nothing.
 */
@Composable
fun PromptBox(
    text: String,
    onText: (String) -> Unit,
    provider: String?,
    drive: String?,
    busy: Boolean,
    sending: Boolean,
    showQuickReplies: Boolean,
    recentPrompts: () -> List<String>,
    voice: VoiceController?,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    disabledHint: String? = null,
) {
    val enabled = disabledHint == null
    val suggestions = remember(text, provider, drive) { SlashCommands.matching(provider, drive, text.trim()) }
    var historyOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            if (suggestions.isNotEmpty()) Column(Modifier.heightIn(max = 220.dp)) {
                suggestions.take(6).forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { onText(c.name + " ") }.padding(horizontal = 8.dp, vertical = 6.dp)) {
                        Text(c.name, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        Text("  " + c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (enabled && showQuickReplies && text.isEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QUICK_REPLIES.forEach { q -> SuggestionChip(onClick = { onSend(q) }, label = { Text(q) }, enabled = !sending) }
            }
            voice?.let { v -> (v.partial ?: v.error)?.let { Text(it, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            Row(verticalAlignment = Alignment.Bottom) {
                FieldAligned {
                    IconButton(onClick = { historyOpen = true }, enabled = enabled) { Icon(AppIcons.History, "Recent prompts") }
                    val recent = if (historyOpen) recentPrompts() else emptyList()
                    DropdownMenu(historyOpen, { historyOpen = false }) {
                        if (recent.isEmpty()) DropdownMenuItem(text = { Text("No recent prompts") }, onClick = { historyOpen = false }, enabled = false)
                        recent.take(15).forEach { p -> DropdownMenuItem(text = { Text(Format.oneLine(p, 60)) }, onClick = { onText(p); historyOpen = false }) }
                    }
                }
                OutlinedTextField(
                    text, onText,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    placeholder = { Text(disabledHint ?: if (busy) "Queue a message…" else "Message, or / for commands") },
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    trailingIcon = voice?.takeIf { enabled }?.let { v -> { MicButton(v) { onText(appendText(text, it)) } } },
                )
                if (busy && enabled) FieldAligned {
                    IconButton(onClick = onInterrupt) { Icon(AppIcons.Stop, "Interrupt", tint = MaterialTheme.colorScheme.error) }
                }
                FieldAligned(Modifier.padding(start = 4.dp)) {
                    FilledIconButton(
                        onClick = { onSend(text.trim()) },
                        enabled = enabled && text.isNotBlank() && !sending,
                        colors = IconButtonDefaults.filledIconButtonColors(),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                }
            }
        }
    }
}

/**
 * A control beside the text field, centred in a box as tall as a one-line field: the buttons (48 and 40 dp) then
 * line up with the field's text while it has one line, and stay level with its last line as it grows, since the
 * row aligns to the bottom. Aligning the buttons' own bottoms with the field's left each one a few dp off.
 */
@Composable
private fun FieldAligned(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.height(OutlinedTextFieldDefaults.MinHeight), contentAlignment = Alignment.Center) { content() }
}

private val QUICK_REPLIES = listOf("Continue", "Yes, go ahead", "Run the tests", "Commit this", "/compact")
