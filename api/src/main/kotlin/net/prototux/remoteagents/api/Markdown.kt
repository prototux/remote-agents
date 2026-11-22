package net.prototux.remoteagents.api

/**
 * A small Markdown block parser for assistant text: enough of CommonMark/GFM for what coding agents write
 * (headings, paragraphs, fenced code, lists, quotes, rules, tables). Inline markup is left in the text and
 * handled by [MarkdownInline]. Tolerates unfinished input, since text arrives in streamed deltas.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
    data class ListBlock(val ordered: Boolean, val items: List<ListItem>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

data class ListItem(val text: String, val depth: Int, val number: Int?, val checked: Boolean?)

object Markdown {
    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val fence = Regex("^\\s*(```+|~~~+)\\s*([^`\\s]*).*$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val numbered = Regex("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val task = Regex("^\\[([ xX])]\\s+(.*)$")

    fun parse(text: String): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").split('\n')
        val out = ArrayList<MdBlock>()
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim()); para.clear() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val f = fence.matchEntire(line)
            when {
                f != null -> {
                    flush()
                    val marker = f.groupValues[1]
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(marker)) { code.append(lines[i]).append('\n'); i++ }
                    out += MdBlock.Code(f.groupValues[2].ifBlank { null }, code.toString().trimEnd('\n'))
                }
                line.isBlank() -> flush()
                heading.matchEntire(line) != null -> { flush(); heading.matchEntire(line)!!.let { out += MdBlock.Heading(it.groupValues[1].length, it.groupValues[2].trim().trimEnd('#').trim()) } }
                rule.matches(line) -> { flush(); out += MdBlock.Rule }
                line.trimStart().startsWith(">") -> {
                    flush()
                    val q = StringBuilder()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) { q.append(lines[i].trimStart().removePrefix(">").removePrefix(" ")).append('\n'); i++ }
                    out += MdBlock.Quote(q.toString().trim()); continue
                }
                line.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1]) -> {
                    flush()
                    val header = cells(line)
                    i += 2
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) { rows += cells(lines[i]); i++ }
                    out += MdBlock.Table(header, rows); continue
                }
                bullet.matches(line) || numbered.matches(line) -> {
                    flush()
                    val ordered = numbered.matches(line) && !bullet.matches(line)
                    val items = ArrayList<ListItem>()
                    while (i < lines.size) {
                        val l = lines[i]
                        val b = bullet.matchEntire(l)
                        val n = numbered.matchEntire(l)
                        // A top-level item of the other kind starts a new list.
                        if ((if (ordered) b else n)?.groupValues?.get(1)?.isEmpty() == true) break
                        when {
                            b != null -> items += item(b.groupValues[2], b.groupValues[1].length / 2, null)
                            n != null -> items += item(n.groupValues[3], n.groupValues[1].length / 2, n.groupValues[2].toInt())
                            l.isNotBlank() && l.startsWith("  ") && items.isNotEmpty() ->
                                items[items.lastIndex] = items.last().copy(text = items.last().text + " " + l.trim())
                            else -> break
                        }
                        i++
                    }
                    out += MdBlock.ListBlock(ordered, items); continue
                }
                else -> para.append(line).append('\n')
            }
            i++
        }
        flush()
        return out
    }

    private fun item(text: String, depth: Int, number: Int?): ListItem {
        val t = task.matchEntire(text)
        return if (t != null) ListItem(t.groupValues[2], depth, number, t.groupValues[1] != " ") else ListItem(text, depth, number, null)
    }

    private fun cells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
}

/** Inline spans: the UI turns them into styled text. */
sealed interface MdSpan {
    data class Plain(val text: String) : MdSpan
    data class Bold(val text: String) : MdSpan
    data class Italic(val text: String) : MdSpan
    data class Strike(val text: String) : MdSpan
    data class Code(val text: String) : MdSpan
    data class Link(val text: String, val url: String) : MdSpan
}

object MarkdownInline {
    private val token = Regex("""`([^`]+)`|\*\*(.+?)\*\*|__(.+?)__|~~(.+?)~~|\[([^\]]+)]\(([^)\s]+)\)|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\w)|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)""")

    fun parse(text: String): List<MdSpan> {
        val out = ArrayList<MdSpan>()
        var last = 0
        for (m in token.findAll(text)) {
            if (m.range.first > last) out += MdSpan.Plain(text.substring(last, m.range.first))
            val g = m.groupValues
            out += when {
                g[1].isNotEmpty() -> MdSpan.Code(g[1])
                g[2].isNotEmpty() -> MdSpan.Bold(g[2])
                g[3].isNotEmpty() -> MdSpan.Bold(g[3])
                g[4].isNotEmpty() -> MdSpan.Strike(g[4])
                g[5].isNotEmpty() -> MdSpan.Link(g[5], g[6])
                g[7].isNotEmpty() -> MdSpan.Italic(g[7])
                else -> MdSpan.Italic(g[8])
            }
            last = m.range.last + 1
        }
        if (last < text.length) out += MdSpan.Plain(text.substring(last))
        return out
    }
}
