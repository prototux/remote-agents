package net.prototux.remoteagents.api

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** `sha256/<base64>` of a certificate's SubjectPublicKeyInfo, the same format as OkHttp's CertificatePinner. */
fun X509Certificate.spkiPin(): String =
    "sha256/" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(publicKey.encoded))

data class PeerCertificate(val subject: String, val issuer: String, val pin: String, val notAfter: java.util.Date)

/**
 * Trusts a chain only when one of its certificates has a pinned public key, whatever CA signed it.
 * The pin is the server's identity (taken at pairing time and confirmed by the owner), so this also
 * works with a self-signed certificate on a reverse proxy, and survives a CA change but not a key change.
 */
class PinnedTrustManager(private val pins: Set<String>) : X509TrustManager {
    init { require(pins.isNotEmpty()) { "PinnedTrustManager needs at least one pin" } }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
        if (chain.none { it.spkiPin() in pins }) {
            throw PinMismatchException(chain.firstOrNull()?.spkiPin())
        }
        chain.first().checkValidity()
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) = throw CertificateException("client auth unsupported")
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** The server presented a key that is not pinned: either it rotated its key or someone is in the path. */
class PinMismatchException(val presentedPin: String?) :
    CertificateException("Server certificate does not match the pinned key (presented $presentedPin)")

object TlsProbe {
    /**
     * Reads the certificate chain of [host]:[port] without trusting or sending anything (no HTTP, no token),
     * so the owner can compare the fingerprint before pinning it.
     */
    fun fetchChain(host: String, port: Int, timeoutMs: Int = 10_000): List<PeerCertificate> {
        // Accepting any chain is safe here only because nothing is sent over this socket: it exists to show
        // the owner the key, and trust comes from their confirming it (then PinnedTrustManager enforces it).
        @Suppress("CustomX509TrustManager")
        val acceptAll = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(acceptAll), null) }
        val socket = java.net.Socket()
        socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = timeoutMs
        (context.socketFactory.createSocket(socket, host, port, true) as SSLSocket).use { ssl ->
            ssl.startHandshake()
            return ssl.session.peerCertificates.filterIsInstance<X509Certificate>().map {
                PeerCertificate(it.subjectX500Principal.name, it.issuerX500Principal.name, it.spkiPin(), it.notAfter)
            }
        }
    }
}

/** Finds a [PinMismatchException] in a failure, including causes and suppressed attempts (OkHttp tries every address). */
fun Throwable.findPinMismatch(): PinMismatchException? {
    val seen = HashSet<Throwable>()
    fun walk(t: Throwable?): PinMismatchException? {
        if (t == null || !seen.add(t)) return null
        if (t is PinMismatchException) return t
        return walk(t.cause) ?: t.suppressed.firstNotNullOfOrNull { walk(it) }
    }
    return walk(this)
}
