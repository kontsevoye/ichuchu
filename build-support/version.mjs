import { execFileSync } from "node:child_process";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

export function parseVersion(tag) {
  const match = /^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.exec(tag);
  if (!match) return undefined;
  const parts = match.slice(1).map(Number);
  return parts.every(Number.isSafeInteger) ? parts : undefined;
}

export function compareVersions(left, right) {
  const a = parseVersion(left);
  const b = parseVersion(right);
  if (!a || !b) throw new Error("Expected stable vMAJOR.MINOR.PATCH tags.");
  for (let index = 0; index < 3; index++) {
    if (a[index] !== b[index]) return a[index] > b[index] ? 1 : -1;
  }
  return 0;
}

export function changeLevel(messages) {
  if (
    messages.some(
      (message) =>
        /^[a-z][\w-]*(?:\([^\r\n)]+\))?!:[ \t]/i.test(message) ||
        /^BREAKING[ -]CHANGE:[ \t]*\S/m.test(message),
    )
  ) {
    return "major";
  }
  return messages.some((message) =>
    /^feat(?:\([^\r\n)]+\))?:[ \t]/.test(message),
  )
    ? "minor"
    : "patch";
}

export function bumpVersion(tag, level) {
  const parts = parseVersion(tag);
  if (!parts) throw new Error(`Invalid version tag: ${tag}`);
  const index = { major: 0, minor: 1, patch: 2 }[level];
  if (index === undefined)
    throw new Error(`Invalid version increment: ${level}`);
  parts[index]++;
  for (let next = index + 1; next < 3; next++) parts[next] = 0;
  if (!parts.every(Number.isSafeInteger))
    throw new Error("Version is too large.");
  return `v${parts.join(".")}`;
}

export function planRelease(cwd = process.cwd(), revision = "HEAD") {
  const git = (...args) =>
    execFileSync("git", args, {
      cwd,
      encoding: "utf8",
      maxBuffer: 16 * 1024 * 1024,
    }).trim();
  if (git("rev-parse", "--is-shallow-repository") === "true") {
    throw new Error(
      "Release planning requires complete Git history and tags (fetch-depth: 0).",
    );
  }
  const sha = git("rev-parse", "--verify", `${revision}^{commit}`);
  const tags = git("tag", "--list", "v*")
    .split("\n")
    .filter((tag) => parseVersion(tag))
    .sort((a, b) => compareVersions(b, a));
  const tagged = tags.map((tag) => ({
    tag,
    sha: git("rev-parse", `${tag}^{commit}`),
  }));
  function isAncestor(ancestor) {
    try {
      git("merge-base", "--is-ancestor", ancestor, sha);
      return true;
    } catch (error) {
      if (error.status === 1) return false;
      throw error;
    }
  }

  const existing = tagged.find((item) => item.sha === sha);
  if (existing) {
    const previous = tagged.find(
      (item) => item.sha !== sha && isAncestor(item.sha),
    );
    return {
      tag: existing.tag,
      sha,
      previousTag: previous?.tag,
      existingTag: true,
      latest: existing.tag === tags[0],
    };
  }

  const previous = tagged[0];
  // A delayed/retried older push must not publish a new version of old code.
  if (previous && !isAncestor(previous.sha)) {
    return {
      skip: true,
      sha,
      reason: `Newest version ${previous.tag} is not an ancestor of this commit.`,
    };
  }
  const range = previous ? `${previous.tag}..${sha}` : sha;
  const messages = git("log", "--format=%B%x00", range)
    .split("\0")
    .map((message) => message.trim())
    .filter(Boolean);
  if (messages.length === 0)
    return { skip: true, sha, reason: "No unreleased commits." };
  const level = changeLevel(messages);
  return {
    tag: bumpVersion(previous?.tag ?? "v0.0.0", level),
    sha,
    previousTag: previous?.tag,
    existingTag: false,
    latest: true,
    level,
    commits: messages.length,
  };
}

// Preserve an upgrade path from the pre-automation local builds (201xxx).
export const VERSION_CODE_OFFSET = 1_000_000;
export const MAX_VERSION_CODE = 2_100_000_000;

export function androidVersion(tag) {
  const parts = parseVersion(tag);
  if (!parts || parts[1] > 999 || parts[2] > 999)
    throw new Error("Android releases require vMAJOR.MINOR.PATCH with minor/patch <= 999.");
  const versionCode = VERSION_CODE_OFFSET + parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2];
  if (!Number.isSafeInteger(versionCode) || versionCode > MAX_VERSION_CODE)
    throw new Error("Android versionCode exceeds 2100000000.");
  return { versionName: tag.slice(1), versionCode };
}

export function buildVersion(cwd = process.cwd(), env = process.env) {
  if (["VERSION_MAJOR", "VERSION_MINOR", "VERSION_PATCH"].some((key) => env[key] !== undefined))
    throw new Error("Use VERSION_NAME and VERSION_CODE together instead of VERSION_MAJOR/MINOR/PATCH.");
  if (env.VERSION_NAME !== undefined || env.VERSION_CODE !== undefined) {
    const versionName = env.VERSION_NAME;
    const versionCode = Number(env.VERSION_CODE);
    if (!versionName || /[\r\n\\]/.test(versionName) || versionName !== versionName.trim() ||
        !/^[1-9]\d*$/.test(env.VERSION_CODE ?? "") ||
        !Number.isSafeInteger(versionCode) || versionCode > MAX_VERSION_CODE)
      throw new Error("Set a non-empty VERSION_NAME and a positive VERSION_CODE <= 2100000000 together.");
    return { versionName, versionCode };
  }
  const plan = planRelease(cwd);
  if (plan.skip) throw new Error(`${plan.reason} Set VERSION_NAME and VERSION_CODE for a historical build.`);
  return androidVersion(plan.tag);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const version = buildVersion();
  if (process.argv.includes("--gradle"))
    console.log(`versionName=${version.versionName}\nversionCode=${version.versionCode}`);
  else console.log(JSON.stringify(version, null, 2));
}
