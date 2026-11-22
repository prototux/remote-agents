package net.prototux.remoteagents.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.prototux.remoteagents.api.Providers
import net.prototux.remoteagents.api.SessionState
import net.prototux.remoteagents.ui.theme.tokens

/** A monospace block with a copy button, scrolling sideways rather than wrapping (code keeps its shape). */
@Composable
fun CodeBlock(code: String, modifier: Modifier = Modifier, language: String? = null, maxLines: Int = Int.MAX_VALUE) {
    val clipboard = LocalClipboardManager.current
    Surface(color = tokens.codeBackground, shape = MaterialTheme.shapes.small, modifier = modifier.fillMaxWidth()) {
        Box {
            SelectionContainer {
                Text(
                    code,
                    fontFamily = tokens.mono,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    maxLines = maxLines,
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 40.dp, top = 10.dp, bottom = 10.dp),
                )
            }
            Row(Modifier.align(Alignment.TopEnd), verticalAlignment = Alignment.CenterVertically) {
                language?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }, modifier = Modifier.size(36.dp)) {
                    Icon(AppIcons.Copy, "Copy", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** idle / working / needs you. Working pulses; awaiting uses the attention colour. */
@Composable
fun StateBadge(state: String?, modifier: Modifier = Modifier) {
    val (label, bg, fg) = when (state) {
        SessionState.AWAITING -> Triple("Needs you", tokens.attention, tokens.onAttention)
        SessionState.BUSY -> Triple("Working", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        else -> return
    }
    val pulse = if (state == SessionState.BUSY) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(1f, 0.45f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha").value
    } else 1f
    Row(
        modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).alpha(pulse).background(fg, CircleShape))
        Text(label, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ProviderBadge(provider: String?, modifier: Modifier = Modifier) {
    Text(
        Providers.label(provider),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Small coloured dot for connection state. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) = Box(modifier.size(8.dp).background(color, CircleShape))

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) = Text(
    text.uppercase(),
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = modifier.padding(top = 16.dp, bottom = 6.dp),
)

@Composable
fun Banner(text: String, color: Color, onColor: Color, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Surface(color = color, contentColor = onColor, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).padding(vertical = 6.dp), style = MaterialTheme.typography.bodyMedium)
            action?.invoke()
        }
    }
}

@Composable
fun KeyValue(key: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}
