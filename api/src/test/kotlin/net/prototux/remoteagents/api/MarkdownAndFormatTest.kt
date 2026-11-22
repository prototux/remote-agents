package net.prototux.remoteagents.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownAndFormatTest {
    @Test fun `blocks`() {
        val md = """
            # Title
            Some *text* here
            and more.

            ```kotlin
            val x = 1
            ```
            - one
            - [x] two
              continued
            1. first
            > quoted
            ---
            | a | b |
            |---|---|
            | 1 | 2 |
        """.trimIndent()
        val b = Markdown.parse(md)
        assertEquals(MdBlock.Heading(1, "Title"), b[0])
        assertEquals(MdBlock.Paragraph("Some *text* here\nand more."), b[1])
        assertEquals(MdBlock.Code("kotlin", "val x = 1"), b[2])
        val list = b[3] as MdBlock.ListBlock
        assertFalse(list.ordered)
        assertEquals(ListItem("two continued", 0, null, true), list.items[1])
        assertTrue((b[4] as MdBlock.ListBlock).ordered)
        assertEquals(MdBlock.Quote("quoted"), b[5])
        assertEquals(MdBlock.Rule, b[6])
        assertEquals(MdBlock.Table(listOf("a", "b"), listOf(listOf("1", "2"))), b[7])
    }

    @Test fun `unfinished code fence while streaming`() {
        assertEquals(listOf(MdBlock.Paragraph("Look:"), MdBlock.Code("sh", "ls")), Markdown.parse("Look:\n```sh\nls"))
    }

    @Test fun `inline spans`() {
        assertEquals(
            listOf(MdSpan.Plain("a "), MdSpan.Bold("b"), MdSpan.Plain(" "), MdSpan.Code("c*d"), MdSpan.Plain(" "),
                MdSpan.Italic("e"), MdSpan.Plain(" "), MdSpan.Link("f", "https://x.org"), MdSpan.Plain(" snake_case_name")),
            MarkdownInline.parse("a **b** `c*d` *e* [f](https://x.org) snake_case_name"),
        )
    }

    private val single = Question("Which?", null, false, listOf(QuestionOption("A", null, null), QuestionOption("B", null, null)))
    private val multi = Question("Which ones?", null, true, listOf(QuestionOption("X", null, null), QuestionOption("Y", null, null)))

    @Test fun `question draft`() {
        var d = QuestionDraft(listOf(single, multi))
        assertFalse(d.complete)
        d = d.toggle(single, "A").toggle(single, "B")
        assertEquals("B", d.answerFor(single))
        d = d.toggle(multi, "Y").toggle(multi, "X").setText(multi, "and Z")
        assertEquals("X, Y, and Z", d.answerFor(multi))
        assertTrue(d.complete)
        d = d.setText(single, "neither")
        assertEquals(mapOf("Which?" to "neither", "Which ones?" to "X, Y, and Z"), d.answers)
    }

    @Test fun `formats`() {
        assertEquals("42s", Format.duration(42_000))
        assertEquals("2m 5s", Format.duration(125_000))
        assertEquals("1.2k", Format.tokens(1234))
        assertEquals("$0.04", Format.cost(0.0421))
        assertEquals("$0.0042", Format.cost(0.0042))
        assertEquals("~/Projects/x", Format.path("/home/me/Projects/x"))
        assertEquals("…/b/c/d", Format.path("/srv/a/b/c/d"))
        assertEquals("5 min ago", Format.ago("2026-11-22T13:40:00Z", java.time.Instant.parse("2026-11-22T13:45:00Z").toEpochMilli()))
        assertEquals("a b…", Format.oneLine("a\n b  cdef", 4))
    }
}
