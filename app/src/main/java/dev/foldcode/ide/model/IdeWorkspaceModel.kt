package dev.foldcode.ide

import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableStateOf

internal enum class IdeTheme(val label: String) {
    FoldCodeDark("FoldCode Dark"),
    Midnight("Midnight"),
    HighContrast("High Contrast"),
}

internal data class IdePalette(
    val background: Color,
    val panel: Color,
    val rail: Color,
    val editorTab: Color,
    val border: Color,
    val foreground: Color,
    val muted: Color,
    val accent: Color,
    val success: Color,
)

private fun paletteFor(theme: IdeTheme): IdePalette = when (theme) {
    IdeTheme.FoldCodeDark -> IdePalette(
        Color(0xFF181A1F), Color(0xFF202329), Color(0xFF17191D), Color(0xFF25282E),
        Color(0xFF343840), Color(0xFFD8DEE9), Color(0xFF89909E), Color(0xFF5B8DEF), Color(0xFF82C99A),
    )
    IdeTheme.Midnight -> IdePalette(
        Color(0xFF0D1117), Color(0xFF161B22), Color(0xFF0B0F14), Color(0xFF1C2128),
        Color(0xFF30363D), Color(0xFFE6EDF3), Color(0xFF8B949E), Color(0xFF2F81F7), Color(0xFF56D364),
    )
    IdeTheme.HighContrast -> IdePalette(
        Color(0xFF000000), Color(0xFF101010), Color(0xFF050505), Color(0xFF181818),
        Color(0xFF6E7681), Color(0xFFFFFFFF), Color(0xFFBBC2CC), Color(0xFF67A6FF), Color(0xFF7EE787),
    )
}

private val activeIdePalette = mutableStateOf(paletteFor(IdeTheme.FoldCodeDark))
internal fun applyIdeTheme(theme: IdeTheme) { activeIdePalette.value = paletteFor(theme) }
internal fun currentIdeThemePalette(): IdePalette = activeIdePalette.value
internal val Background: Color get() = activeIdePalette.value.background
internal val Panel: Color get() = activeIdePalette.value.panel
internal val Rail: Color get() = activeIdePalette.value.rail
internal val EditorTab: Color get() = activeIdePalette.value.editorTab
internal val Border: Color get() = activeIdePalette.value.border
internal val Foreground: Color get() = activeIdePalette.value.foreground
internal val Muted: Color get() = activeIdePalette.value.muted
internal val Accent: Color get() = activeIdePalette.value.accent
internal val Success: Color get() = activeIdePalette.value.success

internal enum class LayoutMode { Compact, Medium, Expanded }

internal enum class BottomPanel(val label: String) {
    Problems("PROBLEMS"), Execution("EXECUTION"), Terminal("TERMINAL"),
}

internal data class TerminalSessionInfo(
    val id: Int,
    val commandRunning: Boolean = false,
    val webServerOwner: Boolean = false,
)

internal fun workspaceToolbarOperationRunning(
    building: Boolean,
    terminalSessions: List<TerminalSessionInfo>,
): Boolean = building || terminalSessions.any(TerminalSessionInfo::webServerOwner)

internal enum class ActivityDestination(val label: String, val glyph: String) {
    Files("Explorer", "▱"),
    Search("Search", "⌕"),
    SourceControl("Source control", "⑂"),
    Run("Run and debug", "▷"),
    Extensions("Extensions", "▦"),
    Pico("Raspberry Pi Pico", "µ"),
}

internal enum class ProjectFileKind {
    Source,
    Documentation,
    CppExtensionDetails,
    PythonExtensionDetails,
    RustExtensionDetails,
    GnuLanguagesExtensionDetails,
    WebExtensionDetails,
    WebPreview,
    GitExtensionDetails,
    PicoExtensionDetails,
}

