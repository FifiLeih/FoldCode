#!/usr/bin/env python3
"""Validate FoldCode extension legal metadata and packaged notice files."""

from __future__ import annotations

import json
import sys
import zipfile
from pathlib import Path

NOTICE = "licenses/THIRD_PARTY_NOTICES.md"
SOURCES = "licenses/SOURCES.json"
REQUIRED_COMPONENT_FIELDS = {"name", "version", "license", "upstream"}


def validate_documents(manifest: dict, notice: str, sources_text: str, label: str) -> None:
    if manifest.get("schemaVersion", 0) < 3:
        raise ValueError(f"{label}: schemaVersion must be at least 3")
    legal = manifest.get("legal")
    if legal != {"notices": NOTICE, "sources": SOURCES}:
        raise ValueError(f"{label}: legal paths must point to the standard notice files")
    if not notice.strip():
        raise ValueError(f"{label}: third-party notice is empty")
    sources = json.loads(sources_text)
    if sources.get("extensionId") != manifest.get("id"):
        raise ValueError(f"{label}: SOURCES.json extensionId does not match manifest id")
    components = sources.get("components")
    if not isinstance(components, list) or not components:
        raise ValueError(f"{label}: SOURCES.json must declare at least one component")
    for index, component in enumerate(components):
        if not isinstance(component, dict):
            raise ValueError(f"{label}: component {index} is not an object")
        missing = REQUIRED_COMPONENT_FIELDS - component.keys()
        if missing:
            raise ValueError(f"{label}: component {index} is missing {', '.join(sorted(missing))}")
        for field in REQUIRED_COMPONENT_FIELDS:
            if not str(component[field]).strip():
                raise ValueError(f"{label}: component {index} has an empty {field}")


def validate_directory(directory: Path) -> None:
    manifest_path = next(
        (candidate for candidate in (directory / "manifest.json", directory / "manifest.template.json") if candidate.is_file()),
        None,
    )
    if manifest_path is None:
        raise ValueError(f"{directory}: extension manifest is missing")
    validate_documents(
        json.loads(manifest_path.read_text()),
        (directory / NOTICE).read_text(),
        (directory / SOURCES).read_text(),
        str(directory),
    )


def validate_package(package: Path) -> None:
    with zipfile.ZipFile(package) as archive:
        names = set(archive.namelist())
        required = {"manifest.json", NOTICE, SOURCES}
        if not required <= names:
            raise ValueError(f"{package}: package is missing {', '.join(sorted(required - names))}")
        validate_documents(
            json.loads(archive.read("manifest.json")),
            archive.read(NOTICE).decode(),
            archive.read(SOURCES).decode(),
            str(package),
        )


def main() -> None:
    paths = [Path(value) for value in sys.argv[1:]]
    if not paths:
        root = Path(__file__).resolve().parent.parent
        paths = sorted(path.parent for path in (root / "extensions").glob("*/manifest*.json"))
    for path in paths:
        if path.is_dir():
            validate_directory(path)
        elif path.suffix == ".fcex":
            validate_package(path)
        else:
            raise ValueError(f"Unsupported validation input: {path}")
    print(f"Extension legal metadata passed for {len(paths)} item(s).")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, json.JSONDecodeError, zipfile.BadZipFile) as error:
        print(error, file=sys.stderr)
        raise SystemExit(1)
