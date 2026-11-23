package net.prototux.remoteagents.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersTest {
    private fun info(json: String) = ApiJson.decodeFromString<ServerInfo>(json)

    /** agentd's `/info` as served on 2026-11-23 (Claude and pi installed), with Hermes made installed to see an experimental one. */
    private val agentd = info(
        """{"account":{},"model":"opus","version":"2.1.280","provider":"claude","extra":{"expose":"off"},"server":{"name":"agentd","version":"0.1.0-dev","providers":[""" +
            """{"name":"claude","label":"Claude Code","installed":true,"experimental":false,"canFollow":true,"canHost":true,"canDriveTerminal":true,"canApprove":true},""" +
            """{"name":"pi","label":"pi","installed":true,"experimental":false,"canFollow":true,"canHost":true,"canDriveTerminal":true,"canApprove":true},""" +
            """{"name":"codex","label":"Codex","installed":false,"experimental":true,"canFollow":true,"canHost":true,"canDriveTerminal":true,"canApprove":true},""" +
            """{"name":"opencode","label":"opencode","installed":false,"experimental":true,"canFollow":true,"canHost":true,"canDriveTerminal":true,"canApprove":true},""" +
            """{"name":"hermes","label":"Hermes Agent","installed":true,"experimental":true,"canFollow":true,"canHost":true,"canDriveTerminal":true,"canApprove":true}]}}""",
    )

    @Test fun `parses server providers`() {
        val p = agentd.providers!!
        assertEquals(listOf("claude", "pi", "codex", "opencode", "hermes"), p.map { it.name })
        assertEquals(ProviderInfo("codex", "Codex", installed = false, experimental = true, canFollow = true, canHost = true, canDriveTerminal = true, canApprove = true), p[2])
        assertTrue(agentd.isAgentd)
    }

    @Test fun `even-terminal and older agentd list none`() {
        assertNull(info("""{"version":"0.10.4","provider":"claude","extra":{"expose":"off"}}""").providers)
        assertNull(info("""{"server":{"name":"agentd","version":"0.0.9"}}""").providers)
        // extra.server is read too, as for isAgentd; unknown fields are ignored.
        val nested = info("""{"extra":{"server":{"name":"agentd","providers":[{"name":"pi","installed":true,"canHost":true,"future":1}]}}}""")
        assertEquals(listOf(ProviderOption("pi", "pi")), ProviderPicker.options(nested))
    }

    @Test fun `picker offers installed providers that can host, with the experimental tag`() {
        assertEquals(
            listOf(ProviderOption("claude", "Claude Code"), ProviderOption("pi", "pi"), ProviderOption("hermes", "Hermes Agent", experimental = true)),
            ProviderPicker.options(agentd),
        )
        val cannotHost = info("""{"server":{"name":"agentd","providers":[{"name":"claude","installed":true,"canHost":true},{"name":"newagent","label":"New Agent","installed":true,"canHost":true},{"name":"watcher","installed":true,"canHost":false}]}}""")
        assertEquals(listOf(ProviderOption("claude", "Claude Code"), ProviderOption("newagent", "New Agent")), ProviderPicker.options(cannotHost))
        assertEquals(emptyList<ProviderOption>(), ProviderPicker.options(info("""{"server":{"name":"agentd","providers":[]}}""")))
    }

    @Test fun `picker falls back to claude and codex`() {
        val fallback = listOf(ProviderOption("claude", "Claude Code"), ProviderOption("codex", "Codex"))
        assertEquals(fallback, ProviderPicker.options(null))
        assertEquals(fallback, ProviderPicker.options(info("""{"version":"0.10.4"}""")))
    }

    @Test fun `picker selection`() {
        val options = ProviderPicker.options(agentd)
        assertEquals("pi", ProviderPicker.select(options, "pi"))
        assertEquals("claude", ProviderPicker.select(options, "claude-sync"))
        assertEquals("claude", ProviderPicker.select(options, "codex")) // the server default is not installed here
        assertNull(ProviderPicker.select(emptyList(), "claude"))
    }

    @Test fun `session filters`() {
        assertEquals(listOf("claude", "codex"), ProviderPicker.filters(null, emptyList()))
        val sessions = listOf(SessionSummary("a", provider = "opencode"), SessionSummary("b", provider = null), SessionSummary("c", provider = "zed"))
        assertEquals(listOf("claude", "pi", "codex", "opencode", "hermes", "zed"), ProviderPicker.filters(agentd, sessions))
    }

    @Test fun `labels and canonical names`() {
        assertEquals(
            listOf("Claude Code", "Claude Code", "pi", "Codex", "opencode", "Hermes Agent", "Agent", "zed"),
            listOf("claude", "claude-sync", "pi", "codex", "opencode", "hermes", null, "zed").map { Providers.label(it) },
        )
        assertEquals("claude", Providers.canonical("claude-sync"))
        assertEquals("claude", Providers.canonical(null))
        assertEquals("hermes", Providers.canonical("hermes"))
        assertTrue("pi" in Providers.ALL)
        assertFalse("evil" in Providers.ALL)
    }

    @Test fun `slash commands only where documented`() {
        assertTrue(SlashCommands.forProvider("claude-sync").any { it.name == "/compact" })
        assertTrue(SlashCommands.forProvider(null).isNotEmpty())
        for (p in listOf("pi", "opencode", "hermes", "zed")) assertEquals(p, emptyList<SlashCommand>(), SlashCommands.forProvider(p))
        assertEquals(emptyList<SlashCommand>(), SlashCommands.matching("hermes", "/"))
    }
}
