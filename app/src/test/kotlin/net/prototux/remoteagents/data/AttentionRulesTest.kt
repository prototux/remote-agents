package net.prototux.remoteagents.data

import net.prototux.remoteagents.api.AgentMessage
import net.prototux.remoteagents.api.AgentsState
import net.prototux.remoteagents.api.SessionState
import net.prototux.remoteagents.api.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionRulesTest {
    private val key = SessionKey("srv", "s1")
    private fun m(json: String, id: Long) = AgentMessage.parse(json, id)

    private val request = m("""{"type":"permission_request","toolName":"Bash","options":[{"key":"allow","text":"Allow"}]}""", 1)
    private val result = m("""{"type":"permission_result","toolName":"Bash","decision":"allowed"}""", 2)

    @Test fun `snapshot alerts only for what is still pending`() {
        val resolved = Transcript().reduceAll(listOf(request, result))
        assertTrue(AttentionRules.onSnapshot(key, resolved).isEmpty())
        val pending = Transcript().reduce(request)
        assertEquals(listOf(Alert.Permission(key, request as AgentMessage.PermissionRequest)), AttentionRules.onSnapshot(key, pending))
    }

    @Test fun `live messages alert and clear`() {
        val t = Transcript().reduce(request)
        assertTrue(AttentionRules.onLive(key, request, t).single() is Alert.Permission)
        assertEquals(listOf(Alert.Clear(key, Alert.Kind.Permission)), AttentionRules.onLive(key, result, t.reduce(result)))
        val text = m("""{"type":"text_delta","text":"All done"}""", 3)
        val done = m("""{"type":"result","success":true,"text":"All done","costUsd":0.1}""", 4)
        val after = t.reduce(result).reduce(text).reduce(done)
        val finished = AttentionRules.onLive(key, done, after).filterIsInstance<Alert.Finished>().single()
        assertEquals("All done", finished.lastText)
        assertTrue(AttentionRules.onLive(key, m("""{"type":"text_delta","text":"x"}""", 5), after).isEmpty())
    }

    @Test fun `streams stay open while on screen or watched and working`() {
        val idle = Transcript()
        val busy = Transcript(state = SessionState.BUSY)
        assertTrue(AttentionRules.keepOpen(onScreen = true, watched = false, t = idle))
        assertFalse(AttentionRules.keepOpen(onScreen = false, watched = true, t = idle))
        assertTrue(AttentionRules.keepOpen(onScreen = false, watched = true, t = busy))
        assertFalse(AttentionRules.keepOpen(onScreen = false, watched = false, t = busy))
        assertTrue(AttentionRules.needsService(watched = true, t = Transcript(state = SessionState.AWAITING)))
        assertFalse(AttentionRules.needsService(watched = true, t = idle))
    }

    @Test fun `running subagents keep a watched idle session followed, and agent updates never alert`() {
        val idle = Transcript()
        val update = m("""{"type":"agent_update","sessionId":"s1","agent":{"id":"a1","kind":"workflow","runId":"wf1","state":"running"}}""", 0)
        val running = AgentsState().reduce(update)
        assertTrue(AttentionRules.needsService(watched = true, t = idle, agents = running))
        assertTrue(AttentionRules.keepOpen(onScreen = false, watched = true, t = idle, agents = running))
        assertFalse(AttentionRules.needsService(watched = false, t = idle, agents = running))
        val done = running.reduce(m("""{"type":"agent_update","sessionId":"s1","agent":{"id":"a1","runId":"wf1","state":"done"}}""", 0))
            .reduce(m("""{"type":"workflow_update","sessionId":"s1","workflow":{"runId":"wf1","status":"completed"}}""", 0))
        assertFalse(AttentionRules.needsService(watched = true, t = idle, agents = done))
        assertTrue(AttentionRules.onLive(key, update, idle).isEmpty())
    }
}
