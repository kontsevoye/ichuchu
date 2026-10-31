# tmux sessions over SSH and Mosh

In the host editor, open **additional settings** and select **tmux** under
**SESSION PERSISTENCE**. This works with SSH, Tailscale SSH and direct Mosh
connections. The remote account must have `tmux` on its command search path;
Mosh also requires `mosh-server` and a reachable UDP path to the target.

Leave **auto-run on connect** empty unless you want to run an additional command
inside the attached session. Remove any manual `tmux attach` or `tmux new`
workaround from that field when enabling the built-in tmux option.

Connecting reuses a detached `ichuchu-N` session when available, or creates one.
The terminal's session manager lists remote sessions, opens another session and
switches to an existing one. Duplicating a terminal creates another tmux session.
Reconnect returns to the tab's selected session. Closing a tab detaches its
client; the tmux session continues on the host. Exiting its final shell ends
that remote session.

Session discovery uses a separate SSH connection with the profile's credentials
and normal host-key verification. SSH jump routes use the same route as the
terminal. Mosh starts tmux directly as the `mosh-server` child process; session
selection does not depend on typing a command into a shell after connection.
Mosh jump routes remain unsupported because the UDP connection is direct.

If tmux cannot be found or session discovery fails, the connection recovery
dialog allows retrying or connecting without a multiplexer. Zellij is not
implemented. Zmx is available only on the SSH transports.

The session-list command supports POSIX shells and zsh, including hosts where
zsh reserves `status` as a read-only parameter. Shell command generation has
local regressions; real tmux SSH/Mosh lifecycle acceptance uses the opt-in
[disposable-emulator fixture](../android/test-fixtures/README.md#tmux-over-ssh-and-mosh).
