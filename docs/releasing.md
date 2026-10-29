# Automatic SemVer releases

The version policy is adapted from
[kontsevoye/raycast-nixpkgs-tracker at 4a6c32c](https://github.com/kontsevoye/raycast-nixpkgs-tracker/tree/4a6c32c02f1901985fb659c29786dcb29119b3ef).
The primary branch is `main`; each push queues **Check and release**. PRs and
manual workflow runs check code and build unsigned artifacts without publishing.
There is one stable release stream.

## Version policy

Stable `vMAJOR.MINOR.PATCH` Git tags are the source of release versions. All
commit messages since the newest stable tag are considered:

- `feat:` or `feat(scope):` increments minor.
- `type!:` or `type(scope)!:`, or a `BREAKING CHANGE:` / `BREAKING-CHANGE:`
  footer, increments major, including while the project is on `0.x`.
- Everything else increments patch, including documentation and build changes.

The highest requested level wins; lower components reset to zero. Non-stable
tags are ignored. Without stable tags, the starting point is `v0.0.0`.
No version-bump commit is pushed into `main`.

Preview the next release for the local **committed** history:

```sh
git fetch origin --tags
nix develop --command make release-plan
```

`build-support/version.mjs` is the shared source for Android version values,
including direct Gradle builds. Git and Node.js must be available; `nix develop`
provides both. A complete Git history and tags are required (`fetch-depth: 0`).
At an existing stable tag the exact tagged version is reused; otherwise the next
release version is used. Uncommitted changes do not affect the release number.
An untagged historical checkout superseded by a newer release requires an
explicit local version override.

## Android versionCode

`versionName` is the SemVer without the `v` prefix. The Android code is:

```text
1_000_000 + major * 1_000_000 + minor * 1_000 + patch
```

For example, `v0.1.0` is code `1001000`, `v0.1.1` is `1001001`, and `v1.0.0`
is `2000000`. Minor and patch must each be at most 999; overflow fails rather
than clamping. The total must not exceed Android/Google Play's `2100000000`.
The offset retains an upgrade path from the pre-automation local codes `201xxx`.
Changing this encoding after distribution requires a separate migration plan.
[Android's version requirements](https://developer.android.com/studio/publish/versioning).

Local test builds may explicitly set **both** `VERSION_NAME` and `VERSION_CODE`.
Partial, malformed or overflowing overrides fail. The old
`VERSION_MAJOR`/`VERSION_MINOR`/`VERSION_PATCH` inputs are rejected.
These overrides do not allocate release tags.

## Checked artifacts and signing

1. A read-only check job runs release tests, ShellCheck and actionlint, then saves
   the release plan for the checked SHA.
2. With that plan's version, the Nix shell runs native regressions, builds the
   debug APK, runs JVM tests, and builds the unsigned release APK/AAB with R8/lint.
3. The plan, unsigned APK, its build metadata and AAB are uploaded as one workflow
   artifact. The upload paths are explicit, including only the specific plan file
   from `.cache`.
4. A separate job downloads that exact artifact and signs it with
   `build-apk signed-release --sign-only --non-interactive --bundle`.
   No native or Gradle build runs with signing credentials in this mode. The APK
   manifest, metadata and planned version must agree; signatures, certificates
   and unchanged application payloads are verified. Signed APK/AAB hashes are
   recorded in `signed-artifacts.json`.
5. Publication refreshes tags and verifies that the current plan still matches
   the checked SHA/version. It validates the signed files, creates an annotated
   tag, creates a draft with generated notes, uploads `ichuchu.apk`,
   `ichuchu.aab` and signed-APK-derived `update.json`, then publishes the complete draft.

Only the release job has `contents: write` and receives the Android signing
secrets described in [building.md](building.md). GitHub publication uses the
short-lived `GITHUB_TOKEN`, without a PAT or personal GPG key. Automatic tags are
annotated and are not signed with the maintainer's personal key. Git authentication
for the tag refresh is supplied only through the child process environment,
including when the repository is private; checkout credentials are not persisted.

## Queueing, retries and recovery

The whole workflow is serialized per ref with `cancel-in-progress: false` and
`queue: max`, so planning, checking, signing and publication cannot race with
another main push in the same workflow. GitHub permits up to 100 queued runs;
ordering is based on when runs start waiting. The planner skips a delayed older
push when the newest stable tag is not its ancestor.
[GitHub concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency).
The actionlint configuration ignores only its unsupported `queue` diagnostic for
this workflow; other checks remain enabled.

An existing tag on the same SHA is reused. Retry a failed job in that push's
workflow to finish an incomplete draft. Already-uploaded assets are preserved;
missing assets are added before publication. Completed releases are a no-op on
retry. An incomplete release that was manually published is rejected rather than
modified. Do not force-push across released commits or move stable tags.

Artifacts are retained for seven days. If they expire, rerun all jobs in the
original push workflow to rebuild. A new manual workflow run only checks code;
it cannot publish. Local `--publish` is also rejected before contacting GitHub.

Run the release/recovery regression tests without making GitHub writes:

```sh
nix develop --command make check-release
```

The check job explicitly sets `ORG_GRADLE_PROJECT_enableSelfUpdate=true` for its release build. Debug remains disabled. Publication derives schema-1 `update.json` from the verified signed APK (package, SDK, ABI, version, certificate and exact final size/hash), binds it to the checked commit, and uploads it before publishing. See [updating.md](updating.md).

On partial-draft recovery, an already-uploaded APK or `update.json` must have a GitHub digest/size matching the checked local file. A rebuilt or re-signed different APK cannot be paired with a new manifest silently. Preserve/recover the original checked artifact; if unavailable, review and remove only the conflicting unpublished draft asset before retrying. Published assets are never changed.
