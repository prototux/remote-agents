package net.prototux.remoteagents.api

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolFormatTest {
    private fun json(s: String) = ApiJson.parseToJsonElement(s) as JsonObject

    @Test fun `edit becomes a line diff`() {
        val v = ToolFormat.view("Edit", json("""{"file_path":"a.kt","old_string":"x\na\ny","new_string":"x\nb\ny"}"""))
        assertEquals("a.kt", v.subject)
        assertEquals(
            listOf(DiffLine.Kind.Context to "x", DiffLine.Kind.Removed to "a", DiffLine.Kind.Added to "b", DiffLine.Kind.Context to "y"),
            v.diff.map { it.kind to it.text },
        )
    }

    @Test fun `bash shows the command`() {
        val v = ToolFormat.view("Bash", json("""{"command":"ls -la","description":"List"}"""))
        assertEquals("ls -la", v.code)
        assertEquals("List", v.subject)
    }

    @Test fun `unknown tool falls back to common keys`() {
        assertEquals("/tmp/x", ToolFormat.view("mcp__fs__stat", json("""{"path":"/tmp/x"}""")).subject)
    }

    @Test fun `slash command matching`() {
        assertEquals(listOf("/compact"), SlashCommands.matching("claude", "/comp").map { it.name })
        assertEquals(emptyList<SlashCommand>(), SlashCommands.matching("claude", "/compact now"))
        assertEquals(emptyList<SlashCommand>(), SlashCommands.matching("claude", "hello"))
        assertEquals(true, SlashCommands.matching("codex", "/").any { it.name == "/diff" })
    }
}
