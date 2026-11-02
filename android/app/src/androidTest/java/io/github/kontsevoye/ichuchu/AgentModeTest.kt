package io.github.kontsevoye.ichuchu

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kontsevoye.ichuchu.data.db.AppDatabase
import io.github.kontsevoye.ichuchu.model.AuthMethod
import io.github.kontsevoye.ichuchu.model.HostOpenMode
import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.service.agents.DeckClient
import io.github.kontsevoye.ichuchu.service.agents.SshDeckTransport
import io.github.kontsevoye.ichuchu.service.ssh.NativeSshService
import io.github.kontsevoye.ichuchu.ui.screens.agents.AgentScreen
import io.github.kontsevoye.ichuchu.ui.screens.agents.AgentViewModel
import io.github.kontsevoye.ichuchu.ui.theme.GhosttyThemeRegistry
import io.github.kontsevoye.ichuchu.ui.theme.IchuTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic Deck, actual JNI/SSH and Compose. Never connect this suite to a user's Mac. */
class AgentModeTest {
    @get:Rule val compose = createComposeRule()

    private fun requireFixture() =
        assumeTrue(InstrumentationRegistry.getArguments().getString("agentDeckFixture") == "true")

    private fun transport() = SshDeckTransport {
        NativeSshService(hostKeyPolicy = { true }).also {
            it.connect("10.0.2.2", 22229, "deck-fixture", AuthMethod.None, "")
        }
    }

    @Test
    fun processStdinEofSeparateStreamsExitCodeAndRekey() = runBlocking {
        requireFixture()
        val wire = transport()
        try {
            // Keep the prompt within the product limit while crossing multiple SSH packets.
            val bounded = "quote ' \$(no-shell)\nпроверка 世界 👋".repeat(1000).toByteArray()
            val result = wire.execute("fixture-streams", bounded)
            assertEquals(7, result.exitCode)
            assertTrue(result.stdout.contains("проверка 世界 👋"))
            assertFalse(result.stdout.contains("synthetic warning"))
            assertEquals("synthetic warning\n".repeat(1000), result.stderr)
            val flood = wire.execute("fixture-flood")
            assertEquals(9, flood.exitCode)
            assertEquals(128 * 8192, flood.stdout.length)
            assertEquals(128 * 128, flood.stderr.length)
        } finally {
            wire.interrupt()
        }
    }

    @Test
    fun idleProcessCancellationCompletesPromptly() = runBlocking {
        requireFixture()
        val wire = transport()
        val job = launch(Dispatchers.IO) { wire.follow("fixture-idle") {} }
        delay(500)
        wire.interrupt()
        withTimeout(5000) { job.cancelAndJoin() }
    }

    @Test
    fun deckProtocolRunsOverRealSsh() = runBlocking {
        requireFixture()
        val wire = transport()
        try {
            val deck = DeckClient(wire, "default", "/fixture/agent-deck")
            assertTrue(deck.probe().guardedSend)
            val session = deck.sessions().first()
            assertEquals("fixture-existing-full-id", session.id)
            assertTrue(deck.timeline(session.id).rows.any { it.kind == "edit" })
            val receipt = deck.send(session.id, "Synthetic JNI request 世界") {}
            assertTrue(receipt.toString().contains("landed_row_id"))
            assertEquals(emptyList<Any>(), deck.queue(session.id))
        } finally {
            wire.interrupt()
        }
    }

    @Test
    fun compactUiLoadsHistorySendsAndKeepsOfflineDraft() {
        requireFixture()
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as Application
        val db = AppDatabase.getInstance(app)
        val id = runBlocking {
            db.hostProfileDao()
                .insert(
                    HostProfile(
                        name = "Agent fixture Mac",
                        host = "10.0.2.2",
                        port = 22229,
                        username = "deck-fixture",
                        authMethod = AuthMethod.None,
                        openMode = HostOpenMode.Agents,
                        deckExecutable = "/fixture/agent-deck",
                    )
                )
        }
        val vm = AgentViewModel(app, id)
        try {
            val arguments = InstrumentationRegistry.getArguments()
            GhosttyThemeRegistry.init(app)
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides
                        Density(
                            density.density,
                            arguments.getString("agentFontScale", "1.0").toFloat(),
                        )
                ) {
                    IchuTheme(themeName = arguments.getString("agentTheme")) {
                        AgentScreen(vm, {}, {}, {})
                    }
                }
            }
            compose.waitUntil(30_000) { vm.hostKeyPrompt.value != null || vm.state.value.online }
            compose.runOnIdle { vm.respondToHostKey(true) }
            compose.waitUntil(30_000) { vm.state.value.online }
            capture("agent-sessions")
            compose.onNodeWithText("Fix draft recovery").performClick()
            compose.waitUntil(20_000) { vm.state.value.chat.timeline.rows.isNotEmpty() }
            capture("agent-chat")
            val prompt = "Synthetic UI request 世界 ${java.util.UUID.randomUUID()}"
            compose.onAllNodes(hasSetTextAction()).onLast().performTextInput(prompt)
            capture("agent-chat-composing")
            compose.onNodeWithText("send ↑").assertIsDisplayed().performClick()
            compose.waitUntil(20_000) {
                vm.state.value.chat.timeline.rows.any { it.body == prompt } &&
                    !vm.state.value.sending &&
                    vm.state.value.chat.draft.isEmpty()
            }
            compose
                .onAllNodes(hasSetTextAction())
                .onLast()
                .performTextInput("Keep this offline draft")
            runBlocking {
                val control = transport()
                try {
                    control.execute("fixture-drop-next-list")
                } finally {
                    control.interrupt()
                }
            }
            compose.waitUntil(15_000) { !vm.state.value.online }
            compose.waitUntil(30_000) { vm.state.value.online }
            assertEquals("Keep this offline draft", vm.state.value.chat.draft)
            compose.runOnIdle { vm.setForeground(false) }
            compose.waitUntil { !vm.state.value.online }
            compose.onNodeWithText("Keep this offline draft").assertExists()
            compose.onNodeWithText("send ↑").assertIsNotEnabled()
            compose.runOnIdle { vm.setForeground(true) }
            compose.waitUntil(30_000) { vm.state.value.online }
            assertEquals("Keep this offline draft", vm.state.value.chat.draft)
            val existingId = requireNotNull(vm.state.value.selectedId)
            compose.runOnIdle {
                repeat(12) { vm.updateDraft("Rapid draft $it") }
                vm.back()
                vm.openSession(existingId)
            }
            compose.waitUntil(10_000) {
                !vm.state.value.chatLoading && vm.state.value.chat.draft == "Rapid draft 11"
            }
            compose.runOnIdle {
                vm.back()
                vm.showCreate()
            }
            compose.onNodeWithText("create session").assertExists()
            capture("agent-create")
            compose.runOnIdle {
                vm.updateCreate(
                    vm.state.value.create.copy(
                        title = "Synthetic created session",
                        message = "Draft first task",
                    )
                )
                vm.create()
            }
            compose.waitUntil(30_000) {
                vm.state.value.selectedId?.startsWith("fixture-created-") == true &&
                    vm.state.value.chat.draft == "Draft first task"
            }
        } finally {
            compose.runOnIdle { vm.setForeground(false) }
            runBlocking {
                db.hostProfileDao().getById(id)?.let { db.hostProfileDao().deleteUnused(it) }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = java.io.File(instrumentation.targetContext.cacheDir, "$name.png")
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            file.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
