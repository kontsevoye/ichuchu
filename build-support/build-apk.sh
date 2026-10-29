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

[[ -x "${JAVA_HOME:-}/bin/java" ]] || { echo 'Pinned JDK is unavailable' >&2; exit 1; }
bash "$repo_root/build-support/build-native.sh"

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

python3 "$repo_root/build-support/verify-apk-native.py" \
  "$repo_root/android/app/build/outputs/apk/$variant" \
  "$repo_root/android/app/src/main/jniLibs/arm64-v8a/libichuchu_jni.so"
