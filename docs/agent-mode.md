# Agent mode

Agent mode opens an SSH server as a native Agent Deck workspace: sessions grouped
by project, live status, session creation, conversation history, expandable agent
actions and a message composer. It uses Ichuchu's existing theme, font and controls.

Edit a server and select **Open as → agents**. Choose the Deck profile (`default`
unless you use another profile). The executable is discovered in the remote PATH
and common Go/Homebrew/Nix locations; set its full path if needed. **Test connection**
checks SSH trust/authentication, Deck capabilities, installed agents and Recall.
Existing server profiles continue to open terminals.

## Requirements and interaction scope

The CLI contract was checked against Agent Deck **v1.16.35**, commit
[`c5bf3195e895607680c4ca0f77a8e6cba89eb602`](https://github.com/asheshgoplani/agent-deck/tree/c5bf3195e895607680c4ca0f77a8e6cba89eb602).
The app probes capabilities instead of accepting a version string alone. Native
chat supports local Claude and Codex sessions. Other agents can be opened in the
terminal. Deck sessions owned by a different SSH host must be opened through a
connection to that host; the app does not forward prompts through Deck proxies.

Ordinary prompts use native chat. Permissions, trust/login screens, interactive
questions and model/account menus use **Open this session in terminal** (`>_`).
The app invokes `agent-deck -p PROFILE session attach FULL_ID`: Deck resolves the
session's saved tmux socket. It does not create an outer tmux session or guess a
socket/session name. There are no native Approve/Allow buttons or a misleading
Stop button that kills the entire agent process.

The session list and chat always use SSH commands without a PTY. Choosing Mosh
for the server applies to the optional terminal, including its existing UDP
reachability requirements. SSH jump hosts use the same credentials, host-key
checks and route snapshots as other Ichuchu connections.

## History and new sessions

History uses `recall timeline` and `recall follow` with the
[`agent-deck.recall.rows/v2` contract](https://github.com/asheshgoplani/agent-deck/blob/c5bf3195e895607680c4ca0f77a8e6cba89eb602/docs/recall-timeline.md).
If Recall is disabled, the app offers a confirmation before enabling it. Recall
is a **global Mac setting across Deck profiles**; enabling it may index existing
conversations. No daemon, HTTP listener, hooks, permission bypasses or transcript
event flags are installed or enabled automatically.

Projects for **New session** come from the full working-directory paths of local
sessions in the selected profile. Equal folder names at different paths remain
separate projects. Deck does not expose a separate project registry in this
contract. If the profile has no known projects, create its first session on the
Mac. Agent/model/effort/account choices come from the remote CLI; advertised
models do not guarantee provider access.

Creation uses `launch --no-parent`. An optional first message becomes a saved
draft in the exact new chat. Press **send** when ready. This keeps first-run setup
and capacity-queued sessions visible before the first task is delivered.
If the creation response is lost, review the refreshed session list before
explicitly allowing another creation; the app never guesses ownership from a
matching title or path and never retries launch automatically.

## Delivery, reconnection and privacy

Prompts travel in SSH stdin using `--message-file - --queue
--require-input-prompt --no-tag`. They are not interpolated into shell code.
Sending is disabled when the server lacks the guarded-send capability. Deck can
queue messages while an agent is busy, and the chat displays delivery receipts.
Only a landed transcript row ID merges a local message with remote history.
Identical prompt text is not a deduplication key.

The app saves a local send intent before execution. A lost response remains
**delivery unconfirmed**; reconnecting never resends that intent. Review the
history/queue before an explicit resend. A settled `typed`/`submitted` receipt
also remains unconfirmed. Cancelling a queued message is only shown as cancelled
when Deck returns the `cancelled` outcome; `already_sent` or `not_found` do not
prove cancellation.

Synchronization runs while the workspace screen is in the foreground. Network
failures reconnect with bounded backoff, subject to the same route and host-key
policy. On return to the screen the app reconciles the list, history and known
receipts. Leaving the app closes local readers; agents keep running on the Mac.
This release does not provide background push notifications. Remote followers
have a 55-second lease so a lost no-PTY SSH channel cannot leave an indefinite
idle reader; healthy followers resume from their last committed cursor.

History, drafts and receipts live in the existing SQLCipher database and are
excluded from portable host/key backups. Cache identity includes the SSH route,
Deck profile and verified server keys. Defaults retain 20 recent unprotected
chats and target 50 MiB per host, with at most 1,000 rows / 2 MiB per cached chat.
Drafts and unresolved delivery records are protected from automatic eviction
and may exceed the cache target. Clearing phone history only removes unprotected
cache records; it does not delete remote sessions or local drafts.

The renderer supports text, bold, inline/fenced code and explicit HTTP(S) links.
It does not execute HTML or fetch remote images. Tool output truncation supplied
by Deck remains visible. Transcript history is not a trusted permission request.

## Compatibility and validation

Room schema 14 adds host mode/preferences and local agent state. Migrated hosts
default to Terminal. Portable backup payload 4 includes the new host preferences;
payloads 1–3 retain their backward defaults. The original terminal read API is
unchanged; process I/O has separate stdout/stderr, stdin EOF and exit-status APIs.

JVM contract tests cover quoting, chunked UTF-8/NDJSON, row reconciliation,
unknown receipts and backup compatibility. Opt-in tests exercise actual JNI/SSH,
Compose, SQLCipher, schema 13→14 migration, and SSH/Mosh attach with isolated
fixtures. See [the fixture instructions](../android/test-fixtures/README.md).
Synthetic fixture acceptance is separate from real provider, physical-phone,
permission-menu and network-roaming acceptance.
