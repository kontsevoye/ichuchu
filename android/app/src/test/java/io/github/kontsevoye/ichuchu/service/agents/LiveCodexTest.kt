package io.github.kontsevoye.ichuchu.service.agents

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in acceptance against an installed Deck and authenticated, real Codex. */
class LiveCodexTest {
    @Test
    fun firstSendAutomaticHistoryReopenAndSecondSend() = runBlocking {
        val executable = System.getenv("ICHUCHU_LIVE_DECK").orEmpty()
        val profile = System.getenv("ICHUCHU_LIVE_DECK_PROFILE").orEmpty()
        val project = System.getenv("ICHUCHU_LIVE_DECK_PROJECT").orEmpty()
        assumeTrue("Live Codex acceptance is opt-in", executable.isNotBlank())
        require(profile.startsWith("ichuchu-liveqa-")) { "Use a disposable QA profile" }
        require(File(project).isDirectory && project.contains("/.plans/"))
        val wire = LocalDeckTransport()
        val client = DeckClient(wire, profile, executable)
        assertTrue(client.probe().codexComposerSend)
        val created =
            client.launch(
                DeckProject(project, "acceptance"),
                "codex",
                "Ichuchu live acceptance ${UUID.randomUUID()}",
                "",
                "",
                "",
            ) {}
        val id = created.text("id").ifBlank { created.text("session_id") }
        require(id.isNotBlank())
        val modelControl = System.getenv("ICHUCHU_LIVE_MODEL_CONTROL") == "1"
        val config =
            File(
                System.getenv("CODEX_HOME") ?: "${System.getProperty("user.home")}/.codex",
                "config.toml",
            )
        val configBefore = if (modelControl) config.readBytes() else byteArrayOf()
        var nativeId = ""
        try {
            delay(3000)
            for (turn in 1..2) {
                val wantedModel = if (turn == 1) "gpt-6.1-sol" else "gpt-6-luna"
                val wantedEffort = if (turn == 1) "low" else "high"
                if (modelControl) {
                    val available = client.modelSettings(id)
                    assertTrue(wantedModel in available.models)
                    assertTrue(wantedEffort in available.models.getValue(wantedModel))
                    assertEquals(
                        DeckModelSettings(wantedModel, wantedEffort),
                        client.modelSettings(id, wantedModel, wantedEffort).current,
                    )
                }
                val marker = "ICHUCHU_${turn}_${UUID.randomUUID().toString().replace("-", "")}"
                val message = "Reply with exactly $marker. Do not use tools."
                val session = client.sessions().single { it.id == id }
                val receipt = client.send(session, message) {}
                assertTrue(receipt.flag("submitted") || receipt.text("send_id").isNotBlank())
                val history =
                    withTimeout(90_000) {
                        var loaded: DeckTimeline? = null
                        while (loaded == null) {
                            val page =
                                try {
                                    client.timeline(id)
                                } catch (e: DeckCommandException) {
                                    if (e.code != "NOT_FOUND") throw e
                                    null
                                }
                            if (
                                page?.rows?.any { it.kind == "assistant" && marker in it.body } ==
                                    true
                            ) {
                                loaded = page
                            } else {
                                delay(1000)
                            }
                        }
                        loaded
                    }
                assertEquals(1, history.rows.count { it.kind == "user" && marker in it.body })
                assertTrue(
                    client
                        .sessions()
                        .single { it.id == id }
                        .raw
                        .text("codex_session_id")
                        .isNotBlank()
                )
                if (modelControl) {
                    val session = client.sessions().single { it.id == id }
                    val actualId = session.raw.text("codex_session_id")
                    if (nativeId.isNotBlank()) assertEquals(nativeId, actualId)
                    nativeId = actualId
                    val home = session.raw.text("resolved_codex_home").ifBlank { config.parent }
                    val rollout =
                        File(home, "sessions").walkTopDown().maxDepth(5).first {
                            it.name.endsWith("$nativeId.jsonl")
                        }
                    val context =
                        rollout
                            .useLines { lines ->
                                lines.map(::parseDeckObject).last {
                                    it.text("type") == "turn_context"
                                }
                            }["payload"]
                            as kotlinx.serialization.json.JsonObject
                    assertEquals(wantedModel, context.text("model"))
                    assertEquals(wantedEffort, context.text("effort"))
                    org.junit.Assert.assertArrayEquals(
                        "Session controls changed global defaults",
                        configBefore,
                        config.readBytes(),
                    )
                    println(
                        "PASS native turn_context: $wantedModel $wantedEffort; same conversation; defaults unchanged"
                    )
                }
                // A new client must recover persisted history by Deck ID without linkHistory().
                val reopened = DeckClient(wire, profile, executable)
                reopened.probe()
                assertTrue(
                    reopened.timeline(id).rows.any { it.kind == "assistant" && marker in it.body }
                )
                println(
                    "PASS real Codex turn $turn: exact single submission, automatic history, reopen"
                )
            }
        } finally {
            val removed =
                wire.execute(DeckCommands.command(executable, profile, "remove", id, "--json"))
            assertEquals(
                "Could not remove owned QA session: ${removed.stderr}",
                0,
                removed.exitCode,
            )
        }
    }
}

private class LocalDeckTransport : DeckTransport {
    override suspend fun execute(
        command: String,
        stdin: ByteArray?,
        timeoutMs: Long,
        onStarted: suspend () -> Unit,
    ): DeckExecResult =
        withContext(Dispatchers.IO) {
            onStarted()
            val process = ProcessBuilder("/bin/sh", "-c", command).start()
            try {
                coroutineScope {
                    val out =
                        async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
                    val err =
                        async(Dispatchers.IO) { process.errorStream.bufferedReader().readText() }
                    process.outputStream.use { if (stdin != null) it.write(stdin) }
                    if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly()
                        error("Live Deck command timed out")
                    }
                    DeckExecResult(out.await(), err.await(), process.exitValue())
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }

    override suspend fun follow(command: String, onFrame: suspend (String) -> Unit) =
        error("This acceptance test uses snapshots")

    override fun interrupt() = Unit
}
