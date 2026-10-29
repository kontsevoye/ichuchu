import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { appendFileSync, copyFileSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { androidVersion, planRelease } from "./version.mjs";

export const ASSET_NAMES = ["ichuchu.apk", "ichuchu.aab"];
const runGh = (args, input) => execFileSync("gh", args, {
  encoding: "utf8", input: input === undefined ? undefined : JSON.stringify(input),
  stdio: ["pipe", "pipe", "pipe"],
});

// Separate the remote operations so recovery tests never publish anything.
export function publishRelease(plan, repository, artifacts, gh = runGh) {
  if (plan.skip) return { status: "skipped", reason: plan.reason };
  if (!/^[\w.-]+\/[\w.-]+$/.test(repository)) throw new Error("Invalid GitHub repository.");
  if (artifacts.length !== ASSET_NAMES.length) throw new Error("Expected APK and AAB artifacts.");
  const endpoint = `repos/${repository}`;
  let release;
  try {
    release = JSON.parse(gh(["api", `${endpoint}/releases/tags/${plan.tag}`]));
  } catch (error) {
    if (!/HTTP 404/.test(String(error.stderr))) throw error;
  }
  const present = new Set(release?.assets.map((asset) => asset.name) ?? []);
  if (release && !release.draft && ASSET_NAMES.every((name) => present.has(name)))
    return { status: "already-published", tag: plan.tag, url: release.html_url };
  if (release && !release.draft)
    throw new Error("Published release is incomplete; refusing to modify published artifacts.");
  if (!plan.existingTag) {
    const tag = JSON.parse(gh(["api", "--method", "POST", `${endpoint}/git/tags`, "--input", "-"], {
      tag: plan.tag, message: `${plan.tag}\n\nAutomated release of ${plan.sha}.`, object: plan.sha, type: "commit",
    }));
    gh(["api", "--method", "POST", `${endpoint}/git/refs`, "--input", "-"], {
      ref: `refs/tags/${plan.tag}`, sha: tag.sha,
    });
  }
  if (!release) {
    const args = ["release", "create", plan.tag, "--repo", repository, "--verify-tag", "--draft",
      "--generate-notes", "--title", `${plan.tag} — ichuchu`];
    if (plan.previousTag) args.push("--notes-start-tag", plan.previousTag);
    gh(args);
  }
  for (let index = 0; index < ASSET_NAMES.length; index++) {
    // A retry fills only missing assets, preserving already-checked uploaded bytes.
    if (!present.has(ASSET_NAMES[index]))
      gh(["release", "upload", plan.tag, artifacts[index], "--repo", repository]);
  }
  gh(["release", "edit", plan.tag, "--repo", repository, "--draft=false", `--latest=${plan.latest}`]);
  return { status: "published", tag: plan.tag, url: `https://github.com/${repository}/releases/tag/${plan.tag}` };
}

export function releasePlan(cwd = process.cwd()) {
  const plan = planRelease(cwd);
  return plan.skip ? plan : { ...plan, ...androidVersion(plan.tag) };
}

export function checkPreparedPlan(prepared, current, sha) {
  if (prepared.skip || current.skip || prepared.sha !== sha || current.sha !== sha ||
      prepared.tag !== current.tag || prepared.versionName !== current.versionName ||
      prepared.versionCode !== current.versionCode)
    throw new Error("Release plan changed since the checked build; refusing to publish mismatched artifacts.");
}

export function prepareArtifacts(plan, cwd = process.cwd()) {
  const apkDir = resolve(cwd, "android/app/build/outputs/apk/release");
  const metadata = JSON.parse(readFileSync(resolve(apkDir, "output-metadata.json")));
  const signed = JSON.parse(readFileSync(resolve(apkDir, "signed-artifacts.json")));
  if (metadata.applicationId !== "io.github.kontsevoye.ichuchu" || metadata.elements.length !== 1 ||
      metadata.elements[0].versionCode !== plan.versionCode || metadata.elements[0].versionName !== plan.versionName ||
      signed.version_code !== plan.versionCode || signed.version_name !== plan.versionName ||
      !/^[0-9a-f]{64}$/.test(signed.certificate_sha256))
    throw new Error("Signed artifact metadata does not match the checked release plan.");
  const sources = [resolve(apkDir, "app-release.apk"), resolve(cwd, "android/app/build/outputs/bundle/release/app-release.aab")];
  const hashes = [signed.sha256, signed.aab_sha256];
  const directory = resolve(cwd, ".cache/release");
  const artifacts = sources.map((source, index) => {
    const bytes = readFileSync(source);
    if (!bytes.length || createHash("sha256").update(bytes).digest("hex") !== hashes[index])
      throw new Error(`Signed artifact hash mismatch: ${ASSET_NAMES[index]}`);
    return resolve(directory, ASSET_NAMES[index]);
  });
  mkdirSync(directory, { recursive: true });
  sources.forEach((source, index) => copyFileSync(source, artifacts[index]));
  return artifacts;
}

function refreshTags(token) {
  // checkout does not persist credentials; authenticate even for private repositories.
  // The temporary Git configuration stays in the child environment, never argv or disk.
  execFileSync("git", ["fetch", "origin", "--tags"], {
    stdio: "inherit", env: { ...process.env, GIT_CONFIG_COUNT: "1",
      GIT_CONFIG_KEY_0: "http.https://github.com/.extraheader",
      GIT_CONFIG_VALUE_0: `AUTHORIZATION: basic ${Buffer.from(`x-access-token:${token}`).toString("base64")}` },
  });
}

function main() {
  const publish = process.argv.includes("--publish");
  if (publish) {
    if (process.env.GITHUB_ACTIONS !== "true" || process.env.GITHUB_EVENT_NAME !== "push" ||
        process.env.GITHUB_REF !== "refs/heads/main")
      throw new Error("Publishing is only allowed in the main-branch push workflow.");
    if (!process.env.GH_TOKEN) throw new Error("The release job requires GH_TOKEN.");
    refreshTags(process.env.GH_TOKEN);
  }
  const plan = releasePlan();
  if (!publish) {
    const index = process.argv.indexOf("--prepare");
    if (index !== -1) {
      const path = resolve(process.argv[index + 1]);
      mkdirSync(dirname(path), { recursive: true });
      writeFileSync(path, JSON.stringify(plan, null, 2) + "\n");
      if (process.env.GITHUB_OUTPUT)
        appendFileSync(process.env.GITHUB_OUTPUT, `skip=${Boolean(plan.skip)}\nversion_name=${plan.versionName ?? ""}\nversion_code=${plan.versionCode ?? ""}\n`);
    }
    console.log(JSON.stringify(plan, null, 2));
    return;
  }
  const index = process.argv.indexOf("--plan");
  if (index === -1 || !process.argv[index + 1]) throw new Error("Publishing requires the checked --plan file.");
  const prepared = JSON.parse(readFileSync(resolve(process.argv[index + 1])));
  checkPreparedPlan(prepared, plan, process.env.GITHUB_SHA);
  const artifacts = prepareArtifacts(plan);
  const result = publishRelease(plan, process.env.GITHUB_REPOSITORY, artifacts);
  console.log(JSON.stringify(result, null, 2));
  if (process.env.GITHUB_STEP_SUMMARY)
    appendFileSync(process.env.GITHUB_STEP_SUMMARY, `## Release\n\n${result.status}: [${result.tag}](${result.url})\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) main();
