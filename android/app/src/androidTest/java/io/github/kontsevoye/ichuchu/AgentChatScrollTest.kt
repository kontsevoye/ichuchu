package io.github.kontsevoye.ichuchu

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kontsevoye.ichuchu.service.agents.AgentAttachment
import io.github.kontsevoye.ichuchu.service.agents.AgentChatState
import io.github.kontsevoye.ichuchu.service.agents.DeckCapabilities
import io.github.kontsevoye.ichuchu.service.agents.DeckRow
import io.github.kontsevoye.ichuchu.service.agents.DeckSession
import io.github.kontsevoye.ichuchu.service.agents.DeckTimeline
import io.github.kontsevoye.ichuchu.service.agents.parseDeckObject
import io.github.kontsevoye.ichuchu.ui.screens.agents.AgentChat
import io.github.kontsevoye.ichuchu.ui.screens.agents.AgentUiState
import io.github.kontsevoye.ichuchu.ui.theme.GhosttyThemeRegistry
import io.github.kontsevoye.ichuchu.ui.theme.IchuColors
import io.github.kontsevoye.ichuchu.ui.theme.IchuTheme
import kotlin.math.abs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Actual chat, live Compose layout and platform IME; all transcript content is synthetic. */
class AgentChatScrollTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var list: LazyListState
    private var keyboard: SoftwareKeyboardController? = null
    private var imeBottom = 0
    private var ui by mutableStateOf(AgentUiState())

    @Test
    fun memorySourcesExpandAndCollapseInsideTheRealChat() {
        val location =
            "rollout_summaries/2026-11-03T16-48-46-vwTp-chuchu_agent_session_recovery_v073.md:30–39"
        show(
            """Finished the session.
<oai-mem-citation>
<citation_entries>
MEMORY.md:116-119|note=[guarded sending and release verification workflow]
${location.replace('–', '-')}|note=[prior identity recovery and fixture coverage limits]
</citation_entries>
<rollout_ids>
01a1a250-2f3b-7e02-b336-177b8651f373
</rollout_ids>
</oai-mem-citation>"""
        )
        atEnd()
        compose.onNodeWithText("<oai-mem-citation>", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Memory sources · 2").performClick()
        compose.onNodeWithText(location).performScrollTo().assertIsDisplayed()
        capture("chat-memory-expanded")
        compose.onNodeWithContentDescription("Memory sources · 2").performScrollTo().performClick()
        compose.onNodeWithText(location).assertDoesNotExist()
        if (list.canScrollForward) compose.onNodeWithText("↓ latest").performClick()
        atEnd()
        capture("chat-memory-collapsed")
    }

    private fun row(id: Int, body: String = "Message $id. " + "Synthetic chat text. ".repeat(6)) =
        DeckRow(
            buildJsonObject {
                put("id", "message-$id")
                put("kind", "assistant")
                put("body", body)
            }
        )

    private fun show(lastBody: String = "End of the conversation.") {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        GhosttyThemeRegistry.init(context)
        ui =
            AgentUiState(
                online = true,
                capabilities =
                    DeckCapabilities(
                        "fixture",
                        "fixture",
                        true,
                        true,
                        emptyList(),
                        emptyList(),
                        emptySet(),
                        true,
                    ),
                selectedId = "chat",
                sessions =
                    listOf(
                        DeckSession(
                            parseDeckObject(
                                """{"id":"chat","tool":"codex","path":"/fixture/chuchu","status":"running"}"""
                            )
                        )
                    ),
                chat =
                    AgentChatState(
                        DeckTimeline((0..29).map { row(it) } + row(30, lastBody), null, null)
                    ),
            )
        val arguments = InstrumentationRegistry.getArguments()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides
                    Density(density.density, arguments.getString("agentFontScale", "1.0").toFloat())
            ) {
                keyboard = LocalSoftwareKeyboardController.current
                imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
                list = rememberLazyListState()
                IchuTheme(themeName = arguments.getString("agentTheme")) {
                    Column(
                        Modifier.fillMaxSize()
                            .background(IchuColors.current.background)
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .imePadding()
                    ) {
                        AgentChat(
                            state = ui,
                            onDraft = { ui = ui.copy(chat = ui.chat.copy(draft = it)) },
                            onSend = {},
                            onOlder = {},
                            onCancel = {},
                            onRecover = {},
                            onTerminal = {},
                            onRecall = {},
                            onChooseHistory = {},
                            onRetryHistory = {},
                            onAttach = {},
                            onRemoveAttachment = { id ->
                                ui =
                                    ui.copy(
                                        chat =
                                            ui.chat.copy(
                                                attachments =
                                                    ui.chat.attachments.filterNot { it.id == id }
                                            )
                                    )
                            },
                            onCancelUpload = {},
                            list = list,
                        )
                    }
                }
            }
        }
        compose.waitUntil(5_000) { ::list.isInitialized && list.layoutInfo.totalItemsCount > 20 }
        compose.waitForIdle()
    }

    private fun atEnd() {
        compose.waitUntil(3_000) { !list.canScrollForward }
        compose.runOnIdle {
            val layout = list.layoutInfo
            val last = layout.visibleItemsInfo.last()
            assertEquals(layout.totalItemsCount - 1, last.index)
            assertTrue(
                "Last message must end above the composer: $last / $layout",
                last.offset + last.size <= layout.viewportEndOffset - layout.afterContentPadding + 1,
            )
        }
    }

    private fun openKeyboard() {
        val previousHeight = list.layoutInfo.viewportSize.height
        compose.onAllNodes(hasSetTextAction()).onLast().performClick()
        compose.waitUntil(5_000) {
            imeBottom > 0 && list.layoutInfo.viewportSize.height < previousHeight
        }
        settledViewport()
        compose.waitForIdle()
    }

    private fun hideKeyboard() {
        compose.runOnIdle { keyboard?.hide() }
        compose.waitUntil(5_000) { imeBottom == 0 }
        settledViewport()
        compose.waitForIdle()
    }

    private fun settledViewport() {
        var height = -1
        var stableSince = SystemClock.uptimeMillis()
        compose.waitUntil(5_000) {
            val current = list.layoutInfo.viewportSize.height
            if (current != height) {
                height = current
                stableSince = SystemClock.uptimeMillis()
            }
            SystemClock.uptimeMillis() - stableSince >= 200
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val file = java.io.File(instrumentation.targetContext.cacheDir, "$name.png")
        requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
            file.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun append(id: Int) {
        compose.runOnIdle {
            ui =
                ui.copy(
                    chat =
                        ui.chat.copy(
                            timeline = ui.chat.timeline.copy(rows = ui.chat.timeline.rows + row(id))
                        )
                )
        }
        compose.waitForIdle()
    }

    private fun anchor(): Pair<Any, Int> =
        compose.runOnIdle {
            list.layoutInfo.visibleItemsInfo.first { it.index == list.firstVisibleItemIndex }.key to
                list.firstVisibleItemScrollOffset
        }

    private fun sameAnchor(expected: Pair<Any, Int>) {
        val actual = anchor()
        assertEquals("Keep the message being read", expected.first, actual.first)
        assertTrue(
            "Keep the pixel offset being read: $expected -> $actual",
            abs(expected.second - actual.second) <= 1,
        )
    }

    private fun bottomAnchor(): Pair<Any, Int> =
        compose.runOnIdle {
            val layout = list.layoutInfo
            val last = layout.visibleItemsInfo.last { it.offset < layout.viewportEndOffset }
            last.key to layout.viewportEndOffset - last.offset
        }

    private fun sameBottomAnchor(expected: Pair<Any, Int>) {
        val actual = bottomAnchor()
        assertEquals("Keep the message at the lower reading edge", expected.first, actual.first)
        assertTrue(
            "Keep the text at the lower reading edge: $expected -> $actual",
            abs(expected.second - actual.second) <= 1,
        )
    }

    private fun readEarlier() {
        compose.onNodeWithTag("agent_chat_history").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertTrue(list.canScrollForward)
    }

    @Test
    fun keyboardKeepsTheEndVisibleAndRestoresItOnClose() {
        show()
        atEnd()
        val originalHeight = list.layoutInfo.viewportSize.height
        val original = anchor()
        capture("scroll-bottom-before")
        openKeyboard()
        atEnd()
        capture("scroll-bottom-keyboard")
        hideKeyboard()
        assertEquals(originalHeight, list.layoutInfo.viewportSize.height)
        atEnd()
        sameAnchor(original)
        capture("scroll-bottom-restored")
    }

    @Test
    fun followsNewMessagesAndGrowthOfTheSameMessage() {
        show()
        atEnd()
        append(31)
        atEnd()
        compose.runOnIdle {
            ui =
                ui.copy(
                    chat =
                        ui.chat.copy(
                            timeline =
                                ui.chat.timeline.copy(
                                    rows =
                                        ui.chat.timeline.rows.dropLast(1) +
                                            row(31, "Streaming response. ".repeat(160))
                                )
                        )
                )
        }
        atEnd()
    }

    @Test
    fun initialLongLastMessageOpensAtItsEnd() {
        show("A long final response. ".repeat(160))
        atEnd()
    }

    @Test
    fun readingHistoryKeepsItsAnchorThroughUpdatesAndKeyboardRoundTrip() {
        show()
        atEnd()
        readEarlier()
        val reading = anchor()
        val bottom = bottomAnchor()
        capture("scroll-reading-before")
        append(31)
        sameAnchor(reading)
        openKeyboard()
        sameBottomAnchor(bottom)
        capture("scroll-reading-keyboard")
        append(32)
        sameBottomAnchor(bottom)
        hideKeyboard()
        sameBottomAnchor(bottom)
        sameAnchor(reading)
        capture("scroll-reading-restored")
        assertTrue(list.canScrollForward)
    }

    @Test
    fun scrollingAwayWhileTypingSuspendsFollowingUntilLatestIsTapped() {
        show()
        atEnd()
        openKeyboard()
        atEnd()
        readEarlier()
        val reading = anchor()
        val bottom = bottomAnchor()
        append(31)
        sameAnchor(reading)
        hideKeyboard()
        sameBottomAnchor(bottom)
        compose.onNodeWithText("↓ latest").performClick()
        atEnd()
        append(32)
        atEnd()
        assertFalse(list.canScrollForward)
    }

    @Test
    fun scrollingBackToTheEndResumesFollowingWithoutLatest() {
        show()
        atEnd()
        readEarlier()
        var attempts = 0
        while (list.canScrollForward && attempts++ < 8) {
            compose.onNodeWithTag("agent_chat_history").performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        atEnd()
        append(31)
        atEnd()
    }

    @Test
    fun streamingAndComposerChangesKeepTheEndWhileKeyboardIsOpen() {
        show()
        atEnd()
        openKeyboard()
        atEnd()
        repeat(5) { part ->
            compose.runOnIdle {
                ui =
                    ui.copy(
                        chat =
                            ui.chat.copy(
                                draft = "A multiline draft. ".repeat(part * 8),
                                timeline =
                                    ui.chat.timeline.copy(
                                        rows =
                                            ui.chat.timeline.rows.dropLast(1) +
                                                row(30, "Response chunk. ".repeat(20 + part * 30)),
                                        status =
                                            buildJsonObject { put("verb", "Working on step $part") },
                                    ),
                            )
                    )
            }
            atEnd()
        }
        hideKeyboard()
        atEnd()
    }

    @Test
    fun multipleAttachmentsShowCountAndNewestFileAndCanBeScrolledToRemove() {
        show()
        atEnd()
        openKeyboard()
        repeat(3) { index ->
            compose.runOnIdle {
                ui =
                    ui.copy(
                        chat =
                            ui.chat.copy(
                                attachments =
                                    ui.chat.attachments +
                                        AgentAttachment(
                                            "file-$index",
                                            "Screenshot-$index.png",
                                            "image/png",
                                            1024,
                                            "/fixture/file-$index",
                                        )
                            )
                    )
            }
            compose.waitForIdle()
            compose
                .onNodeWithContentDescription("Remove attachment Screenshot-$index.png")
                .assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("3 files attached").assertIsDisplayed()
        hideKeyboard()
        openKeyboard()
        val newest =
            compose
                .onNodeWithContentDescription("Remove attachment Screenshot-2.png")
                .fetchSemanticsNode()
        assertTrue(
            "Newest remove stays fully visible through IME resize: ${newest.boundsInRoot} / ${newest.size}",
            newest.boundsInRoot.width >= newest.size.width - 1 &&
                newest.boundsInRoot.height >= newest.size.height - 1,
        )
        capture("attachments-three-keyboard")
        compose.onNodeWithTag("agent_chat_attachments").performTouchInput { swipeRight() }
        compose.onNodeWithTag("agent_chat_attachments").performTouchInput { swipeRight() }
        compose.onNodeWithContentDescription("Remove attachment Screenshot-0.png").performClick()
        compose.onNodeWithContentDescription("2 files attached").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("file-1", "file-2"), ui.chat.attachments.map { it.id })
        }
        val sendBounds = compose.onNodeWithText("send ↑").fetchSemanticsNode().boundsInRoot
        val density =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .resources
                .displayMetrics
                .density
        assertTrue(
            "Send keeps its full touch target above IME",
            sendBounds.height >= 48 * density - 1,
        )
        hideKeyboard()
        compose.onNodeWithContentDescription("2 files attached").assertIsDisplayed()
        capture("attachments-two-restored")
    }

    @Test
    fun readingBottomSurvivesRepeatedKeyboardAndComposerResizes() {
        show()
        atEnd()
        readEarlier()
        val top = anchor()
        val bottom = bottomAnchor()
        compose.runOnIdle {
            ui =
                ui.copy(
                    chat =
                        ui.chat.copy(
                            attachments =
                                listOf(
                                    AgentAttachment(
                                        "file",
                                        "Screenshot.png",
                                        "image/png",
                                        1024,
                                        "/fixture/file",
                                    )
                                )
                        )
                )
        }
        settledViewport()
        sameBottomAnchor(bottom)
        val topWithFiles = anchor()
        repeat(3) {
            openKeyboard()
            sameBottomAnchor(bottom)
            compose.runOnIdle {
                ui = ui.copy(chat = ui.chat.copy(draft = "Multiline draft. ".repeat(30)))
            }
            settledViewport()
            sameBottomAnchor(bottom)
            append(31 + it)
            sameBottomAnchor(bottom)
            compose.runOnIdle { ui = ui.copy(chat = ui.chat.copy(draft = "")) }
            settledViewport()
            sameBottomAnchor(bottom)
            hideKeyboard()
            sameBottomAnchor(bottom)
            sameAnchor(topWithFiles)
            assertTrue(list.canScrollForward)
        }
        compose.runOnIdle { ui = ui.copy(chat = ui.chat.copy(attachments = emptyList())) }
        settledViewport()
        sameBottomAnchor(bottom)
        sameAnchor(top)
    }
}
