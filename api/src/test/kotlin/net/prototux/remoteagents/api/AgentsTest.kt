package net.prototux.remoteagents.api

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Subagents and workflow runs (agentd S11). The fixtures are real agentd answers, captured from agentd
 * serving its own anonymised test data (server/testdata/claude/agents) on a scratch port.
 */
class AgentsTest {
    private val list: AgentsList = ApiJson.decodeFromString(Fixtures.text("agents_agentd.json"))

    @Test fun `the agents list groups runs and subagents`() {
        val s = AgentsState.of(list)
        assertEquals(5, s.agents.size)
        assertEquals(1, s.runningCount)
        // Running run first, its agents in start order.
        assertEquals(listOf("wf_fixture-live", "wf_fixture-done"), s.groups.map { it.run.runId })
        assertEquals(listOf("live review", "live build"), s.groups[0].agents.map { it.title })
        assertEquals(listOf("build step", "review step"), s.groups[1].agents.map { it.title })
        assertEquals(listOf("a0000000000000001"), s.standalone.map { it.id })
        val sub = s.standalone.single()
        assertEquals("general-purpose", sub.agentType)
        assertEquals("toolu_fixture000000000000001", sub.toolUseId)
        assertEquals(95840L, sub.tokens)
        assertEquals("workflow fixture-live · Review · 1/2 agents", s.statusLine)
        val done = s.groups[1].run
        assertEquals(WorkflowStatus.COMPLETED, done.status)
        assertEquals(listOf("Build", "Review"), done.phases.map { it.title })
        assertEquals(2025856L, done.elapsedMs(0))
    }

    @Test fun `a refresh read while the stream ran keeps what the stream updated meanwhile`() {
        fun update(json: String) = AgentMessage.parse(json)
        val before = AgentsState.of(list)
        val running = list.agents.first { it.running }
        // The stream says done (and the run completed) while /agents was in flight with the old state.
        val done = update("""{"type":"agent_update","sessionId":"s","agent":{"id":"${running.id}","kind":"workflow","runId":"${running.runId}","label":"x","state":"done","toolCount":1}}""")
        val completed = update("""{"type":"workflow_update","sessionId":"s","workflow":{"runId":"${running.runId}","status":"completed","agentsDone":2,"agentsTotal":2}}""")
        val now = before.reduce(done).reduce(completed)
        val touched = setOfNotNull(AgentsState.touchedKey(done), AgentsState.touchedKey(completed))
        val refreshed = now.refreshedWith(list, touched)
        assertEquals(AgentState.DONE, refreshed.agents.getValue(running.id).state)
        assertEquals(WorkflowStatus.COMPLETED, refreshed.workflows.getValue(running.runId!!).status)
        assertFalse(refreshed.anyRunning)
        // Untouched entries take the answer; with nothing touched it is the answer.
        assertEquals(before.agents - running.id, refreshed.agents - running.id)
        assertEquals(before, now.refreshedWith(list, emptySet()))
        assertNull(AgentsState.touchedKey(AgentMessage.parse("""{"type":"text_delta","text":"x"}""")))
    }

    @Test fun `elapsed runs to now while running and to the end after`() {
        val running = list.agents.first { it.running }
        val start = java.time.Instant.parse(running.startedAt).toEpochMilli()
        assertEquals(90_000L, running.elapsedMs(start + 90_000))
        val sub = list.agents.first { it.id == "a0000000000000001" }
        assertEquals(183_005L, sub.elapsedMs(Long.MAX_VALUE))
        assertNull(AgentSummary("x").elapsedMs(0))
    }

