package io.github.kontsevoye.ichuchu.ui.screens.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryCitationsTest {
    private val block =
        """<oai-mem-citation>
<citation_entries>
MEMORY.md:116-119|note=[guarded sending]
rollout_summaries/тест.md:3|note=[Unicode 世界]
</citation_entries>
<rollout_ids>
01a1a250-2f3b-7e02-b336-177b8651f373
</rollout_ids>
</oai-mem-citation>"""

    @Test
    fun extractsSourcesAndPreservesSurroundingAnswer() {
        val result = agentMarkdownParts("Before.\n$block\nAfter.")
        assertEquals("Before.\n", result.first().text)
        assertEquals("After.", result.last().text)
        assertEquals(
            listOf(
                MemorySource("MEMORY.md:116–119", "guarded sending"),
                MemorySource("rollout_summaries/тест.md:3", "Unicode 世界"),
            ),
            result[1].sources,
        )
    }

    @Test
    fun keepsFencedInlineQuotedAndIndentedExamplesLiteral() {
        for (example in
            listOf(
                "```xml\n$block\n```",
                "~~~xml\n$block\n~~~",
                "`<oai-mem-citation>`",
                block.prependIndent("> "),
                block.prependIndent("    "),
                block.prependIndent("\t"),
                "Inline `example\n$block\nends here`",
            )) {
            assertEquals(listOf(AgentMarkdownPart(text = example)), agentMarkdownParts(example))
        }
    }

    @Test
    fun incompleteStreamRemainsCompactAndSettlesWithoutLosingSources() {
        val partial = block.substringBefore("</citation_entries>")
        val parsed = agentMarkdownParts(partial).single()
        assertTrue(parsed.receiving)
        assertEquals(2, parsed.sources!!.size)
        assertEquals(parsed.sources, agentMarkdownParts(block).single().sources)
    }

    @Test
    fun malformedAndUnboundedAnnotationsStayReadable() {
        for (bad in
            listOf(
                block.replace("<citation_entries>", "<other>"),
                block.replace(
                    "MEMORY.md:116-119|note=[guarded sending]",
                    "Do not swallow ordinary prose.",
                ),
                "<oai-mem-citation>\n<citation_entries>\n" + "x".repeat(33_000),
                "<oai-mem-citation>\nThis is just a quote.",
            )) {
            assertEquals(listOf(AgentMarkdownPart(text = bad)), agentMarkdownParts(bad))
        }
    }

    @Test
    fun multipleBlocksAndCrLfAreHandledIndependently() {
        val parts = agentMarkdownParts("$block\nBetween\n$block".replace("\n", "\r\n"))
        assertEquals(2, parts.count { it.sources != null })
        assertEquals("Between\r\n", parts[1].text)
    }
}
