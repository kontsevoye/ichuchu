package io.github.kontsevoye.ichuchu

import androidx.test.platform.app.InstrumentationRegistry
import io.github.kontsevoye.ichuchu.service.ssh.NativeSshBridge
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in integration probe: negotiates SSH and reads the host key, without authenticating. */
class NativeSshHandshakeTest {
    @Test
    fun negotiateWithConfiguredServer() {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("sshHandshakeHost")
        assumeTrue(
            "Supply sshHandshakeHost to run against a test SSH server",
            !host.isNullOrBlank(),
        )
        val port = args.getString("sshHandshakePort")?.toInt() ?: 22
        val user = args.getString("sshHandshakeUser") ?: "handshake-probe"
        val bridge = NativeSshBridge()
        val handle = bridge.nativeCreateSession()
        assertNotEquals(0L, handle)
        try {
            val connected = bridge.nativeConnect(handle, host!!, port, user)
            assertTrue(bridge.nativeGetLastError(handle), connected)
            assertTrue(bridge.nativeGetHostKey(handle)?.isNotEmpty() == true)
        } finally {
            bridge.nativeDestroySession(handle)
        }
    }
}
