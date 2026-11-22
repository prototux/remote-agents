package net.prototux.remoteagents.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import net.prototux.remoteagents.api.Markdown
import net.prototux.remoteagents.api.MarkdownInline
import net.prototux.remoteagents.api.MdBlock
import net.prototux.remoteagents.api.MdSpan
import net.prototux.remoteagents.ui.theme.tokens

/** Assistant text rendered from Markdown. Parsing is cheap enough to redo on every streamed delta. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val blocks = remember(text) { Markdown.parse(text) }
    val codeBg = tokens.codeBackground
    val link = MaterialTheme.colorScheme.primary
    val mono = tokens.mono
    fun inline(s: String): AnnotatedString = buildAnnotatedString {
        for (span in MarkdownInline.parse(s)) when (span) {
            is MdSpan.Plain -> append(span.text)
            is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
            is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
            is MdSpan.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(span.text) }
            is MdSpan.Code -> withStyle(SpanStyle(fontFamily = mono, background = codeBg)) { append(" ${span.text} ") }
            is MdSpan.Link -> withLink(LinkAnnotation.Url(span.url, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) { append(span.text) }
        }
    }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (b in blocks) when (b) {
                is MdBlock.Heading -> Text(
                    inline(b.text), color = color, fontWeight = FontWeight.SemiBold,
                    style = when (b.level) { 1 -> MaterialTheme.typography.titleLarge; 2 -> MaterialTheme.typography.titleMedium; else -> MaterialTheme.typography.titleSmall },
                )
                is MdBlock.Paragraph -> Text(inline(b.text), color = color, style = MaterialTheme.typography.bodyMedium)
                is MdBlock.Code -> CodeBlock(b.code, language = b.language)
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                    Text(inline(b.text), Modifier.padding(start = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    b.items.forEachIndexed { i, item ->
                        Row(Modifier.padding(start = (item.depth * 16).dp)) {
                            val marker = when {
                                item.checked == true -> "☑"
                                item.checked == false -> "☐"
                                b.ordered -> "${item.number ?: (i + 1)}."
                                else -> "•"
                            }
                            Text(marker, Modifier.widthIn(min = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            Text(inline(item.text), color = color, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                is MdBlock.Table -> Column(Modifier.horizontalScroll(rememberScrollState())) {
                    val widths = (listOf(b.header) + b.rows).fold(IntArray(b.header.size)) { acc, row ->
                        row.forEachIndexed { i, c -> if (i < acc.size) acc[i] = maxOf(acc[i], c.length) }; acc
                    }
                    @Composable fun row(cells: List<String>, bold: Boolean) = Row {
                        cells.forEachIndexed { i, c ->
                            Text(inline(c), Modifier.width(((widths.getOrElse(i) { 8 }.coerceIn(3, 40)) * 8 + 16).dp).padding(end = 12.dp, top = 3.dp, bottom = 3.dp),
                                fontWeight = if (bold) FontWeight.SemiBold else null, style = MaterialTheme.typography.bodySmall, color = color)
                        }
                    }
                    row(b.header, true)
                    HorizontalDivider()
                    b.rows.forEach { row(it, false) }
                }
                MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
        }
    }
}
