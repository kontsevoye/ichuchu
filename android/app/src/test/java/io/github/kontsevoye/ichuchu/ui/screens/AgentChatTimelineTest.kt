package io.github.kontsevoye.ichuchu.ui.screens

import io.github.kontsevoye.ichuchu.service.agents.AgentChatState
import io.github.kontsevoye.ichuchu.service.agents.DeckRow
import io.github.kontsevoye.ichuchu.service.agents.DeckSend
import io.github.kontsevoye.ichuchu.service.agents.DeckTimeline
import io.github.kontsevoye.ichuchu.service.agents.parseDeckObject
import io.github.kontsevoye.ichuchu.ui.screens.agents.chatBlocks
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentChatTimelineTest {
    @Test
    fun reopeningOldCacheDoesNotReuseLazyColumnKeysOrCollapseSeparateRequests() {
        val failed =
            DeckSend(
                "remote:one",
                "same text",
                parseDeckObject("""{"send_id":"one","state":"failed"}"""),
            )
        val distinctRequest =
            failed.copy(
                localId = "remote:two",
                receipt = parseDeckObject("""{"send_id":"two","state":"failed"}"""),
            )
        val blocks = chatBlocks(AgentChatState(sends = listOf(failed, failed, distinctRequest)))
        assertEquals(listOf("send:remote:one", "send:remote:two"), blocks.map { it.key })
    }

    @Test
    fun anchoredSendsDoNotMoveBehindNewRowsOrUseThePhoneClock() {
        val first =
            DeckRow(
                parseDeckObject("""{"id":"a","kind":"assistant","ts":"2026-11-02T20:00:00Z"}""")
            )
        val next =
            DeckRow(
                parseDeckObject("""{"id":"b","kind":"assistant","ts":"2026-11-02T19:00:00Z"}""")
            )
        val send =
            DeckSend(
                "local",
                "again",
                parseDeckObject("""{"state":"unknown"}"""),
                "a",
                Long.MAX_VALUE,
            )
        val chat =
            AgentChatState(DeckTimeline(listOf(first, next), null, null), sends = listOf(send))
        assertEquals(listOf("row:a", "send:local", "row:b"), chatBlocks(chat).map { it.key })
        val landed =
            send.copy(receipt = parseDeckObject("""{"state":"landed","landed_row_id":"a"}"""))
        assertEquals(
            listOf("row:a", "row:b"),
            chatBlocks(chat.copy(sends = listOf(landed))).map { it.key },
        )
    }

    @Test
    fun delayedReceiptStaysBeforeLaterRepliesAfterReload() {
        val rows =
            listOf(
                DeckRow(
                    parseDeckObject(
                        """{"id":"a","kind":"assistant","ts":"2026-11-02T20:00:00Z","body":"Earlier reply"}"""
                    )
                ),
                DeckRow(
                    parseDeckObject(
                        """{"id":"b","kind":"assistant","ts":"2026-11-02T20:02:00Z","body":"Later reply"}"""
                    )
                ),
            )
        val send =
            DeckSend(
                "local",
                "A request",
                parseDeckObject(
                    """{"state":"submitted","settled":true,"created_at":"2026-11-02T20:01:00Z"}"""
                ),
            )
        val chat = AgentChatState(DeckTimeline(rows, null, null), sends = listOf(send))
        assertEquals(listOf("row:a", "send:local", "row:b"), chatBlocks(chat).map { it.key })
    }
}
