#!/usr/bin/env python3
"""Synthetic N→N+1 acceptance on a fresh arm64 API35 emulator; never targets a physical device."""
import argparse
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PKG = "io.github.kontsevoye.ichuchu.updatetest"
ACTIVITY = "io.github.kontsevoye.ichuchu.service.update.UpdateAcceptanceActivity"
EVIDENCE = ROOT / ".plans/update-verification"
ASSETS = ROOT / "android/app/src/updateTest/assets"


def run(command, env=None, output=None):
    return subprocess.run(list(map(str, command)), cwd=ROOT, env=env, check=True,
                          stdout=output or subprocess.PIPE, stderr=subprocess.STDOUT).stdout


def build_fixtures(env):
    ASSETS.mkdir(parents=True, exist_ok=True)
    for name in ("next-update.apk", "wrong-signer.apk"):
        (ASSETS / name).unlink(missing_ok=True)
    def assemble(name, code, output):
        with (EVIDENCE / "fixture-build.log").open("ab") as log:
            run([ROOT / "android/gradlew", "-p", ROOT / "android", ":app:assembleUpdateTest",
                 "--no-daemon", "--no-configuration-cache", "--console=plain"],
                dict(env, VERSION_NAME=name, VERSION_CODE=str(code)), log)
        shutil.copy2(ROOT / "android/app/build/outputs/apk/updateTest/app-updateTest.apk", output)
    assemble("0.0.2", 1000002, EVIDENCE / "next-update.apk")
    shutil.copy2(EVIDENCE / "next-update.apk", ASSETS / "next-update.apk")
    with tempfile.TemporaryDirectory(prefix="ichuchu-update-fixture-key-") as folder:
        key = Path(folder) / "test.p12"
        keyenv = dict(env, UPDATE_FIXTURE_PASSWORD="only-synthetic-fixture-password")
        run([Path(env["JAVA_HOME"]) / "bin/keytool", "-genkeypair", "-alias", "test", "-keystore", key,
             "-storetype", "PKCS12", "-storepass:env", "UPDATE_FIXTURE_PASSWORD", "-keypass:env", "UPDATE_FIXTURE_PASSWORD",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=updater-fixture"], keyenv)
        run([Path(env["ANDROID_HOME"]) / "build-tools/36.0.0/apksigner", "sign", "--ks", key,
             "--ks-key-alias", "test", "--ks-pass", "env:UPDATE_FIXTURE_PASSWORD", "--key-pass", "env:UPDATE_FIXTURE_PASSWORD",
             "--out", ASSETS / "wrong-signer.apk", EVIDENCE / "next-update.apk"], keyenv)
    assemble("0.0.1", 1000001, EVIDENCE / "baseline.apk")


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def emulator_port():
    for port in range(5584, 5680, 2):
        try:
            with socket.socket() as first, socket.socket() as second:
                first.bind(("127.0.0.1", port)); second.bind(("127.0.0.1", port + 1))
            return port
        except OSError:
            continue
    raise RuntimeError("No unused emulator port available")


class Device:
    def __init__(self, sdk, port, adb_port):
        self.command = [sdk / "platform-tools/adb", "-P", str(adb_port), "-s", f"emulator-{port}"]
    def adb(self, *args):
        return run(self.command + list(args))
    def wait(self, condition, seconds=40):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                if condition(): return
            except subprocess.CalledProcessError:
                pass
            time.sleep(.5)
        raise RuntimeError("Timed out waiting for isolated device state")
    def xml(self):
        self.adb("shell", "uiautomator", "dump", "/sdcard/ichuchu-updater-ui.xml")
        data = self.adb("shell", "cat", "/sdcard/ichuchu-updater-ui.xml")
        (EVIDENCE / "current-ui.xml").write_bytes(data)
        return ET.fromstring(data)
    def click(self, text, scroll=False):
        for _ in range(10 if scroll else 1):
            for node in self.xml().iter("node"):
                if node.get("text") == text:
                    bounds = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
                    if len(bounds) == 4 and bounds[3] > bounds[1]:
                        self.adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
                        return
            if scroll: self.adb("shell", "input", "swipe", "540", "1710", "540", "500", "300")
        raise RuntimeError(f"UI text not found: {text}")
    def result(self):
        return self.adb("shell", "run-as", PKG, "cat", "files/updater-acceptance-result").decode().strip()
    def start(self, mode=None):
        self.adb("shell", "run-as", PKG, "rm", "-f", "files/updater-acceptance-result")
        args = ["shell", "am", "start", "-W", "-n", PKG + "/" + ACTIVITY]
        if mode: args += ["--es", "mode", mode]
        self.adb(*args)
        self.wait(lambda: self.result() == "PASS")
    def upgrade(self):
        self.adb("install", "-r", EVIDENCE / "baseline.apk")
        self.start()
        self.click("install update", scroll=True)
        self.click("allow app installs", scroll=True)
        self.click("Allow from this source")
        self.adb("shell", "input", "keyevent", "KEYCODE_BACK")
        self.click("permission granted · retry install", scroll=True)
        self.click("CANCEL")
        self.click("install update", scroll=True)
        self.wait(lambda: any(n.get("text") == "UPDATE" for n in self.xml().iter("node")))
        pid = self.adb("shell", "pidof", PKG).decode().strip().split()[0]
        assert pid.isdigit()
        self.adb("shell", "run-as", PKG, "kill", "-9", pid)
        self.adb("shell", "am", "start", "-W", "-n", PKG + "/io.github.kontsevoye.ichuchu.MainActivity",
                 "--ez", "ichuchu.open_updates", "true")
        self.click("continue")
        self.wait(lambda: any(n.get("text") == "UPDATE" for n in self.xml().iter("node")))
        self.click("UPDATE")
        self.wait(lambda: b"versionCode=1000002" in self.adb("shell", "dumpsys", "package", PKG))
        self.start("verify")


def accept(sdk, env):
    image = sdk / "system-images/android-35/default/arm64-v8a"
    if not image.is_dir(): raise RuntimeError("Install the API35 default arm64-v8a system image in the supplied test SDK")
    port, adb_port = emulator_port(), free_port()
    device = Device(sdk, port, adb_port)
    with tempfile.TemporaryDirectory(prefix="ichuchu-updater-avd-") as folder:
        home = Path(folder); avd = home / "ichuchu-updater.avd"; avd.mkdir()
        (home / "ichuchu-updater.ini").write_text(f"avd.ini.encoding=UTF-8\npath={avd}\ntarget=android-35\n")
        (avd / "config.ini").write_text(f"AvdId=ichuchu-updater\nabi.type=arm64-v8a\ntag.id=default\nimage.sysdir.1={image}/\nhw.cpu.arch=arm64\nhw.cpu.ncore=4\nhw.ramSize=2048\nhw.lcd.width=1080\nhw.lcd.height=1920\nhw.lcd.density=420\nhw.keyboard=yes\nhw.gpu.enabled=yes\nhw.gpu.mode=swiftshader_indirect\ndisk.dataPartition.size=3221225472\nshowDeviceFrame=no\n")
        runtime = dict(env, ANDROID_AVD_HOME=str(home), ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk),
                       ANDROID_ADB_SERVER_PORT=str(adb_port), ADB_SERVER_SOCKET=f"tcp:127.0.0.1:{adb_port}", ADB_MDNS_AUTO_CONNECT="0")
        run([sdk / "platform-tools/adb", "-P", str(adb_port), "start-server"], runtime)
        with (EVIDENCE / "emulator.log").open("wb") as log:
            process = subprocess.Popen([str(sdk / "emulator/emulator"), "-avd", "ichuchu-updater", "-port", str(port),
                                        "-no-window", "-no-audio", "-no-boot-anim", "-no-snapshot", "-gpu", "swiftshader_indirect"],
                                       env=runtime, stdout=log, stderr=subprocess.STDOUT)
            try:
                device.wait(lambda: device.adb("shell", "getprop", "sys.boot_completed").strip() == b"1", 100)
                device.adb("shell", "settings", "put", "system", "screen_off_timeout", "2147483647")
                device.upgrade()
                (EVIDENCE / "result.txt").write_text("PASS: APK preflight rejection; terminal guard; scheduler; permission UI; system cancellation; process-death resume; N→N+1 install; SQLCipher/Keystore/settings retained.\n")
                print((EVIDENCE / "result.txt").read_text(), flush=True)
            finally:
                process.terminate()
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=10)
                run([sdk / "platform-tools/adb", "-P", str(adb_port), "kill-server"], runtime)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True, help="Separate SDK with emulator, platform-tools and API35 arm64 image")
    parser.add_argument("--skip-build", action="store_true", help="Reuse the already-generated baseline/next fixtures")
    args = parser.parse_args()
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    env = {k: v for k, v in os.environ.items() if not k.startswith(("KEYSTORE_", "KEY_", "ICHUCHU_SIGN_"))}
    env["GRADLE_USER_HOME"] = str(ROOT / ".cache/gradle")
    if not args.skip_build: build_fixtures(env)
    accept(args.sdk.resolve(), env)


if __name__ == "__main__":
    main()
