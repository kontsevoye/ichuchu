# SSH jump hosts

A server can use an explicit, ordered list of up to three saved SSH profiles as
jump hosts. For example, `This device → Bastion → Gateway → Production` opens
one terminal on Production. Each hop uses its own username, port and credentials.
Private keys stay on the Android device; agent forwarding is not required.

## Configure a route

1. Save the intermediate servers as ordinary SSH profiles.
2. Edit the destination server and select SSH transport.
3. Under **SSH JUMP HOSTS**, add the intermediate profiles in connection order.
   Use the arrows to reorder them or remove a hop with **×**.
4. Review the complete path and save. **Test connection** verifies the SSH route
   without starting a shell or running the post-connect command.

The list specifies the complete path. A selected hop's own saved jump list is
not included. Its post-connect command and multiplexer settings are not run;
those settings apply only to the destination. Changing a hop's endpoint or
credentials affects future connections that reference that profile. A profile
used by another route cannot be deleted until those references are removed.

The device resolves the first host. Later hostnames are resolved by the preceding
SSH server, so private DNS names can work without being resolvable on Android.
Each intermediate server must allow TCP forwarding to the next endpoint. For
OpenSSH, inspect `AllowTcpForwarding`, `DisableForwarding`, `PermitOpen`, `Match`
and authorized-key restrictions. SSH login success does not prove that forwarding
is permitted. No shell or SSH executable is needed on an intermediate server.

## Trust and connection lifecycle

Every hop's host key is verified before its credentials are sent. Dialogs identify
the endpoint and position in the route. Internal endpoints use a trust identity
derived from their endpoint path, including usernames and ports, so identical
private IP addresses behind different bastions do not share trust. Renaming a
profile or importing it with new database IDs does not change the endpoint path.
Host-key trust is not included in backup exports.

A changed key requires explicit replacement of the trusted key. Automatic
reconnect stops when a known host key changes. A failed route never falls back
to a direct connection. The route is used for terminal I/O, SFTP, connection tests
and supported multiplexer operations, including their auxiliary SSH connections.

Each tab owns its connection stack. Disconnect and failed setup release children
before parents. Network interruptions rebuild the whole stack with bounded retry;
authentication errors, host-key rejection and forwarding refusal stop retry.
`exit` ends the shell normally. Plain SSH reconnect starts a new shell; use the
supported multiplexer to return to persistent remote work.

Active tabs keep a snapshot of the route. If its profiles or keys change, reconnect
requires closing the old tab and opening a fresh connection. A hop's requirement
for local user verification applies to the route as well. Credentials are not
serialized as a reconnect session for restoration after process death.

## Compatibility

Jump routes currently support SSH profiles only. Mosh uses UDP after its SSH
bootstrap; an SSH forwarding chain alone cannot carry that traffic. Switching
transport preserves the configured list but requires removing it before saving
an unsupported combination. TailscaleSSH and LocalShell are not jump endpoints.
Arbitrary ProxyCommand, ssh_config import, MFA challenge dialogs and connection
pooling across tabs are outside this implementation.

Database schema 12 adds an ordered jump-host ID list with an empty default for
existing profiles. Backup payload 3 preserves and remaps route references during
transactional import. Payloads 1 and 2 remain readable as direct connections.
Older app versions reject payload 3. APK downgrade across a database migration
requires a separately verified compatible backup/restore procedure.

## Validation

Run JVM and native integration checks from the pinned Nix shell:

```sh
nix develop
cd android
./gradlew :app:testDebugUnitTest
```

The opt-in `NativeSshJumpTest` suite uses a synthetic loopback server with internal
SSH endpoints inside forwarding channels, including encrypted-key/password auth,
large shell I/O, SFTP and cancellation. See [fixture instructions](../android/test-fixtures/README.md).
Emulator acceptance is separate from physical-device, real-network and real-sshd
configuration acceptance.
