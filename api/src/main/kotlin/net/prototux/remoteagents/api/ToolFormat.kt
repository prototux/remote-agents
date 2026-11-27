package net.prototux.remoteagents.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** How to show one tool call, like Claude Code's terminal does: a title, a subject and an optional body. */
data class ToolView(
    val title: String,
    /** The main argument: a command, a file path, a pattern, a URL. */
    val subject: String?,
    val diff: List<DiffLine> = emptyList(),
    /** Code or text to show in a block (a Bash command, written file content). */
    val code: String? = null,
)

data class DiffLine(val kind: Kind, val text: String) {
    enum class Kind { Context, Added, Removed }
}

object ToolFormat {
    fun view(name: String, input: JsonElement?): ToolView {
        val o = input as? JsonObject
        fun s(k: String) = o?.str(k)
        val file = s("file_path") ?: s("notebook_path") ?: s("path")
        return when (name) {
            "Bash" -> ToolView("Bash", s("description"), code = s("command"))
            "Edit" -> ToolView("Update", file, diff = lineDiff(s("old_string").orEmpty(), s("new_string").orEmpty()))
            "MultiEdit" -> ToolView("Update", file, diff = (o?.get("edits") as? JsonArray).orEmpty().flatMap { e ->
                val eo = e as? JsonObject
                lineDiff(eo?.str("old_string").orEmpty(), eo?.str("new_string").orEmpty()) + DiffLine(DiffLine.Kind.Context, "⋯")
            }.dropLast(1))
            "Write" -> ToolView("Write", file, code = s("content")?.let(::clip))
            "Read" -> ToolView("Read", file)
            "NotebookEdit" -> ToolView("Notebook", file, code = s("new_source")?.let(::clip))
            "Grep" -> ToolView("Search", listOfNotNull(s("pattern"), s("path") ?: s("glob")).joinToString("  in  "))
            "Glob" -> ToolView("Find", listOfNotNull(s("pattern"), s("path")).joinToString("  in  "))
            "WebFetch" -> ToolView("Fetch", s("url"))
            "WebSearch" -> ToolView("Web search", s("query"))
            "Task", "Agent" -> ToolView("Agent", listOfNotNull(s("subagent_type"), s("description")).joinToString(": "), code = s("prompt")?.let(::clip))
            "TodoWrite" -> ToolView("Todos", null)
            else -> ToolView(name, file ?: s("command") ?: s("url") ?: s("query"), code = if (o == null) input?.asText()?.let(::clip) else null)
        }
    }

    /**
     * A small line diff (longest common subsequence) for Edit inputs. Inputs are bounded by the server's
     * truncation, but the table is capped anyway so a large edit cannot blow up memory.
     */
    fun lineDiff(old: String, new: String): List<DiffLine> {
        val a = old.lines()
        val b = new.lines()
        if (a.size * b.size > 250_000) return a.map { DiffLine(DiffLine.Kind.Removed, it) } + b.map { DiffLine(DiffLine.Kind.Added, it) }
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = ArrayList<DiffLine>()
        var i = 0; var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { out += DiffLine(DiffLine.Kind.Context, a[i]); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> out += DiffLine(DiffLine.Kind.Removed, a[i++])
                else -> out += DiffLine(DiffLine.Kind.Added, b[j++])
            }
        }
        while (i < a.size) out += DiffLine(DiffLine.Kind.Removed, a[i++])
        while (j < b.size) out += DiffLine(DiffLine.Kind.Added, b[j++])
        return out
    }

    private fun clip(text: String) = if (text.length > 4000) text.take(4000) + "\n⋯" else text
}

/** A slash command offered in the prompt box. They are sent as prompt text; the provider decides what it does with it. */
data class SlashCommand(val name: String, val description: String)

object SlashCommands {
    val CLAUDE = listOf(
        SlashCommand("/compact", "Summarise the conversation to free context"),
        SlashCommand("/clear", "Start the conversation over"),
        SlashCommand("/context", "Show context window usage"),
        SlashCommand("/cost", "Show token usage and cost"),
        SlashCommand("/review", "Review the current changes"),
        SlashCommand("/security-review", "Security review of pending changes"),
        SlashCommand("/init", "Write a CLAUDE.md for this project"),
        SlashCommand("/memory", "Show memory files"),
        SlashCommand("/todos", "List the current todos"),
        SlashCommand("/model", "Show or change the model"),
        SlashCommand("/status", "Show session status"),
        SlashCommand("/help", "List commands"),
    )
    val CODEX = listOf(
        SlashCommand("/compact", "Summarise the conversation to free context"),
        SlashCommand("/review", "Review the current changes"),
        SlashCommand("/diff", "Show the git diff"),
        SlashCommand("/init", "Write an AGENTS.md for this project"),
        SlashCommand("/status", "Show session status"),
        SlashCommand("/model", "Show or change the model"),
    )

    /**
     * The commands for a session whose prompts reach it the way [drive] says (agentd's `drive`; null on
     * even-terminal). pi, opencode and Hermes get none: agentd's protocol research (its docs/protocols) records no
     * commands for prompts sent the way agentd sends them, and a command the agent does not run would reach the model
     * as plain text. Codex's commands exist only in its TUI: they are offered when the text is typed into one
     * (tmux, agentd's pty relay) or on even-terminal, not when agentd hosts the thread through `codex app-server` (or resumes an ended
     * one there), which passes `/compact` to the model as text.
     */
    fun forProvider(provider: String?, drive: String?) = when (Providers.canonical(provider)) {
        Providers.CLAUDE -> CLAUDE
        Providers.CODEX -> if (drive == null || drive == Drive.TMUX || drive == Drive.PTY) CODEX else emptyList()
        else -> emptyList()
    }

    fun matching(provider: String?, drive: String?, typed: String): List<SlashCommand> =
        if (!typed.startsWith("/") || typed.contains(' ') || typed.contains('\n')) emptyList()
        else forProvider(provider, drive).filter { it.name.startsWith(typed) }
}
