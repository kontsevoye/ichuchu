#!/usr/bin/env bash
# Run inside the pinned devshell; rebuild JNI before every APK assembly.
set -euo pipefail

if [[ "${ICHUCHU_NIX_SHELL:-}" != 1 ]]; then
  echo 'Run: nix develop --command build-apk debug|release|signed-release' >&2
  exit 1
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "${1:-}" == signed-release ]]; then
  shift
  exec python3 "$repo_root/build-support/signed-release.py" "$@"
fi
variant="${1:-debug}"
bundle=false
if [[ "${2:-}" == --bundle && "$variant" == release ]]; then bundle=true; fi
if [[ $# -gt 2 || ( $# -eq 2 && "$bundle" != true ) || ( "$variant" != debug && "$variant" != release ) ]]; then
  echo 'Usage: build-apk debug|release [--bundle] | signed-release [options]' >&2
  exit 2
fi

export ZIG_GLOBAL_CACHE_DIR="${ZIG_GLOBAL_CACHE_DIR:-$repo_root/.cache/zig}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$repo_root/.cache/gradle}"
export ANDROID_ABI_FILTERS=arm64-v8a
mkdir -p "$ZIG_GLOBAL_CACHE_DIR" "$GRADLE_USER_HOME"

[[ -d "$ANDROID_NDK_HOME/toolchains/llvm" ]] || { echo 'Pinned Android NDK is unavailable' >&2; exit 1; }
[[ -x "$JAVA_HOME/bin/java" ]] || { echo 'Pinned JDK is unavailable' >&2; exit 1; }

(
  cd "$repo_root/zig-src"
  zig build -Doptimize=ReleaseSmall -Dtarget=aarch64-linux-android.24 jni
)

case "$variant" in
  debug) task=assembleDebug ;;
  release) task=assembleRelease ;;
esac
tasks=(":app:$task")
if [[ "$bundle" == true ]]; then tasks+=(":app:bundleRelease"); fi
(
  cd "$repo_root/android"
  ./gradlew "${tasks[@]}" --no-daemon --no-configuration-cache --console=plain
)

python3 - "$repo_root/android/app/build/outputs/apk/$variant" "$repo_root/android/app/src/main/jniLibs/arm64-v8a/libichuchu_jni.so" <<'PY'
import hashlib
import json
from pathlib import Path
import sys
import zipfile

directory, native = map(Path, sys.argv[1:])
metadata = json.loads((directory / "output-metadata.json").read_text())
if len(metadata["elements"]) != 1:
    raise SystemExit("Expected one arm64 APK output")
apk = directory / metadata["elements"][0]["outputFile"]
with zipfile.ZipFile(apk) as archive:
    packaged = archive.read("lib/arm64-v8a/libichuchu_jni.so")
    if packaged != native.read_bytes():
        raise SystemExit("APK contains a stale or different JNI library")
print(f"APK: {apk}")
print(f"SHA256: {hashlib.sha256(apk.read_bytes()).hexdigest()}")
PY
