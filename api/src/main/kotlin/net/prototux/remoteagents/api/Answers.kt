package net.prototux.remoteagents.api

/**
 * The owner's answer to a `user_question` while they fill it in: chosen labels per question and an
 * optional free text ("Other"). [answers] is what `/question-response` takes.
 */
data class QuestionDraft(
    val questions: List<Question>,
    val selected: Map<String, Set<String>> = emptyMap(),
    val freeText: Map<String, String> = emptyMap(),
) {
    fun toggle(question: Question, label: String): QuestionDraft {
        val current = selected[question.key].orEmpty()
        val next = when {
            label in current -> current - label
            question.multiSelect -> current + label
            else -> setOf(label)
        }
        // A single choice replaces free text; free text is an alternative answer, not an addition.
        val text = if (!question.multiSelect && next.isNotEmpty()) freeText - question.key else freeText
        return copy(selected = selected + (question.key to next), freeText = text)
    }

    fun setText(question: Question, text: String): QuestionDraft {
        val sel = if (!question.multiSelect && text.isNotBlank()) selected - question.key else selected
        return copy(freeText = freeText + (question.key to text), selected = sel)
    }

    /** One answer text per question: selected labels in option order joined by ", ", then free text. */
    fun answerFor(question: Question): String? {
        val labels = question.options.map { it.label }.filter { it in selected[question.key].orEmpty() }
        val text = freeText[question.key]?.trim().orEmpty()
        val parts = labels + listOfNotNull(text.ifBlank { null })
        return parts.joinToString(", ").ifBlank { null }
    }

    val complete: Boolean get() = questions.all { answerFor(it) != null }

    val answers: Map<String, String> get() = questions.mapNotNull { q -> answerFor(q)?.let { q.key to it } }.toMap()
}

/** Display formats shared by the screens and notifications. */
object Format {
    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }

    fun tokens(n: Long): String = when {
        n < 1000 -> n.toString()
        n < 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fk", n / 1000.0)
        else -> String.format(java.util.Locale.ROOT, "%.2fM", n / 1_000_000.0)
    }

    fun cost(usd: Double): String = if (usd < 0.01) String.format(java.util.Locale.ROOT, "$%.4f", usd) else String.format(java.util.Locale.ROOT, "$%.2f", usd)

    /** "3 min ago" style, from the server's ISO timestamp; null when it cannot be read. */
    fun ago(iso: String?, nowMs: Long = System.currentTimeMillis()): String? {
        val t = iso?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
        val s = ((nowMs - t) / 1000).coerceAtLeast(0)
        return when {
            s < 45 -> "just now"
            s < 3600 -> "${(s + 30) / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            s < 7 * 86_400 -> "${s / 86_400} d ago"
            else -> iso.take(10)
        }
    }

    /** `/home/me/Projects/x` → `~/Projects/x`; keeps the last [keep] segments of long paths. */
    fun path(path: String?, keep: Int = 3): String {
        if (path.isNullOrBlank()) return ""
        val home = Regex("^/(home|Users)/[^/]+").find(path)
        val p = if (home != null) "~" + path.substring(home.range.last + 1) else path
        val parts = p.split('/').filter { it.isNotEmpty() }
        return if (parts.size > keep + 1) "…/" + parts.takeLast(keep).joinToString("/") else p
    }

    /** One line, at most [max] characters, for notifications and rows. */
    fun oneLine(text: String?, max: Int = 120): String {
        val t = text.orEmpty().replace(Regex("\\s+"), " ").trim()
        return if (t.length > max) t.take(max - 1) + "…" else t
    }
}
