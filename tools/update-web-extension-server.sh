#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SOURCE=${1:-"$ROOT/artifacts/extensions"}
REGISTRY=${2:-"$ROOT/artifacts/extension-server/v2"}
PACKAGE="$SOURCE/foldcode-web-1.0.0-10.fcex"
PAYLOAD="$SOURCE/components/web-runtime-arm64.zip"
CATALOG="$REGISTRY/catalog.json"

for file in "$PACKAGE" "$PAYLOAD" "$CATALOG"; do
  [[ -f "$file" ]] || { echo "Missing $file" >&2; exit 2; }
done

mkdir -p "$REGISTRY/packages" "$REGISTRY/components"
cp "$PACKAGE" "$REGISTRY/packages/"
cp "$PAYLOAD" "$REGISTRY/components/"

CATALOG="$CATALOG" PACKAGE="$REGISTRY/packages/$(basename "$PACKAGE")" \
PAYLOAD="$REGISTRY/components/$(basename "$PAYLOAD")" node <<'NODE'
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const catalogPath = process.env.CATALOG;
const catalog = JSON.parse(fs.readFileSync(catalogPath, "utf8"));
const extension = catalog.extensions.find(entry => entry.id === "dev.foldcode.web");
if (!extension) throw new Error("Web Development is missing from catalog.json");

function descriptor(file, prefix) {
  const bytes = fs.readFileSync(file);
  return {
    url: `${prefix}/${path.basename(file)}`,
    sha256: crypto.createHash("sha256").update(bytes).digest("hex"),
    sizeBytes: bytes.length,
  };
}

extension.version = "1.0.0+10";
extension.description = "Node.js, npm, JavaScript, TypeScript, React and browser preview with dedicated TypeScript, HTML, CSS and JSON IntelliSense on Android ARM64.";
extension.provides = [
  "command:node", "command:npm", "command:npx",
  "language:javascript", "language:typescript", "language:html", "language:css", "language:json",
  "language-server:typescript", "language-server:html", "language-server:css", "language-server:json",
  "debugger:v8", "debugger:webview-cdp",
];
extension.corePackage = descriptor(process.env.PACKAGE, "packages");
extension.payloads = {
  [path.basename(process.env.PAYLOAD)]: descriptor(process.env.PAYLOAD, "components"),
};
fs.writeFileSync(catalogPath, `${JSON.stringify(catalog, null, 2)}\n`);
NODE

echo "Updated Web Development package and checksums in $REGISTRY"
