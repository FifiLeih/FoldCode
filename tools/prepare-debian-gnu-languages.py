#!/usr/bin/env python3
"""Resolve and extract Debian ARM64 compiler packages without requiring Docker."""

import argparse
import lzma
import os
import re
import shutil
import subprocess
import tempfile
import urllib.request
from pathlib import Path

MIRROR = "https://deb.debian.org/debian"
DEBIAN_SUITE = "bookworm"
PACKAGES_URL = f"{MIRROR}/dists/{DEBIAN_SUITE}/main/binary-arm64/Packages.xz"


def fields(paragraph: str) -> dict[str, str]:
    result: dict[str, str] = {}
    current = None
    for line in paragraph.splitlines():
        if line.startswith(" ") and current:
            result[current] += " " + line.strip()
        elif ": " in line:
            current, value = line.split(": ", 1)
            result[current] = value
    return result


def dependency_names(value: str) -> list[list[str]]:
    groups = []
    for requirement in value.split(","):
        alternatives = []
        for alternative in requirement.split("|"):
            name = re.split(r"\s|\(", alternative.strip(), maxsplit=1)[0]
            name = name.split(":", 1)[0]
            if name:
                alternatives.append(name)
        if alternatives:
            groups.append(alternatives)
    return groups


def download(url: str, destination: Path) -> None:
    with urllib.request.urlopen(url) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    rootfs = args.output / "rootfs"
    rootfs.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="foldcode-gnu-") as temporary_name:
        temporary = Path(temporary_name)
        index_archive = temporary / "Packages.xz"
        print(f"Downloading Debian {DEBIAN_SUITE.title()} ARM64 package index…", flush=True)
        download(PACKAGES_URL, index_archive)
        index = lzma.decompress(index_archive.read_bytes()).decode("utf-8")
        available = {}
        for paragraph in index.split("\n\n"):
            package = fields(paragraph)
            name = package.get("Package")
            if name and package.get("Architecture") in {"arm64", "all"} and "Filename" in package:
                available.setdefault(name, package)

        # Keep the compiler and XML development/runtime packages in one stable
        # Debian suite. Mixing Trixie's transitional GnuCOBOL build with its
        # reverted libxml2 runtime produced the 2.12-versus-2.9 ABI warning.
        pending = [
            "gfortran",
            "gnucobol3",
            "gcc",
            "binutils",
            "libc6-dev",
            "libcob4-dev",
            "libxml2",
            "libxml2-dev",
            "dash",
        ]
        selected = {}
        while pending:
            name = pending.pop(0)
            if name in selected:
                continue
            package = available.get(name)
            if package is None:
                raise SystemExit(f"Debian package not found: {name}")
            selected[name] = package
            for key in ("Pre-Depends", "Depends"):
                for alternatives in dependency_names(package.get(key, "")):
                    candidate = next((item for item in alternatives if item in available), None)
                    if candidate and candidate not in selected:
                        pending.append(candidate)

        print(f"Extracting {len(selected)} Debian packages…", flush=True)
        for index_number, (name, package) in enumerate(sorted(selected.items()), 1):
            archive = temporary / f"{name}.deb"
            print(f"[{index_number}/{len(selected)}] {name}", flush=True)
            download(f"{MIRROR}/{package['Filename']}", archive)
            unpack = temporary / f"unpack-{index_number}"
            unpack.mkdir()
            subprocess.run(["bsdtar", "-xf", str(archive), "-C", str(unpack)], check=True)
            data = next(unpack.glob("data.tar.*"), None)
            if data is None:
                raise SystemExit(f"No data archive in {archive}")
            subprocess.run(["bsdtar", "-xf", str(data), "-C", str(rootfs)], check=True)

    # Debian packages may contain absolute links such as /lib -> /usr/lib.
    # Resolve those inside the staged rootfs rather than against the macOS host.
    links = [path for path in rootfs.rglob("*") if path.is_symlink()]
    for link in links:
        raw_target = os.readlink(link)
        if os.path.isabs(raw_target):
            staged_target = rootfs / raw_target.lstrip("/")
            link.unlink()
            link.symlink_to(os.path.relpath(staged_target, start=link.parent))

    # FoldCode's ZIP installer cannot recreate symlinks. Materialize every one.
    links = [path for path in rootfs.rglob("*") if path.is_symlink()]
    for link in sorted(links, key=lambda path: len(path.parts), reverse=True):
        try:
            target = link.resolve(strict=True)
        except FileNotFoundError:
            # Debian packages can intentionally leave links to optional tools
            # from packages that are not part of this compiler runtime (for
            # example /etc/rmt). They cannot work in FoldCode, so omit them.
            print(f"Omitting optional dangling symlink: {link} -> {os.readlink(link)}", flush=True)
            link.unlink()
            continue
        link.unlink()
        if target.is_dir():
            shutil.copytree(target, link, symlinks=False)
        else:
            shutil.copy2(target, link)

    # Retain the authoritative Debian copyright file for every package while
    # dropping manuals, changelogs and translations that are not needed by the
    # compiler runtime. This mirrors the Docker packaging path above.
    license_directory = rootfs / "usr/share/foldcode-licenses"
    license_directory.mkdir(parents=True, exist_ok=True)
    documentation_directory = rootfs / "usr/share/doc"
    if documentation_directory.is_dir():
        for copyright in documentation_directory.glob("*/copyright"):
            shutil.copy2(
                copyright,
                license_directory / f"{copyright.parent.name}.copyright",
            )
        shutil.rmtree(documentation_directory)
    for optional_directory in (rootfs / "usr/share/man", rootfs / "usr/share/locale"):
        if optional_directory.exists():
            shutil.rmtree(optional_directory)

    (rootfs / "tmp").mkdir(exist_ok=True)
    (args.output / "tmp").mkdir(exist_ok=True)
    (args.output / ".debian-suite").write_text(DEBIAN_SUITE + "\n")
    print(args.output)


if __name__ == "__main__":
    main()
