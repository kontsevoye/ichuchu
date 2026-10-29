#!/usr/bin/env python3
"""Real signing integration checks. Uses disposable keys and restores signed outputs."""

import base64
import importlib.util
import json
import os
from pathlib import Path
import pty
import select
import signal
import subprocess
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[1]
STORE_PASSWORD = "synthetic-store-only-937"
KEY_PASSWORD = "synthetic-key-only-428"
spec = importlib.util.spec_from_file_location("sign_release", ROOT / "build-support/signed-release.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SigningIntegration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.environ.get("ICHUCHU_NIX_SHELL") != "1":
            raise unittest.SkipTest("Run inside the Nix devshell")
        cls.temporary = tempfile.TemporaryDirectory(prefix="ichuchu-signing-tests-")
        cls.directory = Path(cls.temporary.name)
        cls.env = {key: value for key, value in os.environ.items() if key not in module.SIGNING_INPUTS}
        cls.env.update(CI="true", RUNNER_TEMP=str(cls.directory / "runner"))
        Path(cls.env["RUNNER_TEMP"]).mkdir(mode=0o700)
        cls.saved = {}
        for relative in ["apk/release/app-release.apk", "apk/release/signed-artifacts.json", "bundle/release/app-release.aab"]:
            path = ROOT / "android/app/build/outputs" / relative
            cls.saved[path] = path.read_bytes() if path.exists() else None
        cls.jks = cls.create_key("test.jks", "JKS", KEY_PASSWORD)
        cls.p12 = cls.create_key("test.p12", "PKCS12", STORE_PASSWORD)

    @classmethod
    def create_key(cls, filename, kind, key_password):
        path = cls.directory / filename
        env = dict(cls.env, TEST_STORE=STORE_PASSWORD, TEST_KEY=key_password)
        command = [str(Path(os.environ["JAVA_HOME"]) / "bin/keytool"), "-genkeypair", "-alias", "synthetic-release",
                   "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=Ichuchu signing test",
                   "-storetype", kind, "-keystore", str(path), "-storepass:env", "TEST_STORE", "-keypass:env", "TEST_KEY"]
        subprocess.run(command, env=env, capture_output=True, check=True)
        path.chmod(0o600)
        return path

    @classmethod
    def tearDownClass(cls):
        for path, content in cls.saved.items():
            if content is None:
                path.unlink(missing_ok=True)
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
        cls.temporary.cleanup()

    def check_output(self, output):
        if STORE_PASSWORD in output or KEY_PASSWORD in output:
            self.fail("Signing command exposed a password in output")

    def run_signing(self, inputs, *flags):
        result = subprocess.run(["build-apk", "signed-release", "--non-interactive", *flags],
                                cwd=ROOT, env=dict(self.env, **inputs), capture_output=True, text=True, timeout=240)
        self.check_output(result.stdout + result.stderr)
        return result

    def check_signed(self):
        metadata = json.loads((ROOT / "android/app/build/outputs/apk/release/signed-artifacts.json").read_text())
        self.assertEqual(len(metadata["certificate_sha256"]), 64)
        self.assertTrue(Path(metadata["apk"]).is_file())
        return metadata

    def test_ci_base64_pkcs12_signs_and_verifies_apk_and_bundle(self):
        result = self.run_signing({"KEYSTORE_BASE64": base64.b64encode(self.p12.read_bytes()).decode(),
                                   "KEYSTORE_PASSWORD": STORE_PASSWORD}, "--bundle")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(Path(self.check_signed()["aab"]).is_file())
        self.assertEqual(list(Path(self.env["RUNNER_TEMP"]).iterdir()), [])

    def test_password_files_support_distinct_jks_key_password(self):
        inputs = {"KEYSTORE_FILE": str(self.jks), "KEY_ALIAS": "synthetic-release"}
        for name, value in [("KEYSTORE_PASSWORD", STORE_PASSWORD), ("KEY_PASSWORD", KEY_PASSWORD)]:
            path = self.directory / (name + ".txt")
            path.write_text(value + "\n")
            path.chmod(0o600)
            inputs[name + "_FILE"] = str(path)
        result = self.run_signing(inputs)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.check_signed()

    def test_missing_ci_password_fails_before_build_and_cleans_temporary_keystore(self):
        result = self.run_signing({"KEYSTORE_BASE64": base64.b64encode(self.p12.read_bytes()).decode()})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing KEYSTORE_PASSWORD", result.stderr)
        self.assertNotIn("Task :app:", result.stdout)
        self.assertEqual(list(Path(self.env["RUNNER_TEMP"]).iterdir()), [])

    def test_wrong_password_fails_without_echoing_it(self):
        result = self.run_signing({"KEYSTORE_FILE": str(self.jks), "KEYSTORE_PASSWORD": "invalid-synthetic-password"})
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("invalid-synthetic-password", result.stdout + result.stderr)
        self.assertNotIn("Task :app:", result.stdout)

    def test_interactive_terminal_hides_both_passwords(self):
        master, slave = pty.openpty()
        process = subprocess.Popen(["build-apk", "signed-release", "--interactive", "--keystore", str(self.jks)],
                                   cwd=ROOT, env=self.env, stdin=slave, stdout=slave, stderr=slave, start_new_session=True)
        os.close(slave)
        output = b""
        prompts = [(b"Keystore password: ", STORE_PASSWORD),
                   (b"Private key password [same as keystore]: ", KEY_PASSWORD)]
        deadline = time.monotonic() + 240
        try:
            while time.monotonic() < deadline:
                if select.select([master], [], [], 0.1)[0]:
                    try:
                        chunk = os.read(master, 65536)
                    except OSError:
                        break
                    if not chunk:
                        break
                    output += chunk
                    if prompts and prompts[0][0] in output:
                        _, response = prompts.pop(0)
                        os.write(master, (response + "\n").encode())
                if process.poll() is not None:
                    break
            else:
                self.fail("Interactive signing timed out")
            self.assertEqual(process.wait(timeout=10), 0)
            self.assertEqual(prompts, [])
            self.check_output(output.decode(errors="replace"))
            self.check_signed()
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=10)
            os.close(master)


if __name__ == "__main__":
    unittest.main(verbosity=2)
