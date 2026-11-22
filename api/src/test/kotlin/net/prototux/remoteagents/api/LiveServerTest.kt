package net.prototux.remoteagents.api

import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Read-only checks against a real even-terminal, skipped unless ET_TOKEN is set
 * (ET_HOST and ET_PORT default to 127.0.0.1:3456). Never sends a prompt or a decision.
 */
class LiveServerTest {
    private val token = System.getenv("ET_TOKEN")
    private fun client() = EvenTerminalClient(Endpoint(false, System.getenv("ET_HOST") ?: "127.0.0.1", System.getenv("ET_PORT")?.toInt() ?: 3456, token!!))

    @Test fun `info, sessions, messages and the event stream parse`() = runBlocking {
        assumeTrue(token != null)
        val c = client()
        assertNotNull(c.info().version)
        val sessions = c.sessions(limit = 5)
        assumeTrue(sessions.isNotEmpty())
        val id = sessions.first().id
        val page = c.messages(id, provider = sessions.first().provider)
        assertTrue(page.messages.none { it.type.isEmpty() })
        c.history(id, provider = sessions.first().provider)
        // The stream opens (":ok"), replays the buffer and stays open; three seconds are enough to see it live.
        val events = withTimeoutOrNull(3_000) {
            val out = mutableListOf<StreamEvent>()
            c.events(id, sessions.first().provider).takeWhile { out += it; it !is StreamEvent.Disconnected }.toList()
            out
        }
        assertTrue("stream did not stay open", events == null)
        assertTrue(runCatching { EvenTerminalClient(Endpoint(false, "127.0.0.1", 3456, "wrong")).info() }.exceptionOrNull() is AuthFailedException)
    }
}
