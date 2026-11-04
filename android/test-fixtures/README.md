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

## tmux over SSH and Mosh

`tmux_transport_server.py` runs real tmux in a temporary HOME, with a separate
socket and configuration, plus real `mosh-server` processes. It requires
`paramiko==4.0.0`, `/bin/zsh`, `tmux` and `mosh-server` on PATH. SSH exec requests
are restricted to the app's discovery and generated session-startup templates.
The interactive terminal runs a disposable `/bin/sh`; use synthetic test input
only. It does not use personal SSH credentials, shell startup files or tmux
sockets. Mosh keys stay in memory and the SSH channel.

```sh
/tmp/ichuchu-ssh-fixture-venv/bin/python android/test-fixtures/tmux_transport_server.py
```

The SSH listener binds to `127.0.0.1:22228`, with Mosh bound to loopback UDP
63100–63199. The fixture accepts only the synthetic `tmux-fixture` username with
`none` authentication. Mosh servers expire after 20 seconds without client traffic.
Stop with Ctrl+C; cleanup kills only the fixture's tmux socket and tracked Mosh
processes.

On a disposable emulator with debug and Android test APKs installed:

```sh
adb -s <test-emulator> shell am instrument -w \
  -e class io.github.kontsevoye.ichuchu.TmuxTransportTest \
  -e tmuxFixture true \
  io.github.kontsevoye.ichuchu.test/androidx.test.runner.AndroidJUnitRunner
```

The three tests cover selecting/saving tmux in the Mosh editor and the terminal
actions for both SSH and Mosh: create, list, duplicate, switch, reconnect,
detach and reattach. Unique shell variables prove that the same remote tmux
session survives; terminal output proves post-connect commands run inside tmux.
The Mosh case requires actual encrypted UDP output. Without `tmuxFixture=true`
these tests skip. The editor test removes its synthetic database profile.

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

`FileBrowserIntegrationTest` uses the same fixture to exercise the file-browser
ViewModel through real JNI/SFTP: tab characters in filenames, empty files, and
switching active terminal tabs during an upload. Uploaded files stay in memory.

## Screen and edge-case QA

Use the [application checklist](../../docs/qa-checklist.md) to record coverage and
unavailable device cases. With the SSH jump fixture running, the additional
synthetic regression and screen checks run on the disposable emulator with:

```sh
adb -s <test-emulator> shell am instrument -w \
  -e class io.github.kontsevoye.ichuchu.QaRegressionTest,io.github.kontsevoye.ichuchu.QaScreenCoverageTest,io.github.kontsevoye.ichuchu.FileBrowserIntegrationTest \
  -e qaFixture true -e sshJumpFixture true \
  io.github.kontsevoye.ichuchu.test/androidx.test.runner.AndroidJUnitRunner
```

These tests cover duplicate saves, connection-test invalidation/validation,
key-container import, switch accessibility, large-font settings, file actions,
settings/key dialogs and app-lock gating. They create and remove only synthetic
profiles/keys. `qa-*.png` screenshots are written to the test app's private cache.

## Native Agent Deck workspace

`agent_deck_server.py` accepts only `deck-fixture` with synthetic `none`
authentication on `127.0.0.1:22229`. It never executes incoming shell commands.
It serves synthetic Deck v1.16.35-shaped session/transcript/receipt data through
real SSH, with separate stderr warnings and forced 64 KiB rekey. Prompt bytes,
session state and the generated host key stay in memory.

```sh
/tmp/ichuchu-ssh-fixture-venv/bin/python android/test-fixtures/agent_deck_server.py
# In another shell, create synthetic picker inputs and copy only to this emulator:
attachment_fixtures=$(mktemp -d)
python3 android/test-fixtures/create_agent_attachments.py "$attachment_fixtures"
adb -s <test-emulator> push "$attachment_fixtures/." /sdcard/Download/
# Android 13+ only, on this disposable emulator:
adb -s <test-emulator> shell pm grant io.github.kontsevoye.ichuchu android.permission.POST_NOTIFICATIONS
adb -s <test-emulator> shell am instrument -w \
  -e class io.github.kontsevoye.ichuchu.AgentModeTest,io.github.kontsevoye.ichuchu.data.db.EncryptedStorageTest \
  -e agentDeckFixture true \
  io.github.kontsevoye.ichuchu.test/androidx.test.runner.AndroidJUnitRunner
```

