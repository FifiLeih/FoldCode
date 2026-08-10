#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
VERSION=${SDK_VERSION:-2.3.0}
REVISION=${BUNDLE_REVISION:-18}
OUTPUT=${1:-"$ROOT/artifacts/extension-server/v2"}
PACKAGES="$OUTPUT/packages"
COMPONENTS="$OUTPUT/components"
ARTIFACTS="$ROOT/artifacts/extensions"

mkdir -p "$PACKAGES" "$COMPONENTS"

if [[ "${FOLDCODE_SKIP_EXTENSION_BUILD:-0}" != "1" ]]; then
  FOLDCODE_EXTENSION_OUTPUT_DIR="$ARTIFACTS" "$ROOT/tools/build-cpp-extension.sh"
  FOLDCODE_EXTENSION_OUTPUT_DIR="$ARTIFACTS" "$ROOT/tools/build-python-extension.sh"
  "$ROOT/tools/build-gnu-arm-extension.sh" "$ARTIFACTS"
  if [[ -n "${FOLDCODE_RUST_RUNTIME:-}" ]]; then
    "$ROOT/tools/build-rust-extension.sh" "$FOLDCODE_RUST_RUNTIME" "$ARTIFACTS"
  fi
  if [[ -n "${FOLDCODE_GNU_LANGUAGES_RUNTIME:-}" ]]; then
    "$ROOT/tools/build-gnu-language-extensions.sh" "$FOLDCODE_GNU_LANGUAGES_RUNTIME" "$ARTIFACTS"
  fi
  if [[ -n "${FOLDCODE_WEB_RUNTIME:-}" ]]; then
    "$ROOT/tools/build-web-extension.sh" "$FOLDCODE_WEB_RUNTIME" "$ARTIFACTS"
  fi
  PICO_CORE_ONLY=1 "$ROOT/tools/build-pico-extension.sh"
fi

CPP_PACKAGE="$ARTIFACTS/foldcode-cpp-1.0.0-3.fcex"
PICO_PACKAGE="$ARTIFACTS/foldcode-pico-$VERSION-$REVISION.fcex"
PYTHON_VERSION=${PYTHON_VERSION:-3.14.6}
PYTHON_REVISION=${PYTHON_EXTENSION_REVISION:-6}
PYTHON_PACKAGE="$ARTIFACTS/foldcode-python-$PYTHON_VERSION-$PYTHON_REVISION.fcex"
GNU_ARM_PACKAGE="$ARTIFACTS/foldcode-gnu-arm-15.2.1-1.fcex"
cp "$CPP_PACKAGE" "$PACKAGES/$(basename "$CPP_PACKAGE")"
cp "$PICO_PACKAGE" "$PACKAGES/$(basename "$PICO_PACKAGE")"
cp "$PYTHON_PACKAGE" "$PACKAGES/$(basename "$PYTHON_PACKAGE")"
cp "$GNU_ARM_PACKAGE" "$PACKAGES/$(basename "$GNU_ARM_PACKAGE")"
RUST_PACKAGE="$ARTIFACTS/foldcode-rust-1.0.0-4.fcex"
if [[ -f "$RUST_PACKAGE" ]]; then
  cp "$RUST_PACKAGE" "$PACKAGES/$(basename "$RUST_PACKAGE")"
  cp "$ARTIFACTS/components/rust-runtime-arm64.zip" "$COMPONENTS/"
fi
GNU_LANGUAGES_PACKAGE="$ARTIFACTS/foldcode-gnu-languages-1.0.0-6.fcex"
if [[ -f "$GNU_LANGUAGES_PACKAGE" ]]; then
  cp "$GNU_LANGUAGES_PACKAGE" "$PACKAGES/$(basename "$GNU_LANGUAGES_PACKAGE")"
  cp "$ARTIFACTS/components/gnu-languages-runtime-arm64.zip" "$COMPONENTS/"
