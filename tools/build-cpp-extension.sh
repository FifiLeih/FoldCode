#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUTPUT_DIR=${FOLDCODE_EXTENSION_OUTPUT_DIR:-"$ROOT/artifacts/extensions"}
WORK="$ROOT/build/cpp-extension"
PAYLOAD="$OUTPUT_DIR/components/cpp-runtime-arm64.zip"
PACKAGE="$OUTPUT_DIR/foldcode-cpp-1.0.0-3.fcex"
NATIVE="$ROOT/app/src/main/jniLibs/arm64-v8a"

LLVM_STRIP=${FOLDCODE_LLVM_STRIP:-}
if [[ -z "$LLVM_STRIP" ]]; then
  NDK_ROOT=${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}
  SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
  if [[ -z "$SDK_ROOT" && -f "$ROOT/local.properties" ]]; then
    SDK_ROOT=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | sed 's/\\\\/:BACKSLASH:/g; s/\\:/:/g; s/:BACKSLASH:/\\/g' | head -1)
  fi
  if [[ -z "$NDK_ROOT" && -n "$SDK_ROOT" ]]; then
    for candidate in "$SDK_ROOT"/ndk/*; do
      if [[ -d "$candidate" ]]; then
        NDK_ROOT=$candidate
      fi
    done
  fi
  if [[ -n "$NDK_ROOT" ]]; then
    for candidate in "$NDK_ROOT"/toolchains/llvm/prebuilt/*/bin/llvm-strip; do
      if [[ -x "$candidate" ]]; then
        LLVM_STRIP=$candidate
        break
      fi
    done
  fi
fi

copy_debugger_library() {
  local source=$1
  local destination=$2
  cp "$source" "$destination"
  if [[ -n "$LLVM_STRIP" && -x "$LLVM_STRIP" ]]; then
    "$LLVM_STRIP" --strip-debug "$destination"
  fi
}

DEPENDENCIES=(
  libLLVM.so libclang-cpp.so libfoldlldcore.so
  libicudataxxx.so libicuucxxx.so libxml2xxx.so libiconv.so
  libzstdxx.so libffi.so libzzz.so
)

rm -rf "$WORK"
rm -f "$PAYLOAD" "$PACKAGE"
mkdir -p "$WORK/runtime/native" "$WORK/runtime/toolchain" "$WORK/package/payload" "$(dirname "$PAYLOAD")"
for library in "${DEPENDENCIES[@]}"; do
  test -f "$NATIVE/$library"
  cp "$NATIVE/$library" "$WORK/runtime/native/$library"
done
# Android does not allow an app to execute downloaded ELF files from writable
# storage. Package clangd as a shared core loaded by the APK-owned launcher.
CLANGD_CORE=${FOLDCODE_CLANGD_CORE:-"$ROOT/build/clangd-android-arm64/libfoldclangdcore.so"}
if [[ -n "${FOLDCODE_CLANGD_CORE:-}" ]]; then
  test -f "$CLANGD_CORE"
fi
if [[ -f "$CLANGD_CORE" ]]; then
  cp "$CLANGD_CORE" "$WORK/runtime/native/libfoldclangdcore.so"
fi
# LLDB follows the same Android-safe architecture: the APK owns a tiny launcher
# while the complete debugger is delivered and updated with this extension.
LLDB_DAP_CORE=${FOLDCODE_LLDB_DAP_CORE:-"$ROOT/build/llvm-android-lldb/lib/libfoldlldbdapcore.so"}
if [[ -n "${FOLDCODE_LLDB_DAP_CORE:-}" ]]; then
  test -f "$LLDB_DAP_CORE"
fi
if [[ -f "$LLDB_DAP_CORE" ]]; then
  copy_debugger_library "$LLDB_DAP_CORE" "$WORK/runtime/native/libfoldlldbdapcore.so"
  LLDB_LIBRARY=${FOLDCODE_LLDB_LIBRARY:-"$ROOT/build/llvm-android-lldb/lib/liblldb.so"}
  if [[ -f "$LLDB_LIBRARY" ]]; then
    copy_debugger_library "$LLDB_LIBRARY" "$WORK/runtime/native/liblldb.so"
  fi
fi
unzip -q "$ROOT/app/src/main/assets/toolchain.zip" -d "$WORK/runtime/toolchain"
(cd "$WORK/runtime" && zip -9 -q -r "$PAYLOAD" native toolchain)

HASH=$(shasum -a 256 "$PAYLOAD" | awk '{print $1}')
sed "s/__CPP_RUNTIME_SHA256__/$HASH/" "$ROOT/extensions/cpp/manifest.template.json" > "$WORK/package/manifest.json"
cp "$PAYLOAD" "$WORK/package/payload/cpp-runtime-arm64.zip"
"$ROOT/tools/package-extension-legal.sh" "$ROOT/extensions/cpp" "$WORK/package"
(cd "$WORK/package" && zip -0 -q "$PACKAGE" manifest.json payload/cpp-runtime-arm64.zip licenses/THIRD_PARTY_NOTICES.md licenses/SOURCES.json)
python3 "$ROOT/tools/verify-extension-notices.py" "$PACKAGE"

echo "C/C++ extension: $PACKAGE"
echo "Runtime component: $PAYLOAD"
