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
- beautiful and working terminal renderer with fully working resize, scrollback, focus, modifier keys, mouse actions 

## Status

ichuchu is independently maintained with additional security, build, and usability improvements. Issues and contributions are welcome.


### Getting Started

Checkout our [releases](https://github.com/kontsevoye/ichuchu/releases) and download the APK from there.

- **Stable:** the latest non-prerelease build
- **Nightly:** every push to `main` publishes a rolling prerelease at the [`nightly`](https://github.com/kontsevoye/ichuchu/releases/tag/nightly) tag



## Stack

- Kotlin + Jetpack Compose for the Android app
- Zig for native build orchestration and JNI/native bridge code
- Ghostty VT for terminal emulation
- `libssh2` + `openssl` for the current native SSH path
- Room for local data storage


### Development - Prerequisites

If you have nix installed, the following three steps will get you started

1. nix develop - will set you up with everything you will need.
2. running 'make build' will build the native code needed 
3. running 'make app' will build the apk and install it in a connected device. 

If you don't have nix installed, you will need

1. setup tools
- Android Studio - This will set up the needed Android SDK, Android NDK and Java runtime (JDK 17+).
- Zig 0.15.2
2. build the native library

Set `ANDROID_NDK_HOME` or `ANDROID_NDK_ROOT`, then build the JNI library for Android arm64:

```sh
zig build jni -Dtarget=aarch64-linux-android
```

That copies `libichuchu_jni.so` into `app/src/main/jniLibs/arm64-v8a/`.

3. From android studio run 
```sh
./gradlew assembleDebug
```

For a manual debug APK, run the native build first, then assemble the app:

```sh
make build
cd android && ./gradlew assembleDebug
```

The APK should include `lib/arm64-v8a/libichuchu_jni.so`; without it native SSH/key generation will fail on device.
