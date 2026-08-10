#!/usr/bin/env bash
set -euo pipefail

# Packages an AArch64 Linux Rust host toolchain and its minimal glibc rootfs.
# Android runs it through FoldCode's APK-labelled PRoot launcher.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME="${1:?usage: build-rust-extension.sh <rust-runtime-directory> [output-directory]}"
OUTPUT="${2:-$ROOT/artifacts/extensions}"
PAYLOAD="$OUTPUT/components/rust-runtime-arm64.zip"
PACKAGE="$OUTPUT/foldcode-rust-1.0.0-4.fcex"

test -f "$RUNTIME/rootfs/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" || { echo "missing Rust rootfs loader" >&2; exit 1; }
for tool in rustc cargo rust-analyzer rustfmt; do
  test -f "$RUNTIME/toolchain/bin/$tool" || { echo "missing $RUNTIME/toolchain/bin/$tool" >&2; exit 1; }
done
for target in thumbv6m-none-eabi thumbv8m.main-none-eabihf riscv32imac-unknown-none-elf; do
  test -d "$RUNTIME/toolchain/lib/rustlib/$target/lib" || { echo "missing Rust standard library target $target" >&2; exit 1; }
done
test -f "$RUNTIME/toolchain/lib/rustlib/src/rust/library/std/src/lib.rs" || { echo "missing Rust standard-library sources" >&2; exit 1; }
test ! -L "$RUNTIME/rootfs/usr/lib/aarch64-linux-gnu/libz.so.1" || { echo "Rust runtime contains ZIP-incompatible symlinks; rerun prepare-rust-runtime.sh" >&2; exit 1; }
test "$(wc -c < "$RUNTIME/rootfs/usr/lib/aarch64-linux-gnu/libz.so.1")" -gt 100000 || { echo "Rust runtime contains an invalid libz.so.1" >&2; exit 1; }

rm -rf "$OUTPUT/package"
mkdir -p "$OUTPUT/components" "$OUTPUT/package/payload"
(cd "$RUNTIME" && zip -qry "$PAYLOAD" .)
HASH="$(shasum -a 256 "$PAYLOAD" | awk '{print $1}')"
sed "s/__RUST_RUNTIME_SHA256__/$HASH/g" \
  "$ROOT/extensions/rust/manifest.template.json" > "$OUTPUT/package/manifest.json"
cp "$PAYLOAD" "$OUTPUT/package/payload/rust-runtime-arm64.zip"
"$ROOT/tools/package-extension-legal.sh" "$ROOT/extensions/rust" "$OUTPUT/package"
rm -f "$PACKAGE"
# List the payload file explicitly.  Adding the directory itself emits a
# `payload/` ZIP entry, which FoldCode's package validator intentionally
# rejects because extension packages may contain files only.
(cd "$OUTPUT/package" && zip -0 -q "$PACKAGE" manifest.json payload/rust-runtime-arm64.zip licenses/THIRD_PARTY_NOTICES.md licenses/SOURCES.json)
python3 "$ROOT/tools/verify-extension-notices.py" "$PACKAGE"
echo "$PACKAGE"
