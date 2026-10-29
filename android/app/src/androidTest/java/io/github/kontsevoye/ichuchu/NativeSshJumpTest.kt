package io.github.kontsevoye.ichuchu

import androidx.test.platform.app.InstrumentationRegistry
import io.github.kontsevoye.ichuchu.model.AuthMethod
import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.service.ssh.Ed25519KeyGenerator
import io.github.kontsevoye.ichuchu.service.ssh.NativeSshService
import io.github.kontsevoye.ichuchu.service.ssh.SshConnectionException
import io.github.kontsevoye.ichuchu.service.ssh.SshJumpHost
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in, synthetic loopback-only integration against ssh_jump_server.py. */
class NativeSshJumpTest {
    private fun requireFixture() =
        assumeTrue(InstrumentationRegistry.getArguments().getString("sshJumpFixture") == "true")

    private fun hop(index: Int, username: String = "fixture"): SshJumpHost =
        SshJumpHost(
            HostProfile(
                id = index + 1L,
                name = "hop-$index",
                host = if (index == 0) "10.0.2.2" else "jump-$index.internal",
                port = if (index == 0) 22227 else 2200 + index,
                username = username,
                password = "fixture-$index",
            )
        )

    private fun connect(ssh: NativeSshService, hops: List<SshJumpHost>, depth: Int) {
        val target = hop(depth).profile
        ssh.connect(
            target.host,
            target.port,
            target.username,
            password = target.password,
            jumpHosts = hops,
        )
    }

    @Test
    fun sftpCanStartAfterAnIdleConnection() {
        requireFixture()
        NativeSshService(hostKeyPolicy = { true }).use { ssh ->
            connect(ssh, listOf(hop(0)), 1)
            Thread.sleep(10_200)
            ssh.sftpOpenWrite("/after-idle")
            ssh.sftpWriteChunk(byteArrayOf(1, 2, 3))
            ssh.sftpCloseWrite()
            assertArrayEquals(byteArrayOf(1, 2, 3), ssh.sftpReadFile("/after-idle"))
        }
    }

    @Test
    fun execThroughZeroToThreeBastions() {
        requireFixture()
        for (depth in 0..3) {
            val verified = mutableListOf<String>()
            NativeSshService(
                    hostKeyPolicy = { request ->
                        verified += request.host
                        true
                    }
                )
                .use { ssh ->
                    connect(ssh, (0 until depth).map { hop(it) }, depth)
                    assertEquals(depth + 1, verified.size)
                    assertTrue(ssh.openExec("jump-probe"))
                    val out = StringBuilder()
                    val deadline = System.nanoTime() + 10_000_000_000L
                    while (!ssh.isChannelEof() && System.nanoTime() < deadline) {
                        ssh.read(4096)?.let { out.append(String(it)) }
                        Thread.sleep(10)
                    }
                    assertTrue(out.toString(), out.contains("JUMP_READY:$depth"))
                }
        }
    }

    @Test
    fun encryptedKeyAndPasswordOnSeparateHopsWithLargeShellIo() {
        requireFixture()
        val key = Ed25519KeyGenerator().generate("synthetic-jump", "fixture-passphrase")
        val keyed =
            hop(1).let {
                it.copy(
                    profile =
                        it.profile.copy(
                            authMethod = AuthMethod.KeyWithPassphrase,
                            keyPassphrase = "fixture-passphrase",
                        ),
                    publicKeyOpenSsh = key.publicKeyOpenSsh,
                    privateKeyPem = key.privateKeyPem,
                )
            }
        NativeSshService(hostKeyPolicy = { true }).use { ssh ->
            connect(ssh, listOf(hop(0), keyed), 2)
            ssh.openShell(80, 24, 800, 480)
            ssh.resize(100, 30, 1000, 600)
            val payload = "x".repeat(128 * 1024).toByteArray()
            ssh.write(payload)
            var bytes = 0
            val deadline = System.nanoTime() + 15_000_000_000L
            while (bytes < payload.size && System.nanoTime() < deadline) {
                ssh.read(65536)?.let { data -> bytes += data.count { it == 'x'.code.toByte() } }
                Thread.sleep(5)
            }
            assertEquals(payload.size, bytes)
        }
    }

    @Test
    fun sftpRoundtripThroughTwoBastions() {
        requireFixture()
        NativeSshService(hostKeyPolicy = { true }).use { ssh ->
            connect(ssh, listOf(hop(0), hop(1)), 2)
            val payload = "synthetic-file".repeat(16384).toByteArray()
            ssh.sftpOpenWrite("/jump-file")
            var offset = 0
            while (offset < payload.size) {
                offset +=
                    ssh.sftpWriteChunk(
                        payload.copyOfRange(offset, minOf(offset + 16384, payload.size))
                    )
            }
            ssh.sftpCloseWrite()
            assertArrayEquals(payload, ssh.sftpReadFile("/jump-file", 1024 * 1024))
        }
    }

    @Test
    fun interruptStalledChildHandshake() {
        requireFixture()
        val waiting = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        var failure: Throwable? = null
        val ssh = NativeSshService(hostKeyPolicy = { true })
        val thread = Thread {
            try {
                ssh.connect(
                    "jump-1.internal",
                    2201,
                    "fixture",
                    password = "fixture-1",
                    jumpHosts = listOf(hop(0, "stall-next")),
                    onProgress = { if (it.startsWith("2/")) waiting.countDown() },
                )
                failure = AssertionError("Stalled handshake unexpectedly connected")
            } catch (e: java.util.concurrent.CancellationException) {
                // Expected: cancellation must not wait out the handshake deadline.
            } catch (e: Throwable) {
                failure = e
            } finally {
                ssh.close()
                finished.countDown()
            }
        }
        thread.start()
        assertTrue(waiting.await(10, java.util.concurrent.TimeUnit.SECONDS))
        Thread.sleep(100)
        ssh.interrupt()
        assertTrue("Cancellation stalled", finished.await(2, java.util.concurrent.TimeUnit.SECONDS))
        failure?.let { throw AssertionError("Unexpected cancellation failure", it) }
    }

    @Test
    fun forwardingDeniedAndHostKeyRejectionStopTheChain() {
        requireFixture()
        NativeSshService(hostKeyPolicy = { true }).use { ssh ->
            try {
                connect(ssh, listOf(hop(0, "deny-forward")), 1)
                fail("forwarding should fail")
            } catch (e: SshConnectionException) {
                assertFalse(e.retryable)
                assertTrue(e.message!!.contains("TCP forwarding"))
            }
        }
        var checks = 0
        NativeSshService(hostKeyPolicy = { ++checks < 2 }).use { ssh ->
            try {
                connect(ssh, listOf(hop(0), hop(1)), 2)
                fail("host key should be rejected")
            } catch (e: SshConnectionException) {
                assertFalse(e.retryable)
                assertEquals(2, checks)
            }
        }
    }
}
