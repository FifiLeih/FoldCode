#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
MODE=full
REQUIRE_PICO=0

usage() {
  cat <<'EOF'
Usage: tools/test-core.sh [--quick] [--require-pico]

  default         JVM tests, lint, both APK builds, release-content verification
  --quick         JVM tests and lint only; works without the base-native bundle
  --require-pico  require the 42-build host Pico SDK matrix instead of allowing its skip

Set FOLDCODE_NETWORK_AUDIT=1 to query npm's advisory service as part of the run.
EOF
}

while (($#)); do
  case "$1" in
    --quick) MODE=quick ;;
    --require-pico) REQUIRE_PICO=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

cd "$ROOT"

if ((REQUIRE_PICO)); then
  missing=()
  for name in PICO_TEST_SDK PICO_TEST_TOOLCHAIN PICO_TEST_CMAKE PICO_TEST_NINJA PICO_TEST_PICOTOOL; do
    [[ -n "${!name:-}" ]] || missing+=("$name")
  done
  if ((${#missing[@]})); then
    echo "Pico integration was required, but these variables are missing: ${missing[*]}" >&2
    exit 2
  fi
  export FOLDCODE_REQUIRE_PICO_INTEGRATION=1
fi

git diff --check
tools/verify-repository-clean.sh
find tools -maxdepth 1 -type f -name '*.sh' -exec bash -n {} +
python3 -c 'import ast,pathlib; files=list(pathlib.Path("tools").glob("*.py")); [ast.parse(path.read_text(), filename=str(path)) for path in files]'

if [[ "$MODE" == quick ]]; then
  GRADLE_TASK=:app:coreUnitCheck
else
  GRADLE_TASK=:app:coreCheck
fi
./gradlew --console=plain "$GRADLE_TASK"
python3 tools/verify-test-results.py

EXTENSIONS=(
  foldcode-cpp-1.0.0-3.fcex
  foldcode-python-3.14.6-6.fcex
  foldcode-pico-2.3.0-18.fcex
  foldcode-rust-1.0.0-4.fcex
  foldcode-gnu-arm-15.2.1-1.fcex
  foldcode-gnu-languages-1.0.0-6.fcex
  foldcode-web-1.0.0-10.fcex
)
packages=()
for package in "${EXTENSIONS[@]}"; do
  path="$ROOT/artifacts/extensions/$package"
  [[ -f "$path" ]] && packages+=("$path")
done
if ((${#packages[@]} == 0)); then
  echo "Extension packages are not present; source checks completed without release-asset validation."
elif ((${#packages[@]} != ${#EXTENSIONS[@]})); then
  echo "Only ${#packages[@]}/${#EXTENSIONS[@]} approved extension packages are present." >&2
  exit 1
else
  python3 tools/verify-extension-notices.py "${packages[@]}"
fi

PYTHON_EXTENSION="$ROOT/artifacts/extensions/foldcode-python-3.14.6-6.fcex"
if [[ -f "$PYTHON_EXTENSION" ]]; then
  python3 tools/verify-python-extension.py "$PYTHON_EXTENSION"
fi

if [[ "${FOLDCODE_NETWORK_AUDIT:-0}" == 1 ]]; then
  npm --prefix extensions/web/tooling audit --package-lock-only --audit-level=moderate
  npm --prefix extensions/web/npm-patches audit --package-lock-only --audit-level=moderate
fi

echo "FoldCode ${MODE} core checks passed."
