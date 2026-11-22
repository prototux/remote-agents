package net.prototux.remoteagents.api

import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Read-only checks against a real even-terminal, skipped unless ET_TOKEN is set
 * (ET_HOST and ET_PORT default to 127.0.0.1:3456). Never sends a prompt or a decision.
 * The agents check also needs agentd and ET_SESSION, a session with subagents.
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

    @Test fun `agentd lists agents, pages their transcripts and streams their state`() = runBlocking {
        val session = System.getenv("ET_SESSION")
        assumeTrue(token != null && session != null)
        val c = client()
        assumeTrue(c.info().isAgentd)
        val list = c.agents(session!!)
        assertTrue("no agents in $session", list.agents.isNotEmpty())
        val state = AgentsState.of(list)
        println("agents: ${list.agents.size} (${state.runningCount} running), runs: ${list.workflows.map { "${it.title} ${it.status} ${it.agentsDone}/${it.agentsTotal}" }}")
        println("status line: ${state.statusLine}")
        for (a in (list.agents.filter { it.running } + list.agents.filterNot { it.running }).take(3)) {
            val page = c.messages(session, agentId = a.id)
            assertEquals(a.id, page.agentId)
            val t = Transcript().reduceAll(page.messages)
            println("agent ${a.id} ${a.state} '${a.title}': ${page.messages.size} messages, state ${page.state}, ${t.items.size} rows")
            assertTrue(page.messages.none { it is AgentMessage.AgentUpdate || it is AgentMessage.WorkflowUpdate })
        }
        // agents=1: the replay, then the full state (id-less), then changes. Waits until every listed agent came.
        val seen = mutableListOf<AgentMessage>()
        val start = System.currentTimeMillis()
        withTimeoutOrNull(20_000) {
            c.events(session, agents = { true }).takeWhile { ev ->
                if (ev is StreamEvent.Message) seen += ev.message else println("stream event after ${System.currentTimeMillis() - start} ms: $ev")
                !seen.filterIsInstance<AgentMessage.AgentUpdate>().map { it.agent.id }.containsAll(list.agents.map { it.id })
            }.collect {}
        }
        val updates = seen.filter { it is AgentMessage.AgentUpdate || it is AgentMessage.WorkflowUpdate }
        println("stream after ${System.currentTimeMillis() - start} ms: ${seen.size - updates.size} messages (last id ${seen.lastOrNull { it.id != null }?.id}), ${updates.size} agent/workflow updates")
        assertTrue(updates.all { it.id == null })
        val streamed = updates.fold(AgentsState()) { s, m -> s.reduce(m) }
        assertEquals(list.agents.map { it.id }.toSet(), streamed.agents.keys)
        assertEquals(list.workflows.map { it.runId }.toSet(), streamed.workflows.keys)
    }
}
