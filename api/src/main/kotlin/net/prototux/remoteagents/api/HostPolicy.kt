package net.prototux.remoteagents.api

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Where an address sits, for the "VPN only unless TLS or overridden" rule (spec §7). */
enum class AddressClass { Loopback, Private, Vpn, Public }

object HostPolicy {
    fun classify(address: InetAddress): AddressClass {
        if (address.isLoopbackAddress) return AddressClass.Loopback
        val b = address.address.map { it.toInt() and 0xFF }
        return when (address) {
            is Inet4Address -> when {
                b[0] == 10 -> AddressClass.Private
                b[0] == 172 && b[1] in 16..31 -> AddressClass.Private
                b[0] == 192 && b[1] == 168 -> AddressClass.Private
                b[0] == 169 && b[1] == 254 -> AddressClass.Private
                // 100.64.0.0/10: carrier-grade NAT, which Tailscale uses for tailnet addresses.
                b[0] == 100 && b[1] in 64..127 -> AddressClass.Vpn
                else -> AddressClass.Public
            }
            is Inet6Address -> when {
                // fd00::/8 unique local (Tailscale uses fd7a:115c:a1e0::/48); fe80::/10 link local.
                b[0] == 0xFD || b[0] == 0xFC -> AddressClass.Vpn
                b[0] == 0xFE && (b[1] and 0xC0) == 0x80 -> AddressClass.Private
                else -> AddressClass.Public
            }
            else -> AddressClass.Public
        }
    }

    /** Whether plain HTTP to [address] is acceptable without an explicit per-server override. */
    fun allowsCleartext(address: InetAddress) = classify(address) != AddressClass.Public

    /**
     * Classifies a literal IP [host] without DNS; null for host names (they are checked after resolution,
     * at connect time, by [GuardedDns]).
     */
    fun classifyLiteral(host: String): AddressClass? {
        val literal = host.removePrefix("[").removeSuffix("]")
        val looksLikeIp = literal.contains(':') || literal.all { it.isDigit() || it == '.' }
        if (!looksLikeIp) return null
        return runCatching { classify(InetAddress.getByName(literal)) }.getOrNull()
    }
}

/** Refuses to connect to a public address when the server is not allowed one. */
class PublicAddressRefused(host: String, address: InetAddress) :
    java.net.UnknownHostException("$host resolves to public address ${address.hostAddress}; this server is VPN-only")

/**
 * DNS that only returns addresses the server may use: with [allowPublic] false, public addresses are
 * dropped, and if nothing is left the connection fails. This is what guarantees no request leaves
 * through a non-VPN address, whatever the host name resolves to.
 */
class GuardedDns(private val allowPublic: Boolean, private val delegate: okhttp3.Dns = okhttp3.Dns.SYSTEM) : okhttp3.Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = delegate.lookup(hostname)
        if (allowPublic) return all
        val ok = all.filter { HostPolicy.allowsCleartext(it) }
        if (ok.isEmpty()) throw PublicAddressRefused(hostname, all.first())
        return ok
    }
}
