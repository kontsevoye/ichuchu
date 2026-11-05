# Application QA checklist

Use synthetic profiles, keys and files on a disposable emulator. Live-agent
acceptance uses separately created QA sessions and the actual configured launcher.
Never run destructive cases against a personal host or clear a user's app data.
See [test fixtures](../android/test-fixtures/README.md), [updater acceptance](updating.md)
and [release verification](releasing.md) for reproducible commands.

For each run record commit, APK hash, Android API/ABI, display/font configuration,
steps, expected/actual behavior and evidence. Use PASS, FAIL, PARTIAL (only some
listed variants exercised), STATIC (code review
only), BLOCKED (environment missing) or NOT RUN. An automated assertion that a
control exists is insufficient: inspect its clipped bounds and an actual screenshot.
This is a risk-based regression matrix, not proof that every possible combination
of device, server and input has been exhausted.

## Global navigation and lifecycle

- [ ] NAV-01 Fresh install opens an empty host list without errors.
- [ ] NAV-02 All routes open and Back returns to the expected parent.
- [ ] NAV-03 Rotation preserves editable state and does not duplicate actions.
- [ ] NAV-04 Background/foreground retains sessions, drafts and selected tabs.
- [ ] NAV-05 Process restart restores durable data without implying a live connection.
- [ ] NAV-06 Rapid repeated navigation/back/connect/disconnect does not crash.
- [ ] NAV-07 IME open/close leaves controls visible and closes on terminal exit.
- [ ] NAV-08 320dp width and 200% font retain readable content and usable controls.
- [ ] NAV-09 Landscape and split-screen retain reachable actions and scrolling.
- [ ] NAV-10 Light/dark/system themes remain legible, including errors and selection.
- [ ] NAV-11 TalkBack identifies icon actions, state, focus and destructive controls.
- [ ] NAV-12 Hardware keyboard, touch and accessibility activation reach the same actions.
- [ ] NAV-13 Notifications denied or dismissed do not break app navigation.
- [ ] NAV-14 Repeated launch, low-memory recreation and active work do not leak resources.

## Host list

- [ ] HOST-01 Empty state exposes creation and local shell.
- [ ] HOST-02 Create/edit/reopen preserves all profile fields.
- [ ] HOST-03 Search handles case, Unicode, spaces, no matches and clearing.
- [ ] HOST-04 Long names, addresses and many hosts remain usable.
- [ ] HOST-05 Reorder both directions, autoscroll and restart preserve order.
- [ ] HOST-06 Filtering prevents ambiguous drag reordering.
- [ ] HOST-07 Long press selects once; drag/select haptic feedback is bounded.
- [ ] HOST-08 Connect/disconnect state represents terminal and agent connections.
- [ ] HOST-09 Opening a connected host resumes its existing workspace.
- [ ] HOST-10 Delete/cancel and multi-selection affect only chosen inactive profiles.
- [ ] HOST-11 Active-host deletion/disconnection has the correct guard and scope.
- [ ] HOST-12 Deleting a bastion cannot silently change another host's route.

## Add/edit host and connection test

