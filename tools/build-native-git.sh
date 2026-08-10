#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
NDK=${ANDROID_NDK_ROOT:-"$HOME/Library/Android/sdk/ndk/27.2.12479018"}
CMAKE=${CMAKE:-"$HOME/Library/Android/sdk/cmake/3.22.1/bin/cmake"}
JOBS=${JOBS:-8}
WORK=${WORK:-"${TMPDIR:-/tmp}/foldcode-native-git"}
DOWNLOADS="$WORK/downloads"
PREFIX="$WORK/prefix"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"

mkdir -p "$DOWNLOADS" "$PREFIX"

fetch() {
    local url=$1 output=$2 sha=$3
    if [[ ! -f "$output" ]] || [[ "$(shasum -a 256 "$output" | awk '{print $1}')" != "$sha" ]]; then
        curl -fL "$url" -o "$output"
    fi
    [[ "$(shasum -a 256 "$output" | awk '{print $1}')" == "$sha" ]] || { echo "Checksum failed: $output" >&2; exit 1; }
}

fetch https://mirrors.edge.kernel.org/pub/software/scm/git/git-2.55.0.tar.xz \
    "$DOWNLOADS/git.tar.xz" 457fdb04dc8728e007d4688695e6912e6f680727920f2a40bf11eacc17505357
fetch https://github.com/openssl/openssl/releases/download/openssl-3.5.7/openssl-3.5.7.tar.gz \
    "$DOWNLOADS/openssl.tar.gz" a8c0d28a529ca480f9f36cf5792e2cd21984552a3c8e4aa11a24aa31aeac98e8
fetch https://curl.se/download/curl-8.21.0.tar.xz \
    "$DOWNLOADS/curl.tar.xz" aa1b66a70eace83dc624508745646c08ae561de512ab403adffb93ac87fc72e6
fetch https://curl.se/ca/cacert-2026-07-16.pem \
    "$DOWNLOADS/cacert.pem" 3ff344e30b9b1ed2971044eabb438a08f2e2245ddb5f8ab1a3ad8b63ab4eaf91

rm -rf "$WORK/openssl" "$WORK/curl" "$WORK/curl-build" "$WORK/git" "$PREFIX"
mkdir -p "$WORK/openssl" "$WORK/curl" "$WORK/git" "$PREFIX"
tar -xf "$DOWNLOADS/openssl.tar.gz" -C "$WORK/openssl" --strip-components=1
tar -xf "$DOWNLOADS/curl.tar.xz" -C "$WORK/curl" --strip-components=1
tar -xf "$DOWNLOADS/git.tar.xz" -C "$WORK/git" --strip-components=1

(
    cd "$WORK/openssl"
    export ANDROID_NDK_ROOT="$NDK" PATH="$TOOLCHAIN:$PATH"
    ./Configure android-arm64 no-shared no-tests no-apps no-docs --prefix="$PREFIX"
    make -s -j"$JOBS" build_sw
    make -s install_sw
)

"$CMAKE" -S "$WORK/curl" -B "$WORK/curl-build" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=26 -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DBUILD_SHARED_LIBS=OFF -DBUILD_CURL_EXE=OFF -DBUILD_TESTING=OFF \
    -DCURL_USE_OPENSSL=ON -DOPENSSL_INCLUDE_DIR="$PREFIX/include" \
    -DOPENSSL_SSL_LIBRARY="$PREFIX/lib/libssl.a" -DOPENSSL_CRYPTO_LIBRARY="$PREFIX/lib/libcrypto.a" \
    -DOPENSSL_USE_STATIC_LIBS=TRUE -DCURL_ZLIB=ON -DHTTP_ONLY=ON \
    -DCURL_DISABLE_LDAP=ON -DCURL_DISABLE_LDAPS=ON -DCURL_DISABLE_ALTSVC=ON \
    -DCURL_DISABLE_HSTS=ON -DCURL_USE_LIBPSL=OFF -DCURL_BROTLI=OFF -DCURL_ZSTD=OFF -DUSE_LIBIDN2=OFF
"$CMAKE" --build "$WORK/curl-build" -j "$JOBS"
"$CMAKE" --install "$WORK/curl-build"

(
    cd "$WORK/git"
    make -j"$JOBS" uname_S=Linux uname_M=arm64 CSPRNG_METHOD=urandom NO_RUST=YesPlease \
        CC="$TOOLCHAIN/aarch64-linux-android26-clang" AR="$TOOLCHAIN/llvm-ar" \
        CURL_CONFIG="$PREFIX/bin/curl-config" NO_OPENSSL=YesPlease BLK_SHA1=YesPlease BLK_SHA256=YesPlease \
        NO_EXPAT=YesPlease NO_GETTEXT=YesPlease NO_ICONV=YesPlease NO_TCLTK=YesPlease \
        NO_PERL=YesPlease NO_PYTHON=YesPlease NO_REGEX=NeedsStartEnd NO_PTHREADS=YesPlease \
        NO_GECOS_IN_PWENT=YesPlease \
        CFLAGS='-O2 -fPIC' LDFLAGS="-L$PREFIX/lib -Wl,-z,max-page-size=16384" git git-remote-http
    "$TOOLCHAIN/llvm-strip" -s git git-remote-http
)

cp "$WORK/git/git" "$ROOT/app/src/main/jniLibs/arm64-v8a/libfoldgitcore.so"
cp "$WORK/git/git-remote-http" "$ROOT/app/src/main/jniLibs/arm64-v8a/libfoldgitremotehttp.so"
mkdir -p "$ROOT/app/src/main/assets/git"
cp "$DOWNLOADS/cacert.pem" "$ROOT/app/src/main/assets/git/cacert.pem"
echo "Built upstream Git 2.55.0 for Android ARM64"
