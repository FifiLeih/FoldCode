#!/usr/bin/env bash
set -euo pipefail

# Builds the shared AArch64 GNU/Linux userspace used by the separate Fortran and
# COBOL extensions. Docker is only a packaging tool; it is never needed on the
# Android device.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUTPUT="${1:-$ROOT/build/gnu-languages-runtime}"
ANDROID_SDK=${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties" | head -1)}
HOST_CLANG=${FOLDCODE_HOST_CLANG:-"$ANDROID_SDK/ndk/27.2.12479018/toolchains/llvm/prebuilt/darwin-x86_64/bin/clang"}

rm -rf "$OUTPUT"
mkdir -p "$OUTPUT/rootfs"

if command -v docker >/dev/null && docker info >/dev/null 2>&1; then
  docker run --rm --platform linux/arm64 \
    -v "$OUTPUT/rootfs:/out" \
    debian:bookworm-slim \
    sh -eu -c '
      apt-get update
      DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        binutils gcc gfortran gnucobol libc6-dev libgfortran5 libcob4-dev libxml2 libxml2-dev
      mkdir -p /usr/share/foldcode-licenses
      for copyright in /usr/share/doc/*/copyright; do
        test -f "$copyright" || continue
        package=$(basename "$(dirname "$copyright")")
        cp "$copyright" "/usr/share/foldcode-licenses/${package}.copyright"
      done
      tar --exclude="usr/share/doc" --exclude="usr/share/man" --exclude="usr/share/locale" \
        -C / -cf - usr/bin usr/include usr/lib usr/libexec usr/share/gnucobol \
        usr/share/foldcode-licenses 2>/dev/null | tar -C /out -xf -
    '
else
  "$ROOT/tools/prepare-debian-gnu-languages.py" "$OUTPUT"
fi

printf 'bookworm\n' > "$OUTPUT/.debian-suite"

# Android's ZipInputStream does not recreate Unix symbolic links. Replace every
# link with its real file/directory so compiler driver aliases and shared
# libraries keep working after extension extraction.
OUTPUT="$OUTPUT" python3 - <<'PY'
import os
import shutil
from pathlib import Path

root = Path(os.environ["OUTPUT"]) / "rootfs"
links = [path for path in root.rglob("*") if path.is_symlink()]
for link in sorted(links, key=lambda p: len(p.parts), reverse=True):
    target = link.resolve(strict=True)
    link.unlink()
    if target.is_dir():
        shutil.copytree(target, link, symlinks=False)
    else:
        shutil.copy2(target, link)
PY

# Debian uses merged-/usr symlinks for /bin and /lib. The staging fallback can
# materialize those paths as real directories, while Android's portable ZIP
# extraction cannot restore the symlinks. FoldCode recreates /bin -> /usr/bin
# and /lib -> /usr/lib after installation, so move every materialized file into
# its canonical /usr location before packaging. Otherwise installation would
# discard essential files such as libc.so.6 when replacing /lib.
if [[ -d "$OUTPUT/rootfs/bin" ]]; then
  mkdir -p "$OUTPUT/rootfs/usr/bin"
  cp -R "$OUTPUT/rootfs/bin/." "$OUTPUT/rootfs/usr/bin/"
  rm -rf "$OUTPUT/rootfs/bin"
fi
if [[ -d "$OUTPUT/rootfs/lib" ]]; then
  mkdir -p "$OUTPUT/rootfs/usr/lib"
  cp -R "$OUTPUT/rootfs/lib/." "$OUTPUT/rootfs/usr/lib/"
  rm -rf "$OUTPUT/rootfs/lib"
fi

mkdir -p "$OUTPUT/rootfs/tmp" "$OUTPUT/tmp"

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
test -f "$OUTPUT/rootfs/usr/bin/gfortran"
test -f "$OUTPUT/rootfs/usr/bin/cobc"
test -f "$OUTPUT/rootfs/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"

# The GNU language launcher enters this rootfs through the loader packaged in
# the base APK.  A loader from another glibc build can reach main() unreliably
# or crash before the compiler starts, so keep the APK copy byte-for-byte in
# sync with the runtime being prepared.
APK_GNU_LOADER="$ROOT/app/src/main/jniLibs/arm64-v8a/libfoldgnuld.so"
install -m 755 \
  "$OUTPUT/rootfs/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" \
  "$APK_GNU_LOADER"
echo "$OUTPUT"