internal data class ProjectFile(
    val name: String,
    val content: String,
    val readOnly: Boolean = false,
    val kind: ProjectFileKind = ProjectFileKind.Source,
)
internal data class EditorNavigationRequest(
    val fileName: String,
    val line: Int,
    val column: Int,
    val revision: Int,
)
internal enum class ExplorerActionType { CreateProject, CreateFile, CreateFolder, Rename, Move }
internal data class ExplorerAction(val type: ExplorerActionType, val path: String = "", val folder: Boolean = false)
internal data class ExplorerEntry(val path: String, val folder: Boolean, val editable: Boolean = true)
internal enum class GeneralProjectTemplate(val label: String, val category: String) {
    Cpp("C++", "Native"),
    C("C", "Native"),
    Python("Python", "Scripting"),
    Rust("Rust", "Native"),
    Fortran("Fortran", "Scientific"),
    Cobol("COBOL", "Business"),
    WebVanilla("Vanilla JavaScript", "Web"),
    WebViteTypeScript("Vite + TypeScript", "Web"),
    WebReactTypeScript("React + Vite + TypeScript", "Web"),
}
internal val initialProject = listOf(
    ProjectFile("main.cpp", """#include <iostream>
#include "greetings.hpp"

int main() {
    std::cout << greeting() << std::endl;
    return 0;
}
"""),
    ProjectFile("greetings.cpp", """#include "greetings.hpp"

std::string greeting() {
    return "Hello from FoldCode!";
}
"""),
    ProjectFile("greetings.hpp", """#pragma once

#include <string>

std::string greeting();
"""),
    ProjectFile("CMakeLists.txt", """cmake_minimum_required(VERSION 3.22)
project(hello_foldcode LANGUAGES CXX)

add_executable(hello main.cpp greetings.cpp)
target_compile_features(hello PRIVATE cxx_std_20)
"""),
).associateBy(ProjectFile::name)

internal val cProject = mapOf(
    "main.c" to """#include <stdio.h>

int main(void) {
    puts("Hello from FoldCode C!");
    return 0;
}
""",
    "CMakeLists.txt" to """cmake_minimum_required(VERSION 3.22)
project(foldcode_c LANGUAGES C)

add_executable(foldcode_c main.c)
set_property(TARGET foldcode_c PROPERTY C_STANDARD 17)
""",
)

internal val pythonProject = mapOf(
    "main.py" to """def main() -> None:
    print("Hello from FoldCode Python!")


if __name__ == "__main__":
    main()
""",
    "README.md" to "# FoldCode Python project\n\nRun `main.py` with the Python extension.\n",
)

internal val rustProject = mapOf(
    "Cargo.toml" to """[package]
name = "foldcode-rust"
version = "0.1.0"
edition = "2024"

[dependencies]
""",
    "src/main.rs" to """fn main() {
    println!("Hello from FoldCode Rust!");
}
""",
)

internal val fortranProject = mapOf(
    "main.f90" to """program foldcode_hello
    implicit none
    character(len=80) :: name

    print *, "What is your name?"
    read (*, '(A)') name
    print *, "Hello, " // trim(name) // " from Fortran!"
end program foldcode_hello
""",
    "README.md" to "# FoldCode Fortran project\n\nCompile and run `main.f90` with the Fortran & COBOL extension.\n",
)

internal val cobolProject = mapOf(
    "main.cob" to """>>SOURCE FORMAT FREE
IDENTIFICATION DIVISION.
PROGRAM-ID. FOLDCODE-HELLO.
DATA DIVISION.
WORKING-STORAGE SECTION.
01 USER-NAME PIC X(60).
PROCEDURE DIVISION.
    DISPLAY "What is your name?"
    ACCEPT USER-NAME
    DISPLAY "Hello, " FUNCTION TRIM(USER-NAME) " from COBOL!"
    STOP RUN.
""",
    "README.md" to "# FoldCode COBOL project\n\nCompile and run `main.cob` with the Fortran & COBOL extension.\n",
)

internal val webProject = mapOf(
    "package.json" to """{
  "name": "foldcode-web-app",
  "private": true,
  "type": "module",
  "scripts": { "start": "node server.js" }
}
""",
    "server.js" to """import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname } from "node:path";

const types = { ".html": "text/html", ".css": "text/css", ".js": "text/javascript", ".json": "application/json" };
const server = createServer(async (request, response) => {
  try {
    const path = request.url === "/" ? "index.html" : request.url.slice(1).split("?")[0];
    response.setHeader("Content-Type", `${'$'}{types[extname(path)] || "application/octet-stream"}; charset=utf-8`);
    response.end(await readFile(new URL(`./${'$'}{path}`, import.meta.url)));
  } catch {
    response.statusCode = 404;
    response.end("Not found");
  }
});

server.on("error", error => {
  console.error(`Web server failed: ${'$'}{error.message}`);
  process.exitCode = 1;
});

server.listen(3000, "127.0.0.1", () => {
  console.log("FoldCode preview: http://127.0.0.1:3000");
});
""",
    "index.html" to """<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>FoldCode Web</title>
    <link rel="stylesheet" href="style.css">
  </head>
  <body>
    <main><h1>Hello from FoldCode</h1><button id="hello">Run JavaScript</button></main>
    <script type="module" src="app.js"></script>
  </body>
</html>
""",
    "style.css" to """body { font-family: system-ui, sans-serif; margin: 3rem; background: #181a1f; color: #d8dee9; }
button { padding: .7rem 1rem; }
""",
    "app.js" to """document.querySelector("#hello").addEventListener("click", () => {
  alert("JavaScript is running in FoldCode!");
});
""",
    "README.md" to "# FoldCode Web project\n\nInstall Web Development, then run `npm start`.\n",
)

