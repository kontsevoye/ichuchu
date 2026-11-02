package io.github.kontsevoye.ichuchu.service.agents

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeckProtocolTest {
    @Test
    fun shellArgumentsRemainLiteralInShAndZsh() {
        val text = "quote' space\n\"\$HOME\" `printf unsafe` \$(printf unsafe); | & 世界"
        for (shell in listOf("/bin/sh", "/bin/zsh").filter { java.io.File(it).exists() }) {
            val command = DeckCommands.command("/usr/bin/printf", "default", "%s", text)
            // printf consumes -p/profile too; inspect shell tokenization via a fixed Python script.
            val probe =
                "exec python3 -c " +
                    DeckCommands.quote("import sys; print(repr(sys.argv[1]))") +
                    " " +
                    DeckCommands.quote(text)
            val process = ProcessBuilder(shell, "-c", probe).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor())
            assertTrue(output.contains("printf unsafe"))
            assertTrue(output.contains("\\n"))
            assertTrue(command.startsWith("exec '/usr/bin/printf' '-p' 'default'"))
        }
    }

    @Test
    fun splitUtf8AndCrlfAreDecodedOnlyAfterAWholeFrame() {
        val bytes = "{\"message\":\"проверка 世界 👋\"}\r\n\n{\"cursor\":\"opaque\"}\n".toByteArray()
        for (split in bytes.indices) {
            val decoder = DeckLineDecoder()
            val lines =
                decoder.feed(bytes.copyOfRange(0, split)) +
                    decoder.feed(bytes.copyOfRange(split, bytes.size))
            assertEquals(
                listOf("{\"message\":\"проверка 世界 👋\"}", "{\"cursor\":\"opaque\"}"),
                lines,
            )
            assertNull(decoder.finish())
        }
    }

    @Test
    fun malformedUtf8AndOversizedFramesFail() {
        assertThrows(Exception::class.java) {
            DeckLineDecoder().feed(byteArrayOf(0xc3.toByte(), 10))
        }
        assertThrows(IllegalStateException::class.java) {
            DeckLineDecoder(4).feed("12345".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) { DeckCommands.quote("bad\u0000value") }
    }

    @Test
    fun stableIdsMergeUpdatesRemoveRowsAndNeverDeduplicateRepeatedPrompts() {
        val reducer = DeckTimelineReducer()
        reducer.snapshot(
            page(
                """{"id":"a","kind":"user","body":"again","queued":true,"meta":{"source":"native"}},{"id":"b","kind":"user","body":"again"}""",
                "first",
            )
        )
        reducer.apply(
            listOf(
                json(
                    """{"frame":"update","row":{"id":"a","queued":false,"ts":"2026-11-02","meta":{"delivery":"absorbed"}}}"""
                ),
                json("""{"frame":"update","row":{"id":"missing","body":"ignore me"}}"""),
                json(
                    """{"frame":"row","row":{"id":"c","kind":"future-kind","body":"visible"},"cursor":"next"}"""
                ),
            )
        )
        assertEquals(listOf("b", "a", "c"), reducer.value().rows.map { it.id })
        assertEquals("again", reducer.value().rows[1].body)
        assertEquals("native", reducer.value().rows[1].meta.text("source"))
        assertEquals("next", reducer.cursor)
        reducer.apply(listOf(json("""{"frame":"remove","id":"a","cursor":"final"}""")))
        reducer.prepend(
            page("""{"id":"old","kind":"assistant"},{"id":"b","body":"stale"}""", "old-cursor")
        )
        assertEquals(listOf("old", "b", "c"), reducer.value().rows.map { it.id })
        assertEquals("again", reducer.value().rows[1].body)
        assertEquals("final", reducer.cursor)
    }

    @Test
    fun statusDoesNotInventCompletionAndProjectsUseFullPaths() {
        fun session(id: String, path: String, status: String, extra: String = "") =
            DeckSession(
                json("""{"id":"$id","path":"$path","status":"$status","tool":"codex"$extra}""")
            )
        assertEquals(AgentStatus.Waiting, session("1", "/a/repo", "idle").status)
        assertEquals(
            AgentStatus.Ready,
            session("1", "/a/repo", "waiting", """, "substate":"idle-at-empty-prompt"""").status,
        )
        assertEquals(
            AgentStatus.Attention,
            session("1", "/a/repo", "idle", """, "substate":"interactive-menu"""").status,
        )
        assertEquals(
            AgentStatus.Unknown,
            session("1", "/a/repo", "running", """, "status_source":"cached"""").status,
        )
        val sessions =
            listOf(
                session("1", "/a/repo", "idle"),
                session("2", "/b/repo", "running"),
                session("3", "/a/repo", "idle"),
                session("4", "/remote/repo", "idle", """, "ssh_host":"elsewhere""""),
            )
        assertEquals(listOf("/a/repo", "/b/repo"), knownDeckProjects(sessions).map { it.path })
    }

    @Test
    fun nonzeroSendWithReceiptIsKeptAndPromptOnlyTravelsInStdin() = runBlocking {
        val wire = FakeDeckTransport()
        val client = DeckClient(wire, "default", "/fixture/agent-deck")
        client.probe()
        val prompt = "don't execute \$(touch sentinel)\n世界"
        wire.reply =
            DeckExecResult(
                """{"success":false,"code":"DELIVERY_FAILED","send_id":"receipt","state":"failed","verdict":"unknown"}""",
                "warning",
                1,
            )
        var persisted = false
        val receipt = client.send("exact-id", prompt) { persisted = true }
        assertTrue(persisted)
        assertEquals("receipt", receipt.text("send_id"))
        assertEquals("failed", receipt.text("state"))
        assertFalse(DeckSend("local", prompt, receipt).uncertain)
        assertArrayEquals(prompt.toByteArray(), wire.stdin)
        assertFalse(wire.command.contains(prompt))
        assertTrue(wire.command.contains("'--require-input-prompt'"))
        assertTrue(wire.command.contains("'--message-file' '-'"))
        assertEquals(1, wire.mutations)
    }

    @Test
    fun unknownCancellationIsNotReportedAsCancelledAndLostResponseIsNotRetried() = runBlocking {
        val wire = FakeDeckTransport()
        val client = DeckClient(wire, "default", "agent-deck")
        client.probe()
        wire.reply = DeckExecResult("""{"id":"missing","outcome":"not_found"}""", "", 0)
        assertEquals("not_found", client.cancelSend("missing").text("outcome"))
        wire.failure = IllegalStateException("response lost")
        try {
            client.send("session", "prompt") {}
            fail("Must fail")
        } catch (_: IllegalStateException) {}
        assertEquals(1, wire.mutations)
        assertTrue(DeckSend("local", "prompt", json("{} ")).uncertain)
        assertTrue(
            DeckSend("local", "prompt", json("""{"state":"submitted","settled":true}""")).final
        )
    }

    @Test
    fun oldGuardlessVersionStaysReadOnlyAndUnknownTranscriptSchemaFails() = runBlocking {
        val wire = FakeDeckTransport(guard = false)
        val client = DeckClient(wire, "default", "agent-deck")
        assertFalse(client.probe().guardedSend)
        try {
            client.send("session", "prompt") {}
            fail("Must reject")
        } catch (_: IllegalStateException) {}
        assertEquals(0, wire.mutations)
        assertThrows(IllegalArgumentException::class.java) {
            DeckTimeline.parse(json("""{"schema":"future/v99","turns":[]}"""))
        }
        Unit
    }

    private fun json(value: String): JsonObject = parseDeckObject(value)

    private fun page(rows: String, cursor: String) =
        DeckTimeline.parse(
            json(
                """{"schema":"agent-deck.recall.rows/v2","turns":[$rows],"through_cursor":"$cursor","before_cursor":"older"}"""
            )
        )
}

internal class FakeDeckTransport(private val guard: Boolean = true) : DeckTransport {
    var reply: DeckExecResult? = null
    var failure: Exception? = null
    var stdin: ByteArray? = null
    var command = ""
    var mutations = 0

    override suspend fun execute(
        command: String,
        stdin: ByteArray?,
        timeoutMs: Long,
        onStarted: suspend () -> Unit,
    ): DeckExecResult {
        this.command = command
        this.stdin = stdin
        if (command.contains("'send'") && !command.contains("'--help'")) mutations++
        onStarted()
        failure?.let { throw it }
        reply?.let {
            return it
        }
        val output =
            when {
                command.startsWith("command -v") -> "/fixture/agent-deck"
                command.contains("'version'") -> "Agent Deck v1.16.35"
                command.contains("'--capabilities'") ->
                    """{"version":1,"commands":{"launch":[{"name":"no-parent"},{"name":"title"},{"name":"model"},{"name":"group"}]},"tools":[{"name":"claude","models":["fixture-model"]},{"name":"codex","models":null}],"accounts":[]}"""
                command.contains("'--help'") ->
                    if (guard) {
                        "-require-input-prompt -queue -message-file -no-tag -json"
                    } else {
                        "-message-file -json"
                    }
                command.contains("'harness'") ->
                    """[{"name":"claude","installed":true},{"name":"codex","installed":false}]"""
                command.contains("'config'") -> """{"key":"recall.enabled","value":true}"""
                command.contains("'stats'") -> """{"enabled":true,"cursor":42}"""
                else -> "[]"
            }
        return DeckExecResult(output, "synthetic warning, separate stream", 0)
    }

    override suspend fun follow(command: String, onFrame: suspend (String) -> Unit) = Unit

    override fun interrupt() = Unit
}
