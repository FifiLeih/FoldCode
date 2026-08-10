#!/usr/bin/env python3
"""Verify that a packaged FoldCode Python extension contains a usable pip runtime."""

from __future__ import annotations

import hashlib
import io
import json
import sys
import zipfile
from pathlib import Path


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def metadata_version(payload: zipfile.ZipFile, path: str) -> str:
    for line in payload.read(path).decode("utf-8").splitlines():
        if line.startswith("Version: "):
            return line.removeprefix("Version: ").strip()
    raise ValueError(f"{path}: Version field is missing")


def verify(package_path: Path) -> None:
    with zipfile.ZipFile(package_path) as package:
        manifest = json.loads(package.read("manifest.json"))
        require(manifest.get("id") == "dev.foldcode.python", "unexpected extension id")
        require(manifest.get("version") == "3.14.6+6", "unexpected Python extension version")
        provides = set(manifest.get("provides", []))
        require("command:python" in provides, "manifest does not provide python")
        require("command:pip" in provides, "manifest does not provide pip")
        require("command:pip3" in provides, "manifest does not provide pip3")

        payload_name = "python-runtime-arm64.zip"
        payload_path = f"payload/{payload_name}"
        payload_bytes = package.read(payload_path)
        expected_hash = manifest.get("payloads", {}).get(payload_name)
        actual_hash = hashlib.sha256(payload_bytes).hexdigest()
        require(actual_hash == expected_hash, f"{payload_name}: SHA-256 mismatch")

    with zipfile.ZipFile(io.BytesIO(payload_bytes)) as payload:
        names = set(payload.namelist())
        required = {
            "prefix/lib/libpython3.14.so",
            "prefix/lib/libcrypto_python.so",
            "prefix/lib/libssl_python.so",
            "prefix/lib/python3.14/site-packages/pip/__init__.py",
            "prefix/lib/python3.14/site-packages/pip-26.1.2.dist-info/METADATA",
            "prefix/lib/python3.14/ensurepip/_bundled/pip-26.1.2-py3-none-any.whl",
            "foldcode/python_completion_server.py",
        }
        missing = sorted(required - names)
        require(not missing, f"Python runtime is missing: {', '.join(missing)}")
        require(
            any(name.startswith("prefix/lib/python3.14/lib-dynload/_ssl.") for name in names),
            "Python runtime is missing the Android _ssl module",
        )
        require(
            metadata_version(
                payload,
                "prefix/lib/python3.14/site-packages/pip-26.1.2.dist-info/METADATA",
            )
            == "26.1.2",
            "installed pip metadata has the wrong version",
        )

        wheels = {
            "cffi-2.1.1-cp314-cp314-android_24_arm64_v8a.whl": (
                "20dccd96614a4485b30e6f22f1dc89e09e8b668ec63824eaa3d4896e7c530c61",
                "cffi-2.1.1.dist-info/licenses/LICENSE",
            ),
            "cryptography-50.0.0-cp314-abi3-android_24_arm64_v8a.whl": (
                "208afbc30d9cdcf8ea34024b3360aad93fb9e81f0829b0d842ea675de16ccdb1",
                "cryptography-50.0.0.dist-info/licenses/LICENSE",
            ),
            "pycparser-3.0-py3-none-any.whl": (
                "b727414169a36b7d524c1c3e31839a521725078d7b2ff038656844266160a992",
                "pycparser-3.0.dist-info/licenses/LICENSE",
            ),
        }
        for wheel_name, (expected_wheel_hash, license_path) in wheels.items():
            wheel_path = f"foldcode/wheelhouse/{wheel_name}"
            require(wheel_path in names, f"Python wheelhouse is missing {wheel_name}")
            wheel_bytes = payload.read(wheel_path)
            require(
                hashlib.sha256(wheel_bytes).hexdigest() == expected_wheel_hash,
                f"{wheel_name}: SHA-256 mismatch",
            )
            with zipfile.ZipFile(io.BytesIO(wheel_bytes)) as wheel:
                require(license_path in wheel.namelist(), f"{wheel_name}: license is missing")

    print(
        f"{package_path}: Python 3.14, pip 26.1.2, and curated Android wheels verified"
    )


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify-python-extension.py <foldcode-python.fcex>")
    verify(Path(sys.argv[1]))


if __name__ == "__main__":
    main()