fi
WEB_PACKAGE="$ARTIFACTS/foldcode-web-1.0.0-10.fcex"
if [[ -f "$WEB_PACKAGE" ]]; then
  cp "$WEB_PACKAGE" "$PACKAGES/$(basename "$WEB_PACKAGE")"
  cp "$ARTIFACTS/components/web-runtime-arm64.zip" "$COMPONENTS/"
fi
cp "$ARTIFACTS/components/cpp-runtime-arm64.zip" "$COMPONENTS/"
cp "$ARTIFACTS/components/python-runtime-arm64.zip" "$COMPONENTS/"
cp "$ARTIFACTS/gnu-arm-runtime.zip" "$COMPONENTS/"
cp "$ROOT/extensions/pico/payload/"*.zip "$COMPONENTS/"

ROOT="$ROOT" OUTPUT="$OUTPUT" VERSION="$VERSION" REVISION="$REVISION" \
PYTHON_VERSION="$PYTHON_VERSION" PYTHON_REVISION="$PYTHON_REVISION" python3 - <<'PY'
import hashlib, json, os
from pathlib import Path

root = Path(os.environ["ROOT"])
out = Path(os.environ["OUTPUT"])
version = os.environ["VERSION"]
revision = os.environ["REVISION"]
python_version = os.environ["PYTHON_VERSION"]
python_revision = os.environ["PYTHON_REVISION"]

def descriptor(path: Path, relative: str):
    return {
        "url": relative,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "sizeBytes": path.stat().st_size,
    }

cpp_package = out / "packages/foldcode-cpp-1.0.0-3.fcex"
pico_package = out / f"packages/foldcode-pico-{version}-{revision}.fcex"
cpp_payload = out / "components/cpp-runtime-arm64.zip"
python_package = out / f"packages/foldcode-python-{python_version}-{python_revision}.fcex"
python_payload = out / "components/python-runtime-arm64.zip"
gnu_arm_package = out / "packages/foldcode-gnu-arm-15.2.1-1.fcex"
gnu_arm_payload = out / "components/gnu-arm-runtime.zip"
pico_payloads = {}
for path in sorted((out / "components").glob("*.zip")):
    if path.name in {cpp_payload.name, python_payload.name, gnu_arm_payload.name, "rust-runtime-arm64.zip", "gnu-languages-runtime-arm64.zip", "web-runtime-arm64.zip"}:
        continue
    pico_payloads[path.name] = descriptor(path, f"components/{path.name}")

