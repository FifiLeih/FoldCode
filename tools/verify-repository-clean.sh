#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

failed=0

file_size() {
  if [[ "$(uname -s)" == Darwin ]]; then
    stat -f '%z' "$1"
  else
    stat -c '%s' "$1"
  fi
}

tracked_generated=$(
  git ls-files |
    grep -E '^(build|app/build|app/\.cxx|app/\.externalNativeBuild|artifacts|dist|release|server/data/(downloads|submissions)|extensions/[^/]+/payload)(/|$)' || true
)
if [[ -n "$tracked_generated" ]]; then
  echo "Generated files are tracked:" >&2
  echo "$tracked_generated" >&2
  failed=1
fi

tracked_packages=$(git ls-files | grep -E '\.(apk|aab|apks|fcex|tar|tar\.gz|tar\.xz|tar\.zst)$' || true)
if [[ -n "$tracked_packages" ]]; then
  echo "Packaged outputs are tracked:" >&2
  echo "$tracked_packages" >&2
  failed=1
fi

tracked_runtime_inputs=$(
  git ls-files |
    grep -E '(^|/)__pycache__(/|$)|\.py[co]$|^app/src/main/assets/toolchain\.zip$|^app/src/main/jniLibs/.+\.so$' || true
)
if [[ -n "$tracked_runtime_inputs" ]]; then
  echo "Generated or separately delivered runtime inputs are tracked:" >&2
  echo "$tracked_runtime_inputs" >&2
  failed=1
fi

oversized=$(
  git ls-files -z |
    while IFS= read -r -d '' path; do
      [[ -f "$path" ]] || continue
      size=$(file_size "$path")
      if (( size >= 100000000 )); then
        printf '%s %s\n' "$size" "$path"
      fi
    done
)
if [[ -n "$oversized" ]]; then
  echo "Files exceed GitHub's normal 100 MB limit:" >&2
  echo "$oversized" >&2
  failed=1
fi

tracked_secrets=$(git ls-files | grep -E '(^|/)(\.env($|\.)|[^/]+\.(jks|keystore|p12|pfx|key|pem)$|keystore\.properties$)' | grep -v '^app/src/main/assets/git/cacert\.pem$' || true)
if [[ -n "$tracked_secrets" ]]; then
  echo "Potential credential or signing files are tracked:" >&2
  echo "$tracked_secrets" >&2
  failed=1
fi

if (( failed != 0 )); then
  exit 1
fi

python3 tools/verify-extension-notices.py

echo "Repository hygiene checks passed."
