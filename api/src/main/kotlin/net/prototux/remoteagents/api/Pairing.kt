package net.prototux.remoteagents.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * What the even-terminal pairing URL (printed and shown as a QR code) carries:
 * `http://<ip>:<port>?token=<token>&defaultProvider=<claude|codex>&name=<name>`.
 *
 * An `https://` URL is accepted too, for a TLS reverse proxy in front of even-terminal.
 */
data class PairingInfo(
    val https: Boolean,
    val host: String,
    val port: Int,
    val token: String,
    val provider: String?,
    val name: String?,
) {
    companion object {
        /** Returns null when [text] is not a pairing URL (wrong scheme, no host, no token). */
        fun parse(text: String): PairingInfo? {
            val url = text.trim().toHttpUrlOrNull() ?: return null
            val token = url.queryParameter("token")?.takeIf { it.isNotBlank() } ?: return null
            return PairingInfo(
                https = url.isHttps,
                host = url.host,
                port = url.port,
                token = token,
                provider = url.queryParameter("defaultProvider")?.takeIf { it in Providers.ALL },
                name = url.queryParameter("name")?.takeIf { it.isNotBlank() },
            )
        }
    }
}

object Providers {
    const val CLAUDE = "claude"
    const val CODEX = "codex"
    /** The values the server's provider guard accepts. */
    val ALL = setOf(CLAUDE, "claude-sync", CODEX)

    fun label(provider: String?) = when (provider) {
        CODEX -> "Codex"
        null, "" -> "Agent"
        else -> "Claude Code"
    }
}
