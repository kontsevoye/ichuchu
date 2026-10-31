package io.github.kontsevoye.ichuchu.service.mosh

import io.github.kontsevoye.ichuchu.service.multiplexer.TmuxMultiplexer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshReconnectPolicyTest {
    @Test
    fun bootstrapPassesTheExactTmuxCommandAsOneShellArgument() {
        val directory = kotlin.io.path.createTempDirectory("mosh-bootstrap-").toFile()
        try {
            val server = File(directory, "server")
            server.writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\"\n")
            assertTrue(server.setExecutable(true))
            // A discovered session name is untrusted text, including shell syntax.
            val name = "work ' \" ; \$(printf injected)"
            val startup = TmuxMultiplexer.launchCommand(name, false, trustedRemoteName = true)
            val command =
                MoshReconnectPolicy.bootstrapCommand(startup)
                    .replace("mosh-server new", "'${server.absolutePath}' new")
            for (shell in listOf("/bin/sh", "/bin/zsh").filter { File(it).canExecute() }) {
                val process = ProcessBuilder(shell, "-f", "-c", command).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val errors = process.errorStream.bufferedReader().use { it.readText() }
                assertEquals(errors, 0, process.waitFor())
                assertEquals("new\n-s\n-c\n256\n--\n/bin/sh\n-c\n$startup\n", output)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun serverExpiresBeforeClientAllowsReplacementSession() {
        val serverTimeoutMs = MoshReconnectPolicy.SERVER_NETWORK_TIMEOUT_SECONDS * 1_000

        assertTrue(MoshReconnectPolicy.CLIENT_NETWORK_TIMEOUT_MS > serverTimeoutMs)
        assertTrue(MoshReconnectPolicy.CLIENT_NETWORK_TIMEOUT_MS <= 0xFFFF_FFFFL)
    }

    @Test
    fun retransmitBudgetOutlivesClientTimeout() {
        assertTrue(
            MoshReconnectPolicy.CLIENT_MAX_RETRANSMIT_COUNT *
                MoshReconnectPolicy.CLIENT_MIN_RETRANSMIT_INTERVAL_MS >
                MoshReconnectPolicy.CLIENT_NETWORK_TIMEOUT_MS
        )
        assertTrue(MoshReconnectPolicy.CLIENT_MAX_RETRANSMIT_COUNT <= 0xFFFF_FFFFL)
    }

    @Test
    fun bootstrapAppliesServerNetworkTimeout() {
        val command = MoshReconnectPolicy.bootstrapCommand()

        assertTrue(
            command.contains(
                "MOSH_SERVER_NETWORK_TMOUT=${MoshReconnectPolicy.SERVER_NETWORK_TIMEOUT_SECONDS}"
            )
        )
        assertTrue(command.endsWith("mosh-server new -s -c 256"))
    }
}
