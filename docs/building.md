# Building Android APKs

Run these commands from the checkout root. The repository contains the build
environment in `flake.nix`, `flake.lock`, and `devshell.nix`; no temporary SDK or
compiler installation is required.

## Nix workflow

```sh
nix develop --command build-apk debug
nix develop --command build-apk release
nix develop --command build-apk signed-release
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
- Signed release: `android/app/build/outputs/apk/release/app-release.apk`, verified
  against the selected keystore certificate. `signed-artifacts.json` records its
  SHA-256, certificate SHA-256 and version code.

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

### Interactive terminal

Keep the keystore outside the checkout. This single command rebuilds JNI,
assembles a release, signs it and verifies the signature:

```sh
VERSION_NAME=0.2.1-local VERSION_CODE=201006 \
  nix develop --command build-apk signed-release --interactive \
    --keystore "$HOME/.local/share/android-signing/ichuchu-release.jks"
```

Passwords are entered with terminal echo disabled. Enter an empty private-key
password to reuse the keystore password. A single private-key alias is selected
automatically; use `--alias NAME` or `KEY_ALIAS` for a multi-key keystore.

Without `--interactive` or `--non-interactive`, terminal input enables prompts;
`CI=true` or non-terminal input disables them. `make signed-release` is also
available from inside `nix develop`.

### Non-interactive / GitHub Actions

```yaml
- name: Build verified signed APK
  env:
    KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}
    KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
    KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
    KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
    VERSION_NAME: 0.2.2
    VERSION_CODE: 202000
  run: nix develop --command build-apk signed-release --non-interactive
```

Supply these inputs through the process environment:

| Input | Meaning |
| --- | --- |
| `KEYSTORE_BASE64` | Base64-encoded JKS/PKCS12 content, typically an Actions secret. |
| `KEYSTORE_FILE` / `--keystore` | Existing keystore path, as an alternative to Base64. |
| `KEYSTORE_PASSWORD` | Required keystore password. |
| `KEYSTORE_PASSWORD_FILE` | UTF-8 password file instead of the password environment variable. One trailing newline is removed. |
| `KEY_ALIAS` / `--alias` | Optional with exactly one private key; required when selecting among multiple keys. |
| `KEY_PASSWORD` | Private-key password; defaults to the keystore password. |
| `KEY_PASSWORD_FILE` | Password-file alternative for a separate private-key password. |

Use one keystore source and one source per password. Keep password files outside
Git with mode 0600. Missing inputs and an invalid store password stop the command
before compilation; the signed mode never falls back to unsigned success.

To configure repository secrets with GitHub CLI, explicitly select your repository:

```sh
base64 < "$HOME/.local/share/android-signing/ichuchu-release.jks" \
  | gh secret set KEYSTORE_BASE64 --repo OWNER/REPO
gh secret set KEYSTORE_PASSWORD --repo OWNER/REPO
# Set KEY_PASSWORD too if the private key uses a different password.
```

Base64 is an encoding, so keep its value in an Actions secret. See
[GitHub's secret configuration documentation](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets).
The checked-in release and nightly workflows already use this signing command
and publish only the explicit signed APK path, not an unsigned-APK glob.

### Bundle and verification

Add `--bundle` to build, sign and verify the release AAB as well:

```sh
nix develop --command build-apk signed-release --non-interactive --bundle
```

The signed AAB is `android/app/build/outputs/bundle/release/app-release.aab`.
The APK is checked with `apksigner`, its certificate is matched to the selected
keystore entry, and app payloads are compared before/after signing. AABs are
signed and verified with `jarsigner`.

Signing secrets are removed from the JNI/Gradle build environment; only signing
tools receive password environment variables. Password values are never command
arguments. Gradle configuration caching is disabled. Base64 keystores are decoded
into a mode-0600 file in a private temporary directory and removed on normal
completion or handled failure. No passwords or keystore content are put in Nix
expressions or saved in the repository.

Use the same release key and increase `VERSION_CODE` when updating an existing
installation. Direct Gradle signing through the legacy environment variables and
`android/app/key.jks` remains compatible, but the command above keeps compilation
separate from access to signing passwords.

Run `nix develop --command python3 build-support/test-signing.py` for real
JKS/PKCS12, APK/AAB, CI/password-file and interactive-PTY checks with disposable
keys. Tests restore pre-existing signed outputs afterwards. Workflow syntax is
checked with `actionlint .github/workflows/{release,nightly}.yml` inside the shell.

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
