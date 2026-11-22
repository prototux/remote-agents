package net.prototux.remoteagents.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {
    private val turn = Fixtures.messages("full_turn.jsonl")

    @Test fun `a full turn builds the expected conversation`() {
        val t = Transcript().reduceAll(turn)
        val kinds = t.items.map { it::class.simpleName }
        assertEquals(
            listOf("Prompt", "Text", "Tool", "Permission", "Tool", "Ask", "Tool", "Notice", "Text", "Outcome"),
            kinds,
        )
        assertEquals("I'll run the tests first.", (t.items[1] as TranscriptItem.Text).text)
        val bash = t.items[4] as TranscriptItem.Tool
        assertEquals("1 failing", bash.output)
        assertFalse(bash.running)
        assertEquals("allowed", (t.items[3] as TranscriptItem.Permission).result!!.decision)
        assertEquals(mapOf("Which fix?" to "Patch"), (t.items[5] as TranscriptItem.Ask).answers)
        assertEquals(SessionState.IDLE, t.state)
        assertEquals(0.0421, t.totalCostUsd, 1e-9)
        assertEquals(listOf("Run tests", "Fix bug"), t.todos.map { it.content })
        assertEquals(24, t.knownIds.size)
    }

    @Test fun `state follows the turn`() {
        val upToPermission = Transcript().reduceAll(turn.take(12))
        assertEquals(SessionState.AWAITING, upToPermission.state)
        assertNotNull(upToPermission.pendingPermission)
        val answered = upToPermission.reduce(turn[12])
        assertEquals(SessionState.BUSY, answered.state)
        assertNull(answered.pendingPermission)
        val thinking = Transcript().reduceAll(turn.take(3))
        assertEquals(Transcript.Phase.Thinking, thinking.phase)
        val asking = Transcript().reduceAll(turn.take(17))
        assertNotNull(asking.pendingQuestion)
        assertEquals(SessionState.AWAITING, asking.state)
        assertNotNull(Transcript().reduceAll(turn.take(15)).stats)
    }

    @Test fun `local prompt is confirmed by the echo, not duplicated`() {
        val t = Transcript().withLocalPrompt("fix the failing test")
        assertTrue((t.items.single() as TranscriptItem.Prompt).pending)
        val echoed = t.reduce(turn[0])
        assertFalse((echoed.items.single() as TranscriptItem.Prompt).pending)
    }

    @Test fun `running tools stop when the turn ends without tool_end`() {
        val t = Transcript().reduceAll(listOf(
            AgentMessage.parse("""{"type":"tool_start","name":"Bash","toolId":"a"}""", 1),
            AgentMessage.parse("""{"type":"result","success":false,"text":"Interrupted by user"}""", 2),
        ))
        assertFalse((t.items[0] as TranscriptItem.Tool).running)
        assertFalse((t.items[1] as TranscriptItem.Outcome).result.success)
    }

    @Test fun `history goes above live items`() {
        val t = Transcript().reduce(turn[0]).withHistory(listOf(HistoryEntry("user", "old"), HistoryEntry("assistant", "reply")))
        assertEquals(listOf("Past", "Past", "Prompt"), t.items.map { it::class.simpleName })
    }

    @Test fun `unsupported events become items`() {
        val t = Transcript().reduce(AgentMessage.parse("""{"type":"future_thing"}""", 5))
        assertTrue(t.items.single() is TranscriptItem.Unknown)
    }
}
