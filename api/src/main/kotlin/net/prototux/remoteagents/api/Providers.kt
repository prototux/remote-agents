package net.prototux.remoteagents.api

object Providers {
    const val CLAUDE = "claude"
    const val CODEX = "codex"
    const val PI = "pi"
    const val OPENCODE = "opencode"
    const val HERMES = "hermes"

    /** The providers agentd has adapters for, in the order it lists them. */
    val KNOWN = listOf(CLAUDE, PI, CODEX, OPENCODE, HERMES)

    /** The values a server's provider guard may accept: even-terminal takes claude, claude-sync and codex, agentd all of these. */
    val ALL = setOf(CLAUDE, "claude-sync") + KNOWN

    /** One name per agent: claude-sync is Claude, and so is a session without a provider (even-terminal's older ones). */
    fun canonical(provider: String?) = when (provider) {
        null, "", "claude-sync" -> CLAUDE
        else -> provider
    }

    fun label(provider: String?) = when (provider) {
        null, "" -> "Agent"
        CLAUDE, "claude-sync" -> "Claude Code"
        CODEX -> "Codex"
        PI -> "pi"
        OPENCODE -> "opencode"
        HERMES -> "Hermes Agent"
        else -> provider
    }

    /** Shown next to a provider whose agentd adapter was written from documentation and never run against the agent. */
    const val EXPERIMENTAL_TAG = "experimental · untested"
}

/** A provider the new-session sheet offers. */
data class ProviderOption(val name: String, val label: String, val experimental: Boolean = false)

object ProviderPicker {
    /** What a server that does not list its providers (even-terminal) has always been offered. */
    val FALLBACK = listOf(Providers.CLAUDE, Providers.CODEX).map { ProviderOption(it, Providers.label(it)) }

    /**
     * The providers a new session can start with: the installed ones that can host a session when the server lists
     * them (agentd's `server.providers`), else [FALLBACK]. An empty list means the server runs none.
     */
    fun options(info: ServerInfo?): List<ProviderOption> {
        val listed = info?.providers ?: return FALLBACK
        return listed.filter { it.installed && it.canHost && it.name.isNotBlank() }.distinctBy { it.name }.map {
            // The app's labels for the names it knows, so badges and the picker agree; the server's for the others.
            val label = if (it.name in Providers.KNOWN) Providers.label(it.name) else it.label?.ifBlank { null } ?: it.name
            ProviderOption(it.name, label, it.experimental)
        }
    }

    /** The option to select: [preferred] (the server's default, or the user's last pick) when offered, else the first. */
    fun select(options: List<ProviderOption>, preferred: String?): String? =
        options.firstOrNull { it.name == Providers.canonical(preferred) }?.name ?: options.firstOrNull()?.name

    /**
     * The session-list filter chips: Claude Code and Codex as always, then any other provider the server lists or the
     * sessions carry, in agentd's order.
     */
    fun filters(info: ServerInfo?, sessions: List<SessionSummary>): List<String> {
        val seen = (info?.providers.orEmpty().filter { it.installed }.map { it.name } + sessions.map { Providers.canonical(it.provider) }).toSet()
        val known = Providers.KNOWN.filter { it == Providers.CLAUDE || it == Providers.CODEX || it in seen }
        return known + (seen - known.toSet()).filter { it.isNotBlank() }.sorted()
    }
}