- [ ] EDIT-01 Empty/whitespace connection fields disable Save/Test; only Save requires a display name.
- [ ] EDIT-02 Ports 1 and 65535 work; 0, 65536, negative, text and overflow are rejected.
- [ ] EDIT-03 Host/username normalization does not corrupt IPv6 or Unicode input.
- [ ] EDIT-04 Password text is preserved exactly, including spaces and symbols.
- [ ] EDIT-05 Key auth requires an existing selected private key.
- [ ] EDIT-06 Changing key clears obsolete passphrase; encrypted keys remain selectable.
- [ ] EDIT-07 Transport/auth switching preserves compatible values and rejects invalid pairs.
- [ ] EDIT-08 Multiplexer choices match SSH/Mosh/local transport capabilities.
- [ ] EDIT-09 Agent mode validates profile/executable and rejects unsupported transport.
- [ ] EDIT-10 Auto-run text, multiline commands and shell metacharacters persist correctly.
- [ ] EDIT-11 Rapid repeated Save creates exactly one host and navigates once.
- [ ] EDIT-12 Failed save leaves the form editable with an actionable error.
- [ ] EDIT-13 Test success/failure/offline/auth rejection is accurately reported.
- [ ] EDIT-14 Editing connection fields invalidates any previous successful test result.
- [ ] EDIT-15 Cancel, Back or changed inputs cannot accept a stale test result/prompt.
- [ ] EDIT-16 New host key accepts/rejects explicitly; changed key requires replacement consent.
- [ ] EDIT-17 Test respects global, host and bastion authentication requirements.
- [ ] EDIT-18 Jump route supports 0–3 explicit hops, ordering and persistence.
- [ ] EDIT-19 Self/duplicate/deleted/unsupported bastions and excessive depth are rejected.
- [ ] EDIT-20 Save/Test/host-key dialogs remain reachable with IME and large font.

## SSH keys

- [ ] KEY-01 Empty list, generate and generated public-key display work.
- [ ] KEY-02 Encrypted and unencrypted Ed25519 generation produces usable keys.
- [ ] KEY-03 Import valid OpenSSH/PEM and supported RSA/EC formats.
- [ ] KEY-04 Empty/public-only/arbitrary/truncated/corrupt private-key input is rejected.
- [ ] KEY-05 Encrypted private-key import remains possible without revealing its passphrase.
- [ ] KEY-06 Duplicate names are handled consistently; blank rename is rejected.
- [ ] KEY-07 Long key paste keeps Import visible and IME Done imports the entire input.
- [ ] KEY-08 Copy exposes only the selected public key, not private material.
- [ ] KEY-09 Delete/cancel shows usage and clears references atomically.
- [ ] KEY-10 Rapid generate/import/delete and backgrounding do not crash or corrupt storage.

## Terminal, tabs and command palette

- [ ] TERM-01 Local shell starts, accepts input and exits normally.
- [ ] TERM-02 SSH password/key/encrypted-key/none auth works through supported transports.
- [ ] TERM-03 Authentication/DNS/refusal/timeouts produce recoverable errors.
- [ ] TERM-04 EOF/exit closes the session; transport loss reconnects according to policy.
- [ ] TERM-05 Closing active/background/last tab preserves remaining tabs and navigation.
- [ ] TERM-06 Keyboard closes after last-tab exit, remains for surviving active tabs.
- [ ] TERM-07 Unicode, CJK, emoji, combining characters and multiline paste render correctly.
- [ ] TERM-08 ANSI colors, alternate screen, resize and scrollback remain consistent.
- [ ] TERM-09 Selection/copy/paste and clipboard links preserve intended bytes.
- [ ] TERM-10 Ctrl/Alt/Shift/Esc/Tab/arrows and hardware shortcuts send correct sequences.
- [ ] TERM-11 Accessory customization and custom commands survive restart and ordering.
- [ ] TERM-12 Tab rename, duplicate, switch and all tab-display modes work.
- [ ] TERM-13 Command palette filters, empty results and actions work with IME.
- [ ] TERM-14 Reconnect/background/network changes do not replay user commands.
- [ ] TERM-15 Large output/input and rekey do not truncate or deadlock.
- [ ] TERM-16 Host-key/auth cancellation closes every intermediate connection.

## Mosh and multiplexers

- [ ] MUX-01 Real encrypted Mosh UDP carries terminal input and output.
- [ ] MUX-02 Missing mosh-server, blocked UDP and unsupported jump route fail clearly.
- [ ] MUX-03 Graceful Mosh exit and shutdown timeout/ACK paths complete.
- [ ] MUX-04 Wi-Fi/cellular/airplane-mode transitions preserve or recover the session.
- [ ] MUX-05 tmux discovery works under sh and zsh, including no sessions and errors.
- [ ] MUX-06 Create/list/switch/duplicate/detach/reattach work over SSH.
- [ ] MUX-07 The same actions work over Mosh with remote shell state preserved.
- [ ] MUX-08 Names/commands containing spaces and quotes are passed safely.
- [ ] MUX-09 Exact Agent Deck attach uses its own socket/session and survives reconnect.
- [ ] MUX-10 Zmx supported paths work; unavailable/unsupported choices explain limitations.

