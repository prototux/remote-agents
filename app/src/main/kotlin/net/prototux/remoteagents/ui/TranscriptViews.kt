package net.prototux.remoteagents.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.prototux.remoteagents.api.AgentMessage
import net.prototux.remoteagents.api.ApiJson
import net.prototux.remoteagents.api.DiffLine
import net.prototux.remoteagents.api.Format
import net.prototux.remoteagents.api.Todo
import net.prototux.remoteagents.api.ToolFormat
import net.prototux.remoteagents.api.TranscriptItem
import net.prototux.remoteagents.ui.theme.tokens

/** One transcript item, in the spirit of Claude Code's terminal: prompts, text, compact tool rows, outcomes. */
@Composable
fun TranscriptRow(item: TranscriptItem, expandTools: Boolean, onAnswer: () -> Unit) {
    val m = Modifier.fillMaxWidth().padding(horizontal = tokens.screenMargin, vertical = 4.dp)
    when (item) {
        is TranscriptItem.Prompt -> PromptView(item.text, item.pending, m)
        is TranscriptItem.Text -> MarkdownText(item.text, m)
        is TranscriptItem.Tool -> ToolRow(item, expandTools, m)
        is TranscriptItem.Permission -> PermissionRow(item, onAnswer, m)
        is TranscriptItem.Ask -> AskRow(item, onAnswer, m)
        is TranscriptItem.Notice -> Row(m, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(listOfNotNull(item.title, item.message).joinToString(": "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is TranscriptItem.Outcome -> OutcomeRow(item.result, m)
        is TranscriptItem.Failure -> Surface(m, color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                SelectionContainer { Text(item.message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        is TranscriptItem.Unknown -> UnknownRow(item, m)
        is TranscriptItem.Past -> Column(m.alpha(0.75f)) {
            Text(if (item.role == "user") "Earlier prompt" else "Earlier reply", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            if (item.role == "user") PromptView(item.text, false, Modifier) else MarkdownText(item.text)
        }
    }
}

@Composable
private fun PromptView(text: String, pending: Boolean, modifier: Modifier) {
    Surface(modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("›", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
            SelectionContainer(Modifier.weight(1f)) { Text(text, style = MaterialTheme.typography.bodyMedium) }
            if (pending) CircularProgressIndicator(Modifier.size(14.dp).padding(start = 4.dp), strokeWidth = 1.5.dp)
        }
    }
}

@Composable
private fun ToolRow(item: TranscriptItem.Tool, expandByDefault: Boolean, modifier: Modifier) {
    var open by rememberSaveable(item.key) { mutableStateOf(expandByDefault) }
    val view = remember(item.name, item.input) { ToolFormat.view(item.name, item.input) }
    val failed = !item.running && item.output?.let(::isErrorOutput) == true
    Column(modifier.animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (item.running) CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
            else Box(Modifier.size(8.dp).background(if (failed) MaterialTheme.colorScheme.error else tokens.success, CircleShape))
            Text(view.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                view.subject?.ifBlank { null } ?: item.summary.orEmpty(),
                style = MaterialTheme.typography.bodySmall, fontFamily = tokens.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (view.diff.isNotEmpty()) {
                val added = view.diff.count { it.kind == DiffLine.Kind.Added }
                val removed = view.diff.count { it.kind == DiffLine.Kind.Removed }
                Text("+$added −$removed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!open && item.summary != null && item.summary != view.subject && view.subject != null) {
            Text("⎿ " + Format.oneLine(item.summary, 100), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) Column(Modifier.padding(start = 16.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (item.name == "TodoWrite") Todo.parse(item.input)?.let { TodoList(it) }
            view.code?.let { CodeBlock(it, maxLines = 40, language = if (item.name == "Bash") "sh" else null) }
            if (view.diff.isNotEmpty()) DiffView(view.diff)
            if (view.code == null && view.diff.isEmpty() && item.input != null && item.name != "TodoWrite") {
                CodeBlock(prettyJson(item.input.toString()), maxLines = 30, language = "input")
            }
            item.output?.takeIf { it.isNotBlank() }?.let { CodeBlock(it.take(20_000), maxLines = 60, language = "output") }
        }
    }
}

private fun isErrorOutput(output: String) = output.startsWith("Error", ignoreCase = true) || output.contains("<tool_use_error>")

private fun prettyJson(s: String): String = runCatching {
    kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), ApiJson.parseToJsonElement(s))
}.getOrDefault(s)

@Composable
fun DiffView(lines: List<DiffLine>) {
    Surface(color = tokens.codeBackground, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            lines.take(400).forEach { l ->
                val (bg, sign) = when (l.kind) {
                    DiffLine.Kind.Added -> tokens.diffAdded to "+"
                    DiffLine.Kind.Removed -> tokens.diffRemoved to "-"
                    DiffLine.Kind.Context -> androidx.compose.ui.graphics.Color.Transparent to " "
                }
                Text("$sign ${l.text}", Modifier.background(bg).padding(horizontal = 10.dp), fontFamily = tokens.mono, fontSize = 12.sp, lineHeight = 16.sp, softWrap = false)
            }
            if (lines.size > 400) Text("  ⋯ ${lines.size - 400} more lines", fontFamily = tokens.mono, fontSize = 12.sp)
        }
    }
}

@Composable
fun TodoList(todos: List<Todo>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        todos.forEach { t ->
            val (mark, color) = when (t.status) {
                "completed" -> "☑" to MaterialTheme.colorScheme.onSurfaceVariant
                "in_progress" -> "◐" to MaterialTheme.colorScheme.primary
                else -> "☐" to MaterialTheme.colorScheme.onSurface
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(mark, color = color)
                Text(
                    if (t.status == "in_progress") t.activeForm ?: t.content else t.content,
                    style = MaterialTheme.typography.bodySmall, color = color,
                    fontWeight = if (t.status == "in_progress") FontWeight.SemiBold else null,
                    textDecoration = if (t.status == "completed") androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(item: TranscriptItem.Permission, onAnswer: () -> Unit, modifier: Modifier) {
    val r = item.request
    val result = item.result
    Surface(modifier, color = if (result == null) tokens.attention.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Permission" + (r.askedBy?.let { " · subagent " + Format.oneLine(it, 40) } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(r.toolName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                result?.let { DecisionChip(it.decision) }
            }
            r.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            r.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = tokens.mono, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            if (result == null) FilledTonalButton(onClick = onAnswer) { Text("Answer") }
        }
    }
}

@Composable
private fun DecisionChip(decision: String) {
    val (label, ok) = when (decision) {
        "allowed" -> "Allowed" to true
        "always" -> "Always allowed" to true
        "denied" -> "Denied" to false
        "expired" -> "Not answered here" to false
        else -> decision to true
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Filled.Check else Icons.Filled.Close, null, Modifier.size(14.dp), tint = if (ok) tokens.success else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AskRow(item: TranscriptItem.Ask, onAnswer: () -> Unit, modifier: Modifier) {
    Surface(modifier, color = if (item.answers == null) tokens.attention.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item.question.askedBy?.let { Text("Subagent · " + Format.oneLine(it, 40), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            item.question.questions.forEach { q ->
                Text(q.header ?: "Question", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(q.question, style = MaterialTheme.typography.bodyMedium)
                item.answers?.get(q.key)?.let { a -> Text("→ $a", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
            }
            if (item.answers == null) FilledTonalButton(onClick = onAnswer) { Text("Answer") }
        }
    }
}

@Composable
private fun OutcomeRow(r: AgentMessage.Result, modifier: Modifier) {
    val parts = listOfNotNull(
        r.durationMs?.let(Format::duration),
        r.costUsd?.let(Format::cost),
        if (r.inputTokens != null || r.outputTokens != null) "${Format.tokens(r.inputTokens ?: 0)}↑ ${Format.tokens(r.outputTokens ?: 0)}↓" else null,
        r.turns?.let { "$it turns" },
    )
    Column(modifier.padding(vertical = 4.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (r.success) Icons.Filled.Check else Icons.Filled.Close, null, Modifier.size(14.dp), tint = if (r.success) tokens.success else MaterialTheme.colorScheme.error)
            Text(if (r.success) "Done" else (r.text?.let { Format.oneLine(it, 80) } ?: "Stopped"), style = MaterialTheme.typography.labelMedium)
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UnknownRow(item: TranscriptItem.Unknown, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        TextButton(onClick = { open = !open }) {
            Text("Unsupported event: ${item.raw["type"]?.toString()?.trim('"') ?: "?"}", style = MaterialTheme.typography.labelSmall)
        }
        if (open) CodeBlock(prettyJson(item.raw.toString()), maxLines = 40)
    }
}
