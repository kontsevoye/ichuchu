package io.github.kontsevoye.ichuchu.service.agents

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentModelControlTest {
    @Test
    fun sessionModelsUseOneReadAndOmitUnknownSettings() = runBlocking {
        val fake = FakeDeckTransport()
        val client = DeckClient(fake, "default", "")
        client.probe()
        val calls = fake.executions
        fake.reply =
            DeckExecResult(
                """{"success":true,"sessions":[{"id":"a","model":"gpt-6.1-sol","effort":"high"},{"id":"b","model":"gpt-6-astra","effort":"max"},{"id":"unknown","model":""}]}""",
                "",
                0,
            )
        assertEquals(
            mapOf(
                "a" to DeckModelSettings("gpt-6.1-sol", "high"),
                "b" to DeckModelSettings("gpt-6-astra", "max"),
            ),
            client.sessionModels(),
        )
        assertTrue(fake.command.contains("'--models'"))
        assertEquals(calls + 1, fake.executions)
        assertEquals(0, fake.mutations)
        fake.reply = DeckExecResult("""{"success":true,"sessions":[]}""", "", 0)
        assertEquals(emptyMap<String, DeckModelSettings>(), client.sessionModels())
    }

    @Test
    fun unconfirmedControlIsNotReportedAsAppliedOrReplayed() = runBlocking {
        val fake = FakeDeckTransport()
        val client = DeckClient(fake, "default", "")
        client.probe()
        val calls = fake.executions
        fake.reply = DeckExecResult("""{"success":true,"model":"a","effort":"high"}""", "", 0)
        try {
            client.modelSettings("test-id", "a", "high")
            fail("A missing confirmation must fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("unconfirmed"))
        }
        assertTrue(fake.command.startsWith("exec python3 -c "))
        assertEquals(0, fake.mutations)
        assertEquals(calls + 1, fake.executions)
        fake.failure = java.io.IOException("connection lost")
        try {
            client.modelSettings("test-id", "a", "high")
            fail("A disconnected control must fail")
        } catch (expected: java.io.IOException) {
            assertEquals("connection lost", expected.message)
        }
        assertEquals(0, fake.mutations)
        assertEquals(calls + 2, fake.executions)
    }

    @Test
    fun remoteControllerRefusesBusyDraftAndUnknownStates() {
        val dir = kotlin.io.path.createTempDirectory("ichuchu-model-test-").toFile()
        try {
            for (name in listOf("agent_model_control.py", "model_control_test.py")) {
                File(dir, name)
                    .writeBytes(
                        checkNotNull(javaClass.getResourceAsStream("/$name")).use { it.readBytes() }
                    )
            }
            val run =
                ProcessBuilder("python3", File(dir, "model_control_test.py").path)
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start()
            val output = run.inputStream.bufferedReader().readText()
            assertEquals(output, 0, run.waitFor())
        } finally {
            dir.deleteRecursively()
        }
    }
}
