package io.github.kontsevoye.ichuchu.service.multiplexer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class TmuxMultiplexerTest {
    @Test
    fun listSessionsPreservesExitMarkerInPosixShell() {
        assertListSessionsExitMarker("/bin/sh")
    }

    @Test
    fun listSessionsPreservesExitMarkerInZsh() {
        assumeTrue("zsh is not installed", File("/bin/zsh").canExecute())
        assertListSessionsExitMarker("/bin/zsh")
    }

    private fun assertListSessionsExitMarker(shell: String) {
        for (tmuxExitCode in listOf(0, 1, 2)) {
            // Stub tmux so no real server or user sessions are accessed. Match the
            // exec-channel envelope used when loading the remote session picker.
            val command =
                "tmux() { return $tmuxExitCode; }; " +
                    TmuxMultiplexer.listSessionsCommand() +
                    "; printf '\\nICHUCHU_EXIT:%s\\n' \"\$?\""
            val process = ProcessBuilder(shell, "-f", "-c", command).start()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            assertEquals("$shell: $stderr", 0, process.waitFor())
            val expectedExitCode = if (tmuxExitCode <= 1) 0 else 1
            assertEquals("$shell: $stderr", "\nICHUCHU_EXIT:$expectedExitCode\n", stdout)
        }
    }

    @Test
    fun launchCreatesOrSwitchesOutsideTmuxAndSwitchesClientInsideTmux() {
        val command =
            TmuxMultiplexer.launchCommand(
                sessionName = "ichuchu-1",
                createIfMissing = true,
                trustedRemoteName = false,
            )

        assertEquals(
            "if [ -n \"\$TMUX\" ]; then tmux switch-client -t '=ichuchu-1'; else exec tmux new-session -A -s 'ichuchu-1'; fi",
            command,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun launchRejectsUngeneratedUntrustedNames() {
        TmuxMultiplexer.launchCommand(
            sessionName = "work'space",
            createIfMissing = true,
            trustedRemoteName = false,
        )
    }

    @Test
    fun launchQuotesTrustedRemoteNames() {
        val command =
            TmuxMultiplexer.launchCommand(
                sessionName = "work'space; rm -rf /",
                createIfMissing = true,
                trustedRemoteName = true,
            )

        assertEquals(
            "if [ -n \"\$TMUX\" ]; then tmux switch-client -t '=work'\\''space; rm -rf /'; else exec tmux new-session -A -s 'work'\\''space; rm -rf /'; fi",
            command,
        )
    }

    @Test
    fun launchExistingUsesSwitchClientAndExactAttachFallback() {
        val command =
            TmuxMultiplexer.launchCommand(
                sessionName = "ichuchu-1",
                createIfMissing = false,
                trustedRemoteName = true,
            )

        assertEquals(
            "if [ -n \"\$TMUX\" ]; then tmux switch-client -t '=ichuchu-1'; " +
                "elif tmux has-session -t '=ichuchu-1' 2>/dev/null; then exec tmux attach-session -t '=ichuchu-1'; " +
                "else printf 'tmux session %s is no longer available\\n' 'ichuchu-1'; exec \"\${SHELL:-/bin/sh}\" -l; fi",
            command,
        )
    }

    @Test
    fun launchExistingQuotesTrustedRemoteNamesWithMetacharacters() {
        val command =
            TmuxMultiplexer.launchCommand(
                sessionName = "main tab; printf 'x'",
                createIfMissing = false,
                trustedRemoteName = true,
            )

        assertEquals(
            "if [ -n \"\$TMUX\" ]; then tmux switch-client -t '=main tab; printf '\\''x'\\'''; " +
                "elif tmux has-session -t '=main tab; printf '\\''x'\\''' 2>/dev/null; then exec tmux attach-session -t '=main tab; printf '\\''x'\\'''; " +
                "else printf 'tmux session %s is no longer available\\n' 'main tab; printf '\\''x'\\'''; exec \"\${SHELL:-/bin/sh}\" -l; fi",
            command,
        )
    }

    @Test
    fun listSessionsCommandChecksExecutableBeforeTreatingNoServerAsEmptySuccess() {
        val command = TmuxMultiplexer.listSessionsCommand()

        assertEquals(
            "if ! command -v tmux >/dev/null 2>&1; then printf 'tmux executable not found\\n' >&2; false; " +
                "else tmux list-sessions -F '#{session_name}\t#{session_attached}' 2>/dev/null; " +
                "tmux_exit_code=\$?; if [ \"\$tmux_exit_code\" -eq 1 ]; then true; else [ \"\$tmux_exit_code\" -eq 0 ]; fi; fi",
            command,
        )
    }

    @Test
    fun parsesSessionList() {
        val sessions = TmuxMultiplexer.parseSessions("main\t1\nwork\t0\n")

        assertEquals(
            listOf(
                RemoteMultiplexerSession(name = "main", attached = true),
                RemoteMultiplexerSession(name = "work", attached = false),
            ),
            sessions,
        )
    }
}
