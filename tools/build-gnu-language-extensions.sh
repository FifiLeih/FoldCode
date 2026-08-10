#!/usr/bin/env bash
set -euo pipefail

# Packages GNU Fortran and GnuCOBOL together because they share GCC, glibc,
# binutils and the same Linux execution boundary.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME="${1:?usage: build-gnu-language-extensions.sh <runtime-directory> [output-directory]}"
OUTPUT="${2:-$ROOT/artifacts/extensions}"
mkdir -p "$OUTPUT"
OUTPUT="$(cd "$OUTPUT" && pwd)"
PAYLOAD="$OUTPUT/components/gnu-languages-runtime-arm64.zip"

test -f "$RUNTIME/rootfs/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" || { echo "missing GNU/Linux ARM64 loader" >&2; exit 1; }
test -f "$RUNTIME/rootfs/usr/bin/gfortran" || { echo "missing gfortran" >&2; exit 1; }
test -f "$RUNTIME/rootfs/usr/bin/cobc" || { echo "missing cobc" >&2; exit 1; }
test -f "$RUNTIME/rootfs/usr/lib/libfoldspawn.so" || { echo "missing Android-safe spawn shim" >&2; exit 1; }
test "$(tr -d '\n' < "$RUNTIME/.debian-suite")" = "bookworm" || {
  echo "GNU languages runtime must be rebuilt from the consistent Debian Bookworm package set" >&2
  exit 1
}

RUNTIME_LOADER="$RUNTIME/rootfs/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
APK_GNU_LOADER="$ROOT/app/src/main/jniLibs/arm64-v8a/libfoldgnuld.so"
test -f "$APK_GNU_LOADER" || { echo "missing APK GNU/Linux ARM64 loader" >&2; exit 1; }
cmp -s "$RUNTIME_LOADER" "$APK_GNU_LOADER" || {
  echo "APK GNU loader does not match the GNU languages runtime; rebuild the runtime first" >&2
  exit 1
}

mkdir -p "$OUTPUT/components"
rm -f "$PAYLOAD"
(cd "$RUNTIME" && zip -qry "$PAYLOAD" .)
HASH="$(shasum -a 256 "$PAYLOAD" | awk '{print $1}')"

PACKAGE_DIR="$OUTPUT/package-gnu-languages"
PACKAGE="$OUTPUT/foldcode-gnu-languages-1.0.0-6.fcex"
rm -rf "$PACKAGE_DIR"
mkdir -p "$PACKAGE_DIR/payload"
sed "s/__GNU_RUNTIME_SHA256__/$HASH/g" \
  "$ROOT/extensions/gnu-languages/manifest.template.json" > "$PACKAGE_DIR/manifest.json"
cp "$PAYLOAD" "$PACKAGE_DIR/payload/gnu-languages-runtime-arm64.zip"
"$ROOT/tools/package-extension-legal.sh" "$ROOT/extensions/gnu-languages" "$PACKAGE_DIR"
rm -f "$PACKAGE"
(cd "$PACKAGE_DIR" && zip -0 -q "$PACKAGE" manifest.json payload/gnu-languages-runtime-arm64.zip licenses/THIRD_PARTY_NOTICES.md licenses/SOURCES.json)
python3 "$ROOT/tools/verify-extension-notices.py" "$PACKAGE"
echo "$PACKAGE"
