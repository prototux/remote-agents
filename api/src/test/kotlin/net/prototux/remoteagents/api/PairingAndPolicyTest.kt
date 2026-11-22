package net.prototux.remoteagents.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class PairingAndPolicyTest {
    @Test fun `parses the server's pairing url`() {
        val p = PairingInfo.parse("http://100.101.102.103:3456?token=0123456789abcdef0123456789abcdef&defaultProvider=codex&name=Workstation")!!
        assertEquals(PairingInfo(false, "100.101.102.103", 3456, "0123456789abcdef0123456789abcdef", "codex", "Workstation"), p)
    }

    @Test fun `https and missing optional parameters`() {
        val p = PairingInfo.parse("  https://agents.example.org?token=abc  ")!!
        assertTrue(p.https)
        assertEquals(443, p.port)
        assertNull(p.provider)
        assertNull(p.name)
    }

    @Test fun `rejects urls without a token or of another scheme`() {
        assertNull(PairingInfo.parse("http://10.0.0.2:3456"))
        assertNull(PairingInfo.parse("ftp://10.0.0.2?token=x"))
        assertNull(PairingInfo.parse("hello"))
        assertNull(PairingInfo.parse("http://10.0.0.2:3456?token=x&defaultProvider=evil")!!.provider)
    }

    private fun c(ip: String) = HostPolicy.classify(InetAddress.getByName(ip))

    @Test fun `classifies addresses`() {
        assertEquals(AddressClass.Loopback, c("127.0.0.1"))
        assertEquals(AddressClass.Private, c("10.1.2.3"))
        assertEquals(AddressClass.Private, c("172.16.0.1"))
        assertEquals(AddressClass.Public, c("172.32.0.1"))
        assertEquals(AddressClass.Private, c("192.168.1.10"))
        assertEquals(AddressClass.Vpn, c("100.64.0.1"))
        assertEquals(AddressClass.Vpn, c("100.127.255.254"))
        assertEquals(AddressClass.Public, c("100.128.0.1"))
        assertEquals(AddressClass.Vpn, c("fd7a:115c:a1e0::1"))
        assertEquals(AddressClass.Public, c("8.8.8.8"))
        assertEquals(AddressClass.Public, c("2001:4860:4860::8888"))
        assertNull(HostPolicy.classifyLiteral("workstation.tail1234.ts.net"))
        assertEquals(AddressClass.Vpn, HostPolicy.classifyLiteral("[fd7a:115c:a1e0::1]"))
    }

    @Test fun `guarded dns drops public addresses`() {
        val fake = okhttp3.Dns { listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("100.70.0.1")) }
        assertEquals(listOf(InetAddress.getByName("100.70.0.1")), GuardedDns(false, fake).lookup("host"))
        assertEquals(2, GuardedDns(true, fake).lookup("host").size)
        val onlyPublic = okhttp3.Dns { listOf(InetAddress.getByName("8.8.8.8")) }
        val refused = runCatching { GuardedDns(false, onlyPublic).lookup("host") }.exceptionOrNull()
        assertTrue(refused is PublicAddressRefused)
    }

    @Test fun `endpoint never prints its token`() {
        val e = Endpoint(false, "10.0.0.2", 3456, "secret-token")
        assertFalse(e.toString().contains("secret-token"))
        assertFalse(e.baseUrl.toString().contains("secret-token"))
        assertFalse(e.publicAllowed)
        assertTrue(Endpoint(true, "example.org", 443, "t", pins = setOf("sha256/x")).publicAllowed)
        assertFalse(Endpoint(true, "example.org", 443, "t").publicAllowed)
    }
}
