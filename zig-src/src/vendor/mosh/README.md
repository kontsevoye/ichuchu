# Pinned Mosh shutdown overlay

`shutdown_patch.zig` applies one checked source overlay to
`zig-mosh-client@7ddaa2df217ee2e48106a2c970db4a22403924a8`. The dependency pin
and global source cache remain unchanged. The build generates a module copy
with a patched `core.zig`; an unexpected patch context fails the build.

SSP remote shutdown uses state number `UINT64_MAX`. The existing core applies
and acknowledges this state but keeps running. The overlay stops the client
after the last queued datagram, including the final ACK, is successfully sent.
This follows the reference client's
[`counterparty_shutdown_ack_sent()` handling](https://github.com/mobile-shell/mosh/blob/master/src/frontend/stmclient.cc).
An ordinary state, queued-but-unsent ACK, or network timeout is not a clean exit.

Android maps the native stopped state to normal shell completion. JNI preserves
unsigned counter bit patterns in Java longs, including the shutdown sentinel.

Run `zig build test-mosh` for real UDP ACK decoding and timeout checks. Android
acceptance uses the real Mosh fixture described in `android/test-fixtures/README.md`.
Remove or rebase this overlay when updating the upstream dependency.
