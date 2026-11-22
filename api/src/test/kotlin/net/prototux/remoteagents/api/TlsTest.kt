package net.prototux.remoteagents.api

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsTest {
    // A self-signed certificate, as a home reverse proxy might use: no CA would trust it, the pin does.
    private val cert = HeldCertificate.Builder().commonName("proxy").addSubjectAlternativeName("localhost").build()
    private val server = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(200).body("""{"version":"0.10.4"}""").build()
        }
        start()
    }

    @After fun tearDown() = server.close()

    @Test fun `probe reads the pin without trusting`() {
        val chain = TlsProbe.fetchChain(server.hostName, server.port)
        assertEquals(cert.certificate.spkiPin(), chain.first().pin)
    }

    @Test fun `pinned key is trusted, another key is refused`() = runBlocking {
        val ok = EvenTerminalClient(Endpoint(true, server.hostName, server.port, "t", pins = setOf(cert.certificate.spkiPin())))
        assertEquals("0.10.4", ok.info().version)

        val other = HeldCertificate.Builder().build().certificate.spkiPin()
        val bad = EvenTerminalClient(Endpoint(true, server.hostName, server.port, "t", pins = setOf(other)))
        val e = runCatching { bad.info() }.exceptionOrNull()
        assertTrue("expected pin mismatch, got $e", e?.findPinMismatch() != null)
    }
}
