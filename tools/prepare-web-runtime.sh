#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
NODE_VERSION=24.19.0
NODE_SOURCE_SHA256=f6d95e10a0431ee1067fc6aabe9f762908b4716dd35324e1ddb4b1466b76659f
JS_DEBUG_VERSION=1.117.0
JS_DEBUG_SHA256=ad8d04ede9d4b75cc290fd5438a65047a06f786d04f604b6112485b36f090772
NPM_VERSION=12.0.2
NPM_ARCHIVE_SHA256=5dbb86c71d07a1957f2e90734092dd6a58bdcd9ebc2d8d41ca1c6e6a21d364e1
# TypeScript 7 is distributed as platform-native tsgo binaries and currently
# publishes no Android package or tsserver. 5.9.3 is therefore the newest
# TypeScript release that can provide both compilation and offline LSP on
# Android ARM64.
TYPESCRIPT_VERSION=5.9.3
TYPESCRIPT_LANGUAGE_SERVER_VERSION=5.3.0
# The maintained scoped fork currently publishes CSS/JSON launcher files
# without their corresponding server bundles. Keep the complete upstream
# extraction until that distribution is whole again.
WEB_LANGSERVERS_VERSION=4.10.0
WORK=${FOLDCODE_WEB_BUILD_DIR:-"$ROOT/build/web-runtime-source"}
RUNTIME=${1:-"$ROOT/artifacts/web-runtime-arm64"}
NODE_ARCHIVE="$WORK/node-v$NODE_VERSION.tar.xz"
NODE_SOURCE="$WORK/node-v$NODE_VERSION"
ANDROID_API=${FOLDCODE_NODE_ANDROID_API:-28}
NODE_BUILD_JOBS=${FOLDCODE_NODE_BUILD_JOBS:-4}

command -v curl >/dev/null || { echo "curl is required" >&2; exit 2; }
command -v npm >/dev/null || { echo "A host npm is required to prepare JavaScript tooling" >&2; exit 2; }
command -v make >/dev/null || { echo "make is required" >&2; exit 2; }
command -v patch >/dev/null || { echo "patch is required" >&2; exit 2; }
command -v tar >/dev/null || { echo "tar is required" >&2; exit 2; }
command -v perl >/dev/null || { echo "perl is required to prepare the Android js-debug bundle" >&2; exit 2; }

mkdir -p "$WORK" "$RUNTIME/bin" "$RUNTIME/lib"
if [[ ! -f "$NODE_ARCHIVE" ]]; then
  curl -fL "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION.tar.xz" -o "$NODE_ARCHIVE"
fi
echo "$NODE_SOURCE_SHA256  $NODE_ARCHIVE" | shasum -a 256 -c -
if [[ ! -d "$NODE_SOURCE" ]]; then
  tar -xJf "$NODE_ARCHIVE" -C "$WORK"
fi

if ! grep -q 'Android cross builds still execute node_js2c' "$NODE_SOURCE/deps/uv/uv.gyp" ||
   grep -Fq "['_toolset==\"host\"', {" "$NODE_SOURCE/tools/v8_gypfiles/v8.gyp" ||
   grep -Fq 'cmd_link_host = $(LINK.$(TOOLSET)) $(GYP_LDFLAGS) $(LDFLAGS.$(TOOLSET)) -o $@ -Wl,--start-group' "$NODE_SOURCE/tools/gyp/pylib/gyp/generator/make.py"; then
  patch -d "$NODE_SOURCE" -p1 < "$ROOT/tools/patches/node-android-host-libuv.patch"
fi

if ! grep -q 'Android cross builds run the host snapshot generator' "$NODE_SOURCE/tools/v8_gypfiles/v8.gyp"; then
  patch -d "$NODE_SOURCE" -p1 < "$ROOT/tools/patches/node-android-host-v8-trap.patch"
fi

if ! grep -q "cpu_features.c calls the NDK cpufeatures API" "$NODE_SOURCE/deps/zlib/zlib.gyp"; then
  patch -d "$NODE_SOURCE" -p1 < "$ROOT/tools/patches/node-android-cpufeatures.patch"
fi

