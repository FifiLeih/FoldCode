#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
LLVM_SOURCE=${LLVM_SOURCE:-"$ROOT/build/llvm-project-21.1.8"}
BUILD=${LLDB_ANDROID_BUILD:-"$ROOT/build/llvm-android-lldb"}
NDK=${ANDROID_NDK_HOME:-"$HOME/Library/Android/sdk/ndk/27.2.12479018"}
CMAKE=${CMAKE:-"$HOME/Library/Android/sdk/cmake/4.1.2/bin/cmake"}
NINJA=${NINJA:-"$HOME/Library/Android/sdk/cmake/4.1.2/bin/ninja"}
PATCH="$ROOT/tools/debugger/lldb-dap-foldcode.patch"
ENTRY="$ROOT/tools/debugger/lldb_dap_core_entry.cpp"

if ! grep -q foldlldbdapcore "$LLVM_SOURCE/lldb/tools/lldb-dap/tool/CMakeLists.txt"; then
  patch -d "$LLVM_SOURCE" -p1 < "$PATCH"
fi

"$CMAKE" -S "$LLVM_SOURCE/llvm" -B "$BUILD" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release \
  -DLLVM_ENABLE_PROJECTS='clang;lldb' \
  -DLLVM_TARGETS_TO_BUILD=AArch64 \
  -DLLVM_BUILD_LLVM_DYLIB=ON \
  -DLLVM_LINK_LLVM_DYLIB=ON \
  -DLLVM_ENABLE_RTTI=ON \
  -DLLVM_INCLUDE_TESTS=OFF \
  -DLLVM_INCLUDE_EXAMPLES=OFF \
  -DCLANG_ENABLE_STATIC_ANALYZER=OFF \
  -DCLANG_ENABLE_ARCMT=OFF \
  -DLLDB_ENABLE_PYTHON=OFF \
  -DLLDB_ENABLE_CURSES=OFF \
  -DLLDB_ENABLE_LIBEDIT=OFF \
  -DLLDB_ENABLE_LUA=OFF \
  -DLLDB_ENABLE_LZMA=OFF \
  -DLLDB_ENABLE_LIBXML2=OFF \
  -DLLVM_ENABLE_ZLIB=OFF \
  -DLLVM_ENABLE_ZSTD=OFF \
  -DFOLDCODE_LLDB_CORE_ENTRY="$ENTRY"

"$CMAKE" --build "$BUILD" --target foldlldbdapcore -j"${JOBS:-4}"
echo "LLDB DAP core: $BUILD/lib/libfoldlldbdapcore.so"
