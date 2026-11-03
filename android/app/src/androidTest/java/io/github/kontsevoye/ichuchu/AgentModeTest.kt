package io.github.kontsevoye.ichuchu

import android.app.Application
import android.os.SystemClock
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.test.performTextClearance
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

    /** Interact with the real Android document picker, without adding a mock picker to the app. */
    private fun clickPickerItem(label: String, timeout: Long = 10_000): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val end = android.os.SystemClock.uptimeMillis() + timeout
        while (android.os.SystemClock.uptimeMillis() < end) {
            val queue = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let(queue::add)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (
                    node.text?.toString() == label || node.contentDescription?.toString() == label
                ) {
                    var target: android.view.accessibility.AccessibilityNodeInfo? = node
                    while (target != null && !target.isClickable) target = target.parent
                    if (
                        target?.performAction(
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                        ) == true
                    ) {
                        return true
                    }
                }
                for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
            }
            android.os.SystemClock.sleep(100)
        }
        return false
    }

    @Test
    fun documentPickerUploadsImagesAndArbitraryFilesBeforeSending() {
        requireFixture()
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as Application
        val db = AppDatabase.getInstance(app)
        val id = runBlocking {
            db.hostProfileDao()
                .insert(
                    HostProfile(
                        name = "Attachment fixture Mac",
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
        fun choose(name: String) {
            compose.onNodeWithContentDescription("Attach photos or files").performClick()
            if (!clickPickerItem(name, 1000)) {
                assertTrue("Open picker roots", clickPickerItem("Show roots"))
                assertTrue("Open Downloads", clickPickerItem("Downloads"))
                assertTrue("Select $name", clickPickerItem(name))
            }
        }
        try {
            GhosttyThemeRegistry.init(app)
            val arguments = InstrumentationRegistry.getArguments()
            var imeBottom = 0
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides
                        Density(
                            density.density,
                            arguments.getString("agentFontScale", "1.0").toFloat(),
                        )
                ) {
                    imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
                    IchuTheme(themeName = arguments.getString("agentTheme")) {
                        AgentScreen(vm, {}, {}, {})
                    }
                }
            }
            compose.waitUntil(30_000) { vm.hostKeyPrompt.value != null || vm.state.value.online }
            compose.runOnIdle { vm.respondToHostKey(true) }
            compose.waitUntil(30_000) { vm.state.value.online }
            compose.onNodeWithText("Fix draft recovery").performClick()
            compose.waitUntil(20_000) { !vm.state.value.chatLoading }
            choose("Attachment screenshot.png")
            compose.waitUntil(30_000) {
                vm.state.value.chat.attachments.size == 1 && vm.state.value.upload == null
            }
            val image = vm.state.value.chat.attachments.single()
            assertEquals("image/png", image.mimeType)
            choose("Attachment data.bin")
            compose.waitUntil(30_000) {
                vm.state.value.chat.attachments.size == 2 && vm.state.value.upload == null
            }
            val binary = vm.state.value.chat.attachments.last()
            assertEquals(256L * 1024, binary.size)
            runBlocking {
                val wire = transport()
                try {
                    val info =
                        io.github.kontsevoye.ichuchu.service.agents.parseDeckObject(
                            wire
                                .execute(
                                    "fixture-upload-info " +
                                        io.github.kontsevoye.ichuchu.service.agents.DeckCommands
                                            .quote(binary.remotePath)
                                )
                                .stdout
                        )
                    val expected =
                        java.security.MessageDigest.getInstance("SHA-256")
                            .digest(ByteArray(256 * 1024) { (it % 251).toByte() })
                            .joinToString("") { "%02x".format(it) }
                    assertEquals(
                        expected,
                        (info["sha256"] as kotlinx.serialization.json.JsonPrimitive).content,
                    )
                } finally {
                    wire.interrupt()
                }
            }
            assertFalse(
                vm.state.value.chat.timeline.rows.any { it.body.contains(image.remotePath) }
            )
            choose("Attachment too large.bin")
            compose.waitUntil(20_000) {
                vm.state.value.upload == null && vm.state.value.notice?.contains("20 MiB") == true
            }
            assertEquals(2, vm.state.value.chat.attachments.size)
            compose.runOnIdle { vm.clearMessage() }
            compose
                .onNodeWithContentDescription("Remove attachment Attachment data.bin")
                .performClick()
            compose.waitUntil { vm.state.value.chat.attachments.size == 1 }
            choose("Attachment data.bin")
            compose.waitUntil(30_000) {
                vm.state.value.chat.attachments.size == 2 && vm.state.value.upload == null
            }
            val expectedFiles = vm.state.value.chat.attachments
            compose.runOnIdle { vm.disconnect() }
            compose.waitUntil { !vm.state.value.online }
            compose.onNodeWithText("send ↑").assertIsNotEnabled()
            compose.runOnIdle { vm.reconnect() }
            compose.waitUntil(30_000) { vm.state.value.online }
            val session = requireNotNull(vm.state.value.selectedId)
            compose.runOnIdle {
                vm.back()
                vm.openSession(session)
            }
            compose.waitUntil(20_000) {
                !vm.state.value.chatLoading && vm.state.value.chat.attachments.size == 2
            }
            assertEquals(expectedFiles, vm.state.value.chat.attachments)
            capture("agent-attachments-ready")
            compose
                .onAllNodes(hasSetTextAction())
                .onLast()
                .performTextInput("Screenshot and binary attachment")
            // Compose idleness does not mean the platform IME animation has finished.
            compose.waitUntil(5_000) { imeBottom > 0 }
            var previousBounds = emptyList<Rect>()
            var stableSince = SystemClock.uptimeMillis()
            compose.waitUntil(5_000) {
                val current =
                    listOf(
                        compose
                            .onNodeWithTag("agent_chat_history")
                            .fetchSemanticsNode()
                            .boundsInRoot,
                        compose
                            .onNodeWithTag("agent_chat_attachments")
                            .fetchSemanticsNode()
                            .boundsInRoot,
                        compose
                            .onNodeWithContentDescription("Remove attachment Attachment data.bin")
                            .fetchSemanticsNode()
                            .boundsInRoot,
                    )
                if (current != previousBounds) {
                    previousBounds = current
                    stableSince = SystemClock.uptimeMillis()
                }
                SystemClock.uptimeMillis() - stableSince >= 200
            }
            compose.onNodeWithText("send ↑").assertIsDisplayed()
            val sendBounds = compose.onNodeWithText("send ↑").fetchSemanticsNode().boundsInRoot
            assertTrue(
                "Send must retain its full touch target above the keyboard: $sendBounds",
                sendBounds.height >= 48 * app.resources.displayMetrics.density - 1,
            )
            val historyBounds =
                compose.onNodeWithTag("agent_chat_history").fetchSemanticsNode().boundsInRoot
            val fontScale =
                InstrumentationRegistry.getArguments().getString("agentFontScale", "1.0").toFloat()
            assertTrue(
                "Attachments and a multiline draft must leave at least one chat line above IME: $historyBounds",
                historyBounds.height >= 20 * fontScale * app.resources.displayMetrics.density - 1,
            )
            compose
                .onNodeWithContentDescription("Remove attachment Attachment data.bin")
                .assertIsDisplayed()
            val remove =
                compose
                    .onNodeWithContentDescription("Remove attachment Attachment data.bin")
                    .fetchSemanticsNode()
            assertTrue(
                "Current attachment removal remains fully visible after IME resize: ${remove.boundsInRoot} / ${remove.size}",
                remove.boundsInRoot.width >= remove.size.width - 1 &&
                    remove.boundsInRoot.height >= remove.size.height - 1,
            )
            capture("agent-attachments-composing")
            compose.onAllNodes(hasSetTextAction()).onLast().performTextClearance()
            compose.onNodeWithText("send ↑").assertIsDisplayed().performClick()
            compose.waitUntil(20_000) {
                vm.state.value.chat.attachments.isEmpty() &&
                    vm.state.value.chat.timeline.rows.any { row ->
                        expectedFiles.all { row.body.contains(it.remotePath) }
                    }
            }
            assertTrue(java.io.File(app.cacheDir, "agent-uploads").listFiles().orEmpty().isEmpty())
            capture("agent-attachments-sent")
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

    @Test fun newSessionHasStableEmptyChatAndAcceptsFirstMessage() = firstMessage("claude")

    @Test fun newCodexSessionAcceptsFirstMessageWithHostDefaults() = firstMessage("codex")

    @Test
    fun newCodexSessionAcceptsFirstMessageWithExplicitModelAndEffort() =
        firstMessage("codex", "fixture-model", "high")

    private fun firstMessage(tool: String, model: String = "", effort: String = "") = runBlocking {
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
                vm.updateCreate(
                    vm.state.value.create.copy(
                        title = "Empty transcript regression",
                        tool = tool,
                        model = model,
                        effort = effort,
                    )
                )
                vm.create()
            }
            compose.waitUntil(30_000) {
                vm.state.value.selectedId?.startsWith("fixture-created-") == true &&
                    !vm.state.value.creating
            }
            compose.waitUntil(20_000) {
                vm.state.value.historyMissing && !vm.state.value.chatLoading
            }
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
                val arrived =
                    vm.state.value.chat.timeline.rows.any {
                        it.body == "First message creates the native transcript"
                    }
                arrived || vm.state.value.chat.sends.any { it.state == "failed" }
            }
            assertTrue(
                "The first request must reach Codex, not fail in its queue: ${vm.state.value.chat.sends.map { it.receipt }}",
                vm.state.value.chat.timeline.rows.any {
                    it.body == "First message creates the native transcript"
                },
            )
        } finally {
            compose.runOnIdle {
                vm.disconnect()
                vm.setForeground(false)
            }
            db.hostProfileDao().getById(id)?.let { db.hostProfileDao().deleteUnused(it) }
        }
    }

    @Test
    fun queuedReceiptsSurviveNavigationAndReconnect() = runBlocking {
        requireFixture()
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as Application
        val db = AppDatabase.getInstance(app)
        val hostId =
            db.hostProfileDao()
                .insert(
                    HostProfile(
                        name = "Queue recovery fixture",
                        host = "10.0.2.2",
                        port = 22229,
                        username = "deck-fixture",
                        authMethod = AuthMethod.None,
                        openMode = HostOpenMode.Agents,
                        deckExecutable = "/fixture/agent-deck",
                    )
                )
        val vm = AgentViewModel(app, hostId)
        val control = transport()
        try {
            GhosttyThemeRegistry.init(app)
            compose.setContent { IchuTheme { AgentScreen(vm, {}, {}, {}) } }
            compose.waitUntil(30_000) { vm.hostKeyPrompt.value != null || vm.state.value.online }
            compose.runOnIdle { vm.respondToHostKey(true) }
            compose.waitUntil(30_000) {
                vm.state.value.online && vm.state.value.projects.isNotEmpty()
            }
            compose.runOnIdle {
                vm.showCreate()
                vm.updateCreate(
                    vm.state.value.create.copy(tool = "codex", title = "Queue recovery")
                )
                vm.create()
            }
            compose.waitUntil(30_000) {
                vm.state.value.selectedId?.startsWith("fixture-created-") == true &&
                    !vm.state.value.creating
            }
            val id = requireNotNull(vm.state.value.selectedId)
            control.execute("fixture-queue-seed $id")
            compose.runOnIdle { vm.refresh() }
            compose.waitUntil(15_000) { vm.state.value.chat.sends.size == 1 }
            control.execute("fixture-queue-pause $id")
            compose.runOnIdle { vm.refresh() }
            assertEquals("entered", control.execute("fixture-queue-wait $id").stdout.trim())
            // Leave while the remote queue read is in flight, then reopen its persisted chat.
            compose.runOnIdle { vm.back() }
            control.execute("fixture-queue-release $id")
            delay(1500)
            compose.runOnIdle { vm.openSession(id) }
            compose.waitUntil(15_000) {
                !vm.state.value.chatLoading && vm.state.value.chat.sends.isNotEmpty()
            }
            compose.waitForIdle()
            assertEquals(
                "One receipt per remote send after reopening",
                1,
                vm.state.value.chat.sends.size,
            )
            compose.onNodeWithText("Retain this undelivered request").assertIsDisplayed()
            compose.runOnIdle {
                vm.disconnect()
                vm.reconnect()
            }
            compose.waitUntil(30_000) { vm.state.value.online }
            delay(6000)
            assertEquals(1, vm.state.value.chat.sends.size)
        } finally {
            control.interrupt()
            compose.runOnIdle {
                vm.disconnect()
                vm.setForeground(false)
            }
            db.hostProfileDao().getById(hostId)?.let { db.hostProfileDao().deleteUnused(it) }
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