## Files

- [ ] FILE-01 Loading, error, empty directory and no filter matches are distinct.
- [ ] FILE-02 Open directory/up/home/root/Back navigate correctly.
- [ ] FILE-03 Changing directory resets search and list position appropriately.
- [ ] FILE-04 Case-insensitive Unicode search and name/size/date sorts are stable.
- [ ] FILE-05 Long names, quotes, newlines, leading dashes and hidden files remain safe.
- [ ] FILE-06 Symlink to file/directory and broken link have predictable behavior.
- [ ] FILE-07 Open/download/share uses a private staged file and correct MIME type.
- [ ] FILE-08 Upload zero-byte, binary, large and multiple files without truncation.
- [ ] FILE-09 File-provider cancellation, missing size and revoked permission are handled.
- [ ] FILE-10 Failed/cancelled/interrupted transfers clean temporary state and allow retry.
- [ ] FILE-11 Existing filenames and overwrite/delete confirmation protect other data.
- [ ] FILE-12 SFTP through multiple bastions, after idle and rekey preserves exact bytes.
- [ ] FILE-13 Permission denied, disconnected tab and deleted directory fail recoverably.
- [ ] FILE-14 Copy/refresh/upload actions have accessible labels and usable touch bounds.

## Agent Deck workspace and session creation

- [ ] AGENT-01 Missing/old CLI and unsupported capabilities show an actionable state.
- [ ] AGENT-02 Session/project/status/search filters include empty groups and nested paths.
- [ ] AGENT-03 Empty/loading/error/offline lists are distinct and refresh recovers.
- [ ] AGENT-04 Create Claude/Codex with defaults and explicit model/effort works.
- [ ] AGENT-05 Invalid/blank/duplicate/Unicode names and paths are handled safely.
- [ ] AGENT-06 Queued launch explains the slot limit and preserves an editable draft.
- [ ] AGENT-07 Repeated Create/Send taps cannot duplicate a request.
- [ ] AGENT-08 First message in a new empty/unlinked session is sent once or clearly refused.
- [ ] AGENT-09 Exact native history loads automatically when Deck supplies its identity; manual recovery requires explicit selection, never directory-based guessing.
- [ ] AGENT-10 Opening terminal attaches to the exact chosen session and socket.
- [ ] AGENT-11 Connect/disconnect/back/rapid reconnect retains correct ownership.
- [ ] AGENT-12 Background status notifications deduplicate and open the exact chat.
- [ ] AGENT-13 Denied notification permission still permits normal foreground use.
- [ ] AGENT-14 Real installed Codex: a fresh welcome-logo session accepts the first message exactly once, receives a provider response, binds history automatically and reopens it; the second message also lands once.
- [ ] AGENT-15 Verify the actual launcher mode and hooks, including shared app-server compatibility; synthetic fixtures do not satisfy live Codex acceptance.
- [ ] AGENT-16 Change model only, reasoning only, both and model default within one real Codex conversation; verify the next native turn context, unchanged conversation ID and global defaults.
- [ ] AGENT-17 Model controls refuse busy turns, queued sends, menus and existing terminal drafts (including text equal to the empty placeholder); no cancellation or message is submitted.
- [ ] AGENT-18 Current model/effort, per-model choices, stale/missing catalogs, unsupported versions, invalid pairs and unavailable models give accurate states.
- [ ] AGENT-19 Same-value Apply, rapid taps, Close/Back, session switching, offline and lost-control responses never report unconfirmed success or replay a control.
- [ ] AGENT-20 Session options and model dialogs remain reachable at 320dp/200% with IME; closing preserves chat/composer dimensions, draft, attachments and scroll position.
- [ ] AGENT-21 Long-press exposes restart/stop/delete for the exact card; Cancel/Back changes nothing, and remote-owned/offline/in-flight actions are disabled.
- [ ] AGENT-22 Restart preserves the conversation; Stop retains the entry; Delete removes only that entry/process and keeps native history and project/worktree files.
- [ ] AGENT-23 Rapid taps, changed ownership, a missing session and a lost action response cannot repeat a mutation or claim unconfirmed success; other sessions remain intact.
- [ ] AGENT-24 Long titles and 320dp/200% keep every lifecycle action and confirmation reachable, with whole button labels and scrollable content.

