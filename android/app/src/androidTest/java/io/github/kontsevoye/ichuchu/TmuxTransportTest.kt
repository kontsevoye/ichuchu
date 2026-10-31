package io.github.kontsevoye.ichuchu

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kontsevoye.ichuchu.data.db.AppDatabase
import io.github.kontsevoye.ichuchu.model.AuthMethod
import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.model.MultiplexerType
import io.github.kontsevoye.ichuchu.model.Transport
import io.github.kontsevoye.ichuchu.service.terminal.SessionStatus
import io.github.kontsevoye.ichuchu.service.terminal.TabSession
import io.github.kontsevoye.ichuchu.service.terminal.TabSpec
import io.github.kontsevoye.ichuchu.service.terminal.TerminalSessionRepository
import io.github.kontsevoye.ichuchu.ui.screens.AddServer.AddServerScreen
import io.github.kontsevoye.ichuchu.ui.screens.AddServer.AddServerViewModel
import io.github.kontsevoye.ichuchu.ui.screens.Terminal.TerminalViewModel
import io.github.kontsevoye.ichuchu.ui.theme.IchuTheme
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Only for the disposable emulator and loopback tmux_transport_server.py fixture. */
class TmuxTransportTest {
    @get:Rule val compose = createComposeRule()

    private fun requireFixture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tmuxFixture") == "true")
    }

    @Test
    fun tmuxButtonPersistsAcrossSshAndMoshProfiles() {
        requireFixture()
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as Application
        val dao = AppDatabase.getInstance(app).hostProfileDao()
        val id = runBlocking {
            dao.insert(
                HostProfile(
                    name = "tmux-fixture-editor",
                    host = "10.0.2.2",
                    port = 22228,
                    username = "tmux-fixture",
                    transport = Transport.Mosh,
                    authMethod = AuthMethod.None,
                )
            )
        }
        val store = ViewModelStore()
        val vm =
            ViewModelProvider(store, AddServerViewModel.factory(app, id))[
                AddServerViewModel::class.java]
        try {
            compose.waitUntil(5_000) { vm.form.value.id == id }
            compose.setContent { IchuTheme { AddServerScreen(vm, {}) } }
            compose.onNodeWithText("additional settings").performScrollTo().performClick()
            compose.onNodeWithText("tmux").performScrollTo().assertIsEnabled().performClick()
            assertEquals(MultiplexerType.Tmux, vm.form.value.multiplexer)
            compose.runOnIdle { vm.updateTransport(Transport.SSH) }
            assertEquals(MultiplexerType.Tmux, vm.form.value.multiplexer)
            compose.runOnIdle { vm.updateTransport(Transport.Mosh) }
            assertEquals(MultiplexerType.Tmux, vm.form.value.multiplexer)
            var saved = false
            compose.runOnIdle { vm.save { saved = true } }
            compose.waitUntil(5_000) { saved }
            val profile = runBlocking { dao.getById(id)!! }
            assertEquals(Transport.Mosh, profile.transport)
            assertEquals(MultiplexerType.Tmux, profile.multiplexer)
            assertTrue(TabSpec.fromHostProfile(profile).usesRuntimeMultiplexer)
        } finally {
            compose.runOnIdle { store.clear() }
            runBlocking { dao.getById(id)?.let { dao.delete(it) } }
        }
    }

    @Test
    fun sshActionsCreateListSwitchAndReconnectRealTmux() = runBlocking { exercise(Transport.SSH) }

    @Test
    fun moshActionsCreateListSwitchAndReconnectRealTmux() = runBlocking { exercise(Transport.Mosh) }

    private suspend fun exercise(transport: Transport) = coroutineScope {
        requireFixture()
        val app =
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                as Application
        val repository = TerminalSessionRepository.getInstance(app)
        val store = ViewModelStore()
        val vm =
            withContext(Dispatchers.Main) {
                ViewModelProvider(
                    store,
                    ViewModelProvider.AndroidViewModelFactory.getInstance(app),
                )[TerminalViewModel::class.java]
            }
        val prompts = launch {
            vm.hostKeyPrompt.filterNotNull().collect { vm.onHostKeyDecision(true) }
        }
        val opened = mutableListOf<TabSession>()
        val spec =
            TabSpec(
                host = "10.0.2.2",
                port = 22228,
                username = "tmux-fixture",
                authMethod = AuthMethod.None,
                transport = transport,
                multiplexer = MultiplexerType.Tmux,
                multiplexerSessionName = "ichuchu-${(100_000..999_999).random()}",
                postConnectCommand =
                    "if [ -n \"\$TMUX\" ]; then printf '%s%s\\n' 'TMUX_' 'POST_CONNECT'; fi",
            )
        try {
            withContext(Dispatchers.Main) { vm.initiateMultiplexerOpen(spec) }
            val first = awaitTab(repository, vm, opened.map { it.id })
            opened += first
            awaitOutput(first, "TMUX_POST_CONNECT")
            assertEquals(transport, first.spec.transport)
            val firstName = first.spec.multiplexerSessionName!!
            val token = UUID.randomUUID().toString().replace("-", "")
            first.engine.writeText(
                "export ICHUCHU_FIXTURE_TOKEN=$token; printf '%s%s\\n' 'SET_' '$token'\n"
            )
            awaitOutput(first, "SET_$token")
            refreshSessions(vm, firstName)

            withContext(Dispatchers.Main) { vm.duplicateActiveTab() }
            val second = awaitTab(repository, vm, opened.map { it.id })
            opened += second
            awaitOutput(second, "TMUX_POST_CONNECT")
            val secondName = second.spec.multiplexerSessionName!!
            assertNotEquals(firstName, secondName)
            assertEquals(transport, second.spec.transport)
            refreshSessions(vm, firstName)
            assertTrue(vm.multiplexerState.value.sessions.any { it.name == secondName })

            awaitReconnect(second) { vm.switchToMultiplexerSession(firstName, second.id) }
            assertEquals(firstName, second.spec.multiplexerSessionName)
            assertEquals(transport, second.spec.transport)
            checkPersistedVariable(second, token)

            // The reconnect button must return to the chosen tmux session.
            awaitReconnect(second) { vm.reconnect() }
            checkPersistedVariable(second, token)

            // Detach both clients, then use the normal open action to reattach.
            withContext(Dispatchers.Main) { opened.forEach { repository.closeTab(it.id) } }
            opened.forEach { tab ->
                withTimeout(5_000) {
                    tab.sessionState.first { it.status == SessionStatus.Disconnected }
                }
            }
            withContext(Dispatchers.Main) {
                vm.initiateMultiplexerOpen(
                    spec.copy(
                        multiplexerSessionName = firstName,
                        multiplexerCreateIfMissing = false,
                    )
                )
            }
            val reattached = awaitTab(repository, vm, emptyList())
            opened += reattached
            awaitOutput(reattached, "TMUX_POST_CONNECT")
            checkPersistedVariable(reattached, token)
        } finally {
            withContext(Dispatchers.Main) {
                opened.forEach { repository.closeTab(it.id) }
                store.clear()
            }
            prompts.cancel()
        }
    }

    private suspend fun awaitTab(
        repository: TerminalSessionRepository,
        vm: TerminalViewModel,
        previousIds: List<String>,
    ): TabSession =
        withTimeout(20_000) {
            while (true) {
                vm.multiplexerState.value.preflightError?.let { error(it) }
                val tab = repository.activeTab.value
                if (tab != null && tab.id !in previousIds) return@withTimeout tab
                delay(20)
            }
            error("No active tab")
        }

    private suspend fun refreshSessions(vm: TerminalViewModel, expected: String) {
        withContext(Dispatchers.Main) { vm.listMultiplexerSessionsForCurrentHost() }
        val state = withTimeout(10_000) { vm.multiplexerState.first { !it.sessionsLoading } }
        assertEquals(state.sessionsError, null, state.sessionsError)
        assertTrue(state.sessions.toString(), state.sessions.any { it.name == expected })
    }

    private suspend fun awaitReconnect(tab: TabSession, action: () -> Unit) = coroutineScope {
        val connection =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(20_000) {
                    tab.sessionState.first { it.status == SessionStatus.Connecting }
                    tab.sessionState.first {
                        it.status == SessionStatus.Connected || it.status == SessionStatus.Error
                    }
                }
            }
        withContext(Dispatchers.Main) { action() }
        val state = connection.await()
        assertEquals(state.error, SessionStatus.Connected, state.status)
    }

    private suspend fun checkPersistedVariable(tab: TabSession, token: String) {
        val probe = UUID.randomUUID().toString().take(8)
        tab.engine.writeText(
            "if [ \"\$ICHUCHU_FIXTURE_TOKEN\" = '$token' ]; then printf '%s%s\\n' 'PERSIST_' '$probe'; fi\n"
        )
        awaitOutput(tab, "PERSIST_$probe")
    }

    private suspend fun awaitOutput(tab: TabSession, text: String) {
        withTimeout(15_000) {
            tab.sessionState.first { state ->
                check(state.status != SessionStatus.Error) { state.error.orEmpty() }
                state.snapshot?.codepoints?.map(Int::toChar)?.joinToString("")?.contains(text) ==
                    true
            }
        }
    }
}
