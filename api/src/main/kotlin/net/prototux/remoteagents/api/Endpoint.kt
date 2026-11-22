package net.prototux.remoteagents.api

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * One even-terminal server as the client sees it. [toString] never shows the token.
 *
 * @param pins SPKI pins (`sha256/...`) for an HTTPS reverse proxy; when set they replace CA trust.
 * @param allowPublic allow plain HTTP to a public address (owner override). HTTPS with pins may always
 *   use a public address: that is the "public server" path the owner chose.
 */
class Endpoint(
    val https: Boolean,
    val host: String,
    val port: Int,
    val token: String,
    val pins: Set<String> = emptySet(),
    val allowPublic: Boolean = false,
) {
    val baseUrl: HttpUrl = HttpUrl.Builder().scheme(if (https) "https" else "http").host(host).port(port).addPathSegment("api").build()

    /** Whether connections to public addresses are permitted at all. */
    val publicAllowed: Boolean get() = allowPublic || (https && pins.isNotEmpty())

    override fun toString() = "Endpoint(${baseUrl.scheme}://$host:$port, pins=${pins.size}, allowPublic=$allowPublic)"

    /**
     * The OkHttp client for this server: bearer header, DNS that refuses public addresses unless allowed,
     * pinned trust for HTTPS, and a guard that refuses any request to another origin (so the token can
     * only ever go to the paired host).
     */
    fun buildClient(base: OkHttpClient = OkHttpClient()): OkHttpClient {
        val b = base.newBuilder()
            .dns(GuardedDns(publicAllowed))
            .addInterceptor(OriginGuard(baseUrl))
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build()) }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
        if (https && pins.isNotEmpty()) {
            val tm = PinnedTrustManager(pins)
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), null) }
            // The pinned key is the server's identity, so the host name in the certificate does not matter
            // (a self-signed proxy certificate for an IP address works).
            b.sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true }
        }
        return b.build()
    }
}

private class OriginGuard(private val base: HttpUrl) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) {
            throw java.io.IOException("Refused request to another origin than the paired server")
        }
        return chain.proceed(chain.request())
    }
}
