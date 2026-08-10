#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
RUNTIME=${1:-${FOLDCODE_WEB_RUNTIME:-}}
OUTPUT_DIR=${2:-${FOLDCODE_EXTENSION_OUTPUT_DIR:-"$ROOT/artifacts/extensions"}}
mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR=$(cd "$OUTPUT_DIR" && pwd)
VERSION=${WEB_EXTENSION_VERSION:-1.0.0}
REVISION=${WEB_EXTENSION_REVISION:-10}
WORK="$ROOT/build/web-extension"
PAYLOAD="$OUTPUT_DIR/components/web-runtime-arm64.zip"
PACKAGE="$OUTPUT_DIR/foldcode-web-$VERSION-$REVISION.fcex"

if [[ -z "$RUNTIME" || ! -d "$RUNTIME" ]]; then
  echo "Usage: $0 /path/to/android-arm64-node-runtime [output-directory]" >&2
  echo "The runtime must contain Node, npm, TypeScript and typescript-language-server." >&2
  exit 2
fi
[[ -f "$RUNTIME/bin/node" || -f "$RUNTIME/lib/libnode.so" ]] || {
  echo "Missing Android Node executable or libnode.so" >&2
  exit 2
}
[[ -f "$RUNTIME/lib/node_modules/npm/bin/npm-cli.js" ]] || { echo "Missing npm-cli.js" >&2; exit 2; }
[[ -f "$RUNTIME/lib/node_modules/npm/bin/npx-cli.js" ]] || { echo "Missing npx-cli.js" >&2; exit 2; }
[[ -f "$RUNTIME/lib/node_modules/typescript-language-server/lib/cli.mjs" ]] || { echo "Missing typescript-language-server" >&2; exit 2; }
[[ -f "$RUNTIME/lib/node_modules/typescript/lib/tsserver.js" ]] || { echo "Missing TypeScript tsserver" >&2; exit 2; }
for language_server in vscode-html-language-server vscode-css-language-server vscode-json-language-server; do
  [[ -f "$RUNTIME/lib/node_modules/vscode-langservers-extracted/bin/$language_server" || \
     -f "$RUNTIME/lib/node_modules/@zed-industries/vscode-langservers-extracted/bin/$language_server" ]] || {
    echo "Missing $language_server" >&2
    exit 2
  }
done
for server_main in \
  html-language-server/node/htmlServerMain.js \
  css-language-server/node/cssServerMain.js \
  json-language-server/node/jsonServerMain.js; do
  [[ -f "$RUNTIME/lib/node_modules/vscode-langservers-extracted/lib/$server_main" ]] || {
    echo "Missing language-server implementation: $server_main" >&2
    exit 2
  }
done
[[ -f "$RUNTIME/lib/js-debug/src/dapDebugServer.js" || \
   -f "$RUNTIME/lib/node_modules/@vscode/js-debug/src/dapDebugServer.js" || \
   -f "$RUNTIME/lib/node_modules/vscode-js-debug/src/dapDebugServer.js" ]] || {
  echo "Missing Microsoft vscode-js-debug DAP server" >&2
  exit 2
}

rm -rf "$WORK"
mkdir -p "$WORK/runtime" "$WORK/package/payload" "$OUTPUT_DIR/components"
cp -R "$RUNTIME"/. "$WORK/runtime/"
# zip updates existing archives in place and preserves entries that disappeared
# from the source tree. Always recreate extension archives so an upgrade cannot
# resurrect dependencies removed from a newer runtime.
rm -f "$PAYLOAD" "$PACKAGE"
(cd "$WORK/runtime" && zip -9 -q -r "$PAYLOAD" . \
  -x '.DS_Store' '*/.DS_Store' '._*' '*/._*' '__MACOSX/*' '*/__MACOSX/*')
if unzip -Z1 "$PAYLOAD" | grep -Eq '(^|/)(\.DS_Store|\._[^/]+)(/|$)|(^|/)__MACOSX(/|$)'; then
  echo "Web runtime archive contains macOS metadata" >&2
  exit 1
fi
HASH=$(shasum -a 256 "$PAYLOAD" | awk '{print $1}')
sed "s/__WEB_RUNTIME_SHA256__/$HASH/" "$ROOT/extensions/web/manifest.template.json" > "$WORK/package/manifest.json"
cp "$PAYLOAD" "$WORK/package/payload/web-runtime-arm64.zip"
"$ROOT/tools/package-extension-legal.sh" "$ROOT/extensions/web" "$WORK/package"
(cd "$WORK/package" && zip -0 -q "$PACKAGE" manifest.json payload/web-runtime-arm64.zip licenses/THIRD_PARTY_NOTICES.md licenses/SOURCES.json)
python3 "$ROOT/tools/verify-extension-notices.py" "$PACKAGE"

echo "Web Development extension: $PACKAGE"
echo "Runtime component: $PAYLOAD"