internal val viteTypeScriptProject = mapOf(
    "package.json" to """{
  "name": "foldcode-vite-app",
  "private": true,
  "type": "module",
  "scripts": { "start": "node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 3000", "build": "node node_modules/typescript/bin/tsc && node node_modules/vite/bin/vite.js build" },
  "devDependencies": { "typescript": "^5.9.3", "vite": "^8.2.0" },
  "overrides": { "rolldown": "npm:@rolldown/browser@1.2.1", "esbuild": "npm:esbuild-wasm@0.28.1", "rollup": "npm:@rollup/wasm-node@4.62.4" }
}
""",
    "index.html" to """<!doctype html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>FoldCode Vite</title></head><body><div id="app"></div><script type="module" src="/src/main.ts"></script></body></html>
""",
    "src/main.ts" to """import "./style.css";

const app = document.querySelector<HTMLDivElement>("#app");
if (app) app.innerHTML = `<h1>Vite + TypeScript</h1><button id="count"></button>`;

let count = 0;
const counter = document.querySelector<HTMLButtonElement>("#count");
const renderCount = () => {
  if (counter) counter.textContent = `Count: ${'$'}{count}`;
};
renderCount();
counter?.addEventListener("click", () => {
  count += 1;
  renderCount();
});
""",
    "src/style.css" to """:root { font-family: system-ui, sans-serif; color: #e5e9f0; background: #181a1f; }
body { margin: 0; min-height: 100vh; display: grid; place-items: center; }
button { padding: .7rem 1rem; }
""",
    "tsconfig.json" to """{ "compilerOptions": { "target": "ES2022", "module": "ESNext", "moduleResolution": "Bundler", "strict": true, "lib": ["ES2022", "DOM"] }, "include": ["src"] }
""",
    "README.md" to "# FoldCode Vite + TypeScript project\n\nRun the project; FoldCode installs dependencies on the first run.\n",
)

internal val reactTypeScriptProject = mapOf(
    "package.json" to """{
  "name": "foldcode-react-app",
  "private": true,
  "type": "module",
  "scripts": { "start": "node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 3000", "build": "node node_modules/typescript/bin/tsc && node node_modules/vite/bin/vite.js build" },
  "dependencies": { "react": "^19.2.8", "react-dom": "^19.2.8" },
  "devDependencies": { "@types/react": "^19.2.18", "@types/react-dom": "^19.2.4", "@vitejs/plugin-react": "^6.0.5", "typescript": "^5.9.3", "vite": "^8.2.0" },
  "overrides": { "rolldown": "npm:@rolldown/browser@1.2.1", "esbuild": "npm:esbuild-wasm@0.28.1", "rollup": "npm:@rollup/wasm-node@4.62.4" }
}
""",
    "index.html" to """<!doctype html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>FoldCode React</title></head><body><div id="root"></div><script type="module" src="/src/main.tsx"></script></body></html>
""",
    "src/main.tsx" to """import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./style.css";

createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>);
""",
    "src/App.tsx" to """import { useState } from "react";

export default function App() {
  const [count, setCount] = useState(0);
  return <main><h1>React in FoldCode</h1><button onClick={() => setCount(value => value + 1)}>Count: {count}</button></main>;
}
""",
    "src/style.css" to """:root { font-family: system-ui, sans-serif; color: #e5e9f0; background: #181a1f; }
body { margin: 0; min-height: 100vh; display: grid; place-items: center; }
button { padding: .7rem 1rem; }
""",
    "tsconfig.json" to """{ "compilerOptions": { "target": "ES2022", "module": "ESNext", "moduleResolution": "Bundler", "strict": true, "jsx": "react-jsx", "lib": ["ES2022", "DOM"], "skipLibCheck": true }, "include": ["src", "vite.config.ts"] }
""",
    "vite.config.ts" to """import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
export default defineConfig({ plugins: [react()] });
""",
    "README.md" to "# FoldCode React + Vite project\n\nRun the project; FoldCode installs dependencies on the first run.\n",
)