catalog = {
    "schemaVersion": 2,
    "extensions": [
        {
            "id": "dev.foldcode.cpp",
            "name": "C/C++",
            "version": "1.0.0+3",
            "publisher": "FoldCode",
            "description": "Clang 21 C/C++ compilation, persistent clangd intelligence, LLDB debugging and execution on Android ARM64.",
            "dependencies": [],
            "provides": ["command:clang", "command:clang++", "command:clangd", "command:ld.lld", "toolchain:clang-21", "language-server:cpp"],
            "corePackage": descriptor(cpp_package, f"packages/{cpp_package.name}"),
            "payloads": {cpp_payload.name: descriptor(cpp_payload, f"components/{cpp_payload.name}")},
        },
        {
            "id": "dev.foldcode.python",
            "name": "Python",
            "version": f"{python_version}+{python_revision}",
            "publisher": "FoldCode",
            "description": "CPython 3.14, pip, Jedi completion, live diagnostics and offline Python/MicroPython intelligence.",
            "dependencies": [],
            "provides": ["command:python", "command:python3", "command:pip", "command:pip3", "language:python", "intelligence:python", "diagnostics:python", "runtime:python:3.14"],
            "corePackage": descriptor(python_package, f"packages/{python_package.name}"),
            "payloads": {python_payload.name: descriptor(python_payload, f"components/{python_payload.name}")},
        },
        {
            "id": "dev.foldcode.gnu-arm",
            "name": "GNU Arm Embedded",
            "version": "15.2.1+1",
            "publisher": "FoldCode",
            "description": "Official GCC 15.2 for GCC-specific Arm firmware projects.",
            "dependencies": ["dev.foldcode.pico"],
            "provides": ["command:arm-none-eabi-gcc", "command:arm-none-eabi-g++", "toolchain:gnu-arm-none-eabi"],
            "corePackage": descriptor(gnu_arm_package, f"packages/{gnu_arm_package.name}"),
            "payloads": {gnu_arm_payload.name: descriptor(gnu_arm_payload, f"components/{gnu_arm_payload.name}")},
        },
        {
            "id": "dev.foldcode.pico",
            "name": "Raspberry Pi Pico",
            "version": f"{version}+{revision}",
            "publisher": "FoldCode",
            "description": "Official Pico SDK, MicroPython firmware, board profiles, cross toolchains and USB deployment.",
            "dependencies": ["dev.foldcode.cpp"],
            "provides": ["command:cmake", "command:ninja", "command:picotool", "command:pioasm", "sdk:pico:2.3.0"],
            "corePackage": descriptor(pico_package, f"packages/{pico_package.name}"),
            "payloads": pico_payloads,
        },
    ],
}
rust_package = out / "packages/foldcode-rust-1.0.0-4.fcex"
rust_payload = out / "components/rust-runtime-arm64.zip"
if rust_package.is_file() and rust_payload.is_file():
    catalog["extensions"].append({
        "id": "dev.foldcode.rust",
        "name": "Rust",
        "version": "1.0.0+4",
        "publisher": "FoldCode",
        "description": "Rust, Cargo, rustfmt and offline rust-analyzer intelligence with RP2040 and RP2350 targets.",
        "dependencies": [],
        "provides": ["command:rustc", "command:cargo", "command:rust-analyzer", "language:rust", "language-server:rust", "source:rust-stdlib"],
        "corePackage": descriptor(rust_package, f"packages/{rust_package.name}"),
        "payloads": {rust_payload.name: descriptor(rust_payload, f"components/{rust_payload.name}")},
    })
gnu_payload = out / "components/gnu-languages-runtime-arm64.zip"
gnu_package = out / "packages/foldcode-gnu-languages-1.0.0-6.fcex"
if gnu_payload.is_file() and gnu_package.is_file():
    catalog["extensions"].append({
        "id": "dev.foldcode.gnu-languages",
        "name": "Fortran & COBOL",
        "version": "1.0.0+6",
        "publisher": "FoldCode",
        "description": "GNU Fortran and GnuCOBOL compilation, project-aware completion, diagnostics and console execution on Android ARM64.",
        "dependencies": [],
        "provides": ["command:gfortran", "command:cobc", "command:cobcrun", "language:fortran", "language:cobol", "intelligence:fortran", "intelligence:cobol", "runtime:gnu-linux-arm64"],
        "corePackage": descriptor(gnu_package, f"packages/{gnu_package.name}"),
        "payloads": {gnu_payload.name: descriptor(gnu_payload, f"components/{gnu_payload.name}")},
    })
web_payload = out / "components/web-runtime-arm64.zip"
web_package = out / "packages/foldcode-web-1.0.0-10.fcex"
if web_payload.is_file() and web_package.is_file():
    catalog["extensions"].append({
        "id": "dev.foldcode.web",
        "name": "Web Development",
        "version": "1.0.0+10",
        "publisher": "FoldCode",
        "description": "Node.js, npm, JavaScript, TypeScript, React and browser preview with dedicated TypeScript, HTML, CSS and JSON IntelliSense on Android ARM64.",
        "dependencies": [],
        "provides": ["command:node", "command:npm", "command:npx", "language:javascript", "language:typescript", "language:html", "language:css", "language:json", "language-server:typescript", "language-server:html", "language-server:css", "language-server:json", "debugger:v8", "debugger:webview-cdp"],
        "corePackage": descriptor(web_package, f"packages/{web_package.name}"),
        "payloads": {web_payload.name: descriptor(web_payload, f"components/{web_payload.name}")},
    })
(out / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n")
PY

echo "Prepared FoldCode extension server v2 in $OUTPUT"
echo "Catalog: $OUTPUT/catalog.json"
