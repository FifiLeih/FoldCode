#!/usr/bin/env python3
"""Rewrite versioned ELF SONAMEs to APK-compatible names of identical length."""

from pathlib import Path


RENAMES = {
    b"libz.so.1": b"libzzz.so",
    b"libzstd.so.1": b"libzstdxx.so",
    b"libxml2.so.16": b"libxml2xxx.so",
    b"libicuuc.so.78": b"libicuucxxx.so",
    b"libicudata.so.78": b"libicudataxxx.so",
}


def patch(path: Path) -> None:
    data = path.read_bytes()
    original = data
    for old, new in RENAMES.items():
        if len(old) != len(new):
            raise ValueError(f"Replacement length differs: {old!r} -> {new!r}")
        data = data.replace(old, new)
    if data != original:
        path.write_bytes(data)
        print(f"patched {path}")


if __name__ == "__main__":
    root = Path("app/src/main/jniLibs/arm64-v8a")
    for library in root.glob("*.so"):
        patch(library)

