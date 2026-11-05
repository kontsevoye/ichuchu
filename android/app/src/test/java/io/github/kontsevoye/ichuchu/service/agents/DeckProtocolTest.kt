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
        assertEquals(
            AgentStatus.Stopped,
            session("1", "/a/repo", "stopped", """, "status_source":"cached"""").status,
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
    fun projectsIncludeEmptyAndNestedGroupsWithoutInventingDirectories() {
        val paths =
            deckGroupPaths(
                json(
                    """{"groups":[{"path":"active"},{"path":"empty","children":[{"path":"empty/child"}]}]}"""
                )
            )
        assertEquals(listOf("active", "empty", "empty/child"), paths)
        val sessions =
            listOf(DeckSession(json("""{"id":"live","path":"/repo","group":"active"}""")))
        val projects =
            mergeDeckProjects(
                listOf(
                    DeckProject("/repo", "active"),
                    DeckProject("/other", "empty"),
                    DeckProject("", "empty/child"),
                ),
                sessions,
            )
        assertEquals(3, projects.size)
        assertEquals("", projects.single { it.group == "empty/child" }.path)
        assertEquals(
            2,
            mergeDeckProjects(
                    listOf(DeckProject("/repo", "one"), DeckProject("/repo", "two")),
                    emptyList(),
                )
                .size,
        )
    }

    @Test
    fun notificationsBaselineDeduplicateReconnectAndIncludeEverySession() {
        fun sample(id: String, status: String, extra: String = "") =
            DeckSession(json("""{"id":"$id","tool":"claude","status":"$status"$extra}"""))
        val policy = AgentStatusChanges()
        val running = sample("a", "running")
        val ready = sample("a", "waiting", """, "substate":"idle-at-empty-prompt"""")
        val other = sample("b", "running")
        assertTrue(policy.accept(listOf(running, other)).isEmpty())
        assertEquals(listOf("a"), policy.accept(listOf(ready, other)).map { it.id })
        assertTrue(
            policy
                .accept(listOf(sample("a", "running", """, "status_source":"cached""""), other))
                .isEmpty()
        )
        assertTrue(policy.accept(listOf(ready, other)).isEmpty())
        assertTrue(policy.accept(listOf(sample("a", "waiting"), other)).isEmpty())
        assertEquals(
            listOf("b"),
            policy
                .accept(
                    listOf(ready, sample("b", "waiting", """, "substate":"interactive-menu""""))
                )
                .map { it.id },
        )
        assertEquals(listOf("a"), policy.accept(listOf(running)).map { it.id })
        assertEquals(listOf("a"), policy.accept(listOf(ready)).map { it.id })
        policy.reset()
        assertTrue(policy.accept(listOf(ready)).isEmpty())
    }

    @Test
    fun shortTurnsNotifyEvenWhenPollingNeverSeesRunningAndEventsDoNotReplay() {
        val ready =
            DeckSession(json("""{"id":"a","status":"waiting","substate":"idle-at-empty-prompt"}"""))
        val running = DeckSession(json("""{"id":"a","status":"running"}"""))
        fun event(turn: String) =
            json(
                """{"kind":"session.transition","session_id":"a","data":{"from_status":"running","to_status":"waiting","substate":"idle-at-empty-prompt","last_output_hash":"$turn"}}"""
            )
        val policy = AgentStatusChanges()
        policy.accept(listOf(ready))
        policy.startEventStream()
        assertEquals("a", policy.transition(event("turn-1"), listOf(ready))?.session?.id)
        assertNull(policy.transition(event("turn-1"), listOf(ready)))
        assertTrue(policy.accept(listOf(ready)).isEmpty())
        assertEquals("a", policy.transition(event("turn-2"), listOf(ready))?.session?.id)
        policy.accept(listOf(running))
        assertEquals(1, policy.accept(listOf(ready)).size)
        assertTrue(requireNotNull(policy.transition(event("turn-3"), listOf(ready))).updateExisting)
        val staleRead = policy.checkpoint()
        assertEquals("a", policy.transition(event("turn-4"), listOf(ready))?.session?.id)
        assertTrue(
            "A list request started before the event cannot undo it",
            policy.accept(listOf(running), staleRead).isEmpty(),
        )
        assertTrue(policy.accept(listOf(ready)).isEmpty())
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
    fun claudeKeepsDurableQueueAndCodexUsesNativeInputEvenWhenLinkedAndBusy() = runBlocking {
        val wire = FakeDeckTransport()
        val client = DeckClient(wire, "default", "agent-deck")
        client.probe()
        wire.reply = DeckExecResult("""{"send_id":"queued","state":"queued"}""", "", 0)
        val claude = DeckSession(json("""{"id":"claude","tool":"claude"}"""))
        assertEquals("queued", client.send(claude, "keep queue") {}.text("state"))
        assertTrue(wire.command.contains("'--queue'"))
        wire.reply =
            DeckExecResult(
                """{"success":true,"state":"submitted","delivery":"codex_native","submitted":true,"confirmation":"confirmed","settled":false}""",
                "",
                0,
            )
        for (nativeId in listOf("", "native-thread")) {
            val session =
                DeckSession(
                    json(
                        """{"id":"codex","tool":"codex","status":"running","codex_session_id":"$nativeId"}"""
                    )
                )
            val message = "literal $(touch sentinel) 世界"
            val send = DeckSend("local", message, client.send(session, message) {})
            assertEquals("submitted", send.state)
            assertFalse(send.uncertain)
            assertTrue(wire.command.contains("' '--send' '"))
            assertArrayEquals(message.toByteArray(), wire.stdin)
            assertFalse(wire.command.contains(message))
        }
        assertEquals(3, wire.mutations)
    }

    @Test
    fun codexNativeRefusalsAndLostResponsesNeverClaimDeliveryOrReplay() = runBlocking {
        val wire = FakeDeckTransport()
        val client = DeckClient(wire, "default", "agent-deck")
        client.probe()
        val session = DeckSession(json("""{"id":"fresh","tool":"codex","status":"waiting"}"""))
        wire.reply =
            DeckExecResult(
                """{"success":false,"state":"failed","reason":"draft present; no keys typed","settled":true}""",
                "",
                1,
            )
        assertEquals("failed", client.send(session, "prompt") {}.text("state"))
        for (state in listOf("typed", "unknown")) {
            wire.reply =
                DeckExecResult("""{"success":true,"state":"$state","settled":true}""", "", 0)
            assertTrue(DeckSend("local", "prompt", client.send(session, "prompt") {}).uncertain)
        }
        val before = wire.mutations
        wire.failure = IllegalStateException("connection lost after typing")
        try {
            client.send(session, "prompt") {}
            fail("Lost response must be unknown")
        } catch (_: IllegalStateException) {}
        assertEquals(before + 1, wire.mutations)
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

    @Test
    fun sessionActionsUseExactIdentityAndNeverPruneProjectFilesOrReplay() = runBlocking {
        val wire = FakeDeckTransport()
        val client = DeckClient(wire, "qa", "agent-deck")
        client.probe()
        val session = DeckSession(json("""{"id":"exact-id","tool":"codex"}"""))
        wire.reply = DeckExecResult("""{"success":true,"id":"exact-id"}""", "", 0)
        for (action in DeckSessionAction.entries) {
            val before = wire.executions
            assertTrue(client.sessionAction(session, action).flag("success"))
            assertEquals(before + 2, wire.executions)
            assertTrue(wire.command.contains("'-p' 'qa' 'session'"))
            assertTrue(wire.command.contains("'exact-id'"))
            assertFalse(wire.command.contains("prune-worktree"))
            assertFalse(wire.command.contains("--all"))
        }
        wire.reply = DeckExecResult("""{"id":"different","ssh_host":"remote"}""", "", 0)
        val before = wire.executions
        try {
            client.sessionAction(session, DeckSessionAction.Delete)
            fail("A changed identity must be refused before mutation")
        } catch (_: IllegalStateException) {}
        assertEquals(before + 1, wire.executions)
    }

    @Test
    fun contextUnknownAndOutOfRangeAreNeverInventedPercentages() = runBlocking {
        val wire = FakeDeckTransport()
        val client = DeckClient(wire, "qa", "agent-deck")
        client.probe()
        for (value in listOf("null", "-1", "101")) {
            wire.reply = DeckExecResult("""{"remaining":$value,"success":true}""", "", 0)
            assertEquals(null, client.contextRemaining("session"))
        }
        for (value in listOf(0, 73, 100)) {
            wire.reply = DeckExecResult("""{"remaining":$value,"success":true}""", "", 0)
            assertEquals(value, client.contextRemaining("session"))
        }
        assertEquals(0, wire.mutations)
    }

    @Test
    fun currentModelAndReasoningComeFromTheSameReadAsContext() = runBlocking {
        val wire = FakeDeckTransport()
        val client = DeckClient(wire, "qa", "agent-deck")
        client.probe()
        wire.reply =
            DeckExecResult(
                """{"success":true,"remaining":21,"model":"gpt-6-astra","effort":"max"}""",
                "",
                0,
            )
        val before = wire.executions
        assertEquals(
            DeckSessionContext(21, DeckModelSettings("gpt-6-astra", "max")),
            client.sessionContext("session"),
        )
        assertEquals(before + 1, wire.executions)
        wire.reply =
            DeckExecResult(
                """{"success":true,"remaining":null,"model":"gpt-6.1-sol","effort":"high"}""",
                "",
                0,
            )
        assertEquals(
            DeckSessionContext(null, DeckModelSettings("gpt-6.1-sol", "high")),
            client.sessionContext("session"),
        )
        wire.reply = DeckExecResult("""{"success":true,"remaining":73}""", "", 0)
        assertEquals(DeckSessionContext(73, null), client.sessionContext("session"))
        assertEquals(0, wire.mutations)
    }

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
    var executions = 0

    override suspend fun execute(
        command: String,
        stdin: ByteArray?,
        timeoutMs: Long,
        onStarted: suspend () -> Unit,
    ): DeckExecResult {
        this.command = command
        executions++
        this.stdin = stdin
        val deckSend =
            command.startsWith("exec '") &&
                command.contains("'send'") &&
                !command.contains("'--help'")
        if (deckSend || command.contains("' '--send' '")) {
            mutations++
        }
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
                        "-require-input-prompt -queue -message-file -no-tag -json -codex-composer-fallback -no-wait -defer-if-busy -defer-timeout -timeout"
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
