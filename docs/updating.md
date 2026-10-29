# In-app updates

The GitHub distribution has one stable update stream:
`kontsevoye/ichuchu`, package `io.github.kontsevoye.ichuchu`. Release versions and
Android codes follow [releasing.md](releasing.md). The first release containing
this updater must be installed manually; older builds have no update client.

## User flow

In **Settings → General → Updates**, check for a release, review its version,
plain-text notes and size, download the APK, then install it. Downloads display
progress and can be cancelled. A network error preserves a previously discovered
candidate. The release link supports manual installation when an older release
has no update metadata.

Android 8+ may require permission to install apps from this source. After granting
it, return, unlock the app if necessary, and tap install again. The updater asks
before closing open terminals. SSH, Mosh and local-shell connections cannot survive
replacement of the app process. Creating/reconnecting terminals is blocked while
an install is pending; cancelling installation releases that guard.

Android's own installation confirmation is required. Pending confirmation is
opened only from resumed content inside App Lock. If the app process dies, an
explicit **continue** reuses the saved, owned installer session and obtains a new
confirmation callback. Returning from `commit()` alone never means installation
succeeded. A successful replacement is reconciled against the installed version
on next launch. There is no forced restart or silent installation.

Automatic checks are **off by default**. Enabling them schedules unique periodic
WorkManager work every 24 hours, subject to network and battery constraints.
Background work only checks and notifies; it does not download, close terminals,
or install. Notification permission/channel settings are respected. Disabling
checks cancels background-only requests; a check explicitly requested by the user
can finish. Notifications deduplicate by version code.

## Build and distribution controls

- Debug builds disable updates.
- Local release builds disable updates unless explicitly built with
  `-PenableSelfUpdate=true` (or `ORG_GRADLE_PROJECT_enableSelfUpdate=true`).
- The checked release workflow explicitly enables updates in its release APK,
  checks unsigned artifacts, signs those exact artifacts, generates metadata and
  publishes only the completed draft. A signing/publishing failure leaves the
  previous public feed unchanged.
- `BuildConfig.UPDATE_REPOSITORY` and `UPDATE_DISTRIBUTION` are compile-time
  values. Git remotes, installer package names, deep links and arbitrary settings
  do not choose the source. No GitHub token is included in the APK; the feed must
  be public.
- The isolated `updateTest` build uses `.updatetest`, a debug test certificate and
  a test-only acceptance activity. The activity and fixture APK assets are not
  part of debug/release builds or the published workflow artifact.
- Store variants are not implemented. A future store variant must exclude the
  sideload permission and installer components, as well as disable the UI.

## Delivery contract

After signing and signature verification, the release tool emits `update.json`
(schema 1) from APK-derived package/version/minSdk/ABI/certificate values and the
final signed APK bytes. The manifest contains:

```json
{
  "schemaVersion": 1,
  "distribution": "github",
  "channel": "stable",
  "packageName": "io.github.kontsevoye.ichuchu",
  "versionCode": 1001000,
  "versionName": "0.1.0",
  "minSdk": 24,
  "commit": "0000000000000000000000000000000000000000",
  "certificateSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "artifacts": [{
    "abi": "arm64-v8a",
    "name": "ichuchu.apk",
    "size": 1234567,
    "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  }]
}
```

The sizes, hashes, certificate and commit above are illustrative. Published
values are generated, not manually maintained. AAB is a separate manual/store
artifact and is never selected for in-app installation.

The client uses `/releases/latest`, rejects drafts/prereleases and validates the
stable tag, deterministic version code, distribution, package, ABI, minimum SDK,
current installed signer and exact release asset name/URL. Metadata cannot
supply an arbitrary download URL. Updates must have a strictly greater code.
ETag/304 is supported, with a bounded private cached release document; 403/429
backoff respects retry/reset headers. Invalid or missing metadata is an error,
not an “up to date” result.

All request and redirect URLs must use HTTPS, port 443, no userinfo/fragment and
one of the explicit GitHub API/release/CDN hosts. There are at most six hops.
Metadata is capped at 1 MiB; APKs at 256 MiB. A check has a 45-second overall
limit and a download three minutes, with connect/read limits as well.
Files use private `cache/updates/` UUID names, `.part`, exact byte count/SHA-256,
fsync and final rename. Cancellation/error removes partial files. Size/hash,
package/version/minSdk and exact current signer are checked again before the
PackageInstaller session is committed. SFTP FileProvider scope is unchanged.

SHA-256 and the certificate field in a release manifest are consistency checks,
not an independent trust root. PackageManager archive inspection is preflight;
Android's installer performs the final cryptographic signature and upgrade checks.
Key rotation and cross-distribution updates are deliberately unsupported.

## Validation and recovery

The reusable acceptance helper requires the pinned Nix build shell and a separate
SDK with `emulator`, `platform-tools`, and the API35 default arm64-v8a image:

```sh
nix develop --command python3 build-support/test-updater-device.py \
  --sdk /path/to/test-android-sdk
```

It creates a new AVD, chooses private unused emulator/ADB ports, uses only the
explicit emulator serial and `.updatetest` package, and stops its own emulator and
ADB server afterwards. It never installs to a physical device. Generated test
APKs/keys are not committed. Evidence lives in `.plans/update-verification/`.

Verified on AOSP API35 arm64: correct APK acceptance; rejection of wrong
package/version/minSdk/signer and truncated APK; terminal closure/creation guard;
opt-in scheduling; real unknown-source permission; system cancellation; process
death and one-action Resume; real N→N+1 update; retained SQLCipher synthetic hosts
and keys, unchanged Keystore envelope and settings; pending state cleanup.
MockWebServer/Robolectric tests cover metadata, ETag, backoff, URL policy,
size/digest/cancellation, shared requests, callback nonce/session/lock gating and
timeouts. Release tests cover metadata generation and draft recovery.

A public release/CDN download from this repository and a physical device with
hardware Keystore/biometric authentication remain separate acceptance checks.
Runtime installation was exercised on API35; other Android API levels were not
device-tested. At implementation time the new repository's latest-release endpoint
returned HTTP 404.

For an updater problem, turn off automatic checks or ship a local build with the
feature disabled. Cancel any pending installer session in the UI. After an
installed update, prefer a corrective release with a higher code and the same
signer/data schema. Do not uninstall or clear data to force a downgrade.
