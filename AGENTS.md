# AGENTS.md

## Project map

- `android/app/src/main/java/io/github/kontsevoye/ichuchu/`: Kotlin/Compose app, storage, sessions, and updates.
- `zig-src/src/bridge/`: JNI and native SSH, Mosh, backup, and terminal code.
- `zig-src/src/vendor/`: dependency build adapters and the Mosh shutdown patch.
- `build-support/`: APK building, signing, release planning, and acceptance helpers.
- `docs/`: Nuxt site and maintained build, release, and updater documentation.

## Toolchain and checks

Use the pinned Nix environment from the checkout root: `nix develop`. Toolchain
versions and dependencies belong in `flake.lock`, `devshell.nix`, the Gradle
catalog, and Zig manifests; use those files as the source of truth.

Run the checks relevant to the change from inside that shell:

| Change | Check |
| --- | --- |
| Kotlin behavior | `cd android && ./gradlew :app:testDebugUnitTest` |
| Kotlin formatting | `ktfmt --kotlinlang-style <changed .kt files>`; `make lint` |
| Native Mosh protocol/shutdown | `check-native` |
| JNI or native dependency integration | `build-apk debug` |
| Release/version JavaScript | `make check-release` |
| Build shell scripts or workflows | `shellcheck build-support/*.sh`; `actionlint` |
| Nuxt site | `cd docs && pnpm install --frozen-lockfile && pnpm run generate` |

For build/signing changes, read `docs/building.md`. For versioning, release
automation, or publication, read `docs/releasing.md`. For updater changes, read
`docs/updating.md`. Use `build-support/check-android.sh` for the full native,
JVM, debug, and release APK/AAB check sequence.

Android tests live in `android/app/src/test/` and `android/app/src/androidTest/`.
Use a disposable emulator and explicit `adb -s <serial>` for device acceptance;
keep emulator results separate from physical-device validation. The updater
acceptance helper and SSH/Mosh fixture usage are documented in `docs/updating.md`
and `android/test-fixtures/README.md`.

## Zig conventions

Use `zigdoc` to discover standard-library and dependency APIs for the pinned Zig
and dependency versions. It is not supplied by the current devshell. If it is
unavailable, inspect the pinned compiler's library and resolved dependency
sources, and identify that fallback in the validation report. Avoid copying API
examples from another Zig release.

- Use `camelCase` functions, `snake_case` variables/parameters, `PascalCase` types, and `SCREAMING_SNAKE_CASE` constants.
- Prefer explicit type annotations with anonymous struct literals.
- Start modules with `//!`; order imports as `std`, `builtin`, then project modules.
- Order methods as `init`, `deinit`, public API, then private helpers.
- Pass allocators explicitly and use `errdefer` for cleanup on errors.
- Use `///` for public APIs; explain non-obvious ownership, timing, and protocol decisions.
- Assert meaningful boundary/state invariants. Keep functions near the existing 70-line soft limit.
- Keep tests beside the code; register native regression modules in `zig-src/src/main.zig` and wire the relevant build step in `zig-src/build.zig`.

For Kotlin, follow surrounding code and the repository formatter/editorconfig.
Keep JNI declarations, exported native symbols, and Android package names in sync.

## Local work and Git

Keep plans, scratch research, logs, and device evidence under ignored `.plans/`.
Update `.plans/progress.txt` when a feature is fixed, completed, or materially
advanced: update its task status and append a dated entry in the same change.
This journal is local; never stage or force-add it or other ignored work files.

Keep public, maintained instructions in `docs/`; link only to committed files.
Local `.agents/` scripts are optional workstation/orb helpers, outside the build
and CI workflow. They remain ignored and are not project setup instructions.

Before committing, inspect `git diff --cached --name-status` and confirm that
only intended source, tests, configuration, and maintained documentation are staged.
