package net.prototux.remoteagents.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import net.prototux.remoteagents.api.ApiJson
import net.prototux.remoteagents.api.Endpoint
import net.prototux.remoteagents.api.Providers
import java.io.File
import java.util.UUID

/** A paired server. The token is only ever held encrypted here; see [ServerStore.endpoint]. */
@Serializable
data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val https: Boolean = false,
    val host: String,
    val port: Int = 3456,
    val defaultProvider: String = Providers.CLAUDE,
    /** SPKI pins of the HTTPS reverse proxy, confirmed by the owner at pairing. */
    val pins: List<String> = emptyList(),
    /** Owner override: plain HTTP to a public address. */
    val allowPublic: Boolean = false,
    val tokenEnc: String = "",
    /** Working directories used for new sessions, most recent first. */
    val recentCwds: List<String> = emptyList(),
    /** Set when the server refused the token (401): no more retries until re-paired. */
    val needsRepair: Boolean = false,
) {
    val address: String get() = "${if (https) "https" else "http"}://$host:$port"
}

/**
 * Servers as a JSON file in no-backup storage (tokens encrypted with [TokenCipher]). Small and rarely
 * written, so the whole list is rewritten atomically on each change.
 */
class ServerStore(dir: File, private val cipher: TokenCipher) {
    private val file = File(dir, "servers.json")
    private val serializer = ListSerializer(ServerConfig.serializer())
    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()

    fun get(id: String): ServerConfig? = _servers.value.firstOrNull { it.id == id }

    /** Saves [server]; a non-null [token] replaces the stored one (and clears "re-pair needed"). */
    @Synchronized fun save(server: ServerConfig, token: String? = null) {
        val s = if (token != null) server.copy(tokenEnc = cipher.encrypt(token), needsRepair = false) else server
        write(_servers.value.filterNot { it.id == s.id } + s)
    }

    @Synchronized fun update(id: String, change: (ServerConfig) -> ServerConfig) {
        write(_servers.value.map { if (it.id == id) change(it) else it })
    }

    @Synchronized fun delete(id: String) = write(_servers.value.filterNot { it.id == id })

    fun token(server: ServerConfig): String? = server.tokenEnc.takeIf { it.isNotEmpty() }?.let(cipher::decrypt)

    fun endpoint(server: ServerConfig): Endpoint? {
        val token = token(server) ?: return null
        return Endpoint(server.https, server.host, server.port, token, server.pins.toSet(), server.allowPublic)
    }

    fun rememberCwd(id: String, cwd: String) = update(id) { s ->
        s.copy(recentCwds = (listOf(cwd) + s.recentCwds.filterNot { it == cwd }).take(12))
    }

    private fun load(): List<ServerConfig> =
        runCatching { ApiJson.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    private fun write(list: List<ServerConfig>) {
        val tmp = File(file.parentFile, "servers.json.tmp")
        tmp.writeText(ApiJson.encodeToString(serializer, list))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        _servers.value = list
    }
}
