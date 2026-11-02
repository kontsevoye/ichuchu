package io.github.kontsevoye.ichuchu

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kontsevoye.ichuchu.data.db.AppDatabase
import io.github.kontsevoye.ichuchu.model.AuthMethod
import io.github.kontsevoye.ichuchu.model.HostOpenMode
import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.service.agents.AgentConnections
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
    fun hostMenuShowsAgentConnectionResumesChatAndDisconnects() {
        requireFixture()
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as Application
        val db = AppDatabase.getInstance(app)
        val profile =
            HostProfile(
                name = "Menu fixture Mac",
                host = "10.0.2.2",
                port = 22229,
                username = "deck-fixture",
                authMethod = AuthMethod.None,
                openMode = HostOpenMode.Agents,
                deckExecutable = "/fixture/agent-deck",
            )
        val id = runBlocking { db.hostProfileDao().insert(profile) }
        val other = profile.copy(name = "Other fixture Mac")
        val otherId = runBlocking { db.hostProfileDao().insert(other) }
        val connections = AgentConnections.get(app)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(10_000) {
                    compose
                        .onAllNodes(androidx.compose.ui.test.hasTestTag("host_connect_$id"))
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                compose
                    .onNodeWithTag("host_connect_$id")
                    .assertContentDescriptionEquals("Connect to ${profile.name}")
                    .performClick()
                val workspace = compose.runOnIdle { connections.workspace(id) }
                compose.waitUntil(30_000) {
                    workspace.hostKeyPrompt.value != null || workspace.state.value.online
                }
                compose.runOnIdle { workspace.respondToHostKey(true) }
                compose.waitUntil(30_000) { workspace.state.value.online }
                compose.onNodeWithText("Fix draft recovery").performClick()
                compose.waitUntil(20_000) { workspace.state.value.chat.timeline.rows.isNotEmpty() }
                compose.runOnIdle { workspace.updateDraft("Keep this draft when returning home") }
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.runOnIdle { assertTrue(connections.isActive(id)) }
                capture("agent-host-menu-connected")
                compose
                    .onNodeWithTag("host_connect_$id")
                    .assertContentDescriptionEquals("Disconnect from ${profile.name}")
                compose.onNodeWithText(profile.name).performClick()
                compose.onNodeWithText("Fix draft recovery").performClick()
                compose.waitUntil(20_000) { !workspace.state.value.chatLoading }
                assertEquals(
                    "Keep this draft when returning home",
                    workspace.state.value.chat.draft,
                )
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                // An unrelated host must stay connected when this one disconnects.
                compose.onNodeWithTag("host_connect_$otherId").performClick()
                val otherWorkspace = compose.runOnIdle { connections.workspace(otherId) }
                compose.waitUntil(30_000) {
                    otherWorkspace.hostKeyPrompt.value != null || otherWorkspace.state.value.online
                }
                compose.runOnIdle { otherWorkspace.respondToHostKey(true) }
                compose.waitUntil(30_000) { otherWorkspace.state.value.online }
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithTag("host_card_$id").performTouchInput { longClick() }
                compose.onNodeWithText("delete", substring = false).performClick()
                compose.runOnIdle { assertTrue(connections.isActive(id)) }
                assertTrue(runBlocking { db.hostProfileDao().getById(id) } != null)
                compose.onNodeWithText("cancel", substring = false).performClick()
                compose.onNodeWithTag("host_connect_$id").performClick()
                compose.onNodeWithText("Cancel").performClick()
                compose.runOnIdle { assertTrue(connections.isActive(id)) }
                compose.onNodeWithTag("host_card_$id").performTouchInput { swipeLeft() }
                compose
                    .onNodeWithText("Disconnects agent chats. Agents keep running on this host.")
                    .assertIsDisplayed()
                capture("agent-host-menu-disconnect")
                compose.onNodeWithText("Disconnect", substring = false).performClick()
                compose.waitUntil(10_000) {
                    !connections.isActive(id) && !workspace.state.value.online
                }
                compose
                    .onNodeWithTag("host_connect_$id")
                    .assertContentDescriptionEquals("Connect to ${profile.name}")
                compose.runOnIdle { assertTrue(connections.isActive(otherId)) }
                compose
                    .onNodeWithTag("host_connect_$otherId")
                    .assertContentDescriptionEquals("Disconnect from ${other.name}")
            }
        } finally {
            compose.runOnIdle {
                connections.disconnect(id)
                connections.disconnect(otherId)
            }
            runBlocking {
                for (hostId in listOf(id, otherId)) {
                    db.hostProfileDao().getById(hostId)?.let {
                        db.hostProfileDao().deleteUnused(it)
                    }
                }
            }
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
            val projects = deck.projects()
            assertEquals(4, projects.size)
            assertEquals(
                "/fixture/empty-project",
                projects.single { it.group == "Empty project" }.path,
            )
            assertEquals("", projects.single { it.group == "parent" }.path)
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
            compose.runOnIdle { vm.disconnect() }
            compose.waitUntil { !vm.state.value.online }
            compose.onNodeWithText("Keep this offline draft").assertExists()
            compose.onNodeWithText("send ↑").assertIsNotEnabled()
            compose.runOnIdle { vm.reconnect() }
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
            compose.waitUntil(20_000) {
                vm.state.value.projects.any { it.group == "Empty project" }
            }
            val selectedProject =
                vm.state.value.projects.first {
                    it.path == vm.state.value.create.path && it.group == vm.state.value.create.group
                }
            compose.onNodeWithText("${selectedProject.label}  ▾").performClick()
            capture("agent-project-picker")
            compose
                .onNodeWithText(
                    (if (selectedProject.group == "Empty project") "● " else "○ ") +
                        "Empty project · /fixture/empty-project"
                )
                .performClick()
            assertEquals("/fixture/empty-project", vm.state.value.create.path)
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
            assertEquals("/fixture/empty-project", vm.state.value.selected?.path)
        } finally {
            compose.runOnIdle {
                vm.disconnect()
                vm.setForeground(false)
            }
            runBlocking {
                db.hostProfileDao().getById(id)?.let { db.hostProfileDao().deleteUnused(it) }
            }
        }
    }

    @Test
    fun newSessionHasStableEmptyChatAndAcceptsFirstMessage() = runBlocking {
        requireFixture()
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as Application
        val db = AppDatabase.getInstance(app)
        val id =
            db.hostProfileDao()
                .insert(
                    HostProfile(
                        name = "Empty history fixture",
                        host = "10.0.2.2",
                        port = 22229,
                        username = "deck-fixture",
                        authMethod = AuthMethod.None,
                        openMode = HostOpenMode.Agents,
                        deckExecutable = "/fixture/agent-deck",
                    )
                )
        val vm = AgentViewModel(app, id)
        try {
            compose.runOnIdle { vm.setForeground(true) }
            compose.waitUntil(30_000) { vm.hostKeyPrompt.value != null || vm.state.value.online }
            compose.runOnIdle { vm.respondToHostKey(true) }
            compose.waitUntil(30_000) { vm.state.value.online }
            compose.runOnIdle {
                vm.showCreate()
                vm.updateCreate(vm.state.value.create.copy(title = "Empty transcript regression"))
                vm.create()
            }
            compose.waitUntil(30_000) {
                vm.state.value.selectedId?.startsWith("fixture-created-") == true &&
                    !vm.state.value.creating
            }
            delay(4000)
            assertTrue("New session must allow its first message", vm.state.value.canSend)
            assertEquals(
                "A missing first transcript is an empty chat, not a loading error",
                null,
                vm.state.value.chatError,
            )
            repeat(40) {
                assertFalse("Retry must not flash Loading history", vm.state.value.chatLoading)
                delay(100)
            }
            compose.runOnIdle {
                vm.updateDraft("First message creates the native transcript")
                vm.send()
            }
            compose.waitUntil(20_000) {
                vm.state.value.chat.timeline.rows.any {
                    it.body == "First message creates the native transcript"
                }
            }
        } finally {
            compose.runOnIdle {
                vm.disconnect()
                vm.setForeground(false)
            }
            db.hostProfileDao().getById(id)?.let { db.hostProfileDao().deleteUnused(it) }
        }
    }

    @Test
    fun codexHistoryLinksOnlyAfterExplicitSelection() = runBlocking {
        requireFixture()
        val wire = transport()
        try {
            val deck = DeckClient(wire, "default", "/fixture/agent-deck")
            deck.probe()
            val created =
                deck.launch(
                    io.github.kontsevoye.ichuchu.service.agents.DeckProject(
                        "/fixture/chuchu",
                        "work",
                    ),
                    "codex",
                    "Unlinked Codex regression",
                    "",
                    "",
                    "",
                ) {}
            val id = (created["session_id"] as kotlinx.serialization.json.JsonPrimitive).content
            val session = deck.sessions().single { it.id == id }
            try {
                deck.timeline(id)
                org.junit.Assert.fail(
                    "Unlinked history must not select another session by directory"
                )
            } catch (e: io.github.kontsevoye.ichuchu.service.agents.DeckCommandException) {
                assertEquals("NOT_FOUND", e.code)
            }
            val histories = deck.nativeHistories(session)
            assertEquals(1, histories.size)
            deck.linkHistory(session, histories.single())
            assertEquals("Exact linked Codex history", deck.timeline(id).rows.single().body)
        } finally {
            wire.interrupt()
        }
    }

    @Test
    fun activeConnectionNotifiesInBackgroundAndTapOpensExactChat() = runBlocking {
        requireFixture()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val db = AppDatabase.getInstance(app)
        val id =
            db.hostProfileDao()
                .insert(
                    HostProfile(
                        name = "Notification fixture Mac",
                        host = "10.0.2.2",
                        port = 22229,
                        username = "deck-fixture",
                        authMethod = AuthMethod.None,
                        openMode = HostOpenMode.Agents,
                        deckExecutable = "/fixture/agent-deck",
                    )
                )
        val vm = AgentViewModel(app, id)
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        val sessionId = "fixture-existing-full-id"
        val tag = "agent:$id:$sessionId"
        suspend fun change(status: String) {
            val control = transport()
            try {
                control.execute("fixture-status $sessionId $status")
            } finally {
                control.interrupt()
            }
        }
        try {
            compose.runOnIdle { vm.setForeground(true) }
            compose.waitUntil(30_000) { vm.hostKeyPrompt.value != null || vm.state.value.online }
            compose.runOnIdle { vm.respondToHostKey(true) }
            compose.waitUntil(30_000) { vm.state.value.online }
            change("running")
            compose.waitUntil(15_000) {
                vm.state.value.sessions.first { it.id == sessionId }.status ==
                    io.github.kontsevoye.ichuchu.service.agents.AgentStatus.Running
            }
            compose.runOnIdle { vm.openSession(sessionId) }
            change("ready")
            compose.waitUntil(15_000) { manager.activeNotifications.any { it.tag == tag } }
            // The currently open chat is included; another turn must also alert in the background.
            change("running")
            compose.waitUntil(15_000) { manager.activeNotifications.none { it.tag == tag } }
            compose.runOnIdle { vm.setForeground(false) }
            app.startActivity(
                android.content
                    .Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            assertTrue(vm.state.value.online)
            change("attention")
            withTimeout(20_000) {
                while (manager.activeNotifications.none { it.tag == tag }) delay(100)
            }
            val notification = manager.activeNotifications.single { it.tag == tag }
            assertTrue(
                notification.notification.extras
                    .getString(android.app.Notification.EXTRA_TITLE)
                    .orEmpty()
                    .contains("Input needed")
            )
            delay(6000)
            assertEquals(
                "Repeated polling cannot replay an alert",
                notification.postTime,
                manager.activeNotifications.single { it.tag == tag }.postTime,
            )
            compose.runOnIdle { vm.back() }
            notification.notification.contentIntent.send()
            compose.waitUntil(20_000) {
                vm.state.value.selectedId == sessionId && vm.state.value.live
            }
            compose.waitForIdle()
            assertTrue(vm.state.value.online)
            change("ready")
            compose.waitUntil(15_000) {
                vm.state.value.sessions.first { it.id == sessionId }.status ==
                    io.github.kontsevoye.ichuchu.service.agents.AgentStatus.Ready
            }
            manager.cancel(tag, 3002)
            // No poll observes Running: only the event bus can report these short turns.
            repeat(2) {
                val control = transport()
                try {
                    control.execute("fixture-fast-turn $sessionId")
                } finally {
                    control.interrupt()
                }
                compose.waitUntil(15_000) { manager.activeNotifications.any { it.tag == tag } }
                manager.cancel(tag, 3002)
            }
            // A second tap while MainActivity is already open exercises onNewIntent and
            // replacement of a navigation owner without dropping the shared connection.
            compose.runOnIdle { vm.back() }
            notification.notification.contentIntent.send()
            compose.waitUntil(20_000) {
                vm.state.value.selectedId == sessionId && vm.state.value.live
            }
            compose.waitForIdle()
            instrumentation.runOnMainSync { vm.disconnect() }
            withTimeout(10_000) {
                while (manager.activeNotifications.any { it.id == 3001 }) delay(100)
            }
            assertFalse(vm.state.value.online)
        } finally {
            instrumentation.runOnMainSync {
                vm.disconnect()
                vm.setForeground(false)
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .toList()
                    .filterIsInstance<MainActivity>()
                    .forEach { it.finish() }
            }
            change("ready")
            manager.cancel(tag, 3002)
            db.hostProfileDao().getById(id)?.let { db.hostProfileDao().deleteUnused(it) }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = java.io.File(instrumentation.targetContext.cacheDir, "$name.png")
        // Capture the actual display so platform dialogs use their own window coordinates.
        android.os.SystemClock.sleep(100)
        requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
            file.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
