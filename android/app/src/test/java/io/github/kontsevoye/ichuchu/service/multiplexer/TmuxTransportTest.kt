package io.github.kontsevoye.ichuchu.service.multiplexer

import io.github.kontsevoye.ichuchu.model.HostProfile
import io.github.kontsevoye.ichuchu.model.MultiplexerType
import io.github.kontsevoye.ichuchu.model.Transport
import io.github.kontsevoye.ichuchu.service.terminal.TabSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxTransportTest {
    @Test
    fun moshProfileRetainsTmuxAndEnablesSessionActions() {
        val spec =
            TabSpec.fromHostProfile(
                HostProfile(
                    name = "fixture",
                    host = "fixture.invalid",
                    username = "fixture",
                    transport = Transport.Mosh,
                    multiplexer = MultiplexerType.Tmux,
                )
            )
        assertEquals(MultiplexerType.Tmux, spec.multiplexer)
        assertTrue(spec.usesRuntimeMultiplexer)
    }

    @Test
    fun sshTransportsRetainTmuxAndSessionActions() {
        for (transport in listOf(Transport.SSH, Transport.TailscaleSSH)) {
            val spec =
                TabSpec.fromHostProfile(
                    HostProfile(
                        name = "fixture",
                        host = "fixture.invalid",
                        username = "fixture",
                        transport = transport,
                        multiplexer = MultiplexerType.Tmux,
                    )
                )
            assertEquals(MultiplexerType.Tmux, spec.multiplexer)
            assertTrue(spec.usesRuntimeMultiplexer)
        }
    }

    @Test
    fun unavailableCombinationsRemainDisabled() {
        for ((type, transport) in
            listOf(
                MultiplexerType.Tmux to Transport.LocalShell,
                MultiplexerType.Zmx to Transport.Mosh,
                MultiplexerType.Zellij to Transport.SSH,
                MultiplexerType.Zellij to Transport.Mosh,
            )) {
            val spec =
                TabSpec.fromHostProfile(
                    HostProfile(
                        name = "fixture",
                        host = "fixture.invalid",
                        username = "fixture",
                        transport = transport,
                        multiplexer = type,
                    )
                )
            assertFalse(type.supportsTransport(transport))
            assertFalse(spec.usesRuntimeMultiplexer)
            assertNull(spec.multiplexer)
        }
    }
}
