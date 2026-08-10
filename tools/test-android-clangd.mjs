#!/usr/bin/env node

import { execFileSync, spawn } from "node:child_process";

const adb = process.env.ADB || `${process.env.HOME}/Library/Android/sdk/platform-tools/adb`;
const packageName = "dev.foldcode.ide";
const dump = execFileSync(adb, ["shell", "dumpsys", "package", packageName], { encoding: "utf8" });
const codePath = dump.match(/^\s*codePath=(.+)$/m)?.[1];
if (!codePath) throw new Error("FoldCode is not installed");

const native = `${codePath}/lib/arm64`;
const appFiles = `/data/user/0/${packageName}/files`;
const project = process.argv[2] || `${appFiles}/clangd-smoke`;
const compileDirectory = process.env.COMPILE_DIRECTORY || (process.argv[2] ? `${project}/build` : project);
if (!process.argv[2]) {
  const compileCommands = JSON.stringify([{
    directory: project,
    file: `${project}/main.cpp`,
    command: `${native}/libfoldclang.so --driver-mode=g++ --target=aarch64-linux-android24 --sysroot=${appFiles}/cpp-runtime/toolchain/sysroot -resource-dir=${appFiles}/cpp-runtime/toolchain/lib/clang/21 -std=c++17 -c ${project}/main.cpp`,
  }]);
  execFileSync(adb, ["shell", `run-as ${packageName} sh -c 'mkdir -p ${project} && tee ${project}/compile_commands.json >/dev/null'`], {
    input: compileCommands,
  });
}
const command = [
  `FOLDCODE_CLANGD_CORE=${appFiles}/cpp-runtime/native/libfoldclangdcore.so`,
  `LD_LIBRARY_PATH=${appFiles}/cpp-runtime/native:${native}`,
  `TMPDIR=/data/user/0/${packageName}/cache`,
  `${native}/foldclangd.so`,
  "--background-index=false",
  "--clang-tidy=false",
  "--completion-style=detailed",
  "--header-insertion=never",
  `--log=${process.env.CLANGD_LOG || "error"}`,
  `--compile-commands-dir=${compileDirectory}`,
].join(" ");
const child = spawn(adb, ["shell", `run-as ${packageName} sh -c '${command}'`], {
  stdio: ["pipe", "pipe", "inherit"],
});

let nextId = 1;
let buffered = Buffer.alloc(0);
const pending = new Map();
let diagnosticsReady;
function send(message) {
  const body = Buffer.from(JSON.stringify(message));
  child.stdin.write(`Content-Length: ${body.length}\r\n\r\n`);
  child.stdin.write(body);
}
function request(method, params) {
  const id = nextId++;
  send({ jsonrpc: "2.0", id, method, params });
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`${method} timed out`));
    }, 15_000);
    pending.set(id, value => { clearTimeout(timer); resolve(value); });
  });
}
function processMessages() {
  while (true) {
    const headerEnd = buffered.indexOf("\r\n\r\n");
    if (headerEnd < 0) return;
    const header = buffered.subarray(0, headerEnd).toString();
    const length = Number(header.match(/Content-Length:\s*(\d+)/i)?.[1]);
    const bodyStart = headerEnd + 4;
    if (!Number.isFinite(length) || buffered.length < bodyStart + length) return;
    const message = JSON.parse(buffered.subarray(bodyStart, bodyStart + length).toString());
    buffered = buffered.subarray(bodyStart + length);
    if (message.id !== undefined && message.method) {
      send({ jsonrpc: "2.0", id: message.id, result: null });
    } else if (message.id !== undefined) {
      pending.get(message.id)?.(message.result);
      pending.delete(message.id);
    } else if (message.method === "textDocument/publishDiagnostics") {
      diagnosticsReady?.();
      diagnosticsReady = undefined;
    }
  }
}
child.stdout.on("data", chunk => {
  buffered = Buffer.concat([buffered, chunk]);
  processMessages();
});

const uri = `file://${project}/main.cpp`;
await request("initialize", {
  processId: null,
  rootUri: `file://${project}`,
  capabilities: { textDocument: { completion: { completionItem: { snippetSupport: false } } } },
  clientInfo: { name: "FoldCode clangd smoke test", version: "1" },
});
send({ jsonrpc: "2.0", method: "initialized", params: {} });

async function complete(version, text, line, character, triggerCharacter) {
  const method = version === 1 ? "textDocument/didOpen" : "textDocument/didChange";
  const params = version === 1
    ? { textDocument: { uri, languageId: "cpp", version, text } }
    : { textDocument: { uri, version }, contentChanges: [{ text }] };
  const ready = new Promise(resolve => { diagnosticsReady = resolve; });
  send({ jsonrpc: "2.0", method, params });
  await Promise.race([ready, new Promise(resolve => setTimeout(resolve, 900))]);
  const result = await request("textDocument/completion", {
    textDocument: { uri },
    position: { line, character },
    context: triggerCharacter ? { triggerKind: 2, triggerCharacter } : { triggerKind: 1 },
  });
  const items = Array.isArray(result) ? result : result?.items || [];
  return items.map(item => item.label).filter(Boolean);
}

const includeSource = "#include <io";
const includeItems = await complete(1, includeSource, 0, includeSource.length);
const symbolSource = "#include <iostream>\nint main() { std::co }\n";
const symbolCursor = symbolSource.indexOf("std::co") + 7;
const symbolColumn = symbolCursor - symbolSource.lastIndexOf("\n", symbolCursor - 1) - 1;
const symbolItems = await complete(2, symbolSource, 1, symbolColumn);

console.log(`include completions: ${includeItems.slice(0, 12).join(", ")}`);
console.log(`std:: completions: ${symbolItems.slice(0, 12).join(", ")}`);
if (!includeItems.some(value => value.includes("iostream"))) throw new Error("iostream header was not offered");
if (!symbolItems.some(value => value.includes("cout"))) throw new Error("std::cout was not offered");

await request("shutdown", {});
send({ jsonrpc: "2.0", method: "exit", params: {} });
child.stdin.end();