## Agent chat, drafts and attachments

- [ ] CHAT-01 History pages/cursors/empty transcripts load without endless loops.
- [ ] CHAT-02 Message/receipt order and stable IDs survive refresh and queue reconciliation.
- [ ] CHAT-03 Leaving during queue import and reopening never produces duplicate-key crashes.
- [ ] CHAT-04 Legacy duplicate cache repairs without losing draft, files or cursor.
- [ ] CHAT-05 Following end handles new rows, growing rows and a long final message.
- [ ] CHAT-06 Reading earlier history does not jump on incoming data.
- [ ] CHAT-07 IME/composer resize preserves the lower reading edge or the followed end.
- [ ] CHAT-08 Scroll-to-end and Latest resume following; gestures suspend it.
- [ ] CHAT-09 Markdown, long code, links, Unicode and very large messages remain usable.
- [ ] CHAT-10 Offline draft survives navigation, reconnect and process restart.
- [ ] CHAT-11 Busy Claude uses its queue; ordinary busy Codex accepts native input; menus, existing terminal drafts and unknown results never cause an unsafe send or replay.
- [ ] CHAT-12 Actual picker supports images and arbitrary binary files.
- [ ] CHAT-13 0/1/4/5 files and exactly/over 20 MiB enforce limits consistently.
- [ ] CHAT-14 Multiple attachments show count/newest file; every removal control is visible.
- [ ] CHAT-15 Upload hash, owner-only remote permissions and incomplete cleanup are correct.
- [ ] CHAT-16 Cancel/remove/re-add/reconnect retains only intended attachments.
- [ ] CHAT-17 Attachment-only send, text+files and failed send preserve recoverable state.
- [ ] CHAT-18 Send/remove and one readable history line remain visible at 320dp/200% with IME.
- [ ] CHAT-19 Complete/streaming/multiple memory citations collapse to readable sources; long paths, notes and Unicode expand without losing the answer or changing cached text.
- [ ] CHAT-20 Citation-like text inside user messages, code fences, inline code, quotes, malformed or oversized blocks remains literal and inert.
- [ ] CHAT-21 Real Codex accepts a follow-up during a running tool without waiting for turn completion or creating a Deck queue item; history contains exactly one fresh native user row.
- [ ] CHAT-22 Native sends handle multiline/Unicode/long paste/narrow terminal input; drafts, menus, control characters, native commands and older pending Deck deliveries are protected.
- [ ] CHAT-23 Reconnect, delayed indexing, repeated text, changed pane/thread and missing or ambiguous delivery evidence never invent a landed row or resend a request.
- [ ] CHAT-24 Remaining context uses current native usage, handles 0/100/unknown/compaction and clears on navigation/disconnect; narrow footers fall back only to the exact conversation.
- [ ] CHAT-25 Current model, reasoning and remaining context appear together above input, update after model changes, and clear on navigation/disconnect. Model names shorten before reasoning/percent; values and transient sending do not move history/input or overlap Send/attachment controls at 320dp/200% with IME.
- [ ] CHAT-26 Shared-backend `← for agents` shortcuts, with and without warnings, permit one native send and idle model controls; actual menus and drafts still refuse before typing.

## Settings, appearance and custom actions