if [[ ! -x "$NODE_SOURCE/out/Release/node" ]]; then
  NDK=${FOLDCODE_ANDROID_NDK:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}
  if [[ -z "$NDK" ]]; then
    DEFAULT_NDK="$HOME/Library/Android/sdk/ndk/27.2.12479018"
    [[ -d "$DEFAULT_NDK" ]] && NDK="$DEFAULT_NDK"
  fi
  [[ -n "$NDK" && -d "$NDK" ]] || {
    echo "Set FOLDCODE_ANDROID_NDK to an installed Android NDK directory" >&2
    exit 2
  }
  install -m 0644 "$NDK/sources/android/cpufeatures/cpu-features.c" \
    "$NODE_SOURCE/deps/zlib/android_cpu_features.c"
  (
    cd "$NODE_SOURCE"
    HOST_OS=linux
    HOST_TAG=linux-x86_64
    HOST_SDKROOT=
    if [[ "$(uname -s)" == Darwin ]]; then
      HOST_OS=mac
      HOST_TAG=darwin-x86_64
      HOST_SDKROOT=$(xcrun --show-sdk-path)
    fi
    HOST_CC=${FOLDCODE_NODE_HOST_CC:-$(command -v clang)}
    HOST_CXX=${FOLDCODE_NODE_HOST_CXX:-$(command -v clang++)}
    TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
    [[ -d "$TOOLCHAIN" ]] || { echo "Unsupported NDK host toolchain: $TOOLCHAIN" >&2; exit 2; }
    CONFIG_ID="node-$NODE_VERSION-android-arm64-api$ANDROID_API-nosnapshot-v6"
    if [[ ! -f out/.foldcode-config || "$(cat out/.foldcode-config)" != "$CONFIG_ID" ]]; then
      rm -rf out
      PATH="$TOOLCHAIN/bin:$PATH" \
      CC="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang" \
      CXX="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang++" \
      CC_target="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang" \
      CXX_target="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang++" \
      CC_host="$HOST_CC" \
      CXX_host="$HOST_CXX" \
      SDKROOT="$HOST_SDKROOT" \
      GYP_DEFINES="target_arch=arm64 v8_target_arch=arm64 android_target_arch=arm64 host_os=$HOST_OS OS=android android_ndk_path=$NDK" \
        ./configure \
          --dest-cpu=arm64 \
          --dest-os=android \
          --openssl-no-asm \
          --cross-compiling \
          --without-node-snapshot \
          --without-node-code-cache
      mkdir -p out
      printf '%s\n' "$CONFIG_ID" > out/.foldcode-config
    fi
    if command -v xcrun >/dev/null; then
      # Recent Xcode toolchains no longer infer the macOS SDK when their clang
      # binary is invoked directly by Node's generated host-tool rules.
      SDKROOT=$(xcrun --show-sdk-path) make -j"$NODE_BUILD_JOBS" \
        "AR.host=$TOOLCHAIN/bin/llvm-ar" \
        "AR.target=$TOOLCHAIN/bin/llvm-ar" \
        "LDFLAGS.host=-framework CoreFoundation"
    else
      make -j"$NODE_BUILD_JOBS" \
        "AR.host=$TOOLCHAIN/bin/llvm-ar" \
        "AR.target=$TOOLCHAIN/bin/llvm-ar"
    fi
    # JavaScript debugging uses V8's protocol and does not need Node's native
    # DWARF or symbol tables in the downloadable runtime.
    "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded out/Release/node
  )
fi

install -m 0755 "$NODE_SOURCE/out/Release/node" "$RUNTIME/bin/node"
# Node 24 is delivered as the official Android executable. Remove the former
# Node.js Mobile shared library so the launcher cannot accidentally select it.
rm -f "$RUNTIME/lib/libnode.so"
[[ -x "$RUNTIME/bin/node" ]] || { echo "Node.js Android ARM64 build failed" >&2; exit 1; }

# npm, TypeScript and the language server are JavaScript payloads. Preparing
# them with the host npm avoids executing the Android target binary on macOS.
rm -rf "$RUNTIME/lib/node_modules" "$RUNTIME/lib/package-lock.json" "$RUNTIME/lib/package.json"
cp "$ROOT/extensions/web/tooling/package.json" "$ROOT/extensions/web/tooling/package-lock.json" "$RUNTIME/lib/"
npm ci --prefix "$RUNTIME/lib" --ignore-scripts --no-audit --no-fund --omit=optional

