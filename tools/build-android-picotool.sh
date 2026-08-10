#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ANDROID_HOME=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
NDK_ROOT=${ANDROID_NDK_HOME:-$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d | sort | tail -1)}
CMAKE_ROOT=${ANDROID_CMAKE_HOME:-$(find "$ANDROID_HOME/cmake" -mindepth 1 -maxdepth 1 -type d | sort | tail -1)}
PICO_SDK_PATH=${PICO_SDK_PATH:-"$HOME/.pico-sdk/sdk/2.3.0"}
WORK=${FOLDCODE_PICOTOOL_WORK:-"${TMPDIR:-/tmp}/foldcode-picotool-usb"}
PICOTOOL_SOURCE="$WORK/picotool"
LIBUSB_SOURCE="$WORK/libusb"
PICOTOOL_COMMIT=6f6458d792b93685a11423b244a585eaa99eafcf
LIBUSB_COMMIT=15a7ebb4d426c5ce196684347d2b7cafad862626
PICOTOOL_BUILD="$WORK/build"
RUNTIME_STAGE="$WORK/runtime"
PICO_PAYLOAD="$ROOT/extensions/pico/payload/picotool-runtime.zip"
NINJA="$CMAKE_ROOT/bin/ninja"
CMAKE="$CMAKE_ROOT/bin/cmake"

test -x "$NDK_ROOT/ndk-build"
test -x "$CMAKE"
test -x "$NINJA"
test -f "$PICO_SDK_PATH/pico_sdk_init.cmake"

mkdir -p "$WORK"
if [ ! -d "$PICOTOOL_SOURCE/.git" ]; then
    git clone --depth 1 --branch 2.3.0 https://github.com/raspberrypi/picotool.git "$PICOTOOL_SOURCE"
fi
if [ ! -d "$LIBUSB_SOURCE/.git" ]; then
    git clone --depth 1 --branch v1.0.29 https://github.com/libusb/libusb.git "$LIBUSB_SOURCE"
fi

test "$(git -C "$PICOTOOL_SOURCE" rev-parse HEAD)" = "$PICOTOOL_COMMIT" || {
    echo "picotool tag 2.3.0 no longer resolves to the reviewed commit" >&2
    exit 1
}
test "$(git -C "$LIBUSB_SOURCE" rev-parse HEAD)" = "$LIBUSB_COMMIT" || {
    echo "libusb tag v1.0.29 no longer resolves to the reviewed commit" >&2
    exit 1
}

git -C "$PICOTOOL_SOURCE" reset --hard 2.3.0
git -C "$PICOTOOL_SOURCE" apply "$ROOT/tools/picotool-android-usb.patch"

(cd "$LIBUSB_SOURCE/android/jni" && "$NDK_ROOT/ndk-build" APP_ABI=arm64-v8a APP_PLATFORM=android-28)

"$CMAKE" -S "$PICOTOOL_SOURCE" -B "$PICOTOOL_BUILD" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$NINJA" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
    -DPICO_SDK_PATH="$PICO_SDK_PATH" \
    -DLIBUSB_INCLUDE_DIR="$LIBUSB_SOURCE/libusb" \
    -DLIBUSB_LIBRARIES="$LIBUSB_SOURCE/android/obj/local/arm64-v8a/libusb1.0.so" \
    -DUSE_PRECOMPILED=ON -DPICOTOOL_CODE_OTP=ON -DCMAKE_BUILD_TYPE=Release

# picotool 2.3.0 includes this generated fallback header unconditionally.
"$CMAKE" -E copy "$PICOTOOL_SOURCE/otp_header_parser/rp2350.json.h" "$PICOTOOL_BUILD/rp2350.json.h"
PATH="$CMAKE_ROOT/bin:/usr/bin:/bin" "$CMAKE" --build "$PICOTOOL_BUILD" --target picotool --parallel 8

STRIP="$NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip"
"$STRIP" "$PICOTOOL_BUILD/picotool"
"$STRIP" "$LIBUSB_SOURCE/android/obj/local/arm64-v8a/libusb1.0.so"
rm -rf "$RUNTIME_STAGE"
mkdir -p "$RUNTIME_STAGE/bin" "$RUNTIME_STAGE/lib" "$(dirname "$PICO_PAYLOAD")"
cp "$PICOTOOL_BUILD/picotool" "$RUNTIME_STAGE/bin/picotool"
cp "$LIBUSB_SOURCE/android/obj/local/arm64-v8a/libusb1.0.so" "$RUNTIME_STAGE/lib/libusb1.0.so"
rm -f "$PICO_PAYLOAD"
(cd "$RUNTIME_STAGE" && zip -9 -q -r "$PICO_PAYLOAD" bin lib)

echo "Built Pico extension payload: $PICO_PAYLOAD"
