package net.prototux.remoteagents.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * agentd's "always allow Bash" policy (owner decision 2026-11-25): parsing of the new fields (present and
 * absent, for lenient even-terminal compatibility), the client calls and what they send on the wire.
 */
class PolicyTest {
    private val fake = FakeEvenTerminal()
    private val client = EvenTerminalClient(fake.endpoint())

    @After fun tearDown() = fake.close()

    @Test fun `server info policy flag, present and absent`() {
        val withPolicy: ServerInfo = ApiJson.decodeFromString("""{"version":"1.0","policy":true,"server":{"name":"agentd"}}""")
        assertEquals(true, withPolicy.policy)
        val withoutPolicy: ServerInfo = ApiJson.decodeFromString("""{"version":"0.10.4"}""")
        assertNull(withoutPolicy.policy)
    }

    @Test fun `session and status bashAutoAllow fields, present and absent`() {
        val full: SessionSummary = ApiJson.decodeFromString("""{"id":"s1","bashAutoAllow":true,"bashAutoAllowSession":false}""")
        assertEquals(true, full.bashAutoAllow)
        assertEquals(false, full.bashAutoAllowSession)
        val plain: SessionSummary = ApiJson.decodeFromString("""{"id":"s1"}""")
        assertNull(plain.bashAutoAllow)
        assertNull(plain.bashAutoAllowSession)

        val status: StatusResponse = ApiJson.decodeFromString("""{"state":"idle","bashAutoAllow":true}""")
        assertEquals(true, status.bashAutoAllow)
        assertNull(status.bashAutoAllowSession)
        val plainStatus: StatusResponse = ApiJson.decodeFromString("""{"state":"idle"}""")
        assertNull(plainStatus.bashAutoAllow)
    }

    @Test fun `permission result auto flag, present and absent`() {
        val auto = AgentMessage.parse("""{"type":"permission_result","toolName":"Bash","decision":"allowed","toolUseId":"t1","auto":true}""") as AgentMessage.PermissionResult
        assertTrue(auto.auto)
        val manual = AgentMessage.parse("""{"type":"permission_result","toolName":"Bash","decision":"allowed","toolUseId":"t1"}""") as AgentMessage.PermissionResult
        assertFalse(manual.auto)
    }

    @Test fun `auto-allowed tool gets a label and no permission card, without getting stuck awaiting`() {
        val t = Transcript().reduceAll(listOf(
            AgentMessage.parse("""{"type":"tool_start","name":"Bash","toolId":"t1"}""", 1),
            // No permission_request at all: the policy allowed it without reaching the phone.
            AgentMessage.parse("""{"type":"permission_result","toolName":"Bash","decision":"allowed","toolUseId":"t1","auto":true}""", 2),
            AgentMessage.parse("""{"type":"tool_end","name":"Bash","toolId":"t1","detail":{"input":{"command":"ls"},"output":"ok"}}""", 3),
        ))
        assertEquals(listOf("Tool"), t.items.map { it::class.simpleName }) // no Permission card
        val tool = t.items.single() as TranscriptItem.Tool
        assertTrue(tool.autoAllowed)
        assertFalse(tool.running)
        assertNull(t.pendingPermission)
        assertEquals(SessionState.BUSY, t.state) // never AWAITING: there was never anything pending
    }

    @Test fun `policy round trip, and a provider left out of setPolicy inherits global`() = runBlocking {
        val set = client.setPolicy(true, mapOf("claude" to true, "codex" to null))
        assertEquals(true, set.bashAutoAllow.global)
        assertEquals(mapOf("claude" to true), set.bashAutoAllow.providers) // codex omitted: inherits global
        assertFalse(fake.policyPuts.single().toString().contains("codex"))
        assertEquals(listOf("sudo", "rm -rf", "git push --force", "git reset --hard", "git clean -f", "dd", "mkfs"), set.stillAsk)

        val got = client.policy()
        assertEquals(set, got)
    }

    @Test fun `session policy, true false and null clearing the override`() = runBlocking {
        fake.addSession("s1")
        val on = client.setSessionPolicy("s1", true)
        assertEquals(true, on.effective)
        assertEquals(true, on.session)
        assertEquals(JsonPrimitive(true), fake.sessionPolicyPosts[0]["bashAutoAllow"])

        val off = client.setSessionPolicy("s1", false)
        assertEquals(false, off.effective)
        assertEquals(false, off.session)

        val cleared = client.setSessionPolicy("s1", null)
        assertNull(cleared.session)
        assertEquals(JsonNull, fake.sessionPolicyPosts[2]["bashAutoAllow"])
        // Back to the (off) global default now that the session override is gone.
        assertEquals(false, cleared.effective)
    }

    @Test fun `unknown provider is refused`() {
        val e = runCatching { runBlocking { client.setPolicy(false, mapOf("not-a-provider" to true)) } }.exceptionOrNull() as? ApiException
        assertEquals(400, e?.httpStatus)
        assertEquals("unknown_provider", e?.code)
    }
}
