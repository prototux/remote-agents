package net.prototux.remoteagents.api

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClientTest {
    private val fake = FakeEvenTerminal()
    private val client = EvenTerminalClient(fake.endpoint())

    @After fun tearDown() = fake.close()

    private inline fun <reified E : Throwable> expect(block: () -> Unit): E {
        try { block() } catch (e: Throwable) { if (e is E) return e; throw AssertionError("expected ${E::class.simpleName}, got $e", e) }
        fail("expected ${E::class.simpleName}")
        throw IllegalStateException()
    }

    @Test fun `info and token only in the header`() = runBlocking {
        val info = client.info()
        assertEquals("0.10.4", info.version)
        assertEquals("max", info.account?.subscriptionType)
        val recorded = fake.server.takeRequest()
        assertEquals("Bearer ${fake.token}", recorded.headers["Authorization"])
        assertTrue(!recorded.url.toString().contains(fake.token))
    }

    @Test fun `new session, then list and status`() = runBlocking {
        val accepted = client.prompt("hello", cwd = "/home/me/p")
        assertEquals("s1", accepted.sessionId)
        val sessions = client.sessions()
        assertEquals(listOf("s1"), sessions.map { it.id })
        assertEquals("awaiting", sessions.single().status)
        assertEquals("awaiting", client.status("s1").state)
        val page = client.messages("s1", after = 2)
        assertTrue(page.messages.all { (it.id ?: 0) > 2 })
        assertEquals("awaiting", page.state)
    }

    @Test fun `error shapes`() = runBlocking {
        val bad = expect<ApiException> { runBlocking { client.prompt("x", sessionId = "nope") } }
        assertEquals(404, bad.httpStatus)
        assertEquals("Session not found", bad.message)
        val missing = expect<ApiException> { runBlocking { client.interrupt("") .let { } ; client.permissionResponse("s404", "allow") } }
        assertTrue(missing.httpStatus in setOf(400, 404))
        client.prompt("go")
        val notOffered = expect<ApiException> { runBlocking { client.permissionResponse("s1", "allowAlways") } }
        assertEquals(400, notOffered.httpStatus)
        val auth = expect<AuthFailedException> { runBlocking { EvenTerminalClient(Endpoint(false, fake.server.hostName, fake.server.port, "wrong")).info() } }
        assertEquals("auth_failed", auth.code)
        assertEquals(Unit, Unit)
    }

    @Test fun `500 is an api exception`() {
        val e = ApiException.from(500, """{"error":"internal"}""")
        assertEquals("internal", e.message)
        assertEquals(500, e.httpStatus)
        val coded = ApiException.from(500, """{"error":{"code":"info_failed","message":"boom"}}""")
        assertEquals("info_failed", coded.code)
        assertEquals("boom", coded.message)
        assertEquals("HTTP 502", ApiException.from(502, "").message)
    }

    @Test fun `question answer is a json string keyed by question`() = runBlocking {
        client.prompt("go")
        client.permissionResponse("s1", "allow")
        client.questionResponse("s1", mapOf("Which fix?" to "Patch"))
        val answer = fake.buffer("s1").single { it.str("type") == "question_answer" }
        assertEquals("""{"Which fix?":"Patch"}""", answer["answers"].toString())
        assertTrue(fake.tokenInUrl.isEmpty())
    }

    @Test fun `refuses another origin`() {
        val e = runCatching {
            client.http.newCall(okhttp3.Request.Builder().url("http://example.org/api/info").build()).execute()
        }.exceptionOrNull()
        assertTrue(e?.message?.contains("another origin") == true)
    }
}
