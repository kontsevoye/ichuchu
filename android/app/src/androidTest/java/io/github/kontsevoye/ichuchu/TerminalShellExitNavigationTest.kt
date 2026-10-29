package io.github.kontsevoye.ichuchu

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kontsevoye.ichuchu.model.AuthMethod
import io.github.kontsevoye.ichuchu.model.Transport
import io.github.kontsevoye.ichuchu.service.terminal.SessionStatus
import io.github.kontsevoye.ichuchu.service.terminal.TabSession
import io.github.kontsevoye.ichuchu.service.terminal.TabSpec
import io.github.kontsevoye.ichuchu.service.terminal.TerminalSessionRepository
import io.github.kontsevoye.ichuchu.ui.screens.Terminal.TerminalRouteExitEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Real SSH/Mosh -> engine -> repository -> route navigation, using isolated fixtures. */
class TerminalShellExitNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun activeShellExitSelectsRemainingTabAndLastExitNavigatesBack() = checkExit(closeActive = true)

    @Test
    fun backgroundShellExitKeepsTheActiveTabAndLastExitNavigatesBack() =
        checkExit(closeActive = false)

    @Test
    fun realMoshActiveExitPreservesOtherTabAndLastExitNavigatesBack() =
        checkExit(closeActive = true, transport = Transport.Mosh)

    @Test
    fun realMoshBackgroundExitPreservesActiveTabAndLastExitNavigatesBack() =
        checkExit(closeActive = false, transport = Transport.Mosh)

    private fun checkExit(closeActive: Boolean, transport: Transport = Transport.SSH) {
        assumeTrue(
            "Enable only with the synthetic SSH fixture",
            InstrumentationRegistry.getArguments()
                .getString(
                    if (transport == Transport.Mosh) "moshExitFixture" else "shellExitFixture"
                ) == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository =
            TerminalSessionRepository.getInstance(context.applicationContext as Application)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var backCount = 0
        compose.setContent {
            val tabs by repository.tabs.collectAsState()
            TerminalRouteExitEffect(
                hostId = 42,
                openLocalShell = false,
                hasTabs = tabs.isNotEmpty(),
                onBack = { backCount++ },
            )
        }
        fun openTab(): TabSession {
            lateinit var tab: TabSession
            compose.runOnIdle {
                tab =
                    repository.openTab(
                        TabSpec(
                            hostId = 42,
                            host = "10.0.2.2",
                            port = if (transport == Transport.Mosh) 22226 else 22225,
                            username = "synthetic-shell",
                            authMethod = AuthMethod.None,
                            transport = transport,
                        )
                    )
            }
            val hostKeyJob = scope.launch {
                tab.hostKeyPrompt.filterNotNull().collect { tab.engine.respondToHostKey(true) }
            }
            val connected = runBlocking {
                withTimeout(10_000) {
                    tab.sessionState.first {
                        it.status == SessionStatus.Connected || it.status == SessionStatus.Error
                    }
                }
            }
            hostKeyJob.cancel()
            assertEquals(connected.error, SessionStatus.Connected, connected.status)
            if (transport == Transport.Mosh) {
                runBlocking {
                    withTimeout(10_000) {
                        tab.sessionState.first { state ->
                            state.snapshot
                                ?.codepoints
                                ?.map { it.toChar() }
                                ?.joinToString("")
                                ?.contains("MOSH_FIXTURE_READY") == true
                        }
                    }
                }
            }
            return tab
        }
        try {
            compose.runOnIdle {
                assertEquals(emptyList<TabSession>(), repository.tabs.value)
                assertEquals(0, backCount) // The route must not pop before its first tab opens.
            }
            val first = openTab()
            val second = openTab()
            val closed = if (closeActive) second else first
            val remaining = if (closeActive) first else second
            closed.engine.writeText("\u0004")
            compose.waitUntil(6_000) { repository.tabs.value.none { it.id == closed.id } }
            compose.runOnIdle {
                assertEquals(listOf(remaining.id), repository.tabs.value.map { it.id })
                assertEquals(remaining.id, repository.activeTabId.value)
                assertEquals(SessionStatus.Connected, remaining.sessionState.value.status)
                assertEquals(0, backCount)
            }
            remaining.engine.writeText("exit\n")
            compose.waitUntil(6_000) { repository.tabs.value.isEmpty() && backCount == 1 }
            compose.runOnIdle { assertEquals(1, backCount) }
        } finally {
            compose.runOnIdle {
                repository.tabs.value.toList().forEach { repository.closeTab(it.id) }
            }
            scope.cancel()
        }
    }
}