    @Test fun `updates replace state, and an agent may come before its run`() {
        fun update(json: String) = AgentMessage.parse(json)
        var s = AgentsState()
        s = s.reduce(update("""{"type":"agent_update","sessionId":"s","agent":{"id":"a1","kind":"workflow","runId":"wf1","label":"build","state":"running","toolCount":3}}"""))
        assertEquals("wf1", s.groups.single().run.runId)
        assertEquals("workflow wf1 · 0/1 agents", s.statusLine)
        s = s.reduce(update("""{"type":"workflow_update","sessionId":"s","workflow":{"runId":"wf1","name":"agentd-build","phases":[],"currentPhase":"Review","agentsDone":5,"agentsFailed":1,"agentsTotal":8,"status":"running"}}"""))
        assertEquals("workflow agentd-build · Review · 5/8 agents · 1 failed", s.statusLine)
        s = s.reduce(update("""{"type":"agent_update","sessionId":"s","agent":{"id":"a1","kind":"workflow","runId":"wf1","label":"build","state":"running","toolCount":4,"lastAction":"Bash: go test"}}"""))
        assertEquals(4, s.agents["a1"]!!.toolCount)
        assertEquals("Bash: go test", s.agents["a1"]!!.lastAction)
        s = s.reduce(update("""{"type":"agent_update","sessionId":"s","agent":{"id":"a2","kind":"subagent","label":"Look up hooks","state":"running"}}"""))
        assertEquals("workflow agentd-build · Review · 5/8 agents · 1 failed · subagent Look up hooks", s.statusLine)
        assertEquals(2, s.runningCount)
        s = s.reduce(update("""{"type":"workflow_update","sessionId":"s","workflow":{"runId":"wf1","name":"agentd-build","agentsDone":8,"agentsTotal":8,"status":"completed"}}"""))
            .reduce(update("""{"type":"agent_update","sessionId":"s","agent":{"id":"a1","runId":"wf1","state":"done"}}"""))
            .reduce(update("""{"type":"agent_update","sessionId":"s","agent":{"id":"a2","state":"done"}}"""))
        assertNull(s.statusLine)
        assertFalse(s.anyRunning)
        // Other messages leave it alone.
        assertEquals(s, s.reduce(update("""{"type":"text_delta","text":"x"}""")))
    }

    @Test fun `agent updates parse, never reach the transcript, and bad ones are unsupported`() {
        val m = AgentMessage.parse("""{"type":"agent_update","sessionId":"s","agent":{"id":"a1","state":"done","futureField":1}}""")
        assertTrue(m is AgentMessage.AgentUpdate)
        assertEquals(Transcript(), Transcript().reduce(m))
        val bad = AgentMessage.parse("""{"type":"agent_update","sessionId":"s","agent":{"state":"done"}}""")
        assertTrue(bad is AgentMessage.Unsupported)
        assertTrue(AgentMessage.parse("""{"type":"workflow_update","workflow":"nope"}""") is AgentMessage.Unsupported)
    }

    @Test fun `requests name the subagent that asks, and plain ones do not`() {
        val p = AgentMessage.parse("""{"type":"permission_request","toolName":"Bash","toolUseId":"t1","agentId":"a1","agentLabel":"Review the diff","options":[{"key":"allow","text":"Allow"}]}""")
            as AgentMessage.PermissionRequest
        assertEquals("a1", p.agentId)
        assertEquals("Review the diff", p.askedBy)
        val unlabeled = AgentMessage.parse("""{"type":"user_question","toolUseId":"q1","agentId":"a7","agentLabel":"","questions":[]}""") as AgentMessage.UserQuestion
        assertEquals("a7", unlabeled.askedBy)
        val plain = Fixtures.messages("full_turn.jsonl").filterIsInstance<AgentMessage.PermissionRequest>().single()
        assertNull(plain.agentId)
        assertNull(plain.askedBy)
    }

    @Test fun `only agentd is detected as agentd`() {
        assertTrue(ApiJson.decodeFromString<ServerInfo>(Fixtures.text("info_agentd.json")).isAgentd)
        assertTrue(ApiJson.decodeFromString<ServerInfo>("""{"extra":{"expose":"off","server":{"name":"agentd"}}}""").isAgentd)
        assertFalse(ApiJson.decodeFromString<ServerInfo>("""{"model":"opus","version":"0.10.4","provider":"claude","extra":{"expose":"off"}}""").isAgentd)
    }

    @Test fun `even-terminal answers 404 to the agents list`() = runBlocking {
        FakeEvenTerminal().use { fake ->
            val e = runCatching { EvenTerminalClient(fake.endpoint()).agents("s1") }.exceptionOrNull()
            assertEquals(404, (e as ApiException).httpStatus)
        }
    }

