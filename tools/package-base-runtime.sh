#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SOURCE=${FOLDCODE_BASE_NATIVE_DIR:-"$ROOT/app/src/main/jniLibs/arm64-v8a"}
OUTPUT=${1:-"$ROOT/artifacts/base-runtime/foldcode-base-native-arm64-v1.zip"}
WORK=$(mktemp -d "${TMPDIR:-/tmp}/foldcode-base-native.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

FILES=(
  libc++_shared.so
  libfoldar.so
  libfoldclang.so
  libfoldcmake.so
  libfoldgitcore.so
  libfoldgitremotehttp.so
  libfoldgnuld.so
  libfoldld.so
  libfoldninja.so
  libfoldobjcopy.so
  libfoldobjdump.so
  libfoldproot.so
  libfoldprootloader.so
  libfoldranlib.so
  libfoldriscvar.so
  libfoldriscvobjcopy.so
  libfoldriscvobjdump.so
  libfoldriscvranlib.so
)

mkdir -p "$WORK/native" "$WORK/legal" "$(dirname "$OUTPUT")"
for name in "${FILES[@]}"; do
  test -f "$SOURCE/$name" || { echo "Missing base runtime input: $SOURCE/$name" >&2; exit 1; }
  install -m 0755 "$SOURCE/$name" "$WORK/native/$name"
done
install -m 0644 "$ROOT/LICENSE" "$WORK/legal/LICENSE"
install -m 0644 "$ROOT/NOTICE" "$WORK/legal/NOTICE"
install -m 0644 "$ROOT/THIRD_PARTY_NOTICES.md" "$WORK/legal/THIRD_PARTY_NOTICES.md"

WORK="$WORK" python3 - <<'PY'
from pathlib import Path
import hashlib, json, os

root = Path(os.environ["WORK"])
files = {
    path.name: hashlib.sha256(path.read_bytes()).hexdigest()
    for path in sorted((root / "native").iterdir())
    if path.is_file()
}
(root / "manifest.json").write_text(json.dumps({
    "schemaVersion": 1,
    "name": "FoldCode base native runtime",
    "abi": "arm64-v8a",
    "files": files,
}, indent=2) + "\n")
PY

rm -f "$OUTPUT"
(cd "$WORK" && zip -9 -q -r "$OUTPUT" manifest.json native legal)
echo "Base runtime bundle: $OUTPUT"
