#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SOURCE="$ROOT/extensions/pico"
OUTPUT_DIR="$ROOT/artifacts/extensions"
OUTPUT="$OUTPUT_DIR/foldcode-pico-${SDK_VERSION:-2.3.0}-${BUNDLE_REVISION:-18}.fcex"

for payload in picotool-runtime.zip pico-toolchain-arm.zip pico-toolchain-riscv.zip \
    pico-cmake-sdk.zip cmake-data.zip \
    pico-profile-rp2040.zip pico-profile-rp2350.zip pico-profile-pico_w.zip \
    pico-profile-pico2_w.zip pico-profile-rp2350-riscv.zip \
    micropython-rp2040.zip micropython-pico_w.zip micropython-rp2350.zip \
    micropython-pico2_w.zip micropython-rp2350-riscv.zip; do
    test -f "$SOURCE/payload/$payload"
done
test -f "$SOURCE/manifest.json"
test -s "$SOURCE/licenses/THIRD_PARTY_NOTICES.md"
test -s "$SOURCE/licenses/SOURCES.json"
python3 "$ROOT/tools/verify-extension-notices.py" "$SOURCE"
for payload in picotool-runtime.zip pico-toolchain-arm.zip pico-toolchain-riscv.zip \
    pico-cmake-sdk.zip cmake-data.zip \
    pico-profile-rp2040.zip pico-profile-rp2350.zip pico-profile-pico_w.zip \
    pico-profile-pico2_w.zip pico-profile-rp2350-riscv.zip \
    micropython-rp2040.zip micropython-pico_w.zip micropython-rp2350.zip \
    micropython-pico2_w.zip micropython-rp2350-riscv.zip; do
    expected=$(awk -F'"' -v name="$payload" '$2 == name { print $4 }' "$SOURCE/manifest.json")
    actual=$(shasum -a 256 "$SOURCE/payload/$payload" | awk '{ print $1 }')
    test -n "$expected"
    test "$actual" = "$expected" || {
        echo "Manifest checksum is stale for $payload" >&2
        exit 1
    }
done
mkdir -p "$OUTPUT_DIR"
rm -f "$OUTPUT"

# Payloads are already compressed, so store them without a second expensive
# compression pass. The manifest hashes are checked again by the Android host.
if [ "${PICO_CORE_ONLY:-0}" = "1" ]; then
    (cd "$SOURCE" && zip -0 -q "$OUTPUT" manifest.json payload/pico-cmake-sdk.zip payload/cmake-data.zip payload/picotool-runtime.zip licenses/THIRD_PARTY_NOTICES.md licenses/SOURCES.json)
else
    (cd "$SOURCE" && zip -0 -q "$OUTPUT" manifest.json payload/*.zip licenses/THIRD_PARTY_NOTICES.md licenses/SOURCES.json)
fi
python3 "$ROOT/tools/verify-extension-notices.py" "$OUTPUT"
echo "Created $OUTPUT"
