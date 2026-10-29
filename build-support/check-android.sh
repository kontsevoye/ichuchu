#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$repo_root/.cache/gradle}"
export ZIG_GLOBAL_CACHE_DIR="${ZIG_GLOBAL_CACHE_DIR:-$repo_root/.cache/zig}"
check-native
build-apk debug
(
  cd "$repo_root/android"
  ./gradlew :app:testDebugUnitTest --no-daemon --no-configuration-cache --console=plain
)
build-apk release --bundle
