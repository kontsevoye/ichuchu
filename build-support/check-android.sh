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
  gradle_args=(
    :app:assembleDebug :app:testDebugUnitTest --rerun :app:assembleRelease :app:bundleRelease
    --no-daemon --no-configuration-cache --console=plain --profile
  )
  for name in CI_GRADLE_HEAP_MB CI_GRADLE_WORKERS CI_TEST_FORKS; do
    value="${!name:-}"
    [[ -z "$value" || "$value" =~ ^[1-9][0-9]*$ ]] || { echo "Invalid $name" >&2; exit 2; }
  done
  if [[ -n "${CI_GRADLE_HEAP_MB:-}" ]]; then
    ((CI_GRADLE_HEAP_MB >= 1024 && CI_GRADLE_HEAP_MB <= 8192)) || exit 2
    gradle_args+=("-Dorg.gradle.jvmargs=-Xmx${CI_GRADLE_HEAP_MB}m -Dfile.encoding=UTF-8 -Xlog:gc:file=$repo_root/.cache/gradle-gc.log:time,uptime,level,tags")
  fi
  if [[ -n "${CI_GRADLE_WORKERS:-}" ]]; then
    ((CI_GRADLE_WORKERS <= 8)) || exit 2
    gradle_args+=("--max-workers=$CI_GRADLE_WORKERS")
  fi
  if [[ -n "${CI_TEST_FORKS:-}" ]]; then
    ((CI_TEST_FORKS <= 4)) || exit 2
    gradle_args+=("-PtestForks=$CI_TEST_FORKS")
  fi
  if [[ "${CI_BENCHMARK:-0}" == 1 ]]; then
    # Compare execution, never an R8/lint output-cache hit against real work.
    gradle_args+=(--rerun-tasks --no-build-cache)
  fi
  if [[ "${CI_BUILD_DIAGNOSTICS:-0}" == 1 ]]; then
    run_phase gradle python3 "$repo_root/build-support/profile-command.py" \
      --output "$repo_root/.cache/gradle-resources" -- ./gradlew "${gradle_args[@]}"
  else
    run_phase gradle ./gradlew "${gradle_args[@]}"
  fi
)
for variant in debug release; do
  run_phase "verify-$variant-jni" python3 "$repo_root/build-support/verify-apk-native.py" \
    "$repo_root/android/app/build/outputs/apk/$variant" \
    "$repo_root/android/app/src/main/jniLibs/arm64-v8a/libichuchu_jni.so"
done
