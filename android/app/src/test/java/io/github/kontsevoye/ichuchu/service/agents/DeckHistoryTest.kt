package io.github.kontsevoye.ichuchu.service.agents

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckHistoryTest {
    @Test
    fun missingDeckTranscriptUsesItsExactNativeIdentityForSnapshotAndFollow() = runBlocking {
        val wire = FakeDeckTransport()
        val client = DeckClient(wire, "default", "")
        client.probe()
        var nativeId = "native-exact-identity"
        wire.replyForCommand = { command ->
            when {
                command.contains("'timeline' 'deck-session'") ->
                    DeckExecResult(
                        """{"success":false,"code":"NOT_FOUND","error":"no native transcript"}""",
                        "",
                        1,
                    )
                command.contains("'session' 'show'") ->
                    DeckExecResult(
                        """{"id":"deck-session","tool":"codex","codex_session_id":"$nativeId"}""",
                        "",
                        0,
                    )
                command.contains("'timeline' 'native-exact-identity'") ->
                    DeckExecResult(
                        """{"schema":"agent-deck.recall.rows/v2","turns":[{"id":"canonical-user","kind":"user","body":"Already submitted"}],"through_cursor":"exact-cursor"}""",
                        "",
                        0,
                    )
                else -> null
            }
        }
        val page = client.timeline("deck-session")
        assertEquals("canonical-user", page.rows.single().id)
        assertEquals("exact-cursor", page.cursor)
        client.follow("deck-session", page.cursor) { error("No synthetic frames expected") }
        assertTrue(wire.command.contains("native-exact-identity"))
        assertTrue(wire.command.contains("exact-cursor"))
        nativeId = "changed-native-identity"
        var resync = false
        client.follow("deck-session", page.cursor) {
            resync = it.text("frame") == "resync_required"
        }
        assertTrue(resync)
        assertFalse(wire.command.contains("'follow'"))
        assertEquals(0, wire.mutations)
    }

    @Test
    fun missingHistoryNeverSelectsAnotherSessionsConversation() = runBlocking {
        for (extra in
            listOf(
                "\"id\":\"other-session\"",
                "\"id\":\"deck-session\",\"ssh_host\":\"elsewhere\"",
            )) {
            val wire = FakeDeckTransport()
            val client = DeckClient(wire, "default", "")
            client.probe()
            var nativeAttempted = false
            wire.replyForCommand = { command ->
                when {
                    command.contains("'timeline' 'deck-session'") ->
                        DeckExecResult(
                            """{"success":false,"code":"NOT_FOUND","error":"no native transcript"}""",
                            "",
                            1,
                        )
                    command.contains("'session' 'show'") ->
                        DeckExecResult(
                            """{$extra,"tool":"codex","codex_session_id":"native-exact-identity"}""",
                            "",
                            0,
                        )
                    else -> {
                        nativeAttempted = true
                        null
                    }
                }
            }
            try {
                client.timeline("deck-session")
                error("Unrelated history must not be selected")
            } catch (expected: DeckCommandException) {
                assertEquals("NOT_FOUND", expected.code)
            }
            assertFalse(nativeAttempted)
            assertEquals(0, wire.mutations)
        }
    }
}
