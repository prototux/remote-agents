package net.prototux.remoteagents.api

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventStreamTest {
    private val fake = FakeEvenTerminal()
    private val client = EvenTerminalClient(fake.endpoint())

    @After fun tearDown() = fake.close()

    @Test fun `deduplicator drops replays and detects restarts`() {
        val d = Deduplicator(mapOf(1L to 11, 2L to 22))
        assertEquals(Deduplicator.Verdict.Duplicate, d.check(2, 22))
        assertEquals(Deduplicator.Verdict.New, d.check(3, 33))
        assertEquals(Deduplicator.Verdict.Duplicate, d.check(1, 11))
        assertEquals(Deduplicator.Verdict.New, d.check(null, 0))
        // A seen id with other content, or an unseen id below the highest: a new server process.
        assertEquals(Deduplicator.Verdict.Reset, Deduplicator(mapOf(1L to 11)).check(1, 99))
        val fresh = Deduplicator(mapOf(10L to 1, 20L to 2))
        assertEquals(Deduplicator.Verdict.Reset, fresh.check(5, 5))
        assertEquals(Deduplicator.Verdict.New, fresh.check(6, 6))
        assertEquals(6L, fresh.lastId)
    }

    @Test fun `scripted full turn over reconnecting streams, without duplicates`() = runBlocking {
        val sid = client.prompt("fix it").sessionId
        var transcript = Transcript()
        var answeredPermission = false
        var answeredQuestion = false
        withTimeout(15_000) {
            client.events(sid, backoff = { 20L }).takeWhile { ev ->
                if (ev is StreamEvent.Message) transcript = transcript.reduce(ev.message)
                if (transcript.pendingPermission != null && !answeredPermission) {
                    answeredPermission = true
                    launch { client.permissionResponse(sid, transcript.pendingPermission!!.options.first().key) }
                }
                if (transcript.pendingQuestion != null && !answeredQuestion) {
                    answeredQuestion = true
                    launch { client.questionResponse(sid, mapOf(transcript.pendingQuestion!!.questions.single().key to "Patch")) }
                }
                transcript.items.none { it is TranscriptItem.Outcome } || transcript.state != SessionState.IDLE
            }.toList()
        }
        assertEquals(fake.buffer(sid).size, transcript.knownIds.size)
        assertEquals(
            listOf("Prompt", "Text", "Tool", "Permission", "Ask", "Text", "Outcome"),
            transcript.items.map { it::class.simpleName },
        )
        assertEquals(SessionState.IDLE, transcript.state)
        assertTrue(fake.tokenInUrl.isEmpty())
    }

    @Test fun `known ids from a snapshot are not delivered again`() = runBlocking {
        val sid = client.prompt("go").sessionId
        val snapshot = client.messages(sid)
        client.permissionResponse(sid, "deny")
        val first = withTimeout(5_000) {
            client.events(sid, known = Transcript().reduceAll(snapshot.messages).knownIds).first { it is StreamEvent.Message } as StreamEvent.Message
        }
        assertEquals("permission_result", first.message.type)
    }

    @Test fun `server restart is reported as a reset`() = runBlocking {
        val sid = client.prompt("go").sessionId
        val seen = Transcript().reduceAll(client.messages(sid).messages).knownIds
        fake.restart()
        fake.emit(sid, """{"type":"user_prompt","text":"after restart"}""")
        val events = withTimeout(5_000) {
            val out = mutableListOf<StreamEvent>()
            client.events(sid, known = seen, backoff = { 20L }).takeWhile { out += it; it !is StreamEvent.Message }.toList()
            out
        }
        assertTrue(events.any { it is StreamEvent.Reset })
    }

    @Test fun `401 ends the stream with auth failed`() = runBlocking {
        val bad = EvenTerminalClient(Endpoint(false, fake.server.hostName, fake.server.port, "wrong"))
        val events = withTimeout(5_000) { bad.events("s1", backoff = { 20L }).toList() }
        assertEquals(StreamEvent.AuthFailed, events.last())
    }
}