internal fun fileGlyph(name: String): String = when {
    name == CPP_EXTENSION_TAB -> "C+"
    name == PYTHON_EXTENSION_TAB -> "Py"
    name == RUST_EXTENSION_TAB -> "Rs"
    name == GNU_LANGUAGES_EXTENSION_TAB -> "GNU"
    name == WEB_EXTENSION_TAB -> "Web"
    name == GIT_EXTENSION_TAB -> "Git"
    name == PICO_EXTENSION_TAB -> "▦"
    name == PICO_HARDWARE_APIS_TAB -> "◉"
    name == PICO_HIGH_LEVEL_APIS_TAB -> "◇"
    name == PICO_NETWORKING_LIBRARIES_TAB -> "▤"
    name == PICO_RUNTIME_INFRASTRUCTURE_TAB -> "⚙"
    name == PICO_SDK_REFERENCE_TAB -> "▤"
    name.endsWith(".rs", ignoreCase = true) -> "Rs"
    name.endsWith(".ts", true) || name.endsWith(".tsx", true) -> "TS"
    name.endsWith(".js", true) || name.endsWith(".jsx", true) || name.endsWith(".mjs", true) || name.endsWith(".cjs", true) -> "JS"
    name.endsWith(".html", true) || name.endsWith(".htm", true) -> "<>"
    name.endsWith(".css", true) -> "#"
    isFortranSource(name) -> "F"
    isCobolSource(name) -> "COB"
    name.substringAfterLast('/') in setOf("Cargo.toml", "Cargo.lock") -> "Rs"
    name.endsWith(".cpp") -> "C+"
    name.endsWith(".hpp") || name.endsWith(".h") -> "H"
    name.endsWith(".txt") -> "C"
    name.endsWith(".cmake", ignoreCase = true) || name.endsWith(".ninja", ignoreCase = true) -> "⚙"
    name.substringAfterLast('/').startsWith(".ninja_") -> "N"
    name.endsWith(".json", ignoreCase = true) || name.endsWith(".yaml", ignoreCase = true) || name.endsWith(".yml", ignoreCase = true) -> "{}"
    name.endsWith(".log", ignoreCase = true) -> "L"
    name.endsWith(".uf2", ignoreCase = true) -> "U2"
    name.endsWith(".elf", ignoreCase = true) -> "EL"
    else -> "◇"
}

internal const val PICO_EXTENSION_TAB = "Extension: Raspberry Pi Pico"
internal const val CPP_EXTENSION_TAB = "Extension: C/C++"
internal const val PYTHON_EXTENSION_TAB = "Extension: Python"
internal const val RUST_EXTENSION_TAB = "Extension: Rust"
internal const val GNU_LANGUAGES_EXTENSION_TAB = "Extension: Fortran & COBOL"
internal const val WEB_EXTENSION_TAB = "Extension: Web Development"
internal const val WEB_PREVIEW_TAB = "Web Preview"
internal const val DEFAULT_WEB_BROWSER_URL = "https://www.google.com"
internal const val GIT_EXTENSION_TAB = "Extension: Git Tools"
internal const val PICO_HARDWARE_APIS_TAB = "Pico: Hardware APIs"
internal const val PICO_HIGH_LEVEL_APIS_TAB = "Pico: High Level APIs"
internal const val PICO_NETWORKING_LIBRARIES_TAB = "Pico: Networking Libraries"
internal const val PICO_RUNTIME_INFRASTRUCTURE_TAB = "Pico: Runtime Infrastructure"
internal const val PICO_SDK_REFERENCE_TAB = "Pico: SDK Reference"

internal fun isInspectableArtifact(name: String): Boolean {
    val lower = name.lowercase()
    return listOf(
        ".uf2", ".elf", ".bin", ".o", ".obj", ".a", ".so", ".class", ".dex",
        ".jar", ".zip", ".apk", ".aab", ".ninja_deps",
    ).any(lower::endsWith)
}