# npm publishes its CLI with a bundled dependency tree, so root-level npm
# overrides cannot replace vulnerable nested copies. Extract the pinned
# official CLI, then overlay four reviewed fixed packages from their own lock.
npm_archive="$WORK/npm-$NPM_VERSION.tgz"
if [[ ! -f "$npm_archive" ]]; then
  curl -fL "https://registry.npmjs.org/npm/-/npm-$NPM_VERSION.tgz" -o "$npm_archive"
fi
echo "$NPM_ARCHIVE_SHA256  $npm_archive" | shasum -a 256 -c -
npm_root="$RUNTIME/lib/node_modules/npm"
rm -rf "$npm_root"
mkdir -p "$npm_root"
tar -xzf "$npm_archive" --strip-components=1 -C "$npm_root"

patch_root="$WORK/npm-security-patches"
rm -rf "$patch_root"
mkdir -p "$patch_root"
cp "$ROOT/extensions/web/npm-patches/package.json" "$ROOT/extensions/web/npm-patches/package-lock.json" "$patch_root/"
npm ci --prefix "$patch_root" --ignore-scripts --no-audit --no-fund --omit=optional
for package in brace-expansion ip-address tar undici; do
  rm -rf "$npm_root/node_modules/$package"
  cp -R "$patch_root/node_modules/$package" "$npm_root/node_modules/$package"
done

npm audit --prefix "$RUNTIME/lib" --package-lock-only --omit=dev --audit-level=high
npm audit --prefix "$patch_root" --package-lock-only --omit=dev --audit-level=high
NPM_ROOT="$npm_root" node <<'NODE'
const path = require('path');
const root = path.resolve(process.env.NPM_ROOT);
const expected = { 'brace-expansion': '5.0.9', 'ip-address': '10.4.0', tar: '7.5.22', undici: '6.28.0' };
for (const [name, version] of Object.entries(expected)) {
  const actual = require(path.join(root, 'node_modules', name, 'package.json')).version;
  if (actual !== version) throw new Error(`${name}: expected ${version}, found ${actual}`);
}
NODE

adapter_archive="$WORK/js-debug-dap-v$JS_DEBUG_VERSION.tar.gz"
adapter_root="$RUNTIME/lib/js-debug"
if [[ ! -f "$adapter_archive" ]]; then
  curl -fL "https://github.com/microsoft/vscode-js-debug/releases/download/v$JS_DEBUG_VERSION/js-debug-dap-v$JS_DEBUG_VERSION.tar.gz" \
    -o "$adapter_archive"
fi
echo "$JS_DEBUG_SHA256  $adapter_archive" | shasum -a 256 -c -
if [[ ! -f "$adapter_root/src/dapDebugServer.js" ]]; then
  rm -rf "$adapter_root"
  mkdir -p "$adapter_root"
  tar -xzf "$adapter_archive" --strip-components=1 -C "$adapter_root"
fi

[[ -f "$adapter_root/src/dapDebugServer.js" ]] || { echo "vscode-js-debug extraction failed" >&2; exit 1; }
# Node.js Mobile is built without ICU. vscode-js-debug's bundled WebAssembly
# loader asks TextDecoder for fatal decoding, an option Node rejects entirely
# without ICU. The loader receives trusted adapter bytes, so retaining normal
# UTF-8 decoding while dropping only that unsupported flag is equivalent here.
perl -0pi -e 's/\{ignoreBOM:!0,fatal:!0\}/\{ignoreBOM:!0\}/g' \
  "$adapter_root/src/dapDebugServer.js"
echo "$NODE_VERSION" > "$RUNTIME/.node-version"
echo "$NPM_VERSION" > "$RUNTIME/.npm-version"
echo "$TYPESCRIPT_VERSION" > "$RUNTIME/.typescript-version"
echo "$TYPESCRIPT_LANGUAGE_SERVER_VERSION" > "$RUNTIME/.typescript-language-server-version"
echo "$WEB_LANGSERVERS_VERSION" > "$RUNTIME/.web-langservers-version"
echo "$JS_DEBUG_VERSION" > "$RUNTIME/.js-debug-version"
echo "Prepared FoldCode Web runtime: $RUNTIME"
