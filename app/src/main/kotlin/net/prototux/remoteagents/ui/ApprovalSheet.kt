package net.prototux.remoteagents.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.prototux.remoteagents.api.AgentMessage
import net.prototux.remoteagents.api.QuestionDraft
import net.prototux.remoteagents.ui.theme.tokens

/**
 * Answers what the session waits for: a permission request (one button per offered option, sending its
 * key) or AskUserQuestion (options with descriptions and previews, single or multi choice, free text).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(
    permission: AgentMessage.PermissionRequest?,
    question: AgentMessage.UserQuestion?,
    onDismiss: () -> Unit,
    onDecide: (String) -> Unit,
    onAnswer: (Map<String, String>) -> Unit,
    sending: Boolean,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                permission != null -> PermissionContent(permission, onDecide, sending)
                question != null -> QuestionContent(question, onAnswer, sending)
                else -> Text("Nothing to answer any more.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ColumnScope.PermissionContent(r: AgentMessage.PermissionRequest, onDecide: (String) -> Unit, sending: Boolean) {
    Text("Allow ${r.toolName}?", style = MaterialTheme.typography.headlineSmall)
    r.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    r.detail?.takeIf { it.isNotBlank() }?.let { CodeBlock(it, maxLines = 20) }
    Text("Allowing lets the agent run this on your workstation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        r.options.forEach { o ->
            val m = Modifier.fillMaxWidth()
            when {
                o.key == "deny" || o.key.startsWith("deny") || o.key == "reject" -> OutlinedButton({ onDecide(o.key) }, m, enabled = !sending,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(o.text) }
                o.key == "allow" -> Button({ onDecide(o.key) }, m, enabled = !sending) { Text(o.text) }
                else -> FilledTonalButton({ onDecide(o.key) }, m, enabled = !sending) { Text(o.text) }
            }
        }
    }
    if (sending) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
}

@Composable
private fun ColumnScope.QuestionContent(q: AgentMessage.UserQuestion, onAnswer: (Map<String, String>) -> Unit, sending: Boolean) {
    var draft by remember(q) { mutableStateOf(QuestionDraft(q.questions)) }
    q.questions.forEach { question ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            question.header?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(question.question, style = MaterialTheme.typography.titleMedium)
            if (question.multiSelect) Text("Choose any", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            question.options.forEach { o ->
                val selected = o.label in draft.selected[question.key].orEmpty()
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().clickable { draft = draft.toggle(question, o.label) },
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                        if (question.multiSelect) Checkbox(selected, { draft = draft.toggle(question, o.label) })
                        else RadioButton(selected, { draft = draft.toggle(question, o.label) })
                        Column(Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(o.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            o.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            o.preview?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = tokens.mono, maxLines = 12) }
                        }
                    }
                }
            }
            OutlinedTextField(
                draft.freeText[question.key].orEmpty(), { draft = draft.setText(question, it) },
                label = { Text("Other answer") }, modifier = Modifier.fillMaxWidth(), maxLines = 4,
            )
        }
    }
    Button({ onAnswer(draft.answers) }, Modifier.fillMaxWidth(), enabled = draft.complete && !sending) { Text("Send answer") }
    if (sending) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
}
