package io.github.kontsevoye.ichuchu

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kontsevoye.ichuchu.model.AuthMethod
import io.github.kontsevoye.ichuchu.model.Transport
import io.github.kontsevoye.ichuchu.service.ssh.HostKeyStore
import io.github.kontsevoye.ichuchu.service.ssh.TailscaleStatusChecker
import io.github.kontsevoye.ichuchu.service.terminal.NativeLocalShellService
import io.github.kontsevoye.ichuchu.service.terminal.SessionStatus
import io.github.kontsevoye.ichuchu.service.terminal.TerminalSessionEngine
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Requires the isolated synthetic shell fixture; never connects using user credentials. */
class TerminalShellExitTest {
    @Test
    fun ctrlDCompletesTheSessionInsteadOfLeavingItConnected() = runBlocking {
        checkSessionEnd("synthetic-shell", SessionStatus.Disconnected)
    }

    @Test
    fun transportDropStillReconnects() = runBlocking {
        checkSessionEnd("synthetic-drop", SessionStatus.Reconnecting)
    }

    @Test
    fun realMoshCtrlDCompletesTheSession() = runBlocking {
        checkSessionEnd("synthetic-mosh", SessionStatus.Disconnected, Transport.Mosh)
    }

    @Test
    fun realMoshExitCompletesTheSession() = runBlocking {
        checkSessionEnd("synthetic-mosh", SessionStatus.Disconnected, Transport.Mosh, "exit\n")
    }

    private suspend fun checkSessionEnd(
        username: String,
        expected: SessionStatus,
        transport: Transport = Transport.SSH,
        input: String = "\u0004",
    ) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Enable only with the synthetic SSH fixture",
            args.getString(
                if (transport == Transport.Mosh) "moshExitFixture" else "shellExitFixture"
            ) == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefs =
            context.getSharedPreferences(
                "shell-exit-test-${UUID.randomUUID()}",
                Context.MODE_PRIVATE,
            )
        val dir = File(context.cacheDir, "shell-exit-${UUID.randomUUID()}")
        val engine =
            TerminalSessionEngine(
                {},
                scope,
                NativeLocalShellService(File(dir, "home"), File(dir, "tmp")),
                HostKeyStore(prefs),
                TailscaleStatusChecker(context),
            )
        val hostKeyJob = scope.launch {
            engine.hostKeyPrompt.filterNotNull().collect { engine.respondToHostKey(true) }
        }
        try {
            engine.connect(
                host = "10.0.2.2",
                port = if (transport == Transport.Mosh) 22226 else 22225,
                username = username,
                password = "",
                authMethod = AuthMethod.None,
                publicKeyOpenSsh = "",
                privateKeyPem = "",
                keyPassphrase = "",
                transport = transport,
                sessionKey = "synthetic-shell-exit",
            )
            val connected =
                withTimeout(10_000) {
                    engine.state.first {
                        it.status == SessionStatus.Connected || it.status == SessionStatus.Error
                    }
                }
            assertEquals(connected.error, SessionStatus.Connected, connected.status)
            if (transport == Transport.Mosh) {
                // Wait for actual server output over encrypted UDP, not just SSH bootstrap.
                withTimeout(10_000) {
                    engine.state.first { state ->
                        state.snapshot
                            ?.codepoints
                            ?.map { it.toChar() }
                            ?.joinToString("")
                            ?.contains("MOSH_FIXTURE_READY") == true
                    }
                }
            }
            engine.writeText(input)
            val ended =
                withTimeoutOrNull(3_000) {
                    engine.state.first { it.status != SessionStatus.Connected }
                }
            assertEquals(
                "Unexpected session state after synthetic $username exit: ${engine.state.value.status}",
                expected,
                ended?.status,
            )
        } finally {
            hostKeyJob.cancel()
            engine.dispose()
            withTimeoutOrNull(2_000) {
                engine.state.first {
                    it.status == SessionStatus.Disconnected &&
                        it.snapshot == null &&
                        it.handle == 0L
                }
            }
            scope.cancel()
            prefs.edit().clear().commit()
            dir.deleteRecursively()
        }
    }
}
