#!/usr/bin/env python3
"""Verify that an APK's JNI payload matches the just-built native library."""

import hashlib
import json
from pathlib import Path
import sys
import zipfile


def verify(directory, native):
    metadata = json.loads((directory / "output-metadata.json").read_text())
    if len(metadata["elements"]) != 1:
        raise ValueError("Expected one arm64 APK output")
    apk = directory / metadata["elements"][0]["outputFile"]
    with zipfile.ZipFile(apk) as archive:
        packaged = archive.read("lib/arm64-v8a/libichuchu_jni.so")
    if packaged != native.read_bytes():
        raise ValueError("APK contains a stale or different JNI library")
    print(f"APK: {apk}")
    print(f"SHA256: {hashlib.sha256(apk.read_bytes()).hexdigest()}")
    return apk


if __name__ == "__main__":
    try:
        verify(*map(Path, sys.argv[1:]))
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        sys.exit(str(error))
