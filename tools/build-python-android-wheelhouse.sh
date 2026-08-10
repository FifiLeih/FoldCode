#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
PYTHON_VERSION=3.14.6
PYTHON_ARCHIVE_SHA256=38bbe77d3167b5cd554e03b1021324926f09f3825202b065951dd7638e9c37e5
CRYPTOGRAPHY_VERSION=50.0.0
CRYPTOGRAPHY_SHA256=eeac2acb5a20ed25e0ad6d1df9891a520b78b404266b6d11778f25d5d691a6c9
CFFI_VERSION=2.1.1
CFFI_SHA256=dd31f52ea1086513bb9df30f8fcee9b8918323ae067a3d5b78bc826a000712be
LIBFFI_VERSION=3.7.1
LIBFFI_SHA256=d5e9a6638ddbd2513ddb54518eb67e4bbe6fa707bcc01c10f6212f0a088d819d
PYCPARSER_VERSION=3.0
PYCPARSER_SHA256=b727414169a36b7d524c1c3e31839a521725078d7b2ff038656844266160a992
CIBUILDWHEEL_VERSION=3.2.1
RUST_VERSION=1.97.1
NDK_VERSION=27.3.13750724
ANDROID_API=24
SOURCE_DATE_EPOCH=1767225600

CACHE=${FOLDCODE_DOWNLOAD_CACHE:-"$ROOT/build/downloads"}
WORK=${FOLDCODE_PYTHON_WHEEL_WORK:-"$ROOT/build/python-android-wheel-build"}
OUTPUT=${FOLDCODE_PYTHON_WHEELHOUSE:-"$ROOT/build/python-android-wheelhouse"}
ANDROID_HOME=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
HOST_PYTHON=${FOLDCODE_HOST_PYTHON:-python3}
RUSTUP_BIN=${FOLDCODE_RUSTUP_BIN:-$(command -v rustup || true)}
RUSTUP_HOME=${FOLDCODE_RUSTUP_HOME:-"$WORK/rustup"}
CARGO_HOME=${FOLDCODE_CARGO_HOME:-"$WORK/cargo"}

[[ -n "$ANDROID_HOME" ]] || {
  echo "ANDROID_HOME (or ANDROID_SDK_ROOT) must point to the Android SDK" >&2
  exit 2
}
[[ -n "$RUSTUP_BIN" && -x "$RUSTUP_BIN" ]] || {
  echo "rustup is required. Install it or set FOLDCODE_RUSTUP_BIN." >&2
  exit 2
}
command -v "$HOST_PYTHON" >/dev/null || {
  echo "Host Python is unavailable: $HOST_PYTHON" >&2
  exit 2
}
command -v pkg-config >/dev/null || {
  echo "pkg-config is required to build cffi" >&2
  exit 2
}

NDK="$ANDROID_HOME/ndk/$NDK_VERSION"
TOOLCHAIN_ROOTS=("$NDK/toolchains/llvm/prebuilt/"*)
TOOLCHAIN=${TOOLCHAIN_ROOTS[0]}
[[ -d "$TOOLCHAIN" ]] || {
  echo "Android NDK $NDK_VERSION is missing from $ANDROID_HOME/ndk" >&2
  exit 2
}
CC="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang"
CXX="$TOOLCHAIN/bin/aarch64-linux-android${ANDROID_API}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
READELF="$TOOLCHAIN/bin/llvm-readelf"
for tool in "$CC" "$CXX" "$AR" "$RANLIB" "$READELF"; do
  [[ -x "$tool" ]] || { echo "Missing Android tool: $tool" >&2; exit 2; }
done

download_verified() {
  local url=$1
  local destination=$2
  local sha256=$3
  if [[ ! -f "$destination" ]]; then
    curl -L --fail --output "$destination.part" "$url"
    mv "$destination.part" "$destination"
  fi
  echo "$sha256  $destination" | shasum -a 256 -c -
}

mkdir -p "$CACHE" "$OUTPUT"
PYTHON_ARCHIVE="$CACHE/python-$PYTHON_VERSION-aarch64-linux-android.tar.gz"
CRYPTOGRAPHY_ARCHIVE="$CACHE/cryptography-$CRYPTOGRAPHY_VERSION.tar.gz"
CFFI_ARCHIVE="$CACHE/cffi-$CFFI_VERSION.tar.gz"
LIBFFI_ARCHIVE="$CACHE/libffi-$LIBFFI_VERSION.tar.gz"
PYCPARSER_WHEEL="$CACHE/pycparser-$PYCPARSER_VERSION-py3-none-any.whl"

