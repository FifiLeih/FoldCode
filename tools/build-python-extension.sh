#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
VERSION=${PYTHON_VERSION:-3.14.6}
REVISION=${PYTHON_EXTENSION_REVISION:-6}
OUTPUT_DIR=${FOLDCODE_EXTENSION_OUTPUT_DIR:-"$ROOT/artifacts/extensions"}
CACHE=${FOLDCODE_DOWNLOAD_CACHE:-"$ROOT/build/downloads"}
WHEELHOUSE=${FOLDCODE_PYTHON_WHEELHOUSE:-"$ROOT/build/python-android-wheelhouse"}
WORK="$ROOT/build/python-extension"
ARCHIVE="$CACHE/python-$VERSION-aarch64-linux-android.tar.gz"
URL="https://www.python.org/ftp/python/$VERSION/python-$VERSION-aarch64-linux-android.tar.gz"
ARCHIVE_SHA256=38bbe77d3167b5cd554e03b1021324926f09f3825202b065951dd7638e9c37e5
PAYLOAD="$OUTPUT_DIR/components/python-runtime-arm64.zip"
PACKAGE="$OUTPUT_DIR/foldcode-python-$VERSION-$REVISION.fcex"

mkdir -p "$CACHE" "$OUTPUT_DIR/components"
if [[ ! -f "$ARCHIVE" ]]; then
  curl -L --fail --output "$ARCHIVE.part" "$URL"
  mv "$ARCHIVE.part" "$ARCHIVE"
fi
echo "$ARCHIVE_SHA256  $ARCHIVE" | shasum -a 256 -c -
rm -rf "$WORK"
mkdir -p "$WORK/runtime" "$WORK/package/payload"
tar -xzf "$ARCHIVE" -C "$WORK/runtime" ./prefix/lib ./README.md
cp -R "$ROOT/extensions/python/runtime/foldcode" "$WORK/runtime/foldcode"
mkdir -p "$WORK/runtime/foldcode/wheelhouse"
copy_android_wheel() {
  local name=$1
  local sha256=$2
  local source="$WHEELHOUSE/$name"
  [[ -f "$source" ]] || {
    echo "Missing Android Python wheel: $source" >&2
    echo "Run tools/build-python-android-wheelhouse.sh first." >&2
    exit 2
  }
  echo "$sha256  $source" | shasum -a 256 -c -
  cp "$source" "$WORK/runtime/foldcode/wheelhouse/"
}
copy_android_wheel \
  cffi-2.1.1-cp314-cp314-android_24_arm64_v8a.whl \
  20dccd96614a4485b30e6f22f1dc89e09e8b668ec63824eaa3d4896e7c530c61
copy_android_wheel \
  cryptography-50.0.0-cp314-abi3-android_24_arm64_v8a.whl \
  208afbc30d9cdcf8ea34024b3360aad93fb9e81f0829b0d842ea675de16ccdb1
copy_android_wheel \
  pycparser-3.0-py3-none-any.whl \
  b727414169a36b7d524c1c3e31839a521725078d7b2ff038656844266160a992
# The Android CPython archive carries ensurepip's verified pip wheel but does
# not install it into site-packages. Materialize that bundled wheel while
# packaging so `python -m pip` works immediately and entirely offline.
PYTHON_LIB="$WORK/runtime/prefix/lib/python${VERSION%.*}"
PIP_WHEEL=$(find "$PYTHON_LIB/ensurepip/_bundled" -maxdepth 1 -name 'pip-*.whl' -print -quit)
[[ -n "$PIP_WHEEL" && -f "$PIP_WHEEL" ]] || {
  echo "Bundled pip wheel is missing from the CPython archive" >&2
  exit 1
}
mkdir -p "$PYTHON_LIB/site-packages"
python3 -m pip install \
  --disable-pip-version-check --no-index --no-compile --no-deps \
  --target "$PYTHON_LIB/site-packages" "$PIP_WHEEL"
# Jedi and Parso are pure-Python, extension-owned intelligence libraries. They
# stay outside the base APK and add tolerant, project-aware completion plus
# live syntax diagnostics without executing the user's source.
PYTHON_VENDOR_CACHE="$CACHE/python-vendor"
mkdir -p "$PYTHON_VENDOR_CACHE"
if [[ ! -f "$PYTHON_VENDOR_CACHE/jedi-0.20.0-py2.py3-none-any.whl" ||
      ! -f "$PYTHON_VENDOR_CACHE/parso-0.8.7-py2.py3-none-any.whl" ]]; then
  python3 -m pip download \
    --disable-pip-version-check --no-deps --only-binary=:all: --require-hashes \
    --dest "$PYTHON_VENDOR_CACHE" \
    --requirement "$ROOT/extensions/python/requirements-runtime.txt"
fi
python3 -m pip install \
  --disable-pip-version-check --no-index --no-compile --no-deps \
  --find-links "$PYTHON_VENDOR_CACHE" --only-binary=:all: --require-hashes \
  --target "$WORK/runtime/foldcode/vendor" \
  --requirement "$ROOT/extensions/python/requirements-runtime.txt"
# The CMake/script runtime needs the interpreter, extension modules and standard
# library, not development headers or the upstream test suite.
rm -rf "$WORK/runtime/prefix/lib/python$VERSION/test" \
       "$WORK/runtime/prefix/lib/python${VERSION%.*}/test" \
       "$WORK/runtime/prefix/lib/python${VERSION%.*}/idlelib" \
       "$WORK/runtime/prefix/lib/python${VERSION%.*}/tkinter" \
       "$WORK/runtime/prefix/lib/python${VERSION%.*}/turtledemo"
(cd "$WORK/runtime" && zip -9 -q -r "$PAYLOAD" prefix foldcode README.md)
HASH=$(shasum -a 256 "$PAYLOAD" | awk '{print $1}')
sed "s/__PYTHON_RUNTIME_SHA256__/$HASH/" "$ROOT/extensions/python/manifest.template.json" > "$WORK/package/manifest.json"
cp "$PAYLOAD" "$WORK/package/payload/python-runtime-arm64.zip"
"$ROOT/tools/package-extension-legal.sh" "$ROOT/extensions/python" "$WORK/package"
(cd "$WORK/package" && zip -0 -q "$PACKAGE" manifest.json payload/python-runtime-arm64.zip licenses/THIRD_PARTY_NOTICES.md licenses/SOURCES.json)
python3 "$ROOT/tools/verify-extension-notices.py" "$PACKAGE"

echo "Python extension: $PACKAGE"
echo "Runtime component: $PAYLOAD"
