package net.prototux.remoteagents.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** agentd's session extensions, and that even-terminal's answers (no such fields) keep the old behaviour. */
class ReachTest {
    private val fake = FakeEvenTerminal()
    private val client = EvenTerminalClient(fake.endpoint())

    @After fun tearDown() = fake.close()

    private fun sessions(fixture: String) = ApiJson.decodeFromString<SessionsResponse>(Fixtures.text(fixture)).sessions

    @Test fun `agentd session fields are parsed`() {
        val s = sessions("sessions_agentd.json")
        assertEquals(listOf("terminal", "terminal", "hosted", "ended", "unknown"), s.map { it.liveness })
        assertEquals(listOf("tmux", "none", "hosted", "hosted", "none"), s.map { it.drive })
        assertEquals(listOf(true, false, true, false, false), s.map { it.canApprove })
        assertEquals(
            listOf("in terminal · tmux", "in terminal · watch only", "hosted", "ended", null),
            s.map { Reach.of(it).label },
        )
    }

    @Test fun `even-terminal sessions have no reach and need a go-ahead`() {
        val s = sessions("sessions_even_terminal.json")
        s.forEach {
            val r = Reach.of(it)
            assertNull(it.liveness); assertNull(it.drive); assertNull(it.canApprove)
            assertFalse(r.isAgentd); assertFalse(r.canFork); assertFalse(r.watchOnly)
            assertNull(r.label); assertNull(r.banner)
            assertTrue(r.resumeWarning!!.contains("new process"))
        }
        assertNull(s[1].status)
    }

    @Test fun `banners, watch only and resume warnings`() {
        val (tmux, none, hosted, ended, unknown) = sessions("sessions_agentd.json").map(Reach::of)
        assertEquals("Running in a terminal: messages are typed into it as yours", tmux.banner)
        assertEquals(tmux.banner, Reach(Liveness.TERMINAL, Drive.PTY).banner)
        assertEquals("in terminal · pty", Reach(Liveness.TERMINAL, Drive.PTY).label)
        assertEquals("Running in a terminal: watch only", none.banner)
        assertEquals("Running in a terminal: messages are delivered when the turn ends", Reach(Liveness.TERMINAL, Drive.STOPHOOK).banner)
        assertNotNull(Reach(Liveness.TERMINAL, Drive.INBOX).banner)
        assertTrue(Reach(Liveness.TERMINAL, null).watchOnly)
        assertFalse(tmux.watchOnly); assertTrue(none.watchOnly); assertFalse(hosted.watchOnly)
        assertNull(hosted.banner); assertNull(ended.banner)
        // Only sessions without a process of their own are resumed in a new one.
        assertNull(tmux.resumeWarning); assertNull(none.resumeWarning); assertNull(hosted.resumeWarning)
        assertNotNull(ended.resumeWarning); assertTrue(unknown.resumeWarning!!.contains("forks"))
        assertTrue(listOf(tmux, none, hosted, ended, unknown).all { it.canFork })
    }

    @Test fun `continue in a terminal with agentd resume`() {
        val id = "002872a2-cef1-4d60-bc98-765ba5ca725f"
        assertEquals("agentd resume 002872a2", Reach(Liveness.HOSTED, Drive.HOSTED).resumeCommand("claude", id))
        assertEquals("agentd resume 002872a2", Reach(Liveness.ENDED, Drive.NONE).resumeCommand("pi", id))
        assertNull(Reach(Liveness.TERMINAL, Drive.PTY).resumeCommand("claude", id)) // already there
        assertNull(Reach(Liveness.HOSTED, Drive.HOSTED).resumeCommand("codex", id))
        assertNull(Reach().resumeCommand("claude", id)) // even-terminal
    }

    @Test fun `409 session_live is typed, other 409s are not`() {
        val e = ApiException.from(409, Fixtures.text("session_live_409.json"))
        assertTrue(e is SessionLiveException)
        assertEquals("session_live", e.code)
        assertEquals("Session is running in a terminal and cannot be driven", e.message)
        val other = ApiException.from(409, """{"error":"No such request","code":"request_not_pending"}""")
        assertFalse(other is SessionLiveException)
        assertEquals("request_not_pending", other.code)
        assertNull(ApiException.from(400, """{"error":"Missing 'text' field"}""").code)
    }

    @Test fun `status and sessions carry reach from agentd`() = runBlocking {
        fake.addSession("term1", Reach(Liveness.TERMINAL, Drive.TMUX, true))
        fake.addSession("plain1")
        val listed = client.sessions().associateBy { it.id }
        assertEquals(Reach(Liveness.TERMINAL, Drive.TMUX, true), Reach.of(listed.getValue("term1")))
        assertEquals(Reach(), Reach.of(listed.getValue("plain1")))
        assertEquals(Reach(Liveness.TERMINAL, Drive.TMUX, true), Reach.of(client.status("term1")))
        assertEquals(Reach(), Reach.of(client.status("plain1")))
    }

    @Test fun `prompt to a watch-only terminal session is refused, fork makes a new one`() = runBlocking {
        fake.addSession("term1", Reach(Liveness.TERMINAL, Drive.NONE, false))
        try { client.prompt("hi", sessionId = "term1"); fail("expected 409") } catch (e: SessionLiveException) {
            assertEquals(409, e.httpStatus)
        }
        val forked = client.prompt("hi", sessionId = "term1", fork = true)
        assertTrue(forked.sessionId != "term1")
        assertEquals(Liveness.HOSTED, client.status(forked.sessionId).liveness)
        assertTrue(fake.buffer("term1").isEmpty())
        assertEquals(JsonPrimitive(true), fake.prompts.last()["fork"])
    }

    @Test fun `fork and force are sent only when asked for`() = runBlocking {
        fake.addSession("term1", Reach(Liveness.TERMINAL, Drive.NONE, false))
        client.prompt("go", sessionId = "term1", force = true)
        assertEquals(JsonPrimitive(true), fake.prompts.last()["force"])
        assertNull(fake.prompts.last()["fork"])
        client.prompt("new")
        assertEquals(setOf("text"), fake.prompts.last().keys)
    }
}
