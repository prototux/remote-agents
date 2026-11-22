package net.prototux.remoteagents.data

import net.prototux.remoteagents.api.EvenTerminalClient
import okhttp3.OkHttpClient

/**
 * One [EvenTerminalClient] per server, rebuilt when the server's settings change. They share one
 * connection pool and dispatcher through [base].
 */
class ClientRegistry(private val servers: ServerStore) {
    private val base = OkHttpClient()
    private val cache = HashMap<String, Pair<ServerConfig, EvenTerminalClient>>()

    @Synchronized fun get(serverId: String): EvenTerminalClient? {
        val server = servers.get(serverId) ?: return null
        cache[serverId]?.let { (cfg, client) -> if (cfg == server) return client }
        val endpoint = servers.endpoint(server) ?: return null
        return EvenTerminalClient(endpoint, base).also { cache[serverId] = server to it }
    }

    /** A throwaway client for an unsaved server (pairing, connection test). */
    fun forEndpoint(endpoint: net.prototux.remoteagents.api.Endpoint) = EvenTerminalClient(endpoint, base)
}
