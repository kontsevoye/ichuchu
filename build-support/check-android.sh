#!/usr/bin/env bash
set -euo pipefail

if [[ "${ICHUCHU_NIX_SHELL:-}" != 1 ]]; then
  echo 'Run: nix develop --command bash build-support/check-android.sh' >&2
  exit 1
fi
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$repo_root/.cache/gradle}"
export ZIG_GLOBAL_CACHE_DIR="${ZIG_GLOBAL_CACHE_DIR:-$repo_root/.cache/zig}"
export ANDROID_ABI_FILTERS=arm64-v8a
[[ -x "${JAVA_HOME:-}/bin/java" ]] || { echo 'Pinned JDK is unavailable' >&2; exit 1; }
mkdir -p "$repo_root/.cache" "$GRADLE_USER_HOME" "$ZIG_GLOBAL_CACHE_DIR"
timings="$repo_root/.cache/build-timings.tsv"
printf 'phase\tseconds\texit_code\n' > "$timings"

write_summary() {
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    {
      printf '\n### Android build timings\n\n| Phase | Seconds | Exit code |\n| --- | ---: | ---: |\n'
      while IFS=$'\t' read -r name elapsed code; do
        [[ "$name" == phase ]] || printf '| %s | %s | %s |\n' "$name" "$elapsed" "$code"
      done < "$timings"
    } >> "$GITHUB_STEP_SUMMARY"
  fi
}
trap write_summary EXIT

run_phase() {
  local name="$1" started=$SECONDS result=0
  shift
  printf '::group::%s\n' "$name"
  "$@" || result=$?
  printf '::endgroup::\n'
  printf '%s\t%d\t%d\n' "$name" "$((SECONDS - started))" "$result" >> "$timings"
  printf '%s: %ds (exit %d)\n' "$name" "$((SECONDS - started))" "$result"
  return "$result"
}

run_phase native-tests check-native
run_phase jni bash "$repo_root/build-support/build-native.sh"
(
  cd "$repo_root/android"
  # Reuse one task graph/JVM, but execute the test task even with a warm cache.
  run_phase gradle ./gradlew :app:assembleDebug :app:testDebugUnitTest --rerun \
    :app:assembleRelease :app:bundleRelease \
    --no-daemon --no-configuration-cache --console=plain --profile
)
for variant in debug release; do
  run_phase "verify-$variant-jni" python3 "$repo_root/build-support/verify-apk-native.py" \
    "$repo_root/android/app/build/outputs/apk/$variant" \
    "$repo_root/android/app/src/main/jniLibs/arm64-v8a/libichuchu_jni.so"
done