download_verified \
  "https://www.python.org/ftp/python/$PYTHON_VERSION/python-$PYTHON_VERSION-aarch64-linux-android.tar.gz" \
  "$PYTHON_ARCHIVE" "$PYTHON_ARCHIVE_SHA256"
download_verified \
  "https://files.pythonhosted.org/packages/source/c/cryptography/cryptography-$CRYPTOGRAPHY_VERSION.tar.gz" \
  "$CRYPTOGRAPHY_ARCHIVE" "$CRYPTOGRAPHY_SHA256"
download_verified \
  "https://files.pythonhosted.org/packages/source/c/cffi/cffi-$CFFI_VERSION.tar.gz" \
  "$CFFI_ARCHIVE" "$CFFI_SHA256"
download_verified \
  "https://github.com/libffi/libffi/releases/download/v$LIBFFI_VERSION/libffi-$LIBFFI_VERSION.tar.gz" \
  "$LIBFFI_ARCHIVE" "$LIBFFI_SHA256"
download_verified \
  "https://files.pythonhosted.org/packages/0c/c3/44f3fbbfa403ea2a7c779186dc20772604442dde72947e7d01069cbe98e3/pycparser-$PYCPARSER_VERSION-py3-none-any.whl" \
  "$PYCPARSER_WHEEL" "$PYCPARSER_SHA256"

rm -rf "$WORK"
mkdir -p "$WORK/sources" "$WORK/python" "$WORK/wheels" "$RUSTUP_HOME" "$CARGO_HOME"
tar -xzf "$PYTHON_ARCHIVE" -C "$WORK/python"
tar -xzf "$CRYPTOGRAPHY_ARCHIVE" -C "$WORK/sources"
tar -xzf "$CFFI_ARCHIVE" -C "$WORK/sources"
tar -xzf "$LIBFFI_ARCHIVE" -C "$WORK/sources"

PYTHON_PREFIX="$WORK/python/prefix"
# PyO3's Android cross-build probe looks in include/, while CPython stores the
# public headers in include/python3.14/. Keep the upstream layout and mirror the
# headers at the probe location inside this disposable build prefix.
cp -R "$PYTHON_PREFIX/include/python3.14/." "$PYTHON_PREFIX/include/"

if command -v nproc >/dev/null; then
  JOBS=$(nproc)
else
  JOBS=$(sysctl -n hw.ncpu)
fi
LIBFFI_PREFIX="$WORK/libffi-prefix"
(
  cd "$WORK/sources/libffi-$LIBFFI_VERSION"
  env CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" \
    CFLAGS="-D__BIONIC_NO_PAGE_SIZE_MACRO" \
    LDFLAGS="-Wl,--build-id=sha1 -Wl,--no-rosegment -Wl,-z,max-page-size=16384" \
    ./configure \
      --host=aarch64-linux-android \
      --prefix="$LIBFFI_PREFIX" \
      --disable-shared \
      --enable-static \
      --disable-docs
  make -j "$JOBS"
  make install
)

"$HOST_PYTHON" -m venv "$WORK/venv"
"$WORK/venv/bin/python" -m pip install --disable-pip-version-check \
  "cibuildwheel==$CIBUILDWHEEL_VERSION"

export RUSTUP_HOME CARGO_HOME RUSTUP_TOOLCHAIN="$RUST_VERSION"
"$RUSTUP_BIN" toolchain install "$RUST_VERSION" --profile minimal
"$RUSTUP_BIN" target add --toolchain "$RUST_VERSION" aarch64-linux-android

COMMON_CIBW_ENV=(
  "ANDROID_HOME=$ANDROID_HOME"
  "CIBW_CACHE_PATH=$WORK/cibw-cache"
  "CIBW_PLATFORM=android"
  "CIBW_BUILD=cp314-android_arm64_v8a"
  "CIBW_ARCHS_ANDROID=arm64_v8a"
  "SOURCE_DATE_EPOCH=$SOURCE_DATE_EPOCH"
)

env \
  "PATH=$CARGO_HOME/bin:$PATH" \
  "${COMMON_CIBW_ENV[@]}" \
  CIBW_XBUILD_TOOLS="pkg-config" \
  CIBW_ENVIRONMENT_ANDROID="CPATH=$LIBFFI_PREFIX/include LIBRARY_PATH=$LIBFFI_PREFIX/lib PKG_CONFIG_PATH=$LIBFFI_PREFIX/lib/pkgconfig SOURCE_DATE_EPOCH=$SOURCE_DATE_EPOCH" \
  "$WORK/venv/bin/python" -m cibuildwheel \
    --output-dir "$WORK/wheels" "$WORK/sources/cffi-$CFFI_VERSION"

