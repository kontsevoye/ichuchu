#!/usr/bin/env python3
"""Build without signing secrets, then sign/verify locally or from CI inputs."""

import argparse
import base64
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import warnings
import zipfile


ROOT = Path(__file__).resolve().parents[1]
SIGNING_INPUTS = {
    "KEYSTORE_FILE", "KEYSTORE_BASE64", "KEY_ALIAS", "KEYSTORE_PASSWORD",
    "KEY_PASSWORD", "KEYSTORE_PASSWORD_FILE", "KEY_PASSWORD_FILE",
    "ICHUCHU_SIGN_STORE_PASSWORD", "ICHUCHU_SIGN_KEY_PASSWORD",
}


class SigningError(Exception):
    pass


def password(name, prompt, interactive, default=None):
    value, filename = os.environ.get(name), os.environ.get(name + "_FILE")
    if value and filename:
        raise SigningError(f"Choose {name} or {name}_FILE, not both")
    if filename:
        try:
            value = Path(filename).read_text().removesuffix("\n").removesuffix("\r")
        except (OSError, UnicodeError):
            raise SigningError(f"Cannot read {name}_FILE") from None
    if not value and interactive:
        # getpass otherwise falls back to visible input if terminal echo cannot be disabled.
        with warnings.catch_warnings():
            warnings.simplefilter("error", getpass.GetPassWarning)
            try:
                value = getpass.getpass(prompt)
            except getpass.GetPassWarning:
                raise SigningError("Cannot disable terminal echo; use a working terminal or non-interactive inputs") from None
    value = value or default
    if not value:
        raise SigningError(f"Missing {name} (or {name}_FILE)")
    return value


def tool(command, env, secrets):
    result = subprocess.run(command, env=env, capture_output=True, stdin=subprocess.DEVNULL)
    if result.returncode:
        detail = (result.stderr or result.stdout).decode(errors="replace")
        for secret in secrets:
            detail = detail.replace(secret, "<REDACTED>")
        raise SigningError(f"{Path(command[0]).name} failed: {detail[:3000]}")
    return result.stdout


def keystore_path(args, directory, interactive):
    filename = args.keystore or os.environ.get("KEYSTORE_FILE")
    encoded = os.environ.get("KEYSTORE_BASE64")
    if filename and encoded:
        raise SigningError("Choose KEYSTORE_FILE/--keystore or KEYSTORE_BASE64, not both")
    if encoded:
        filename = Path(directory) / "release.jks"
        try:
            data = base64.b64decode("".join(encoded.split()), validate=True)
        except ValueError:
            raise SigningError("KEYSTORE_BASE64 is not valid Base64") from None
        with open(filename, "xb", opener=lambda path, flags: os.open(path, flags, 0o600)) as stream:
            stream.write(data)
    if not filename and interactive:
        default = "~/.local/share/android-signing/ichuchu-release.jks"
        filename = input(f"Keystore path [{default}]: ").strip() or default
    if not filename:
        raise SigningError("Missing KEYSTORE_FILE/--keystore or KEYSTORE_BASE64")
    path = Path(filename).expanduser().resolve()
    if not path.is_file():
        raise SigningError("Keystore file does not exist")
    return path


def credentials(args, directory, interactive, clean_env):
    path = keystore_path(args, directory, interactive)
    store_password = password("KEYSTORE_PASSWORD", "Keystore password: ", interactive)
    key_password = password("KEY_PASSWORD", "Private key password [same as keystore]: ", interactive, store_password)
    secrets = (store_password, key_password)
    env = dict(clean_env, ICHUCHU_SIGN_STORE_PASSWORD=store_password, ICHUCHU_SIGN_KEY_PASSWORD=key_password)
    keytool = str(Path(os.environ["JAVA_HOME"]) / "bin/keytool")
    common = [keytool, "-J-Duser.language=en", "-J-Duser.country=US"]
    access = ["-keystore", str(path), "-storepass:env", "ICHUCHU_SIGN_STORE_PASSWORD"]
    alias = args.alias or os.environ.get("KEY_ALIAS")
    if not alias:
        listing = tool(common + ["-list", "-rfc"] + access, env, secrets).decode()
        entries = re.split(r"(?m)^Alias name: ", listing)[1:]
        aliases = [entry.splitlines()[0].strip() for entry in entries if "Entry type: PrivateKeyEntry" in entry]
        if len(aliases) != 1:
            raise SigningError("Set KEY_ALIAS/--alias: keystore must contain exactly one private key for auto-selection")
        alias = aliases[0]
    certificate = tool(common + ["-exportcert", "-alias", alias] + access, env, secrets)
    return path, alias, env, secrets, hashlib.sha256(certificate).hexdigest()


def verify_payload(source, signed):
    def payload_names(archive):
        return {name for name in archive.namelist() if not re.fullmatch(r"META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))", name)}
    with zipfile.ZipFile(source) as before, zipfile.ZipFile(signed) as after:
        names = payload_names(before)
        if names != payload_names(after) or any(before.read(name) != after.read(name) for name in names):
            raise SigningError("Application payload changed during signing")


