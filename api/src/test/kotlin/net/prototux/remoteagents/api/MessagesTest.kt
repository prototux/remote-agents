package net.prototux.remoteagents.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesTest {
    private val all = Fixtures.messages("full_turn.jsonl")

    @Test fun `every documented type parses to its variant`() {
        val types = all.map { it::class.simpleName }.toSet()
        listOf("Status", "UserPrompt", "TextDelta", "ToolStart", "ToolEnd", "TaskProgress", "PermissionRequest",
            "PermissionResult", "UserQuestion", "QuestionAnswer", "Notification", "RunningStats", "Result").forEach {
            assertTrue("missing $it", it in types)
        }
        assertTrue(all.none { it is AgentMessage.Unsupported })
    }

    @Test fun `permission request keeps option keys`() {
        val p = all.filterIsInstance<AgentMessage.PermissionRequest>().single()
        assertEquals(listOf("allow", "allowAlways", "deny"), p.options.map { it.key })
        assertEquals("Bash", p.toolName)
        assertEquals("npm test", p.detail)
    }

    @Test fun `question parses options and previews`() {
        val q = all.filterIsInstance<AgentMessage.UserQuestion>().single().questions.single()
        assertEquals("Which fix?", q.key)
        assertEquals("- a\n+ b", q.options[0].preview)
        assertNull(q.options[1].preview)
    }

    @Test fun `result carries usage`() {
        val r = all.filterIsInstance<AgentMessage.Result>().single()
        assertEquals(0.0421, r.costUsd!!, 1e-9)
        assertEquals(42000L, r.durationMs)
        assertEquals(4, r.turns)
    }

    @Test fun `error message parses`() {
        val e = AgentMessage.parse("""{"type":"error","message":"SDK query failed"}""", 7)
        assertEquals(AgentMessage.Error(7, (e as AgentMessage.Error).raw, "SDK query failed"), e)
    }

    @Test fun `unknown type and unknown fields are kept, not fatal`() {
        val m = AgentMessage.parse("""{"type":"plan_update","steps":[1,2],"extra":{"a":true}}""", 42)
        assertTrue(m is AgentMessage.Unsupported)
        assertEquals(42L, m.id)
        assertEquals("plan_update", m.type)
        val known = AgentMessage.parse("""{"type":"text_delta","text":"hi","newField":3}""")
        assertEquals("hi", (known as AgentMessage.TextDelta).text)
    }

    @Test fun `garbage data becomes unsupported`() {
        assertTrue(AgentMessage.parse("not json", 1) is AgentMessage.Unsupported)
        assertTrue(AgentMessage.parse("[1,2]", 1) is AgentMessage.Unsupported)
    }

    @Test fun `missing fields get defaults`() {
        val t = AgentMessage.parse("""{"type":"tool_end"}""") as AgentMessage.ToolEnd
        assertEquals("tool", t.name)
        assertNull(t.input)
        val s = AgentMessage.parse("""{"type":"running_stats"}""") as AgentMessage.RunningStats
        assertEquals(0L, s.durationMs)
    }

    @Test fun `sse id wins over body id`() {
        assertEquals(9L, AgentMessage.parse("""{"id":3,"type":"text_delta","text":""}""", 9).id)
        assertEquals(3L, AgentMessage.parse("""{"id":3,"type":"text_delta","text":""}""").id)
    }
}
