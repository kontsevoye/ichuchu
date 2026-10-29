<p align="center">
  <img src="./assets/logo.png" alt="Ichuchu logo" width="48" height="48" />
</p>

<h1 align="center">ichuchu</h1>

<p align="center">
 A Modern, Better, Native Android SSH client powered by libghostty
</p>

<p align="center">
  <a href="https://github.com/kontsevoye/ichuchu/releases/latest">Download Latest</a> ·
  <a href="https://github.com/kontsevoye/ichuchu/releases/">ChangeLog</a> ·
</p>

---

ichuchu is an independently maintained hard fork of [chuchu](https://github.com/jossephus/chuchu), with a fresh Git history and additional personal improvements to security, builds, and usability.

The Android application ID is `io.github.kontsevoye.ichuchu`; it installs separately and keeps its own app data.



Ichuchu is a native Android SSH client powered by libghostty, a terminal-first Compose UI, and has support for both standard SSH and Tailscale SSH workflows.

### Features 
- tailscale, ssh password + key authentication
- image display using libghostty's kitty image protocol support
- more than 400 themes from the official ghostty repository
- configurable accessory keys and custom terminal actions
- per-host post-connect actions that autorun a selected custom action after SSH/Mosh connects or reconnects
- encrypted local import/export backups for SSH keys and server profiles
- verified GitHub APK updates with Android confirmation and optional background checks
- beautiful and working terminal renderer with fully working resize, scrollback, focus, modifier keys, mouse actions 

## Status

ichuchu is independently maintained with additional security, build, and usability improvements. Issues and contributions are welcome.


### Getting Started

Checkout our [releases](https://github.com/kontsevoye/ichuchu/releases) and download the APK from there.

Each checked push to `main` automatically publishes a SemVer release with signed
`ichuchu.apk` and `ichuchu.aab`. `feat:` increments minor, breaking changes increment
major, and other changes increment patch. There is one stable release stream.
See [versioning, release checks and recovery](docs/releasing.md) and
[in-app updates](docs/updating.md).



## Stack

- Kotlin + Jetpack Compose for the Android app
- Zig for native build orchestration and JNI/native bridge code
- Ghostty VT for terminal emulation
- `libssh2` + `openssl` for the current native SSH path
- Room for local data storage


### Building APKs with Nix

From the checkout root, with Nix and flakes enabled:

```sh
nix develop --command build-apk debug
nix develop --command build-apk release
nix develop --command build-apk signed-release
```

The checked-in flake and lock provide the SDK/NDK, JDK, and Zig. Build commands
rebuild arm64 JNI and verify that the APK contains the newly built native library.
Outputs are in `android/app/build/outputs/apk/`; release APKs are unsigned unless
release signing is configured. `signed-release` prompts for credentials in a
terminal and supports non-interactive CI secrets with `--non-interactive`.

For an interactive shell, run `nix develop`, then `make apk`, `make release`, or
`check-native`. `make app` also installs and opens the debug build on a connected
device; set `ANDROID_SERIAL` when selecting a device.

See [building and signing instructions](docs/building.md) for toolchain versions,
caches, external signing keys, and manual builds without Nix.

SSH routes: [Configure jump hosts](docs/ssh-jump-hosts.md).
