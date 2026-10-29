#!/usr/bin/env bash
# Rebuild arm64 JNI through Zig's dependency graph, including on a cache hit.
set -euo pipefail

if [[ "${ICHUCHU_NIX_SHELL:-}" != 1 ]]; then
  echo 'Run inside nix develop' >&2
  exit 1
fi
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ZIG_GLOBAL_CACHE_DIR="${ZIG_GLOBAL_CACHE_DIR:-$repo_root/.cache/zig}"
[[ -d "${ANDROID_NDK_HOME:-}/toolchains/llvm" ]] || { echo 'Pinned Android NDK is unavailable' >&2; exit 1; }
mkdir -p "$ZIG_GLOBAL_CACHE_DIR"
cd "$repo_root/zig-src"
zig build -Doptimize=ReleaseSmall -Dtarget=aarch64-linux-android.24 jni
