#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ARCHIVE=${1:-}
DESTINATION=${2:-"$ROOT/app/src/main/jniLibs/arm64-v8a"}

[[ -n "$ARCHIVE" && -f "$ARCHIVE" ]] || {
  echo "Usage: tools/install-base-runtime.sh /path/to/foldcode-base-native-arm64-v1.zip [destination]" >&2
  exit 2
}
mkdir -p "$DESTINATION"

ARCHIVE="$ARCHIVE" DESTINATION="$DESTINATION" python3 - <<'PY'
from pathlib import Path
import hashlib, json, os, stat, zipfile

archive = Path(os.environ["ARCHIVE"]).resolve()
destination = Path(os.environ["DESTINATION"]).resolve()
with zipfile.ZipFile(archive) as package:
    names = set(package.namelist())
    manifest = json.loads(package.read("manifest.json"))
    if manifest.get("schemaVersion") != 1 or manifest.get("abi") != "arm64-v8a":
        raise SystemExit("Unsupported FoldCode base runtime bundle")
    files = manifest.get("files")
    if not isinstance(files, dict) or not files:
        raise SystemExit("Base runtime manifest has no files")
    expected = {f"native/{name}" for name in files}
    if not expected.issubset(names):
        raise SystemExit("Base runtime bundle is incomplete")
    for name, digest in files.items():
        if Path(name).name != name or not name.endswith(".so"):
            raise SystemExit(f"Unsafe base runtime filename: {name}")
        data = package.read(f"native/{name}")
        if hashlib.sha256(data).hexdigest() != digest:
            raise SystemExit(f"Checksum failed for {name}")
        target = destination / name
        target.write_bytes(data)
        target.chmod(target.stat().st_mode | stat.S_IXUSR)
print(f"Installed {len(files)} verified base runtime files into {destination}")
PY
