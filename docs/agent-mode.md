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

A fresh agent may have no transcript until its first request. This is a stable
empty chat, with the composer available as soon as the agent is ready. The app
checks quietly for the first transcript instead of repeatedly showing a loader.

Some Codex launchers (including remote processes that do not hold a local rollout
file) leave Deck without a native conversation ID. **Choose existing history**
lists indexed Codex conversations in the exact project. Select the correct title,
date and native ID, then confirm **Link selected history**. The app verifies that
native history is readable and saves `codex-session-id` on the selected Deck
session. It never chooses a conversation automatically from its directory.

Projects for **New session** include every group from `group list --json`,
including nested groups without active sessions. `group show --json` supplies
each group's effective `default_path`; the group identifier itself is never used
as a filesystem path. If a group has no default directory, enter an absolute
project path when creating the session. Existing local session directories are
also included, and **refresh projects** reloads the catalog. Agent/model/effort/
account choices come from the remote CLI; advertised models do not guarantee
provider access.

Creation uses `launch --no-parent`. An optional first message becomes a saved
draft in the exact new chat. Press **send** when ready. This keeps first-run setup
and capacity-queued sessions visible before the first task is delivered.
If the creation response is lost, review the refreshed session list before
explicitly allowing another creation; the app never guesses ownership from a
matching title or path and never retries launch automatically.

## Delivery, reconnection and privacy

### Photos and files

Use **+ file** beside the composer to choose photos, documents or any other file
through Android's system picker. A message can have up to four attachments,
20 MiB each. The app shows upload progress and a cancel action. Uploaded files
appear in a horizontal strip with a visible count; the newest file is brought
into view. Swipe through the strip and remove a selected attachment with **×**.
An attachment-only message can be sent without typing text.
With large text and the keyboard open, the strip uses one row and a selected/limit
counter such as **2/4**, leaving room to read the conversation.

Selecting a file uploads a bounded temporary copy over a separate SSH connection,
so session status monitoring continues. The upload uses the existing authenticated
route and host-key checks. The app creates a unique owner-only directory and file
in the host's temporary directory, verifies the byte count and removes incomplete
transfers. Uploaded file paths and metadata are saved with the encrypted chat
draft; leaving the screen or reconnecting retains them. The local scratch copy is
deleted after the upload attempt, and originals remain in the document provider.

Press **send** to give the agent the message and exact host-local file paths. Both
Claude and an already running Codex can read those files using their usual tools
and permissions, including an image-viewing tool for screenshots. This does not
use Deck's `session send --image`, which rejects running Codex sessions. Files
remain in the remote temporary directory according to that host's cleanup policy;
removing a chip removes it from the draft. Chat/attachment metadata and local
scratch files are excluded from portable host/key backups.

### Message order and delivery

At the end of a chat, the view follows new messages and updates to a streaming
reply. It also keeps the end visible as the keyboard opens or closes and the
composer changes height. A reply taller than the screen is aligned at its end.

Scrolling up suspends following. Incoming messages leave the reading position
alone. When the keyboard or composer changes height, the text at the lower edge
of the visible history moves with that edge and returns when the viewport grows.
Scrolling while typing establishes a new reading position. Scroll back to the
end or use **↓ latest** to resume following.

Native history keeps Agent Deck's source order. Local delivery records are placed
at the original send boundary, with server timestamps used for older cached
receipts, so a delayed receipt stays beside its request instead of appearing
after later replies. A confirmed native row ID replaces the local record;
identical text is never used to guess whether a message was delivered. Queue
imports are reconciled against the owning session even when you switch chats
while a request is in flight. Older cached duplicate receipts are repaired on
read, retaining drafts, attachments and send positions.

Prompts travel in SSH stdin using `--message-file - --queue
--require-input-prompt --no-tag`. They are not interpolated into shell code.
Sending is disabled when the server lacks the guarded-send capability. Deck can
queue messages while an agent is busy, and the chat displays delivery receipts.
Only a landed transcript row ID merges a local message with remote history.
Identical prompt text is not a deduplication key.

A Codex session without a native conversation ID uses Deck's guarded composer
fallback when the host advertises it. Deck's queue worker does not forward this
option, so this send runs directly with a bounded wait for the agent to become
idle. An explicit `submitted` confirmation is shown as **submitted to agent**;
it does not invent a transcript row or bind history by project directory.
Busy or interactive sessions are not interrupted. Established Codex sessions
and Claude continue to use the durable queue.

A session marked **waiting to start** is queued behind its Deck group's
concurrent-session limit, independently of model or reasoning settings. The
chat explains that a slot must become available and keeps the editable draft.
The app does not change the host's concurrency settings.

The app saves a local send intent before execution. A lost response remains
**delivery unconfirmed**; reconnecting never resends that intent. Review the
history/queue before an explicit resend. A settled `typed`/`submitted` receipt
without explicit submission confirmation also remains unconfirmed. Cancelling a queued message is only shown as cancelled
when Deck returns the `cancelled` outcome; `already_sent` or `not_found` do not
prove cancellation.

A connected workspace keeps status monitoring active in a foreground service
when you switch screens or background the app. The ongoing **agents connected**
notification has a **Disconnect** action for all agent connections; the workspace
also has a per-host **disconnect** button. Disconnecting leaves the remote agents
running. Process death does not silently restore authenticated connections.

The main host list shows active agent connections with the same green indicator
and **disconnect** action as SSH/Mosh terminals. Tap the host card to reopen its
workspace. The button or swipe-to-disconnect closes this host's agent connection
and any open terminal tabs after confirmation; other hosts stay connected. A host
must be disconnected before it can be deleted.

Allow Android notifications to receive ready, waiting, terminal-attention and
error transitions for every session, including the chat you are currently viewing.
The initial list and unchanged/reconnected status samples stay silent. Transition
events also catch short turns between list polls; event fingerprints prevent
replaying the same turn. Tapping an
alert opens that exact host and session through the app's normal lock screen.
Lock-screen public previews omit project/session names and prompts are never
included. Android notification-channel and battery/network restrictions still
apply: these are local notifications from an active SSH connection, not a cloud
push service that works after disconnection.

History follows only the visible chat. Network reads reconnect with bounded
backoff and preserve the route/host-key checks; mutations are never replayed.
Changes requiring authentication or a new host-key decision require reopening
the connection. On return, the app reconciles history and known receipts. Remote
followers have a 55-second lease so a lost no-PTY SSH channel cannot leave an
indefinite idle reader; healthy followers resume from the committed cursor.

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
