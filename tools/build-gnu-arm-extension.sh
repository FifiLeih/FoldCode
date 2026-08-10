#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUTPUT=${1:-"$ROOT/artifacts/extensions"}
mkdir -p "$OUTPUT"
OUTPUT=$(cd "$OUTPUT" && pwd)
STAGE=${FOLDCODE_GNU_ARM_STAGE:-"$ROOT/build/gnu-arm-stage"}
WORK=$(mktemp -d "${TMPDIR:-/tmp}/foldcode-gnu-arm.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

if [[ ! -x "$STAGE/toolchain/bin/arm-none-eabi-gcc" ]]; then
  echo "GNU Arm toolchain is missing from $STAGE/toolchain" >&2
  echo "Prepare the official AArch64 Arm GNU Toolchain 15.2.Rel1 there first." >&2
  exit 1
fi
if [[ ! -f "$STAGE/toolchain/license.txt" ]]; then
  echo "GNU Arm toolchain license.txt is missing from $STAGE/toolchain" >&2
  exit 1
fi
if [[ ! -f "$STAGE/rootfs/lib/ld-linux-aarch64.so.1" && ! -f "$STAGE/rootfs/usr/lib/ld-linux-aarch64.so.1" ]]; then
  echo "AArch64 glibc runtime is missing from $STAGE/rootfs" >&2
  exit 1
fi

mkdir -p "$WORK/package/payload" "$WORK/runtime"
if [[ "${FOLDCODE_GNU_ARM_REUSE_PAYLOAD:-0}" == "1" && -f "$OUTPUT/gnu-arm-runtime.zip" ]]; then
  cp "$OUTPUT/gnu-arm-runtime.zip" "$WORK/package/payload/gnu-arm-runtime.zip"
else
# zip follows symbolic links by default. That is intentional because Android's
# ZipInputStream extractor does not restore Unix symlinks.
  cp -R -L "$STAGE/rootfs" "$WORK/runtime/rootfs"
  cp -R -L "$STAGE/toolchain" "$WORK/runtime/toolchain"
  # Android restores the canonical guest /lib -> /usr/lib link after
  # extraction; do not ship a dereferenced duplicate of the glibc libraries.
  rm -rf "$WORK/runtime/rootfs/lib"

# C/C++ firmware builds do not need host GDB, Fortran, or documentation. GCC,
# its target libraries, LTO/plugin support, and every Arm multilib stay intact.
  find "$WORK/runtime/toolchain" -type f \( -name 'arm-none-eabi-gdb*' -o -name 'arm-none-eabi-gfortran*' -o -name 'f951' \) -delete
  rm -rf "$WORK/runtime/toolchain/share/doc" "$WORK/runtime/toolchain/share/info" "$WORK/runtime/toolchain/share/man"

  (cd "$WORK/runtime" && zip -6 -q -r "$WORK/package/payload/gnu-arm-runtime.zip" rootfs toolchain)
fi
SHA=$(shasum -a 256 "$WORK/package/payload/gnu-arm-runtime.zip" | awk '{print $1}')
sed "s/__GNU_ARM_RUNTIME_SHA256__/$SHA/g" "$ROOT/extensions/gnu-arm/manifest.template.json" > "$WORK/package/manifest.json"
"$ROOT/tools/package-extension-legal.sh" "$ROOT/extensions/gnu-arm" "$WORK/package"

rm -f "$OUTPUT/foldcode-gnu-arm-15.2.1-1.fcex"
(cd "$WORK/package" && zip -0 -q "$OUTPUT/foldcode-gnu-arm-15.2.1-1.fcex" manifest.json payload/gnu-arm-runtime.zip licenses/THIRD_PARTY_NOTICES.md licenses/SOURCES.json)
python3 "$ROOT/tools/verify-extension-notices.py" "$OUTPUT/foldcode-gnu-arm-15.2.1-1.fcex"
cp "$WORK/package/payload/gnu-arm-runtime.zip" "$OUTPUT/gnu-arm-runtime.zip"
echo "Created $OUTPUT/foldcode-gnu-arm-15.2.1-1.fcex"
