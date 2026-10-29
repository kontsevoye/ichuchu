import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import {
  bumpVersion,
  changeLevel,
  compareVersions,
  parseVersion,
  planRelease,
} from "./version.mjs";

test("stable SemVer is ordered numerically and increments reset lower components", () => {
  assert.equal(compareVersions("v0.10.0", "v0.9.99"), 1);
  for (const invalid of [
    "0.1.0",
    "v01.1.0",
    "v0.1.0-beta.1",
    "v1.2",
    "v1.2.3.4",
  ])
    assert.equal(parseVersion(invalid), undefined);
  assert.equal(bumpVersion("v0.1.9", "patch"), "v0.1.10");
  assert.equal(bumpVersion("v0.1.9", "minor"), "v0.2.0");
  assert.equal(bumpVersion("v0.1.9", "major"), "v1.0.0");
});

test("highest Conventional Commit level wins; other messages default to patch", () => {
  assert.equal(
    changeLevel([
      "fix: repair lookup",
      "docs: explain tokens",
      "Update dependencies",
    ]),
    "patch",
  );
  assert.equal(
    changeLevel(["fix: repair lookup", "feat(auth): add sign-in"]),
    "minor",
  );
  for (const message of [
    "feat!: remove compatibility",
    "refactor(api)!: change contract",
    "chore: update\n\nBREAKING CHANGE: old preferences are removed",
    "fix: update\n\nBREAKING-CHANGE: old preferences are removed",
  ]) {
    assert.equal(changeLevel(["feat: new UI", message]), "major", message);
  }
  assert.equal(changeLevel(["docs: explain feat: syntax"]), "patch");
});

function fixture(t) {
  const cwd = mkdtempSync(join(tmpdir(), "ichuchu-release-test-"));
  t.after(() => rmSync(cwd, { recursive: true, force: true }));
  const git = (...args) =>
    execFileSync("git", args, {
      cwd,
      encoding: "utf8",
      env: {
        ...process.env,
        GIT_CONFIG_GLOBAL: "/dev/null",
        GIT_CONFIG_NOSYSTEM: "1",
      },
    }).trim();
  git("init", "--quiet", "-b", "main");
  git("config", "user.name", "Release Test");
  git("config", "user.email", "test@example.invalid");
  const commit = (message) => {
    git("commit", "--quiet", "--allow-empty", "-m", message);
    return git("rev-parse", "HEAD");
  };
  return { cwd, git, commit };
}

test("plans from all unreleased commits, resumes a tagged commit, and ignores non-stable tags", (t) => {
  const { cwd, git, commit } = fixture(t);
  commit("Initial version");
  git("tag", "-a", "v0.1.0", "-m", "Initial release");
  commit("fix: patch");
  commit("feat: new action");
  const sha = commit("docs: explain action");
  git("tag", "v100.0.0-beta.1");
  const plan = planRelease(cwd);
  assert.deepEqual(plan, {
    tag: "v0.2.0",
    sha,
    previousTag: "v0.1.0",
    existingTag: false,
    latest: true,
    level: "minor",
    commits: 3,
  });
  git("tag", "-a", plan.tag, "-m", "Automated release");
  assert.deepEqual(planRelease(cwd), {
    tag: "v0.2.0",
    sha,
    previousTag: "v0.1.0",
    existingTag: true,
    latest: true,
  });
});

test("a late older push is skipped after a newer commit has already been tagged", (t) => {
  const { cwd, git, commit } = fixture(t);
  commit("Initial version");
  git("tag", "v0.1.0");
  const older = commit("fix: older push");
  commit("fix: newer push");
  git("tag", "v0.1.1");
  assert.equal(planRelease(cwd, older).skip, true);
});

test("a repository with no release tags starts with the appropriate SemVer increment", (t) => {
  const { cwd, commit } = fixture(t);
  commit("feat: first feature");
  assert.equal(planRelease(cwd).tag, "v0.1.0");
});

test("Android encoding increases across patch/minor/major boundaries and rejects overflow", async () => {
  const { androidVersion } = await import("./version.mjs");
  const tags = ["v0.0.1", "v0.0.999", "v0.1.0", "v0.999.999", "v1.0.0", "v2099.0.0"];
  const codes = tags.map((tag) => androidVersion(tag).versionCode);
  assert.deepEqual(codes, [1000001, 1000999, 1001000, 1999999, 2000000, 2100000000]);
  for (let i = 1; i < codes.length; i++) assert.ok(codes[i] > codes[i - 1]);
  for (const tag of ["v0.0.1000", "v0.1000.0", "v2099.0.1", "v999999999999.0.0"])
    assert.throws(() => androidVersion(tag));
});

test("local Gradle version follows the same plan and reuses the exact tagged version", async (t) => {
  const { buildVersion } = await import("./version.mjs");
  const { cwd, git, commit } = fixture(t);
  commit("feat: initial app");
  assert.deepEqual(buildVersion(cwd, {}), { versionName: "0.1.0", versionCode: 1001000 });
  git("tag", "v0.1.0");
  assert.deepEqual(buildVersion(cwd, {}), { versionName: "0.1.0", versionCode: 1001000 });
  commit("fix: session cleanup");
  assert.deepEqual(buildVersion(cwd, {}), { versionName: "0.1.1", versionCode: 1001001 });
});

test("manual version overrides must be complete, valid and within Android limits", async () => {
  const { buildVersion } = await import("./version.mjs");
  assert.deepEqual(buildVersion("/not-a-checkout", { VERSION_NAME: "0.1.0-local", VERSION_CODE: "1001000" }),
    { versionName: "0.1.0-local", versionCode: 1001000 });
  for (const env of [
    { VERSION_NAME: "0.1.0" }, { VERSION_CODE: "1" },
    { VERSION_NAME: "bad\nname", VERSION_CODE: "1" },
    { VERSION_NAME: "local", VERSION_CODE: "0" },
    { VERSION_NAME: "local", VERSION_CODE: "2147483647" },
    { VERSION_NAME: "local", VERSION_CODE: "1e6" },
    { VERSION_MAJOR: "1" },
  ]) assert.throws(() => buildVersion("/not-a-checkout", env));
});

test("a shallow checkout is rejected rather than allocating a version from incomplete history", (t) => {
  const { cwd, git, commit } = fixture(t);
  commit("feat: initial"); git("tag", "v0.1.0"); commit("fix: next");
  const shallow = join(cwd, "shallow");
  execFileSync("git", ["clone", "--quiet", "--depth=1", `file://${cwd}`, shallow]);
  assert.throws(() => planRelease(shallow), /complete Git history/);
});
