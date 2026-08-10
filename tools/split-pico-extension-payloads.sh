#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
FULL=${FULL_PICO_PAYLOAD_DIR:-"$ROOT/artifacts/pico-full-bundles"}
OUTPUT=${PICO_PAYLOAD_OUTPUT_DIR:-"$ROOT/extensions/pico/payload"}
WORK=${WORK_ROOT:-"${TMPDIR:-/tmp}/foldcode-pico-extension-split"}
SDK_SOURCE=${PICO_SDK_SOURCE:-"$HOME/.pico-sdk/sdk/2.3.0"}
CMAKE_DATA_SOURCE=${CMAKE_DATA_SOURCE:-"$ROOT/artifacts/cmake-runtime/share/cmake-4.0"}
ARM_TOOLCHAIN_ROOT=${ARM_TOOLCHAIN_ROOT:-"$HOME/.pico-sdk/toolchain/15_2_Rel1"}
RISCV_TOOLCHAIN_ROOT=${RISCV_TOOLCHAIN_ROOT:-"/private/tmp/foldcode-riscv-toolchain"}

rm -rf "$WORK" "$OUTPUT"
mkdir -p "$WORK" "$OUTPUT"

for id in rp2040 rp2350 pico_w pico2_w rp2350-riscv; do
    archive="$FULL/pico-sdk-$id.zip"
    test -f "$archive"
    mkdir -p "$WORK/$id"
    unzip -q "$archive" -d "$WORK/$id"
done

mkdir -p "$WORK/rp2040/toolchain/sysroot/include"
rsync -a --exclude 'c++' "$ARM_TOOLCHAIN_ROOT/arm-none-eabi/include/" \
    "$WORK/rp2040/toolchain/sysroot/include/"
# Keep the GNU C++ standard library beside the clean newlib C headers. The
# generated profile archive has a merged include tree whose GCC wrapper
# headers rely on include_next, so it cannot be used as a Clang sysroot.
cp -R "$WORK/rp2040/toolchain/include/c++" \
    "$WORK/rp2040/toolchain/sysroot/include/"
(cd "$WORK/rp2040" && zip -qr "$OUTPUT/pico-toolchain-arm.zip" toolchain)
mkdir -p "$WORK/rp2350-riscv/toolchain/sysroot/include"
rsync -a "$RISCV_TOOLCHAIN_ROOT/riscv32-unknown-elf/include/" \
    "$WORK/rp2350-riscv/toolchain/sysroot/include/"
(cd "$WORK/rp2350-riscv" && zip -qr "$OUTPUT/pico-toolchain-riscv.zip" toolchain)

# Full CMake projects need the SDK implementations and host-tool CMake files.
test -f "$SDK_SOURCE/pico_sdk_init.cmake"
mkdir -p "$WORK/cmake-sdk/sdk"
cp -R "$SDK_SOURCE"/. "$WORK/cmake-sdk/sdk/"
rm -rf "$WORK/cmake-sdk/sdk/.git" "$WORK/cmake-sdk/sdk/.github" \
    "$WORK/cmake-sdk/sdk/test" "$WORK/cmake-sdk/sdk/bazel"
# Firmware TLS support uses include/, library/ and the SDK CMake targets. The
# upstream validation suite is large and is not part of any Pico firmware build.
rm -rf "$WORK/cmake-sdk/sdk/lib/mbedtls/tests"
(cd "$WORK/cmake-sdk" && zip -qr "$OUTPUT/pico-cmake-sdk.zip" sdk)

test -f "$CMAKE_DATA_SOURCE/Modules/CMake.cmake"
mkdir -p "$WORK/cmake-data/share"
cp -R "$CMAKE_DATA_SOURCE" "$WORK/cmake-data/share/cmake-4.0"
rm -rf "$WORK/cmake-data/share/cmake-4.0/Help"
(cd "$WORK/cmake-data" && zip -qr "$OUTPUT/cmake-data.zip" share)

for id in rp2040 rp2350 pico_w pico2_w rp2350-riscv; do
    (cd "$WORK/$id" && zip -qr "$OUTPUT/pico-profile-$id.zip" \
        runtime bundle.properties)
done

echo "Created factored Pico extension payloads in $OUTPUT"
