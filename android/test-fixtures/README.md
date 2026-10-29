# SSH shell exit regression fixture

This loopback-only server accepts synthetic `none` authentication and emulates a
shell without executing commands or accessing user credentials. Its host key is
generated in memory. Ctrl+D or `exit` sends channel EOF; the `synthetic-drop` user
instead drops the transport to test automatic reconnect.

```sh
python3 -m venv /tmp/ichuchu-ssh-fixture-venv
/tmp/ichuchu-ssh-fixture-venv/bin/pip install paramiko==4.0.0
/tmp/ichuchu-ssh-fixture-venv/bin/python android/test-fixtures/ssh_shell_exit_server.py
```

Build/install debug and Android test APKs on a disposable AOSP emulator. Tests
use the emulator's host-loopback alias `10.0.2.2:22225`. Select that emulator
explicitly; these tests are not intended for a personal phone.

```sh
adb -s <test-emulator> shell am instrument -w \
  -e class io.github.kontsevoye.ichuchu.TerminalShellExitTest,io.github.kontsevoye.ichuchu.TerminalShellExitNavigationTest \
  -e shellExitFixture true \
  io.github.kontsevoye.ichuchu.test/androidx.test.runner.AndroidJUnitRunner
```

Without `shellExitFixture=true` these network tests are skipped. Stop the server
with Ctrl+C when finished. AES-CTR is deliberately fixed here so EOF checks do
not depend on AES-GCM interoperability with the synthetic server.

## Real Mosh shell exit

The SSH fixture above does **not** test Mosh. For Mosh, run the separate bootstrap
fixture with `mosh-server` available at `~/.nix-profile/bin/mosh-server`:

```sh
/tmp/ichuchu-ssh-fixture-venv/bin/python android/test-fixtures/mosh_shell_exit_server.py
```

It bootstraps a real `mosh-server` on loopback UDP ports 63000–63099 with a
disposable HOME and fixed `/bin/sh`, without running commands supplied by the
SSH client. Session keys stay in pipes/the SSH channel and are never saved or
logged. The SSH bootstrap port is 22226. Stop it with Ctrl+C when finished.

Run the same instrumentation classes with `-e moshExitFixture true`; add
`-e shellExitFixture true` if both fixture servers are running. Tests wait for
`MOSH_FIXTURE_READY` in the terminal snapshot, proving actual encrypted UDP
output reached the terminal before sending Ctrl+D/exit. They verify normal
completion, active/background tab preservation, and last-tab navigation.

Native shutdown/ACK and timeout regressions: `cd zig-src && zig build test-mosh`.

## SSH jump hosts

Run the synthetic fixture using the same Paramiko environment:

```sh
/tmp/ichuchu-ssh-fixture-venv/bin/python android/test-fixtures/ssh_jump_server.py
```

The only TCP listener is `127.0.0.1:22227`. It exposes virtual SSH destinations
`jump-1.internal:2201`, `jump-2.internal:2202` and `jump-3.internal:2203` inside
forwarding channels; they have no separate TCP listeners or public DNS entries.
Passwords are synthetic `fixture-0` through `fixture-3`. Public-key auth accepts
synthetic test keys only within this disposable fixture. Files exist only in
memory; exec accepts the fixed `jump-probe` command and never executes a shell.

Build/install debug and Android test APKs on a disposable emulator, then run:

```sh
adb -s <test-emulator> shell am instrument -w \
  -e class io.github.kontsevoye.ichuchu.NativeSshJumpTest,io.github.kontsevoye.ichuchu.TerminalJumpRouteTest,io.github.kontsevoye.ichuchu.JumpHostEditorTest \
  -e sshJumpFixture true \
  io.github.kontsevoye.ichuchu.test/androidx.test.runner.AndroidJUnitRunner
```

The suite covers 0–3 bastions, host-key rejection, forwarding refusal, encrypted
key and password on different hops, large shell input/output, resize, SFTP
roundtrip after idle, interruption of a stalled child handshake, terminal exit/reconnect
and route editor selection/reordering/persistence. The server forces rekey every
64 KiB. Editor acceptance creates and removes only synthetic profiles in the
disposable test app. Without the explicit
fixture flag the network tests are skipped. Stop the fixture after testing.
This fixture proves nested client transport behavior, not real sshd policy or
physical-device network roaming.
