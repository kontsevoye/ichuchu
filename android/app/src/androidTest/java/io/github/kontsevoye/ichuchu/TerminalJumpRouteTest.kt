package io.github.kontsevoye.ichuchu

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kontsevoye.ichuchu.model.AuthMethod
import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.model.Transport
import io.github.kontsevoye.ichuchu.service.ssh.HostKeyStore
import io.github.kontsevoye.ichuchu.service.ssh.SshJumpHost
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

class TerminalJumpRouteTest {
    @Test
    fun shellExitClosesTheChainWithoutReconnect() = runBlocking { checkLifecycle(drop = false) }

    @Test fun transportDropRebuildsTheChain() = runBlocking { checkLifecycle(drop = true) }

    private suspend fun checkLifecycle(drop: Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sshJumpFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val token = UUID.randomUUID().toString()
        val prefs = context.getSharedPreferences("jump-test-$token", Context.MODE_PRIVATE)
        val dir = File(context.cacheDir, "jump-test-$token")
        val engine =
            TerminalSessionEngine(
                {},
                scope,
                NativeLocalShellService(File(dir, "home"), File(dir, "tmp")),
                HostKeyStore(prefs),
                TailscaleStatusChecker(context),
            )
        val prompts =
            scope.launch {
                engine.hostKeyPrompt.filterNotNull().collect { engine.respondToHostKey(true) }
            }
        val hops =
            listOf(
                    HostProfile(
                        id = 1,
                        name = "root",
                        host = "10.0.2.2",
                        port = 22227,
                        username = "fixture",
                        password = "fixture-0",
                    ),
                    HostProfile(
                        id = 2,
                        name = "gateway",
                        host = "jump-1.internal",
                        port = 2201,
                        username = "fixture",
                        password = "fixture-1",
                    ),
                )
                .map { SshJumpHost(it) }
        try {
            engine.connect(
                jumpHosts = hops,
                host = "jump-2.internal",
                port = 2202,
                username = if (drop) "drop-on-input" else "fixture",
                password = "fixture-2",
                authMethod = AuthMethod.Password,
                publicKeyOpenSsh = "",
                privateKeyPem = "",
                keyPassphrase = "",
                transport = Transport.SSH,
                sessionKey = "jump-$token",
            )
            val connected =
                withTimeout(15_000) {
                    engine.state.first {
                        it.status == SessionStatus.Connected || it.status == SessionStatus.Error
                    }
                }
            assertEquals(connected.error, SessionStatus.Connected, connected.status)
            withTimeout(10_000) {
                engine.state.first {
                    it.snapshot
                        ?.codepoints
                        ?.map(Int::toChar)
                        ?.joinToString("")
                        ?.contains("JUMP_READY:2") == true
                }
            }
            engine.writeText(if (drop) "drop\n" else "exit\n")
            if (drop) {
                withTimeout(10_000) {
                    engine.state.first { it.status == SessionStatus.Reconnecting }
                }
                val restored =
                    withTimeout(15_000) {
                        engine.state.first {
                            it.status == SessionStatus.Connected || it.status == SessionStatus.Error
                        }
                    }
                assertEquals(restored.error, SessionStatus.Connected, restored.status)
                assertFalse(restored.shellExited)
            } else {
                val ended =
                    withTimeout(10_000) {
                        engine.state.first {
                            it.status == SessionStatus.Disconnected ||
                                it.status == SessionStatus.Error
                        }
                    }
                assertEquals(ended.error, SessionStatus.Disconnected, ended.status)
                delay(1200)
                assertEquals(SessionStatus.Disconnected, engine.state.value.status)
            }
        } finally {
            engine.disconnect()
            withTimeout(5000) { engine.state.first { it.status == SessionStatus.Disconnected } }
            engine.dispose()
            prompts.cancel()
            scope.cancel()
            prefs.edit().clear().commit()
            dir.deleteRecursively()
        }
    }
}
