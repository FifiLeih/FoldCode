#!/usr/bin/env bash
set -euo pipefail

# Build a relocatable AArch64 Linux Rust runtime for FoldCode from the official
# stable Rust distribution. The resulting directory is consumed by
# build-rust-extension.sh and executed on Android through FoldCode's PRoot host.
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUTPUT=${1:-"$ROOT/build/rust-runtime-stage"}
GNU_ROOTFS=${FOLDCODE_GLIBC_ROOTFS:-"$ROOT/build/gnu-arm-stage/rootfs"}
CACHE=${FOLDCODE_RUST_DOWNLOAD_CACHE:-"$ROOT/build/rust-downloads"}
ANDROID_SDK=${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties" | head -1)}
HOST_CLANG=${FOLDCODE_HOST_CLANG:-"$ANDROID_SDK/ndk/27.2.12479018/toolchains/llvm/prebuilt/darwin-x86_64/bin/clang"}
MANIFEST="$CACHE/channel-rust-stable.toml"
LIBGCC_URL=https://deb.debian.org/debian/pool/main/g/gcc-14/libgcc-s1_14.2.0-19_arm64.deb
LIBGCC_SHA256=1108bc87879833d6d9a145f22a4a15cddb34e065b4b5f4b97bee586adbac2851
LIBGCC_PACKAGE="$CACHE/$(basename "$LIBGCC_URL")"
ZLIB_URL='https://deb.debian.org/debian/pool/main/z/zlib/zlib1g_1.3.dfsg+really1.3.1-1+b1_arm64.deb'
ZLIB_SHA256=209aa5cf671e97b9eb0410844fa6df4cae2e75b0c72e7802ab6c8ece13e6ddef
ZLIB_PACKAGE="$CACHE/$(basename "$ZLIB_URL")"
LIBC_DEV_URL='https://deb.debian.org/debian/pool/main/g/glibc/libc6-dev_2.41-12+deb13u4_arm64.deb'
LIBC_DEV_SHA256=71e373cb5e9da975b02fb4af555929cb1977560e2dd8245de4eb8a7b03677a08
LIBC_DEV_PACKAGE="$CACHE/$(basename "$LIBC_DEV_URL")"
CA_BUNDLE_URL=https://curl.se/ca/cacert.pem
CA_BUNDLE_SHA256=3ff344e30b9b1ed2971044eabb438a08f2e2245ddb5f8ab1a3ad8b63ab4eaf91
CA_BUNDLE="$CACHE/cacert.pem"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/foldcode-rust.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

test -f "$GNU_ROOTFS/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" || {
  echo "AArch64 glibc rootfs is missing from $GNU_ROOTFS" >&2
  exit 1
}

mkdir -p "$CACHE"
curl -fsSL https://static.rust-lang.org/dist/channel-rust-stable.toml -o "$MANIFEST.tmp"
mv "$MANIFEST.tmp" "$MANIFEST"

python3 - "$MANIFEST" > "$WORK/packages.tsv" <<'PY'
import sys, tomllib

manifest = tomllib.load(open(sys.argv[1], "rb"))
packages = manifest["pkg"]
requested = [
    ("rustc", "aarch64-unknown-linux-gnu"),
    ("cargo", "aarch64-unknown-linux-gnu"),
    ("rust-std", "aarch64-unknown-linux-gnu"),
    ("rust-std", "thumbv6m-none-eabi"),
    ("rust-std", "thumbv8m.main-none-eabihf"),
    ("rust-std", "riscv32imac-unknown-none-elf"),
    # rust-analyzer needs the standard-library sources for completion,
    # navigation and diagnostics. Keep them in the Rust extension payload;
    # they never increase the base APK size.
    ("rust-src", "*"),
    ("rust-analyzer-preview", "aarch64-unknown-linux-gnu"),
    ("rustfmt-preview", "aarch64-unknown-linux-gnu"),
]
for package, target in requested:
    entry = packages[package]["target"][target]
    if not entry.get("available"):
        raise SystemExit(f"Rust stable does not provide {package} for {target}")
    print(package, target, entry["xz_url"], entry["xz_hash"], sep="\t")
PY

rm -rf "$OUTPUT"
mkdir -p "$OUTPUT/toolchain" "$OUTPUT/rootfs"
cp -R -L "$GNU_ROOTFS/." "$OUTPUT/rootfs/"
rm -rf "$OUTPUT/rootfs/lib"
cp "$ROOT/tools/rust-resolv.conf" "$OUTPUT/rootfs/etc/resolv.conf"
if [[ ! -f "$CA_BUNDLE" ]] || [[ "$(shasum -a 256 "$CA_BUNDLE" | awk '{print $1}')" != "$CA_BUNDLE_SHA256" ]]; then
  curl -fL --retry 3 "$CA_BUNDLE_URL" -o "$CA_BUNDLE.tmp"
  test "$(shasum -a 256 "$CA_BUNDLE.tmp" | awk '{print $1}')" = "$CA_BUNDLE_SHA256"
  mv "$CA_BUNDLE.tmp" "$CA_BUNDLE"
fi
mkdir -p "$OUTPUT/rootfs/etc/ssl/certs"
mkdir -p "$OUTPUT/rootfs/usr/bin"
cp "$CA_BUNDLE" "$OUTPUT/rootfs/etc/ssl/certs/ca-certificates.crt"

# The official Linux Rust host tools dynamically link libgcc_s. Keep this
# small host dependency in the extension rootfs; it is unrelated to the
# bare-metal libraries linked into Pico firmware.
if [[ ! -f "$LIBGCC_PACKAGE" ]] || [[ "$(shasum -a 256 "$LIBGCC_PACKAGE" | awk '{print $1}')" != "$LIBGCC_SHA256" ]]; then
  curl -fL --retry 3 "$LIBGCC_URL" -o "$LIBGCC_PACKAGE.tmp"
  test "$(shasum -a 256 "$LIBGCC_PACKAGE.tmp" | awk '{print $1}')" = "$LIBGCC_SHA256"
  mv "$LIBGCC_PACKAGE.tmp" "$LIBGCC_PACKAGE"
fi
ar -p "$LIBGCC_PACKAGE" data.tar.xz | tar -xJf - -C "$OUTPUT/rootfs"
if [[ ! -f "$ZLIB_PACKAGE" ]] || [[ "$(shasum -a 256 "$ZLIB_PACKAGE" | awk '{print $1}')" != "$ZLIB_SHA256" ]]; then
  curl -fL --retry 3 "$ZLIB_URL" -o "$ZLIB_PACKAGE.tmp"
  test "$(shasum -a 256 "$ZLIB_PACKAGE.tmp" | awk '{print $1}')" = "$ZLIB_SHA256"
  mv "$ZLIB_PACKAGE.tmp" "$ZLIB_PACKAGE"
fi
ar -p "$ZLIB_PACKAGE" data.tar.xz | tar -xJf - -C "$OUTPUT/rootfs"
if [[ ! -f "$LIBC_DEV_PACKAGE" ]] || [[ "$(shasum -a 256 "$LIBC_DEV_PACKAGE" | awk '{print $1}')" != "$LIBC_DEV_SHA256" ]]; then
  curl -fL --retry 3 "$LIBC_DEV_URL" -o "$LIBC_DEV_PACKAGE.tmp"
  test "$(shasum -a 256 "$LIBC_DEV_PACKAGE.tmp" | awk '{print $1}')" = "$LIBC_DEV_SHA256"
  mv "$LIBC_DEV_PACKAGE.tmp" "$LIBC_DEV_PACKAGE"
fi
ar -p "$LIBC_DEV_PACKAGE" data.tar.xz | tar -xJf - -C "$OUTPUT/rootfs"
ln -sf libgcc_s.so.1 "$OUTPUT/rootfs/usr/lib/aarch64-linux-gnu/libgcc_s.so"

test -x "$HOST_CLANG" || {
  echo "Host clang is missing from $HOST_CLANG" >&2
  exit 1
}
"$HOST_CLANG" \
  --target=aarch64-linux-gnu \
  -shared -fPIC -nostdlib -fuse-ld=lld \
  -Wl,-soname,libfoldspawn.so \
  -Wl,--version-script="$ROOT/tools/rust-posix-spawn-shim.map" \
  "$ROOT/tools/rust-posix-spawn-shim.c" \
  -o "$OUTPUT/rootfs/usr/lib/libfoldspawn.so"
"$HOST_CLANG" \
  --target=aarch64-linux-gnu \
  -fPIC -fno-stack-protector -nostdlib -fuse-ld=lld \
  -Wl,-pie -Wl,-e,_start \
  -Wl,--dynamic-linker=/usr/lib/ld-linux-aarch64.so.1 \
  "$ROOT/tools/rust-guest-env.c" \
  -Wl,-L,"$OUTPUT/rootfs/usr/lib/aarch64-linux-gnu" -Wl,-l:libc.so.6 \
  -o "$OUTPUT/rootfs/usr/bin/foldrust-env"
"$HOST_CLANG" \
  --target=aarch64-linux-gnu \
  --sysroot="$OUTPUT/rootfs" -fuse-ld=lld -nostdlib -fno-stack-protector \
  -Wl,--dynamic-linker=/usr/lib/ld-linux-aarch64.so.1 \
  "$OUTPUT/rootfs/usr/lib/aarch64-linux-gnu/Scrt1.o" \
  "$OUTPUT/rootfs/usr/lib/aarch64-linux-gnu/crti.o" \
  "$ROOT/tools/rust-host-cc.c" \
  -Wl,-L,"$OUTPUT/rootfs/usr/lib/aarch64-linux-gnu" -Wl,-l:libc.so.6 \
  "$OUTPUT/rootfs/usr/lib/aarch64-linux-gnu/crtn.o" \
  -o "$OUTPUT/rootfs/usr/bin/cc"

while IFS=$'\t' read -r package target url checksum; do
  archive="$CACHE/$(basename "$url")"
  if [[ ! -f "$archive" ]] || [[ "$(shasum -a 256 "$archive" | awk '{print $1}')" != "$checksum" ]]; then
    echo "Downloading $package for $target"
    curl -fL --retry 3 "$url" -o "$archive.tmp"
    test "$(shasum -a 256 "$archive.tmp" | awk '{print $1}')" = "$checksum"
    mv "$archive.tmp" "$archive"
  fi
  component="$WORK/${package}-${target}"
  mkdir -p "$component"
  tar -xJf "$archive" -C "$component"
  installer=$(find "$component" -mindepth 2 -maxdepth 2 -name install.sh -type f | head -1)
  test -n "$installer"
  sh "$installer" --prefix="$OUTPUT/toolchain" --disable-ldconfig
done < "$WORK/packages.tsv"

for tool in rustc cargo rust-analyzer rustfmt; do
  test -f "$OUTPUT/toolchain/bin/$tool"
done
for target in thumbv6m-none-eabi thumbv8m.main-none-eabihf riscv32imac-unknown-none-elf; do
  test -d "$OUTPUT/toolchain/lib/rustlib/$target/lib"
done
test -f "$OUTPUT/toolchain/lib/rustlib/src/rust/library/std/src/lib.rs"

# FoldCode's extension extractor intentionally creates regular files only. A
# ZIP entry representing a Unix symlink would otherwise be extracted as a tiny
# text file containing the link target (for example, libz.so.1 would contain
# "libz.so.1.3.1"). Dereference every runtime symlink before packaging so the
# installed Android runtime contains usable libraries and documentation.
while IFS= read -r -d '' link; do
  target=$(readlink "$link")
  case "$target" in
    /*) source="$OUTPUT/rootfs$target" ;;
    *) source="$(dirname "$link")/$target" ;;
  esac
  if [[ ! -e "$source" ]]; then
    # Documentation symlinks can point at packages intentionally omitted from
    # the minimal runtime. They have no execution value and cannot be restored.
    rm "$link"
    continue
  fi
  if [[ -d "$source" ]]; then
    temporary="${link}.foldcode-directory"
    rm -rf "$temporary"
    cp -R -L "$source" "$temporary"
    rm "$link"
    mv "$temporary" "$link"
  else
    temporary="${link}.foldcode-file"
    cp -L "$source" "$temporary"
    rm "$link"
    mv "$temporary" "$link"
  fi
done < <(find "$OUTPUT" -type l -print0)

test "$(wc -c < "$OUTPUT/rootfs/usr/lib/aarch64-linux-gnu/libz.so.1")" -gt 100000

echo "Prepared FoldCode Rust runtime in $OUTPUT"
echo "Package it with: $ROOT/tools/build-rust-extension.sh $OUTPUT"