def sign_artifacts(args, path, alias, env, secrets, fingerprint, clean_env):
    apk_dir = ROOT / "android/app/build/outputs/apk/release"
    metadata = json.loads((apk_dir / "output-metadata.json").read_text())
    if len(metadata["elements"]) != 1:
        raise SigningError("Expected exactly one release APK")
    source = apk_dir / metadata["elements"][0]["outputFile"]
    apksigner = str(Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0/apksigner")
    result = {"certificate_sha256": fingerprint, "version_code": metadata["elements"][0]["versionCode"]}
    with tempfile.TemporaryDirectory(prefix=".signing-", dir=apk_dir) as directory:
        apk = Path(directory) / "app-release.apk"
        tool([apksigner, "sign", "--ks", str(path), "--ks-key-alias", alias,
              "--ks-pass", "env:ICHUCHU_SIGN_STORE_PASSWORD", "--key-pass", "env:ICHUCHU_SIGN_KEY_PASSWORD",
              "--out", str(apk), str(source)], env, secrets)
        verification = tool([apksigner, "verify", "--verbose", "--print-certs", str(apk)], clean_env, secrets).decode()
        signers = re.findall(r"certificate SHA-256 digest: ([0-9a-f]+)", verification)
        if signers != [fingerprint]:
            raise SigningError("APK signer does not match the selected keystore certificate")
        verify_payload(source, apk)
        bundle = ROOT / "android/app/build/outputs/bundle/release/app-release.aab"
        if args.bundle:
            signed_bundle = Path(directory) / "app-release.aab"
            jarsigner = str(Path(os.environ["JAVA_HOME"]) / "bin/jarsigner")
            common = [jarsigner, "-J-Duser.language=en", "-J-Duser.country=US"]
            tool(common + ["-keystore", str(path), "-storepass:env", "ICHUCHU_SIGN_STORE_PASSWORD",
                           "-keypass:env", "ICHUCHU_SIGN_KEY_PASSWORD", "-signedjar", str(signed_bundle), str(bundle), alias], env, secrets)
            verified_bundle = tool(common + ["-verify", str(signed_bundle)], clean_env, secrets).decode()
            if "jar verified." not in verified_bundle:
                raise SigningError("AAB signature verification failed")
            verify_payload(bundle, signed_bundle)
            os.replace(signed_bundle, bundle)
            result["aab"] = str(bundle)
        destination = apk_dir / "app-release.apk"
        os.replace(apk, destination)
    result.update(apk=str(destination), sha256=hashlib.sha256(destination.read_bytes()).hexdigest())
    (apk_dir / "signed-artifacts.json").write_text(json.dumps(result, indent=2) + "\n")
    print("Signed APK:", destination)
    if args.bundle:
        print("Signed AAB:", bundle)
    print("Certificate SHA256:", fingerprint)
    print("APK SHA256:", result["sha256"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keystore", help="Keystore path (or KEYSTORE_FILE)")
    parser.add_argument("--alias", help="Private key alias; auto-selects a single private key")
    parser.add_argument("--bundle", action="store_true", help="Also build, sign and verify the AAB")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--interactive", action="store_true")
    mode.add_argument("--non-interactive", action="store_true")
    args = parser.parse_args()
    if os.environ.get("ICHUCHU_NIX_SHELL") != "1":
        raise SigningError("Run through nix develop --command build-apk signed-release")
    interactive = args.interactive or (not args.non_interactive and sys.stdin.isatty() and os.environ.get("CI", "").lower() not in ("1", "true"))
    if interactive and not sys.stdin.isatty():
        raise SigningError("Interactive signing requires a terminal")
    clean_env = {key: value for key, value in os.environ.items() if key not in SIGNING_INPUTS}
    # The private decoded CI keystore lives only in a mode-0700 temporary directory.
    with tempfile.TemporaryDirectory(prefix="ichuchu-signing-", dir=os.environ.get("RUNNER_TEMP")) as directory:
        path, alias, env, secrets, fingerprint = credentials(args, directory, interactive, clean_env)
        command = ["bash", str(ROOT / "build-support/build-apk.sh"), "release"]
        if args.bundle:
            command.append("--bundle")
        subprocess.run(command, env=clean_env, check=True)
        sign_artifacts(args, path, alias, env, secrets, fingerprint, clean_env)


if __name__ == "__main__":
    try:
        main()
    except (SigningError, OSError) as error:
        print(f"Signing failed: {error}", file=sys.stderr)
        sys.exit(1)
    except (EOFError, KeyboardInterrupt):
        print("Signing cancelled", file=sys.stderr)
        sys.exit(1)
    except subprocess.CalledProcessError as error:
        print(f"Release build failed (exit {error.returncode})", file=sys.stderr)
        sys.exit(1)