- [ ] SET-01 Every section scrolls into view and Back preserves preferences.
- [ ] SET-02 Light/dark/system selection, preview and search persist correctly.
- [ ] SET-03 Font selection and minimum/maximum/invalid font size work.
- [ ] SET-04 Tab modes/accessory ordering, duplicates, empty layout and reset work.
- [ ] SET-05 Add/edit/delete custom keys, modifiers, escapes and shortcuts work.
- [ ] SET-06 Blank/duplicate/very long custom action labels and values do not break the editor.
- [ ] SET-07 App lock, connect verification and hide-screen switches persist.
- [ ] SET-08 Keep-screen/wake/background behavior matches selected settings.
- [ ] SET-09 All sheets/dialogs remain usable with large font, IME and Back.

## Encrypted backup and storage

- [ ] BACKUP-01 Export requires matching nonempty passphrases and explicit destination.
- [ ] BACKUP-02 Export includes intended hosts/keys/settings and excludes runtime secrets/cache.
- [ ] BACKUP-03 Cancelled picker/write failure leaves the app usable and clears sensitive state.
- [ ] BACKUP-04 Correct passphrase decrypts; wrong passphrase/tampering/truncation are rejected.
- [ ] BACKUP-05 Unsupported version, oversized input and malformed fields fail before mutation.
- [ ] BACKUP-06 Preview accurately distinguishes new, duplicate and replaced items.
- [ ] BACKUP-07 Import cancel is a no-op; confirm is atomic and repeated taps cannot double-apply.
- [ ] BACKUP-08 Key/jump references, ordering and collisions survive export/import.
- [ ] BACKUP-09 Database migration/reopen preserves profiles, drafts and encrypted metadata.
- [ ] BACKUP-10 Database, journals and backups do not contain plaintext credential canaries.
- [ ] BACKUP-11 Missing/invalid keystore material fails safely with a usable recovery message.

## App lock and authentication

- [ ] LOCK-01 Launch/resume and timeout require the configured device authentication.
- [ ] LOCK-02 Cancel/failure/no enrollment/lockout do not reveal protected content.
- [ ] LOCK-03 Background thumbnails/screenshots respect hide-screen policy.
- [ ] LOCK-04 Notifications/deep links cannot bypass app lock or per-host verification.
- [ ] LOCK-05 Backup export/import and update install respect authentication boundaries.
- [ ] LOCK-06 Rotating/backgrounding a prompt cannot duplicate an operation.

## Updates and release

- [ ] UPDATE-01 Disabled builds explain unavailability; enabled builds show actual installed version.
- [ ] UPDATE-02 Current/new release/manual check/network/API error states recover.
- [ ] UPDATE-03 Background opt-in schedules notification-only checks and respects permission.
- [ ] UPDATE-04 Manifest schema/tag/version/commit/URL/redirect validation rejects mismatches.
- [ ] UPDATE-05 Size/hash/truncation/cancellation/disk-full download failures cannot install.
- [ ] UPDATE-06 APK package/version/minSDK/ABI/signer mismatch is rejected.
- [ ] UPDATE-07 Unknown-source permission and installer cancel/failure permit explicit retry.
- [ ] UPDATE-08 Active terminals require explicit closure; install never silently closes them.
- [ ] UPDATE-09 Process death and PackageInstaller callback restore truthful session status.
- [ ] UPDATE-10 Real N→N+1 install preserves profiles/settings and reports completion.
- [ ] UPDATE-11 Signed commit, remote SHA, CI and immutable tag identify the same code.
- [ ] UPDATE-12 Published APK/AAB/update.json sizes, digests, signatures and metadata agree.

## Release gate

Run JVM and relevant native tests, the full Android build/R8/lint graph, all enabled
disposable fixture suites, formatting, release automation checks and documentation
generation. Classify failures by product defect, test defect or unavailable
environment. Do not hide skipped tests in the passed count. Reproduce and repair
confirmed defects, rerun affected paths and retain regression coverage. Record any
remaining device/provider/OS limitations explicitly before publication.
