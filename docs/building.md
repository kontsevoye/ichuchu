# Building Android APKs

Run these commands from the checkout root. The repository contains the build
environment in `flake.nix`, `flake.lock`, and `devshell.nix`; no temporary SDK or
compiler installation is required.

## Nix workflow

```sh
nix develop --command build-apk debug
nix develop --command build-apk release
```

The default target is **arm64-v8a**, Android API 24+. Each command builds native
JNI first, runs the repository's Gradle 8.13 wrapper, and compares the native
library inside the resulting APK with the newly built file. The APK path and
SHA-256 are printed at the end.

Outputs:

- Debug: `android/app/build/outputs/apk/debug/app-debug.apk`, signed with the
  standard Android debug key.
- Release: `android/app/build/outputs/apk/release/app-release-unsigned.apk` by
  default. A fully configured Gradle release signer produces `app-release.apk`.

For interactive use:

```sh
nix develop
make apk          # rebuild native code and assemble debug APK
make release      # rebuild native code and assemble release APK
cd android
./gradlew testDebugUnitTest
```

`make build` builds only arm64 JNI. `make app` builds, installs and launches the
debug app; select the intended device with `ANDROID_SERIAL`.

The flake exposes `aarch64-darwin` and `x86_64-linux` shells. Apple Silicon may
need Rosetta for Google's x86_64 Android tools. Native Zig execution stays arm64
on the Mac. The macOS SDK and SDK discovery tool come from Nix, avoiding a
dependency on the host Xcode SDK format.

## Pinned toolchain and caches

- Zig 0.15.2.
- JDK 17 consistently used by `java`, `JAVA_HOME`, and Gradle.
- Android platform 36, Build Tools 35.0.0 and 36.0.0.
- Android NDK 29.0.14206865; both NDK environment variables point to its actual
  directory.
- Exact Nix inputs remain pinned in `flake.lock`. Gradle versions are pinned by
  `android/gradle/wrapper/gradle-wrapper.properties` and the version catalog.

First use downloads Nix packages, Zig dependencies, and Gradle/Maven artifacts.
Subsequent builds reuse caches. `build-apk` defaults to `.cache/zig` and
`.cache/gradle` inside the checkout, both excluded from Git. Existing
`ZIG_GLOBAL_CACHE_DIR` and `GRADLE_USER_HOME` overrides are honored.

For a versioned build, set the existing version environment variables:

```sh
VERSION_NAME=0.2.1-local VERSION_CODE=201006 nix develop --command build-apk release
```

## Signing a release

Keep the release keystore outside the checkout. To sign an unsigned build
interactively, enter the Nix shell and use its Android Build Tools:

```sh
nix develop
"$ANDROID_HOME/build-tools/36.0.0/apksigner" sign \
  --ks "$HOME/.local/share/android-signing/ichuchu-release.jks" \
  --out android/app/build/outputs/apk/release/ichuchu-signed.apk \
  android/app/build/outputs/apk/release/app-release-unsigned.apk
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs \
  android/app/build/outputs/apk/release/ichuchu-signed.apk
```

`apksigner` requests the keystore password interactively. Do not put passwords
in command arguments, checked-in files, or chat. Use the same signing key and a
higher `VERSION_CODE` to update an existing installation.

CI can use the existing `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`
environment variables together with `KEYSTORE_FILE` pointing to the external
keystore. The legacy `android/app/key.jks` location is still supported. Without
a complete signing configuration, Gradle assembles an unsigned release.

## Manual toolchain

With Zig 0.15.2, JDK17 and the SDK/NDK versions above installed independently:

```sh
cd zig-src
zig build -Doptimize=ReleaseSmall -Dtarget=aarch64-linux-android.24 jni
cd ../android
ANDROID_ABI_FILTERS=arm64-v8a ./gradlew :app:assembleDebug
```

Set `ANDROID_HOME`, `ANDROID_NDK_HOME`, and `JAVA_HOME` to those installations
before building. The Nix workflow supplies them automatically.