CFFI_WHEEL="$WORK/wheels/cffi-$CFFI_VERSION-cp314-cp314-android_24_arm64_v8a.whl"
[[ -f "$CFFI_WHEEL" ]] || { echo "cffi Android wheel was not produced" >&2; exit 1; }

RUSTFLAGS="-C link-arg=-L$PYTHON_PREFIX/lib -C link-arg=-Wl,--no-as-needed -C link-arg=-lpython3.14"
CRYPTO_ENV="RUSTUP_HOME=$RUSTUP_HOME CARGO_HOME=$CARGO_HOME RUSTUP_TOOLCHAIN=$RUST_VERSION CARGO_BUILD_TARGET=aarch64-linux-android CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$CC CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS=\"$RUSTFLAGS\" CC_aarch64_linux_android=$CC AR_aarch64_linux_android=$AR OPENSSL_DIR=$PYTHON_PREFIX OPENSSL_INCLUDE_DIR=$PYTHON_PREFIX/include OPENSSL_LIB_DIR=$PYTHON_PREFIX/lib OPENSSL_NO_VENDOR=1 MATURIN_PEP517_ARGS=\"--interpreter python3.14\" PYO3_CROSS=1 PYO3_CROSS_PYTHON_VERSION=3.14 PYO3_CROSS_LIB_DIR=$PYTHON_PREFIX/lib SOURCE_DATE_EPOCH=$SOURCE_DATE_EPOCH"
env \
  "PATH=$CARGO_HOME/bin:$PATH" \
  "${COMMON_CIBW_ENV[@]}" \
  CIBW_XBUILD_TOOLS="rustc cargo rustup pkg-config" \
  CIBW_ENVIRONMENT_ANDROID="$CRYPTO_ENV" \
  "$WORK/venv/bin/python" -m cibuildwheel \
    --output-dir "$WORK/wheels" "$WORK/sources/cryptography-$CRYPTOGRAPHY_VERSION"

CRYPTOGRAPHY_WHEEL="$WORK/wheels/cryptography-$CRYPTOGRAPHY_VERSION-cp314-abi3-android_24_arm64_v8a.whl"
[[ -f "$CRYPTOGRAPHY_WHEEL" ]] || {
  echo "cryptography Android wheel was not produced" >&2
  exit 1
}

VERIFY="$WORK/verify"
mkdir -p "$VERIFY/cffi" "$VERIFY/cryptography"
unzip -q "$CFFI_WHEEL" -d "$VERIFY/cffi"
unzip -q "$CRYPTOGRAPHY_WHEEL" -d "$VERIFY/cryptography"
CFFI_LIBRARY=$(find "$VERIFY/cffi" -name '_cffi_backend*.so' -print -quit)
CRYPTO_LIBRARY=$(find "$VERIFY/cryptography" -name '_rust.abi3.so' -print -quit)
[[ -n "$CFFI_LIBRARY" && -n "$CRYPTO_LIBRARY" ]] || {
  echo "Expected native Python modules are missing from the wheels" >&2
  exit 1
}
"$READELF" -d "$CFFI_LIBRARY" | grep -q 'libpython3.14.so'
! "$READELF" -d "$CFFI_LIBRARY" | grep -q 'libffi.so'
"$READELF" -d "$CRYPTO_LIBRARY" | grep -q 'libpython3.14.so'
"$READELF" -d "$CRYPTO_LIBRARY" | grep -q 'libssl_python.so'
"$READELF" -d "$CRYPTO_LIBRARY" | grep -q 'libcrypto_python.so'

cp "$CFFI_WHEEL" "$OUTPUT/"
cp "$CRYPTOGRAPHY_WHEEL" "$OUTPUT/"
cp "$PYCPARSER_WHEEL" "$OUTPUT/"
(
  cd "$OUTPUT"
  shasum -a 256 \
    "cffi-$CFFI_VERSION-cp314-cp314-android_24_arm64_v8a.whl" \
    "cryptography-$CRYPTOGRAPHY_VERSION-cp314-abi3-android_24_arm64_v8a.whl" \
    "pycparser-$PYCPARSER_VERSION-py3-none-any.whl" > SHA256SUMS
  cat SHA256SUMS
)

echo "Android Python wheelhouse: $OUTPUT"
