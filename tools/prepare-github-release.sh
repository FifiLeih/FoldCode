#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
APK=${1:-}
OUTPUT=${2:-"$ROOT/dist/foldcode-1.0.0"}

[[ -n "$APK" && -f "$APK" ]] || {
  echo "Usage: tools/prepare-github-release.sh /path/to/signed-release.apk [empty-output-directory]" >&2
  exit 2
}
case "$(basename "$APK")" in
  *debug*|*unsigned*) echo "Refusing to stage a debug or unsigned APK" >&2; exit 1 ;;
esac
if [[ -e "$OUTPUT" && -n "$(find "$OUTPUT" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]]; then
  echo "Release staging directory must be empty: $OUTPUT" >&2
  exit 1
fi

SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [[ -z "$SDK_ROOT" && -f "$ROOT/local.properties" ]]; then
  SDK_ROOT=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | head -1)
fi
APKSIGNER=${APKSIGNER:-}
if [[ -z "$APKSIGNER" && -n "$SDK_ROOT" ]]; then
  for candidate in "$SDK_ROOT"/build-tools/*/apksigner; do
    [[ -x "$candidate" ]] && APKSIGNER=$candidate
  done
fi
[[ -n "$APKSIGNER" && -x "$APKSIGNER" ]] || {
  echo "Set APKSIGNER to the Android SDK apksigner executable" >&2
  exit 2
}
"$APKSIGNER" verify --verbose "$APK" >/dev/null

EXTENSIONS=(
  foldcode-cpp-1.0.0-3.fcex
  foldcode-python-3.14.6-6.fcex
  foldcode-pico-2.3.0-18.fcex
  foldcode-rust-1.0.0-4.fcex
  foldcode-gnu-arm-15.2.1-1.fcex
  foldcode-gnu-languages-1.0.0-6.fcex
  foldcode-web-1.0.0-10.fcex
)
for package in "${EXTENSIONS[@]}"; do
  path="$ROOT/artifacts/extensions/$package"
  [[ -f "$path" ]] || { echo "Missing release extension: $path" >&2; exit 1; }
  python3 "$ROOT/tools/verify-extension-notices.py" "$path"
done
BASE_RUNTIME="$ROOT/artifacts/base-runtime/foldcode-base-native-arm64-v1.zip"
[[ -f "$BASE_RUNTIME" ]] || { echo "Missing contributor base runtime bundle" >&2; exit 1; }

mkdir -p "$OUTPUT"
install -m 0644 "$APK" "$OUTPUT/FoldCode-1.0.0-arm64-v8a.apk"
for package in "${EXTENSIONS[@]}"; do
  install -m 0644 "$ROOT/artifacts/extensions/$package" "$OUTPUT/$package"
done
install -m 0644 "$BASE_RUNTIME" "$OUTPUT/$(basename "$BASE_RUNTIME")"
(
  cd "$OUTPUT"
  shasum -a 256 FoldCode-1.0.0-arm64-v8a.apk "${EXTENSIONS[@]}" "$(basename "$BASE_RUNTIME")" > SHA256SUMS
)
echo "GitHub release assets staged in: $OUTPUT"
