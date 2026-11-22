package net.prototux.remoteagents.notify

import net.prototux.remoteagents.api.AgentMessage
import net.prototux.remoteagents.api.AgentsState
import net.prototux.remoteagents.api.SessionState
import net.prototux.remoteagents.api.Transcript
import net.prototux.remoteagents.data.LiveState
import net.prototux.remoteagents.data.SessionKey
import net.prototux.remoteagents.data.SessionMeta
import org.junit.Assert.assertEquals
import org.junit.Test

class OngoingLinesTest {
    private val key = SessionKey("srv", "s1")
    private fun m(json: String) = AgentMessage.parse(json)

    private val workflow = AgentsState()
        .reduce(m("""{"type":"workflow_update","sessionId":"s1","workflow":{"runId":"wf1","name":"agentd-build","currentPhase":"Review","agentsDone":5,"agentsTotal":8,"status":"running"}}"""))
        .reduce(m("""{"type":"agent_update","sessionId":"s1","agent":{"id":"a1","kind":"workflow","runId":"wf1","label":"review","state":"running"}}"""))

    @Test fun `a session shows its workflow under it`() {
        val busy = LiveState(key, SessionMeta(title = "Build agentd"), Transcript(state = SessionState.BUSY), agents = workflow)
        assertEquals(listOf(OngoingLine("Build agentd — working", "workflow agentd-build · Review · 5/8 agents")), ongoingLines(listOf(busy)))
        // A background workflow runs while the session itself is idle.
        val idle = busy.copy(transcript = Transcript())
        assertEquals("Build agentd — agents working", ongoingLines(listOf(idle)).single().session)
    }

    @Test fun `a subagent's request says so, and even-terminal sessions are unchanged`() {
        val ask = m("""{"type":"permission_request","toolName":"Bash","toolUseId":"t1","agentId":"a1","agentLabel":"review","options":[{"key":"allow","text":"Allow"}]}""")
        val st = LiveState(key, SessionMeta(title = "Build agentd"), Transcript().reduce(ask))
        assertEquals(OngoingLine("Build agentd — a subagent needs approval", null), ongoingLines(listOf(st)).single())
        val plain = LiveState(key, SessionMeta(), Transcript(state = SessionState.BUSY))
        assertEquals(OngoingLine("Session — working", null), ongoingLines(listOf(plain)).single())
    }
}