Tests cover stdin EOF, separated streams, exit status, output larger than the SSH
window, cancellation, CLI negotiation, session/history/send/create UI, offline
drafts, empty-history first messages, groups without sessions, explicit Codex history
binding, background status notifications and notification navigation, encrypted
cache eviction/reopen and a real schema 13→14 migration. Attachment acceptance uses
the actual Android document picker, image and arbitrary binary uploads, size limits,
exact binary hashes across rekey, removal, reconnect persistence and attachment-only
sending. The fixture holds uploaded bytes in memory and never executes the upload
script; separate JVM tests run that script in temporary directories under sh/zsh,
checking byte preservation, owner-only permissions and incomplete-upload cleanup.
The notification test
requires notification permission and briefly opens the emulator launcher before
returning through the notification's actual PendingIntent. Without the explicit
flag the SSH/UI tests skip. Use only a disposable emulator.

The tmux fixture also has a tiny `agent-deck` attach stub mapping one fixed full
ID to an existing session on a **second isolated tmux socket**. The
`exactDeckSessionStartupSurvivesSshAndMoshReconnect` test proves the generated
command traverses the real SSH PTY and Mosh UDP startup paths and reconnects to
the same shell state. It does not claim to run a provider or the complete real
Deck CLI. Run it with `-e tmuxFixture true` as above; cleanup removes only the
fixture's two tmux sockets and tracked processes.

### Live Codex acceptance

`LiveCodexTest` is opt-in and calls the real installed Deck and authenticated Codex
through the Android client's command boundary. It creates a session, sends two
small provider requests, verifies one native user row per request and the actual
responses, and reopens persisted history by Deck ID without manually linking it.
Its `finally` block removes only the session it created. It does not replace the
Android SSH/UI tests or physical-device acceptance.

Use an otherwise disposable profile named `ichuchu-liveqa-*` and a trusted test
project under ignored `.plans/`. Give that project an `AGENTS.md` that restricts it
to answering messages without tools or file changes. The host must already have
Recall, authentication, and the intended Codex launcher configured; see
[Agent mode](../../docs/agent-mode.md) for shared app-server limitations. The test
does not install hooks, change provider permissions, or repair host settings.

```sh
ICHUCHU_LIVE_DECK="$(command -v agent-deck)" \
ICHUCHU_LIVE_DECK_PROFILE=ichuchu-liveqa-local \
ICHUCHU_LIVE_DECK_PROJECT="$PWD/.plans/live-codex-project" \
nix develop --command bash -c \
  'cd android && ./gradlew :app:testDebugUnitTest --tests "*LiveCodexTest" --rerun-tasks'
```

Add `ICHUCHU_LIVE_MODEL_CONTROL=1` to also change reasoning and model between the
two requests. This checks `gpt-6.1-sol / low` and `gpt-6-luna / high` against each
native `turn_context`, requires the same conversation ID, and compares the Codex
global configuration before/after. Both models must be available to the account.
The remote controller's refusal/parser tests also run in the ordinary JVM suite
through Python 3; they do not access real sessions.

The model-menu UI case in `AgentModeTest` uses synthetic settings over real SSH,
preserves a composed draft, reopens the selected settings, and compares the chat
and input bounds before/after with the actual IME visible. `AgentMemoryCitationTest`
and `AgentChatScrollTest` cover collapsed/expanded sources and long source paths.
For the 320dp/200% pass, set the disposable emulator's system `font_scale` to `2.0`
as well as passing `-e agentFontScale 2.0`: popup/dialog windows can use the system
density rather than the test's composition-local override. Restore `font_scale`
to `1.0` before ordinary acceptance.
