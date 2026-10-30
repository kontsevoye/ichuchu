package io.github.kontsevoye.ichuchu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.ui.screens.ServerList.ServerListScreen
import io.github.kontsevoye.ichuchu.ui.theme.IchuTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ServerListInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val feedback = mutableListOf<HapticFeedbackType>()
    private val haptics =
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                feedback += hapticFeedbackType
            }
        }

    @Composable
    private fun WithRecordedHaptics(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalHapticFeedback provides haptics) { IchuTheme { content() } }
    }

    @Test
    fun dragReordersBothWaysAndFilterDisablesDrag() {
        val hosts =
            mutableStateOf(
                (1L..5L).map {
                    HostProfile(
                        id = it,
                        name = "Host $it",
                        host = "fixture.internal",
                        username = "fixture",
                    )
                }
            )
        val query = mutableStateOf("")
        var saved = emptyList<Long>()
        compose.setContent {
            WithRecordedHaptics {
                ServerListScreen(
                    hosts = hosts.value,
                    searchQuery = query.value,
                    onSearchChange = { query.value = it },
                    onAddServer = {},
                    localShellEnabled = true,
                    onOpenLocalShell = {},
                    onEditServer = {},
                    onConnectServer = {},
                    onDeleteServer = {},
                    onOpenSettings = {},
                    onReorderHosts = { ids ->
                        saved = ids
                        val byId = hosts.value.associateBy { it.id }
                        hosts.value = ids.map { byId.getValue(it) }
                        true
                    },
                )
            }
        }
        compose.onNodeWithTag("host_reorder_1", useUnmergedTree = true).performTouchInput {
            down(center)
            advanceEventTime(650)
            repeat(30) { moveBy(Offset(0f, 15f), delayMillis = 30) }
            up()
        }
        compose.waitUntil(5000) { saved.isNotEmpty() }
        assertTrue("first host moved down: $saved", saved.indexOf(1) > 0)
        assertEquals(listOf(HapticFeedbackType.LongPress), feedback)
        val orderAfterDown = saved
        saved = emptyList()
        compose.onNodeWithTag("host_reorder_1", useUnmergedTree = true).performTouchInput {
            down(center)
            advanceEventTime(650)
            repeat(30) { moveBy(Offset(0f, -15f), delayMillis = 30) }
            up()
        }
        compose.waitUntil(5000) { saved.isNotEmpty() }
        assertTrue("first host moved back up: $saved", saved.indexOf(1) < orderAfterDown.indexOf(1))
        assertEquals(List(2) { HapticFeedbackType.LongPress }, feedback)
        val beforeCancel = hosts.value.map { it.id }
        saved = emptyList()
        compose.onNodeWithTag("host_reorder_1", useUnmergedTree = true).performTouchInput {
            down(center)
            advanceEventTime(650)
            repeat(10) { moveBy(Offset(0f, 15f), delayMillis = 30) }
            cancel()
        }
        compose.runOnIdle {
            assertEquals(List(3) { HapticFeedbackType.LongPress }, feedback)
            assertEquals(emptyList<Long>(), saved)
            assertEquals(beforeCancel, hosts.value.map { it.id })
            query.value = "fixture"
        }
        compose.onNodeWithTag("host_reorder_1", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun draggingAtBottomScrollsPastInitiallyVisibleHosts() {
        val hosts =
            mutableStateOf(
                (1L..20L).map {
                    HostProfile(
                        id = it,
                        name = "Host $it",
                        host = "fixture.internal",
                        username = "fixture",
                    )
                }
            )
        var saved = emptyList<Long>()
        compose.setContent {
            WithRecordedHaptics {
                ServerListScreen(
                    hosts = hosts.value,
                    searchQuery = "",
                    onSearchChange = {},
                    onAddServer = {},
                    localShellEnabled = true,
                    onOpenLocalShell = {},
                    onEditServer = {},
                    onConnectServer = {},
                    onDeleteServer = {},
                    onOpenSettings = {},
                    onReorderHosts = { ids ->
                        saved = ids
                        val byId = hosts.value.associateBy { it.id }
                        hosts.value = ids.map { byId.getValue(it) }
                        true
                    },
                )
            }
        }
        val handle = compose.onNodeWithTag("host_reorder_1", useUnmergedTree = true)
        val handleBounds = handle.fetchSemanticsNode().boundsInRoot
        val listBounds = compose.onNodeWithTag("host_list").fetchSemanticsNode().boundsInRoot
        val distance = listBounds.bottom - 30f - handleBounds.center.y
        compose.mainClock.autoAdvance = false
        try {
            handle.performTouchInput {
                down(center)
                advanceEventTime(650)
                repeat(40) { moveBy(Offset(0f, distance / 40), delayMillis = 20) }
            }
            compose.mainClock.advanceTimeBy(3000)
            compose.onRoot().performTouchInput { up() }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitUntil(5000) { saved.isNotEmpty() }
        assertTrue("Auto scroll moves beyond the first screen: $saved", saved.indexOf(1) > 5)
    }

    @Test
    fun holdingHostSelectsItWithOneHaptic() {
        compose.setContent {
            WithRecordedHaptics {
                ServerListScreen(
                    hosts =
                        listOf(
                            HostProfile(
                                id = 1,
                                name = "Host",
                                host = "fixture",
                                username = "fixture",
                            )
                        ),
                    searchQuery = "",
                    onSearchChange = {},
                    onAddServer = {},
                    localShellEnabled = false,
                    onOpenLocalShell = {},
                    onEditServer = {},
                    onConnectServer = {},
                    onDeleteServer = {},
                    onOpenSettings = {},
                )
            }
        }
        compose.onNodeWithTag("host_card_1").performTouchInput { longClick() }
        compose.onNodeWithText("delete").assertExists()
        compose.runOnIdle { assertEquals(listOf(HapticFeedbackType.LongPress), feedback) }
    }
}
