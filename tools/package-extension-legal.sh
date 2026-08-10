#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SOURCE=${1:?usage: package-extension-legal.sh <extension-directory> <package-directory>}
DESTINATION=${2:?usage: package-extension-legal.sh <extension-directory> <package-directory>}

python3 "$ROOT/tools/verify-extension-notices.py" "$SOURCE"
mkdir -p "$DESTINATION/licenses"
cp "$SOURCE/licenses/THIRD_PARTY_NOTICES.md" "$DESTINATION/licenses/THIRD_PARTY_NOTICES.md"
cp "$SOURCE/licenses/SOURCES.json" "$DESTINATION/licenses/SOURCES.json"
