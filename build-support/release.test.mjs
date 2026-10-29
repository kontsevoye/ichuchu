import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { ASSET_NAMES, checkPreparedPlan, prepareArtifacts, publishRelease } from "./release.mjs";

const plan = { tag: "v0.1.1", previousTag: "v0.1.0", sha: "a".repeat(40), existingTag: false,
  latest: true, versionName: "0.1.1", versionCode: 1001001 };
const repository = "example/ichuchu";
const artifacts = ASSET_NAMES.map((name) => `/tmp/${name}`);

function fakeGh({ release, failUpload = 0 } = {}) {
  const calls = [];
  let uploads = 0;
  const gh = (args, input) => {
    calls.push({ args, input });
    if (args[0] === "api" && args[1].includes("/releases/tags/")) {
      if (release) return JSON.stringify(release);
      const error = new Error("Not found"); error.stderr = "gh: Not Found (HTTP 404)"; throw error;
    }
    if (args[0] === "api" && args.includes(`repos/${repository}/git/tags`))
      return JSON.stringify({ sha: "b".repeat(40) });
    if (args[0] === "release" && args[1] === "upload" && ++uploads === failUpload)
      throw new Error("Upload interrupted");
    return "";
  };
  return { gh, calls };
}

test("creates an annotated tag for the checked SHA and publishes only after both uploads", () => {
  const { gh, calls } = fakeGh();
  assert.equal(publishRelease(plan, repository, artifacts, gh).status, "published");
  assert.equal(calls[1].input.object, plan.sha);
  assert.equal(calls[1].input.type, "commit");
  assert.deepEqual(calls[2].input, { ref: "refs/tags/v0.1.1", sha: "b".repeat(40) });
  const releases = calls.filter(({ args }) => args[0] === "release");
  assert.deepEqual(releases.map(({ args }) => args[1]), ["create", "upload", "upload", "edit"]);
  assert.ok(releases[0].args.includes("--draft"));
  assert.ok(releases[0].args.includes("--notes-start-tag"));
  assert.ok(releases[3].args.includes("--draft=false"));
});

test("a completed release is immutable and a rerun is a no-op", () => {
  const { gh, calls } = fakeGh({ release: { draft: false, assets: ASSET_NAMES.map((name) => ({ name })),
    html_url: "https://example.invalid/release" } });
  assert.equal(publishRelease({ ...plan, existingTag: true }, repository, artifacts, gh).status, "already-published");
  assert.equal(calls.length, 1);
});

test("a partially uploaded draft resumes without replacing its tag or APK", () => {
  const { gh, calls } = fakeGh({ release: { draft: true, assets: [{ name: ASSET_NAMES[0] }] } });
  publishRelease({ ...plan, existingTag: true }, repository, artifacts, gh);
  assert.deepEqual(calls.slice(1).map(({ args }) => args.slice(0, 2)), [["release", "upload"], ["release", "edit"]]);
  assert.equal(calls[1].args[3], artifacts[1]);
});

test("second-asset upload failure leaves an unpublished draft, and retry completes it", () => {
  const { gh, calls } = fakeGh({ failUpload: 2 });
  assert.throws(() => publishRelease(plan, repository, artifacts, gh), /Upload interrupted/);
  assert.ok(!calls.some(({ args }) => args[0] === "release" && args[1] === "edit"));
  const retry = fakeGh({ release: { draft: true, assets: [{ name: ASSET_NAMES[0] }] } });
  publishRelease({ ...plan, existingTag: true }, repository, artifacts, retry.gh);
  assert.equal(retry.calls.filter(({ args }) => args[1] === "upload").length, 1);
});

test("published incomplete releases are not modified", () => {
  const { gh, calls } = fakeGh({ release: { draft: false, assets: [{ name: ASSET_NAMES[0] }] } });
  assert.throws(() => publishRelease({ ...plan, existingTag: true }, repository, artifacts, gh), /refusing to modify/);
  assert.equal(calls.length, 1);
});

test("superseded commits do not call GitHub", () => {
  const { gh, calls } = fakeGh();
  assert.equal(publishRelease({ skip: true, reason: "Superseded" }, repository, artifacts, gh).status, "skipped");
  assert.equal(calls.length, 0);
});

test("a changed tag, SHA or Android version invalidates the prepared build plan", () => {
  checkPreparedPlan(plan, { ...plan, existingTag: true }, plan.sha);
  for (const changed of [{ tag: "v0.2.0" }, { sha: "b".repeat(40) }, { versionCode: 2 },
    { versionName: "wrong" }, { skip: true }])
    assert.throws(() => checkPreparedPlan(plan, { ...plan, ...changed }, plan.sha), /plan changed/);
  assert.throws(() => checkPreparedPlan(plan, plan, "c".repeat(40)), /plan changed/);
});

test("publishing validates both signed payloads against the version plan and recorded hashes", (t) => {
  const cwd = mkdtempSync(join(tmpdir(), "ichuchu-artifacts-"));
  t.after(() => rmSync(cwd, { recursive: true, force: true }));
  const apkDir = join(cwd, "android/app/build/outputs/apk/release");
  const aabDir = join(cwd, "android/app/build/outputs/bundle/release");
  mkdirSync(apkDir, { recursive: true }); mkdirSync(aabDir, { recursive: true });
  const apk = Buffer.from("checked signed APK"); const aab = Buffer.from("checked signed AAB");
  const hash = (bytes) => createHash("sha256").update(bytes).digest("hex");
  const metadata = { applicationId: "io.github.kontsevoye.ichuchu", elements: [
    { versionCode: plan.versionCode, versionName: plan.versionName } ] };
  writeFileSync(join(apkDir, "output-metadata.json"), JSON.stringify(metadata));
  writeFileSync(join(apkDir, "app-release.apk"), apk); writeFileSync(join(aabDir, "app-release.aab"), aab);
  writeFileSync(join(apkDir, "signed-artifacts.json"), JSON.stringify({ version_code: plan.versionCode,
    version_name: plan.versionName, certificate_sha256: "a".repeat(64), sha256: hash(apk), aab_sha256: hash(aab) }));
  const paths = prepareArtifacts(plan, cwd);
  assert.deepEqual(paths.map((p) => readFileSync(p)), [apk, aab]);
  assert.throws(() => prepareArtifacts({ ...plan, versionCode: plan.versionCode + 1 }, cwd), /metadata/);
  writeFileSync(join(aabDir, "app-release.aab"), "corrupted");
  assert.throws(() => prepareArtifacts(plan, cwd), /hash mismatch/);
});

test("the publish CLI rejects a local or manually triggered invocation before remote access", async () => {
  const { spawnSync } = await import("node:child_process");
  const { fileURLToPath } = await import("node:url");
  const script = fileURLToPath(new URL("./release.mjs", import.meta.url));
  for (const env of [{ GITHUB_ACTIONS: "false" }, { GITHUB_ACTIONS: "true", GITHUB_EVENT_NAME: "workflow_dispatch" }]) {
    const result = spawnSync(process.execPath, [script, "--publish"], {
      encoding: "utf8", env: { ...process.env, ...env, GITHUB_REF: "refs/heads/main", GH_TOKEN: "synthetic-not-a-token" },
    });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /Publishing is only allowed/);
    assert.doesNotMatch(result.stderr, /synthetic-not-a-token/);
  }
});