/** Text formats commonly found in source trees and CMake/Ninja build directories. */
internal fun isEditableProjectTextFile(name: String): Boolean {
    val base = name.substringAfterLast('/')
    val lower = base.lowercase()
    if (lower == ".ninja_deps") return false // Ninja stores this database as binary data.
    if (base in setOf(
            "CMakeLists.txt", "CMakeCache.txt", "Makefile", "README", "LICENSE",
            "Dockerfile", ".gitignore", ".gitattributes", ".ninja_log",
            "Cargo.toml", "Cargo.lock", "rust-toolchain", "rustfmt.toml",
        )
    ) return true
    val normalized = name.replace('\\', '/')
    if (normalized == ".cargo/config" || normalized.endsWith("/.cargo/config")) return true
    return listOf(
        // Source, linker, assembler and build-system inputs.
        ".cpp", ".cc", ".cxx", ".c", ".s", ".asm", ".h", ".hpp", ".hh", ".hxx",
        ".rs", ".ron", ".f", ".for", ".f77", ".f90", ".f95", ".f03", ".f08", ".f18",
        ".cob", ".cbl", ".cpy", ".x", ".inc", ".in", ".pio", ".cmake", ".ninja", ".make", ".mk", ".ld", ".lds",
        ".rsp", ".d", ".dep",
        // Configuration and structured text emitted or consumed by build tools.
        ".txt", ".md", ".json", ".json5", ".xml", ".yaml", ".yml", ".toml",
        ".ini", ".cfg", ".conf", ".properties", ".gradle", ".kts",
        // Scripts and other useful textual build outputs.
        ".kt", ".java", ".py", ".sh", ".bash", ".zsh", ".js", ".mjs", ".cjs", ".jsx", ".ts", ".tsx", ".css", ".scss", ".html", ".htm",
        ".log", ".map", ".lst", ".hex", ".csv", ".tsv", ".pem", ".marks",
        ".manifest", ".stamp", ".version", ".args",
    ).any(lower::endsWith)
}

internal fun fileColor(name: String): Color = when {
    name.endsWith(".rs", ignoreCase = true) || name.substringAfterLast('/') in setOf("Cargo.toml", "Cargo.lock") -> Color(0xFFE49B5D)
    name.endsWith(".cpp") -> Color(0xFF73A9FF)
    name.endsWith(".ts", true) || name.endsWith(".tsx", true) -> Color(0xFF5DADE2)
    name.endsWith(".js", true) || name.endsWith(".jsx", true) || name.endsWith(".mjs", true) || name.endsWith(".cjs", true) -> Color(0xFFE5D252)
    name.endsWith(".html", true) || name.endsWith(".htm", true) -> Color(0xFFE07A5F)
    name.endsWith(".css", true) -> Color(0xFF73A9FF)
    isFortranSource(name) -> Color(0xFF8E7CC3)
    isCobolSource(name) -> Color(0xFF4EA5A1)
    name.endsWith(".hpp") || name.endsWith(".h") -> Color(0xFFB48EED)
    name == "CMakeLists.txt" -> Color(0xFF82C99A)
    else -> Muted
}

internal fun validatedProjectPath(value: String): String {
    val path = value.trim().replace('\\', '/').trim('/')
    require(path.isNotBlank()) { "Enter a relative path" }
    require(!path.startsWith('.') && path.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Path must stay inside the project" }
    require(path.none { it in setOf(':', '*', '?', '"', '<', '>', '|') }) { "Path contains unsupported characters" }
    return path
}

internal fun validatedEntryName(value: String): String {
    val name = value.trim()
    require(name.isNotBlank()) { "Enter a name" }
    require(name !in setOf(".", "..") && '/' !in name && '\\' !in name) { "Enter a name, not a path" }
    require(name.none { it in setOf(':', '*', '?', '"', '<', '>', '|') }) { "Name contains unsupported characters" }
    return name
}

internal fun parentFolderPaths(path: String): List<String> {
    val parts = path.replace('\\', '/').split('/').dropLast(1)
    return parts.indices.map { parts.take(it + 1).joinToString("/") }
}

internal fun isSupportedSource(name: String): Boolean {
    return isEditableProjectTextFile(name)
}
