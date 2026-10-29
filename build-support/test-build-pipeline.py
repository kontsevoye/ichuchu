#!/usr/bin/env python3
"""Check orchestration and stale-JNI rejection with a disposable toolchain fixture."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class AndroidBuildPipeline(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="ichuchu-build-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        support = self.root / "build-support"
        support.mkdir()
        for name in ["build-apk.sh", "build-native.sh", "check-android.sh", "verify-apk-native.py"]:
            shutil.copyfile(ROOT / "build-support" / name, support / name)
        (self.root / "zig-src").mkdir()
        (self.root / "android").mkdir()
        tools = self.root / "bin"
        tools.mkdir()
        (self.root / "ndk/toolchains/llvm").mkdir(parents=True)
        (self.root / "jdk/bin").mkdir(parents=True)
        self.executable(self.root / "jdk/bin/java", "#!/bin/sh\nexit 0\n")
        self.executable(tools / "check-native", '''#!/usr/bin/env python3
import json, os
from pathlib import Path
with open(os.environ["TEST_CALLS"], "a") as out: out.write(json.dumps(["native-tests"]) + "\\n")
raise SystemExit(int(os.environ.get("NATIVE_TEST_EXIT", "0")))
''')
        self.executable(tools / "zig", '''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
with open(os.environ["TEST_CALLS"], "a") as out: out.write(json.dumps(["zig", *sys.argv[1:]]) + "\\n")
p = Path(os.environ["TEST_REPO"]) / "android/app/src/main/jniLibs/arm64-v8a/libichuchu_jni.so"
p.parent.mkdir(parents=True, exist_ok=True)
p.write_bytes(b"fresh-native")
''')
        self.executable(self.root / "android/gradlew", '''#!/usr/bin/env python3
import json, os, sys, zipfile
from pathlib import Path
with open(os.environ["TEST_CALLS"], "a") as out: out.write(json.dumps(["gradle", *sys.argv[1:]]) + "\\n")
root = Path(os.environ["TEST_REPO"])
for variant in ["debug", "release"]:
    folder = root / f"android/app/build/outputs/apk/{variant}"
    folder.mkdir(parents=True, exist_ok=True)
    name = f"app-{variant}.apk"
    (folder / "output-metadata.json").write_text(json.dumps({"elements": [{"outputFile": name}]}))
    with zipfile.ZipFile(folder / name, "w") as archive:
        archive.writestr("lib/arm64-v8a/libichuchu_jni.so", b"stale" if os.environ.get("STALE_VARIANT") == variant else b"fresh-native")
''')
        self.env = dict(os.environ, ICHUCHU_NIX_SHELL="1", TEST_REPO=str(self.root),
                        TEST_CALLS=str(self.root / "calls.jsonl"), JAVA_HOME=str(self.root / "jdk"),
                        ANDROID_NDK_HOME=str(self.root / "ndk"),
                        GRADLE_USER_HOME=str(self.root / "gradle"), ZIG_GLOBAL_CACHE_DIR=str(self.root / "zig"),
                        GITHUB_STEP_SUMMARY=str(self.root / "summary.md"), PATH=str(tools) + os.pathsep + os.environ["PATH"])

    def executable(self, path, contents):
        path.write_text(contents)
        path.chmod(0o755)

    def run_build(self, script, *args, **env):
        return subprocess.run(["bash", str(self.root / "build-support" / script), *args],
                              env=dict(self.env, **env), capture_output=True, text=True, timeout=30)

    def calls(self):
        return [json.loads(line) for line in (self.root / "calls.jsonl").read_text().splitlines()]

    def test_combined_graph_runs_tests_and_checks_both_jni_payloads(self):
        result = self.run_build("check-android.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.calls()
        self.assertEqual([row[0] for row in calls], ["native-tests", "zig", "gradle"])
        args = calls[-1]
        for task in [":app:assembleDebug", ":app:testDebugUnitTest", ":app:assembleRelease", ":app:bundleRelease"]:
            self.assertIn(task, args)
        self.assertEqual(args[args.index(":app:testDebugUnitTest") + 1], "--rerun")
        self.assertIn("--no-configuration-cache", args)
        self.assertIn("--profile", args)
        timings = (self.root / ".cache/build-timings.tsv").read_text()
        self.assertIn("verify-debug-jni", timings)
        self.assertIn("verify-release-jni", timings)
        self.assertIn("| gradle |", (self.root / "summary.md").read_text())

    def test_stale_release_jni_is_rejected_after_a_successful_gradle_run(self):
        result = self.run_build("check-android.sh", STALE_VARIANT="release")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("stale or different JNI", result.stderr)
        self.assertIn("verify-release-jni", (self.root / "summary.md").read_text())

    def test_native_failure_stops_the_build(self):
        result = self.run_build("check-android.sh", NATIVE_TEST_EXIT="23")
        self.assertEqual(result.returncode, 23)
        self.assertEqual(self.calls(), [["native-tests"]])
        self.assertIn("| native-tests |", (self.root / "summary.md").read_text())

    def test_standalone_release_still_builds_jni_and_checks_the_apk(self):
        result = self.run_build("build-apk.sh", "release", "--bundle")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([row[0] for row in self.calls()], ["zig", "gradle"])
        self.assertIn(":app:bundleRelease", self.calls()[-1])
        self.assertIn("SHA256:", result.stdout)

    def test_standalone_debug_rejects_stale_jni(self):
        result = self.run_build("build-apk.sh", "debug", STALE_VARIANT="debug")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("stale or different JNI", result.stderr)


if __name__ == "__main__":
    unittest.main()