    /** Serves the captured agentd answers and records every request URL. */
    private class FakeAgentd : AutoCloseable {
        val server = MockWebServer()
        val urls = CopyOnWriteArrayList<String>()

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    urls += request.url.toString()
                    val path = request.url.encodedPath
                    return when {
                        path == "/api/events" -> MockResponse.Builder().code(200).setHeader("Content-Type", "text/event-stream")
                            .body(Fixtures.text("events_agents_agentd.txt")).build()
                        path == "/api/sessions/fx-session/agents" -> json(Fixtures.text("agents_agentd.json"))
                        path == "/api/messages" -> json(Fixtures.text("agent_messages_agentd.json"))
                        else -> MockResponse.Builder().code(404).body("""{"error":"Not found"}""").build()
                    }
                }
            }
            server.start()
        }

        fun client() = EvenTerminalClient(Endpoint(false, server.hostName, server.port, "0123456789abcdef0123456789abcdef"))
        private fun json(body: String) = MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(body).build()
        override fun close() = server.close()
    }

    @Test fun `agents=1 stream from agentd - empty id lines give id-less state, messages keep their ids`() = runBlocking {
        FakeAgentd().use { fake ->
            // 42 messages with ids, then 2 workflow and 5 agent updates with an empty `id:` line.
            val messages = withTimeout(15_000) {
                fake.client().events("fx-session", agents = { true }, backoff = { 20L })
                    .filterIsInstance<StreamEvent.Message>().map { it.message }.take(49).toList()
            }
            val updates = messages.filter { it is AgentMessage.AgentUpdate || it is AgentMessage.WorkflowUpdate }
            assertEquals(7, updates.size)
            assertTrue(updates.all { it.id == null })
            val rest = messages - updates.toSet()
            assertEquals((1L..42L).toList(), rest.map { it.id })
            assertTrue(rest.none { it is AgentMessage.Unsupported })
            // The transcript sees the conversation only; the agents state matches the list.
            val t = Transcript().reduceAll(messages)
            assertTrue(t.items.none { it is TranscriptItem.Unknown })
            assertEquals(Transcript().reduceAll(rest), t)
            assertEquals(AgentsState.of(list), messages.fold(AgentsState()) { s, m -> s.reduce(m) })
            assertTrue(fake.urls.first().contains("agents=1"))
        }
    }

    @Test fun `an agent transcript pages with its own ids, and the server echoes the agent`() = runBlocking {
        FakeAgentd().use { fake ->
            val page = fake.client().messages("fx-session", agentId = "a0000000000000005")
            assertEquals("a0000000000000005", page.agentId)
            assertEquals(SessionState.BUSY, page.state)
            assertEquals(30, page.messages.size)
            assertEquals(1L, page.messages.first().id)
            val t = Transcript().reduceAll(page.messages).withServerState(page.state!!)
            assertTrue(t.items.first() is TranscriptItem.Prompt)
            assertTrue(t.items.any { it is TranscriptItem.Tool })
            assertTrue(fake.urls.single().contains("agentId=a0000000000000005"))
            val agents = fake.client().agents("fx-session")
            assertEquals(list, agents)
        }
    }

    @Test fun `whether to ask for agents is decided before every connection`() = runBlocking {
        FakeAgentd().use { fake ->
            // Each /events answer ends, so the client reconnects: agentd first, then (rolled back) not.
            var asks = 0
            withTimeout(15_000) {
                fake.client().events("fx-session", agents = { asks++ == 0 }, backoff = { 20L })
                    .filterIsInstance<StreamEvent.Connected>().take(3).toList()
            }
            val events = fake.urls.filter { "/api/events" in it }
            assertTrue(events.size >= 3)
            assertTrue(events[0].contains("agents=1"))
            assertTrue(events.drop(1).none { it.contains("agents") })
        }
    }

    @Test fun `plain streams never ask for agents`() = runBlocking {
        FakeAgentd().use { fake ->
            withTimeout(15_000) { fake.client().events("fx-session", backoff = { 20L }).filterIsInstance<StreamEvent.Message>().take(1).toList() }
            assertFalse(fake.urls.first().contains("agents"))
            assertFalse(fake.urls.first().contains("agentId"))
        }
    }
}
