package dev.foldcode.ide

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

internal data class FoldCodeExtensionInfo(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val publisher: String,
    val installedBytes: Long,
    val installedComponents: Int,
    val totalComponents: Int,
    val offlineReady: Boolean,
    val components: List<FoldCodeExtensionComponent>,
    val provides: Set<String>,
)

internal data class FoldCodeExtensionComponent(
    val id: String,
    val name: String,
    val installed: Boolean,
    val availableOffline: Boolean,
)

/** Installs manifest/hash-checked extension payloads independently from the APK. */
internal class FoldCodeExtensionManager(private val context: Context) {
    private val extensionsRoot = File(context.filesDir, "extensions")

    init {
        extensionsRoot.mkdirs()
    }

    /** Non-critical cleanup and size accounting must never delay the first frame. */
    @Synchronized
    fun performDeferredMaintenance() {
        val maintenanceMarker = File(context.filesDir, ".extension-maintenance-v1")
        if (!maintenanceMarker.isFile) {
            // These are validation artifacts, never user projects or runtime inputs.
            File(context.filesDir, "pico-cmake-smoke-sysroot").deleteRecursively()
            File(context.filesDir, "cmake-smoke").deleteRecursively()
            listOf("rp2040", "rp2350", "rp2350-riscv").forEach { platform ->
                val root = File(context.filesDir, "pico-cmake-sysroot-$platform")
                if (File(root, ".version").takeIf(File::isFile)?.readText()?.contains("shared-headers-v3") != true) {
                    deleteGeneratedPicoSysrootSafely(platform)
                }
            }
            // Upstream Mbed TLS validation data is not linked by pico_mbedtls. Keep the
            // complete TLS implementation, headers, CMake integration and programs.
            File(context.filesDir, "pico-cmake-sdk/sdk/lib/mbedtls/tests").deleteRecursively()
            if (!isPicoInstalled()) removeExtractedPicoSdks()
            if (!isCppInstalled()) File(context.filesDir, CPP_RUNTIME_DIRECTORY).deleteRecursively()
            if (!isPythonInstalled()) File(context.filesDir, PYTHON_RUNTIME_DIRECTORY).deleteRecursively()
            if (!isRustInstalled()) File(context.filesDir, RUST_RUNTIME_DIRECTORY).deleteRecursively()
            if (!isGnuLanguagesInstalled()) File(context.filesDir, GNU_LANGUAGES_RUNTIME_DIRECTORY).deleteRecursively()
            if (!isWebInstalled()) File(context.filesDir, WEB_RUNTIME_DIRECTORY).deleteRecursively()
            // Git is a base FoldCode service now. Remove the obsolete JGit package
            // and extracted dex left by installations made before native Git 2.55.
            File(extensionsRoot, GIT_EXTENSION_ID).deleteRecursively()
            File(context.filesDir, GIT_RUNTIME_DIRECTORY).deleteRecursively()
            if (!isGnuArmInstalled()) File(context.filesDir, GNU_ARM_RUNTIME_DIRECTORY).deleteRecursively()
            maintenanceMarker.writeText("complete")
        }
        invalidateDamagedPicoToolchains()
        refreshMissingInstalledSizeCaches()
    }

    fun picoInfo(): FoldCodeExtensionInfo? = installedInfo(PICO_EXTENSION_ID)

    fun cppInfo(): FoldCodeExtensionInfo? = installedInfo(CPP_EXTENSION_ID)

    fun pythonInfo(): FoldCodeExtensionInfo? = installedInfo(PYTHON_EXTENSION_ID)

    fun gitInfo(): FoldCodeExtensionInfo? = installedInfo(GIT_EXTENSION_ID)

    fun gnuArmInfo(): FoldCodeExtensionInfo? = installedInfo(GNU_ARM_EXTENSION_ID)

    fun rustInfo(): FoldCodeExtensionInfo? = installedInfo(RUST_EXTENSION_ID)

    fun gnuLanguagesInfo(): FoldCodeExtensionInfo? = installedInfo(GNU_LANGUAGES_EXTENSION_ID)

    fun webInfo(): FoldCodeExtensionInfo? = installedInfo(WEB_EXTENSION_ID)

    fun isPicoInstalled(): Boolean = picoInfo() != null

    fun isCppInstalled(): Boolean = cppInfo() != null

    fun isPythonInstalled(): Boolean = pythonInfo() != null

    fun isGitInstalled(): Boolean = gitInfo() != null

    fun isGnuArmInstalled(): Boolean = gnuArmInfo() != null

    fun isRustInstalled(): Boolean = rustInfo() != null

    fun isGnuLanguagesInstalled(): Boolean = gnuLanguagesInfo() != null

    fun isWebInstalled(): Boolean = webInfo() != null

    fun installWebRuntime(): FoldCodeExtensionInfo {
        val installed = webInfo() ?: error("Install the Web Development extension first")
        val runtime = File(context.filesDir, WEB_RUNTIME_DIRECTORY)
        val staging = File(context.filesDir, "$WEB_RUNTIME_DIRECTORY.installing")
        staging.deleteRecursively()
        materializePayload(WEB_EXTENSION_ID, WEB_PAYLOAD, staging)
        val nodeExecutable = File(staging, "bin/node")
        val nodeLibrary = File(staging, "lib/libnode.so")
        require(nodeExecutable.isFile || nodeLibrary.isFile) { "Web extension is missing Node.js" }
        if (nodeExecutable.isFile) {
            require(nodeExecutable.setExecutable(true, false) || nodeExecutable.canExecute()) {
                "Cannot make Node.js executable"
            }
        }
        require(File(staging, "lib/node_modules/npm/bin/npm-cli.js").isFile) { "Web extension is missing npm" }
        require(File(staging, "lib/node_modules/npm/bin/npx-cli.js").isFile) { "Web extension is missing npx" }
        require(File(staging, "lib/node_modules/typescript-language-server/lib/cli.mjs").isFile) {
            "Web extension is missing TypeScript Language Server"
        }
        require(File(staging, "lib/node_modules/typescript/lib/tsserver.js").isFile) {
            "Web extension is missing TypeScript"
        }
        listOf(
            "vscode-html-language-server",
            "vscode-css-language-server",
            "vscode-json-language-server",
        ).forEach { executable ->
            require(
                listOf(
                    File(staging, "lib/node_modules/@zed-industries/vscode-langservers-extracted/bin/$executable"),
                    File(staging, "lib/node_modules/vscode-langservers-extracted/bin/$executable"),
                ).any(File::isFile),
            ) { "Web extension is missing $executable" }
        }
        listOf(
            "html-language-server/node/htmlServerMain.js",
            "css-language-server/node/cssServerMain.js",
            "json-language-server/node/jsonServerMain.js",
        ).forEach { serverMain ->
            require(
                File(staging, "lib/node_modules/vscode-langservers-extracted/lib/$serverMain").isFile,
            ) { "Web extension is missing the $serverMain implementation" }
        }
        require(listOf(
            File(staging, "lib/js-debug/src/dapDebugServer.js"),
            File(staging, "lib/node_modules/@vscode/js-debug/src/dapDebugServer.js"),
            File(staging, "lib/node_modules/vscode-js-debug/src/dapDebugServer.js"),
        ).any(File::isFile)) { "Web extension is missing the V8 Debug Adapter" }
        File(staging, "tmp").mkdirs()
        // Extension payloads are complete versioned runtimes, not overlays.
        // Replace the old tree only after validating the new one so files
        // removed by npm or Node upgrades cannot survive into the next version.
        runtime.deleteRecursively()
        require(staging.renameTo(runtime)) { "Could not activate the Web extension runtime" }
        return installed
    }

    fun installCppRuntime(): FoldCodeExtensionInfo {
        require(isCppInstalled()) { "Install the C/C++ compiler extension first" }
        materializePayload(CPP_EXTENSION_ID, CPP_PAYLOAD, File(context.filesDir, CPP_RUNTIME_DIRECTORY))
        return cppInfo() ?: error("C/C++ extension disappeared while installing its runtime")
    }

    fun installPythonRuntime(): FoldCodeExtensionInfo {
        require(isPythonInstalled()) { "Install the Python extension first" }
        materializePayload(PYTHON_EXTENSION_ID, PYTHON_PAYLOAD, File(context.filesDir, PYTHON_RUNTIME_DIRECTORY))
        return pythonInfo() ?: error("Python extension disappeared while installing its runtime")
    }

    fun installRustRuntime(): FoldCodeExtensionInfo {
        val installed = rustInfo() ?: error("Install the Rust extension first")
        val runtime = File(context.filesDir, RUST_RUNTIME_DIRECTORY)
        materializePayload(RUST_EXTENSION_ID, RUST_PAYLOAD, runtime)
        val rootfs = File(runtime, "rootfs")
        val toolchain = File(runtime, "toolchain")
        val ready = File(runtime, ".foldcode-ready")
        if (ready.takeIf(File::isFile)?.readText() == installed.version &&
            File(rootfs, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").isFile &&
            File(toolchain, "lib/rustlib/src/rust/library/std/src/lib.rs").isFile &&
            listOf("rustc", "cargo", "rust-analyzer", "rustfmt")
                .all { File(toolchain, "bin/$it").canExecute() }
        ) return installed
        require(File(rootfs, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").isFile) {
            "Rust extension is missing its glibc runtime"
        }
        listOf("rustc", "cargo", "rust-analyzer", "rustfmt").forEach { name ->
            File(toolchain, "bin/$name").takeIf(File::isFile)?.let { executable ->
                require(executable.setExecutable(true, false) || executable.canExecute()) {
                    "Cannot make Rust tool executable: $name"
                }
            }
        }
        require(listOf("rustc", "cargo", "rust-analyzer", "rustfmt").all { File(toolchain, "bin/$it").isFile }) {
            "Rust extension is missing rustc, Cargo, rustfmt or rust-analyzer"
        }
        require(listOf("thumbv6m-none-eabi", "thumbv8m.main-none-eabihf", "riscv32imac-unknown-none-elf")
            .all { File(toolchain, "lib/rustlib/$it/lib").isDirectory }) {
            "Rust extension is missing one or more Pico compilation targets"
        }
        require(File(toolchain, "lib/rustlib/src/rust/library/std/src/lib.rs").isFile) {
            "Rust extension is missing rust-src required by rust-analyzer"
        }
        // ZipInputStream does not restore Unix links or mode bits. Rust's
        // Linux host tools use /lib/ld-linux-aarch64.so.1 and rustc launches
        // helper executables below libexec and rustlib/*/bin.
        val lib = File(rootfs, "lib")
        if (lib.exists() && !Files.isSymbolicLink(lib.toPath())) lib.deleteRecursively()
        if (!lib.exists()) Files.createSymbolicLink(lib.toPath(), File("usr/lib").toPath())
        val bin = File(rootfs, "bin")
        if (bin.exists() && !Files.isSymbolicLink(bin.toPath())) bin.deleteRecursively()
        if (!bin.exists()) Files.createSymbolicLink(bin.toPath(), File("usr/bin").toPath())
        listOf(
            File(rootfs, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"),
            File(rootfs, "usr/lib/ld-linux-aarch64.so.1"),
        ).filter(File::isFile).forEach { loader -> loader.setExecutable(true, false) }
        listOf("foldrust-env", "cc").forEach { name ->
            File(rootfs, "usr/bin/$name").takeIf(File::isFile)?.let { executable ->
                require(executable.setExecutable(true, false) || executable.canExecute()) {
                    "Cannot make Rust runtime helper executable: $name"
                }
            }
        }
        toolchain.walkTopDown()
            .filter(File::isFile)
            .filter { executable ->
                val relative = executable.relativeTo(toolchain).invariantSeparatorsPath
                relative.startsWith("bin/") ||
                    relative.startsWith("libexec/") ||
                    "/bin/" in relative
            }
            .forEach { executable ->
                require(executable.setExecutable(true, false) || executable.canExecute()) {
                    "Cannot make Rust tool executable: ${executable.name}"
                }
            }
        listOf("opt/rust", "storage/emulated/0", "data/data/dev.foldcode.ide", "data/user/0/dev.foldcode.ide")
            .forEach { File(rootfs, it).mkdirs() }
        File(runtime, "tmp").mkdirs()
        ready.writeText(installed.version)
        return installed
    }

    fun installGnuLanguagesRuntime(): FoldCodeExtensionInfo {
        val installed = gnuLanguagesInfo() ?: error("Install the Fortran & COBOL extension first")
        val runtime = File(context.filesDir, GNU_LANGUAGES_RUNTIME_DIRECTORY)
        materializePayload(GNU_LANGUAGES_EXTENSION_ID, GNU_LANGUAGES_PAYLOAD, runtime)
        val rootfs = File(runtime, "rootfs")
        val loader = File(rootfs, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
        require(loader.isFile) { "Fortran & COBOL extension is missing its glibc loader" }
        require(File(rootfs, "usr/bin/gfortran").isFile) { "Fortran compiler is missing" }
        require(File(rootfs, "usr/bin/cobc").isFile) { "GnuCOBOL compiler is missing" }
        require(File(rootfs, "usr/lib/libfoldspawn.so").isFile) {
            "Fortran & COBOL extension is missing its Android spawn bridge"
        }
        require(File(rootfs, "usr/lib/aarch64-linux-gnu/libgfortran.so.5").isFile) {
            "Fortran runtime library is missing"
        }
        require(rootfs.walkTopDown().any { it.isFile && it.name.startsWith("libcob") && ".so" in it.name }) {
            "GnuCOBOL runtime library is missing"
        }
        val gccInternalExecutables = setOf("cc1", "cc1plus", "collect2", "f951", "lto1", "lto-wrapper")
        listOf(File(rootfs, "usr/bin"), File(rootfs, "usr/lib/gcc"), File(rootfs, "usr/libexec"))
            .filter(File::isDirectory)
            .forEach { directory ->
                directory.walkTopDown().filter(File::isFile).forEach { executable ->
                    val relative = executable.relativeTo(rootfs).invariantSeparatorsPath
                    if (
                        relative.startsWith("usr/bin/") ||
                        "/libexec/" in relative ||
                        executable.name in gccInternalExecutables
                    ) {
                        require(executable.setExecutable(true, false) || executable.canExecute()) {
                            "Cannot make language tool executable: ${executable.name}"
                        }
                    }
                }
            }
        val lib = File(rootfs, "lib")
        if (lib.exists() && !Files.isSymbolicLink(lib.toPath())) lib.deleteRecursively()
        if (!lib.exists()) Files.createSymbolicLink(lib.toPath(), File("usr/lib").toPath())
        val bin = File(rootfs, "bin")
        if (bin.exists() && !Files.isSymbolicLink(bin.toPath())) bin.deleteRecursively()
        if (!bin.exists()) Files.createSymbolicLink(bin.toPath(), File("usr/bin").toPath())
        listOf("storage/emulated/0", "data/data/dev.foldcode.ide", "data/user/0/dev.foldcode.ide", "tmp")
            .forEach { File(rootfs, it).mkdirs() }
        File(runtime, "tmp").mkdirs()
        loader.setExecutable(true, false)
        return installed
    }

    fun installGitRuntime(): FoldCodeExtensionInfo {
        require(isGitInstalled()) { "Install the Git Tools extension first" }
        materializePayload(GIT_EXTENSION_ID, GIT_PAYLOAD, File(context.filesDir, GIT_RUNTIME_DIRECTORY))
        protectGitRuntimeCode()
        return gitInfo() ?: error("Git Tools extension disappeared while installing its runtime")
    }

    private fun protectGitRuntimeCode() {
        val dex = File(context.filesDir, "$GIT_RUNTIME_DIRECTORY/git-tools.dex.jar")
        if (!dex.isFile) return
        require(dex.setReadable(true, true) || dex.canRead()) { "Cannot make Git Tools runtime readable" }
        require(dex.setWritable(false, true) || !dex.canWrite()) { "Cannot protect Git Tools runtime code" }
    }

    fun installGnuArmRuntime(): FoldCodeExtensionInfo {
        require(isGnuArmInstalled()) { "Install the GNU Arm Embedded extension first" }
        val runtime = File(context.filesDir, GNU_ARM_RUNTIME_DIRECTORY)
        materializePayload(GNU_ARM_EXTENSION_ID, GNU_ARM_PAYLOAD, runtime)
        val rootfs = File(runtime, "rootfs")
        val toolchain = File(runtime, "toolchain")
        require(File(rootfs, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").isFile) {
            "GNU Arm extension is missing its glibc loader"
        }
        require(File(toolchain, "bin/arm-none-eabi-gcc").isFile) {
            "GNU Arm extension is missing arm-none-eabi-gcc"
        }
        // The relocatable Arm distribution invokes binutils by their bare
        // names after relocation. Provide those names inside the guest prefix;
        // they remain private to the GNU extension and never shadow Android.
        listOf("as", "ld", "ld.bfd", "ar", "ranlib", "nm", "objcopy", "objdump", "readelf", "size", "strings", "strip")
            .forEach { command ->
                val alias = File(toolchain, "bin/$command")
                val target = File(toolchain, "bin/arm-none-eabi-$command")
                if (target.isFile && !alias.exists()) Files.createSymbolicLink(alias.toPath(), File(target.name).toPath())
            }
        // ZipInputStream intentionally uses portable file extraction and does
        // not restore Unix mode bits. Restore executable modes for the Linux
        // host tools before PRoot attempts to launch GCC and its subprocesses.
        listOf(File(toolchain, "bin"), File(toolchain, "libexec")).forEach { directory ->
            directory.walkTopDown().filter(File::isFile).forEach { executable ->
                require(executable.setExecutable(true, false) || executable.canExecute()) {
                    "Cannot make GNU Arm tool executable: ${executable.name}"
                }
            }
        }
        listOf(
            "opt/gcc",
            "storage/emulated/0",
            "data/data/dev.foldcode.ide",
            "data/user/0/dev.foldcode.ide",
        ).forEach { File(rootfs, it).mkdirs() }
        File(runtime, "tmp").mkdirs()
        val lib = File(rootfs, "lib")
        // Packaging may dereference this Linux compatibility symlink. Replace
        // that duplicate directory with the canonical guest /lib -> /usr/lib.
        if (lib.exists() && !Files.isSymbolicLink(lib.toPath())) lib.deleteRecursively()
        if (!lib.exists()) Files.createSymbolicLink(lib.toPath(), File("usr/lib").toPath())
        File(rootfs, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").setExecutable(true, false)
        return gnuArmInfo() ?: error("GNU Arm extension disappeared while installing its runtime")
    }

    fun preparePicoComponents(
        componentIds: Set<String>,
        onProgress: (name: String, completed: Int, total: Int) -> Unit,
    ): FoldCodeExtensionInfo {
        require(isPicoInstalled()) { "Install the Raspberry Pi Pico extension first" }
        require(componentIds.isNotEmpty()) { "Select at least one component" }
        require(componentIds.all { requested -> PICO_COMPONENTS.any { it.first == requested } }) {
            "Unknown Pico component selection"
        }
        val selectedBoards = PicoBoard.entries.filter { it.bundleId in componentIds }
        val selectedMicroPythonBoards = if (MICROPYTHON_COMPONENT_ID in componentIds) {
            PicoBoard.entries
        } else {
            emptyList()
        }
        // Every board profile depends on the common SDK and CMake data.
        val tasks = buildList<Pair<String, () -> Unit>> {
            if ("core" in componentIds || selectedBoards.isNotEmpty()) {
                add("Core SDK and CMake" to { PicoCMakeSdkBundle(context).install() })
                add("Picotool USB runtime" to { installPicoToolsRuntime() })
            }
            selectedBoards.forEach { board ->
                add(board.displayName to { PicoSdkBundle(context, board).install() })
            }
            selectedMicroPythonBoards.forEach { board ->
                add("MicroPython · ${board.displayName}" to {
                    materializePayload(
                        PICO_EXTENSION_ID,
                        "micropython-${board.bundleId}.zip",
                        File(context.filesDir, "micropython-firmware/${board.bundleId}"),
                    )
                })
            }
        }
        tasks.forEachIndexed { index, task ->
            onProgress(task.first, index, tasks.size)
            task.second()
            onProgress(task.first, index + 1, tasks.size)
        }
        return picoInfo()
            ?: error("Extension disappeared while preparing offline components")
    }

    fun deletePicoComponents(componentIds: Set<String>): FoldCodeExtensionInfo {
        require(isPicoInstalled()) { "Install the Raspberry Pi Pico extension first" }
        require(componentIds.isNotEmpty()) { "Select at least one component" }
        require(componentIds.all { requested -> PICO_COMPONENTS.any { it.first == requested } }) {
            "Unknown Pico component selection"
        }
        val extensionRoot = File(extensionsRoot, PICO_EXTENSION_ID)
        val consumedRoot = File(extensionRoot, CONSUMED_DIRECTORY)
        val selectedBoards = PicoBoard.entries.filter { it.bundleId in componentIds }
        val selectedMicroPythonBoards = if (MICROPYTHON_COMPONENT_ID in componentIds) {
            PicoBoard.entries
        } else {
            emptyList()
        }

        if ("core" in componentIds) {
            File(context.filesDir, "pico-cmake-sdk").deleteRecursively()
            File(context.filesDir, PICO_TOOLS_RUNTIME_DIRECTORY).deleteRecursively()
            File(context.filesDir, "usr").deleteRecursively()
            File(consumedRoot, "pico-cmake-sdk.zip").delete()
            File(consumedRoot, "cmake-data.zip").delete()
            File(consumedRoot, PICO_TOOLS_PAYLOAD).delete()
            listOf("rp2040", "rp2350", "rp2350-riscv")
                .forEach(::deleteGeneratedPicoSysrootSafely)
        }
        selectedBoards.forEach { board ->
            File(context.filesDir, "pico-sdk-${board.bundleId}").deleteRecursively()
            File(consumedRoot, "pico-profile-${board.bundleId}.zip").delete()
        }
        selectedMicroPythonBoards.forEach { board ->
            File(context.filesDir, "micropython-firmware/${board.bundleId}").deleteRecursively()
            File(consumedRoot, "micropython-${board.bundleId}.zip").delete()
        }

        val remainingBoards = PicoBoard.entries.filterNot { it in selectedBoards }.filter { board ->
            File(consumedRoot, "pico-profile-${board.bundleId}.zip").isFile
        }
        if (remainingBoards.none { !it.chip.riscV }) {
            File(context.filesDir, "pico-toolchain-arm").deleteRecursively()
            File(consumedRoot, "pico-toolchain-arm.zip").delete()
        }
        if (remainingBoards.none { it.chip.riscV }) {
            File(context.filesDir, "pico-toolchain-riscv").deleteRecursively()
            File(consumedRoot, "pico-toolchain-riscv.zip").delete()
        }
        PicoBoard.entries.map { it.platform }.distinct().forEach { platform ->
            if (remainingBoards.none { it.platform == platform }) {
                deleteGeneratedPicoSysrootSafely(platform)
            }
        }
        invalidateInstalledSize(PICO_EXTENSION_ID)
        return picoInfo() ?: error("Extension metadata became unavailable after component removal")
    }

    fun install(input: InputStream): FoldCodeExtensionInfo {
        val staging = File(extensionsRoot, ".staging-${UUID.randomUUID()}")
        require(staging.mkdirs()) { "Could not create extension staging directory" }
        try {
            extractPackage(input, staging)
            val manifest = parseAndValidate(staging, verifyChecksums = true)
            require(isSupportedExtension(manifest.id)) {
                "Unsupported extension: ${manifest.id} (${manifest.id.toCharArray().joinToString { it.code.toString(16) }})"
            }
            if (manifest.id == PICO_EXTENSION_ID) require(manifest.version == PicoSdkRelease.extensionVersion) {
                "This FoldCode build requires Pico extension ${PicoSdkRelease.extensionVersion}"
            }
            File(staging, VALIDATION_MARKER).writeText(manifest.version)
            val target = File(extensionsRoot, manifest.id)
            val backup = File(extensionsRoot, ".backup-${manifest.id}")
            if (backup.exists()) backup.deleteRecursively()
            if (target.exists()) require(target.renameTo(backup)) { "Could not prepare extension update" }
            if (!staging.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                error("Could not activate extension")
            }
            backup.deleteRecursively()
            if (manifest.id == PICO_EXTENSION_ID) removeExtractedPicoSdks()
            if (manifest.id == CPP_EXTENSION_ID) File(context.filesDir, CPP_RUNTIME_DIRECTORY).deleteRecursively()
            if (manifest.id == PYTHON_EXTENSION_ID) File(context.filesDir, PYTHON_RUNTIME_DIRECTORY).deleteRecursively()
            if (manifest.id == GIT_EXTENSION_ID) File(context.filesDir, GIT_RUNTIME_DIRECTORY).deleteRecursively()
            if (manifest.id == GNU_ARM_EXTENSION_ID) File(context.filesDir, GNU_ARM_RUNTIME_DIRECTORY).deleteRecursively()
            if (manifest.id == RUST_EXTENSION_ID) File(context.filesDir, RUST_RUNTIME_DIRECTORY).deleteRecursively()
            if (manifest.id == GNU_LANGUAGES_EXTENSION_ID) File(context.filesDir, GNU_LANGUAGES_RUNTIME_DIRECTORY).deleteRecursively()
            if (manifest.id == WEB_EXTENSION_ID) File(context.filesDir, WEB_RUNTIME_DIRECTORY).deleteRecursively()
            invalidateInstalledSize(manifest.id)
            return installedInfo(manifest.id) ?: error("Extension activation failed")
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    fun uninstallPico(): Boolean {
        val removed = File(extensionsRoot, PICO_EXTENSION_ID).let { !it.exists() || it.deleteRecursively() }
        removeExtractedPicoSdks()
        invalidateInstalledSize(PICO_EXTENSION_ID)
        return removed
    }

    fun uninstallCpp(): Boolean {
        // Pico's native build layer consumes the same Clang/LLVM host runtime.
        require(!isPicoInstalled()) { "Remove the Raspberry Pi Pico extension before removing C/C++" }
        val removed = File(extensionsRoot, CPP_EXTENSION_ID).let { !it.exists() || it.deleteRecursively() }
        File(context.filesDir, CPP_RUNTIME_DIRECTORY).deleteRecursively()
        invalidateInstalledSize(CPP_EXTENSION_ID)
        return removed
    }

    fun uninstallPython(): Boolean {
        val removed = File(extensionsRoot, PYTHON_EXTENSION_ID).let { !it.exists() || it.deleteRecursively() }
        File(context.filesDir, PYTHON_RUNTIME_DIRECTORY).deleteRecursively()
        invalidateInstalledSize(PYTHON_EXTENSION_ID)
        return removed
    }

    fun uninstallGit(): Boolean {
        val removed = File(extensionsRoot, GIT_EXTENSION_ID).let { !it.exists() || it.deleteRecursively() }
        File(context.filesDir, GIT_RUNTIME_DIRECTORY).deleteRecursively()
        invalidateInstalledSize(GIT_EXTENSION_ID)
        return removed
    }

    fun uninstallGnuArm(): Boolean {
        val removed = File(extensionsRoot, GNU_ARM_EXTENSION_ID).let { !it.exists() || it.deleteRecursively() }
        File(context.filesDir, GNU_ARM_RUNTIME_DIRECTORY).deleteRecursively()
        invalidateInstalledSize(GNU_ARM_EXTENSION_ID)
        return removed
    }

    fun uninstallRust(): Boolean {
        val removed = File(extensionsRoot, RUST_EXTENSION_ID).let { !it.exists() || it.deleteRecursively() }
        File(context.filesDir, RUST_RUNTIME_DIRECTORY).deleteRecursively()
        invalidateInstalledSize(RUST_EXTENSION_ID)
        return removed
    }

    fun uninstallGnuLanguages(): Boolean {
        val removed = File(extensionsRoot, GNU_LANGUAGES_EXTENSION_ID).let { !it.exists() || it.deleteRecursively() }
        File(context.filesDir, GNU_LANGUAGES_RUNTIME_DIRECTORY).deleteRecursively()
        invalidateInstalledSize(GNU_LANGUAGES_EXTENSION_ID)
        return removed
    }

    fun uninstallWeb(): Boolean {
        val removed = File(extensionsRoot, WEB_EXTENSION_ID).let { !it.exists() || it.deleteRecursively() }
        File(context.filesDir, WEB_RUNTIME_DIRECTORY).deleteRecursively()
        invalidateInstalledSize(WEB_EXTENSION_ID)
        return removed
    }

    fun missingPayloads(extensionId: String, componentIds: Set<String>): Set<String> {
        val root = File(extensionsRoot, extensionId)
        val manifest = parseAndValidate(root, verifyChecksums = false)
        val requested = when (extensionId) {
            CPP_EXTENSION_ID -> setOf(CPP_PAYLOAD)
            PYTHON_EXTENSION_ID -> setOf(PYTHON_PAYLOAD)
            GIT_EXTENSION_ID -> setOf(GIT_PAYLOAD)
            GNU_ARM_EXTENSION_ID -> setOf(GNU_ARM_PAYLOAD)
            RUST_EXTENSION_ID -> setOf(RUST_PAYLOAD)
            GNU_LANGUAGES_EXTENSION_ID -> setOf(GNU_LANGUAGES_PAYLOAD)
            WEB_EXTENSION_ID -> setOf(WEB_PAYLOAD)
            PICO_EXTENSION_ID -> picoPayloadsForComponents(componentIds)
            else -> error("Unsupported extension: $extensionId")
        }
        return requested.filterTo(linkedSetOf()) { name ->
            name in manifest.payloads &&
                !File(root, "payload/$name").isFile &&
                !File(root, "$CONSUMED_DIRECTORY/$name").isFile &&
                !isMaterializedPayloadCurrent(extensionId, name, manifest.version)
        }
    }

    /** Adds one independently downloaded, manifest-hash-verified payload atomically. */
    fun receivePayload(extensionId: String, fileName: String, input: InputStream) {
        val root = File(extensionsRoot, extensionId).canonicalFile
        val manifest = parseAndValidate(root, verifyChecksums = false)
        val expected = manifest.payloads[fileName] ?: error("Unknown extension payload: $fileName")
        require(PAYLOAD_FILE.matches(fileName)) { "Unsafe extension payload name" }
        val payloadRoot = File(root, "payload").apply { mkdirs() }
        val temporary = File(payloadRoot, ".$fileName-${UUID.randomUUID()}")
        input.use { source -> temporary.outputStream().buffered().use(source::copyTo) }
        try {
            require(temporary.length() in 1..MAX_PACKAGE_BYTES) { "Extension payload has an invalid size" }
            require(temporary.sha256().equals(expected, ignoreCase = true)) {
                "Extension payload checksum failed: $fileName"
            }
            val target = File(payloadRoot, fileName)
            target.delete()
            require(temporary.renameTo(target)) { "Could not activate extension payload" }
            invalidateInstalledSize(extensionId)
        } finally {
            temporary.delete()
        }
    }

    fun materializePicoPayload(fileName: String, destination: File) {
        require(fileName in REQUIRED_PICO_PAYLOADS) { "Unknown Pico extension payload" }
        materializePayload(PICO_EXTENSION_ID, fileName, destination)
    }

    private fun installPicoToolsRuntime() {
        val runtime = File(context.filesDir, PICO_TOOLS_RUNTIME_DIRECTORY)
        materializePayload(PICO_EXTENSION_ID, PICO_TOOLS_PAYLOAD, runtime)
        val executable = File(runtime, "bin/picotool")
        val libusb = File(runtime, "lib/libusb1.0.so")
        require(executable.isFile && libusb.isFile) { "Pico extension is missing the picotool runtime" }
        require(executable.setExecutable(true, false) || executable.canExecute()) {
            "Cannot make picotool executable"
        }
        File(runtime, "tmp").mkdirs()
    }

    private fun materializePayload(extensionId: String, fileName: String, destination: File) {
        val root = File(extensionsRoot, extensionId).canonicalFile
        val payload = File(root, "payload/$fileName").canonicalFile
        val manifest = parseAndValidate(root, verifyChecksums = false)
        val destinationMarker = File(destination, PAYLOAD_MARKER)
        if (destinationMarker.takeIf(File::isFile)?.let { runCatching(it::readText).getOrNull() } ==
            "${manifest.version}:$fileName"
        ) {
            markPayloadConsumed(root, payload, fileName, manifest.version)
            return
        }
        require(payload.toPath().startsWith(root.toPath()) && payload.isFile) {
            "Pico extension payload is unavailable: $fileName"
        }
        val staging = File(destination.parentFile, ".${destination.name}-${UUID.randomUUID()}")
        require(staging.mkdirs()) { "Could not create SDK staging directory" }
        try {
            extractArchive(
                payload.inputStream(),
                staging,
                when (extensionId) {
                    GNU_ARM_EXTENSION_ID -> MAX_GNU_ARM_EXTRACTED_PAYLOAD_BYTES
                    RUST_EXTENSION_ID -> MAX_RUST_EXTRACTED_PAYLOAD_BYTES
                    GNU_LANGUAGES_EXTENSION_ID -> MAX_GNU_LANGUAGES_EXTRACTED_PAYLOAD_BYTES
                    WEB_EXTENSION_ID -> MAX_WEB_EXTRACTED_PAYLOAD_BYTES
                    else -> MAX_EXTRACTED_PAYLOAD_BYTES
                },
            )
            destinationMarker.takeIf(File::exists)?.delete()
            if (destination.exists()) require(destination.deleteRecursively()) { "Could not replace SDK cache" }
            File(staging, PAYLOAD_MARKER).writeText("${manifest.version}:$fileName")
            require(staging.renameTo(destination)) { "Could not activate SDK cache" }
            markPayloadConsumed(root, payload, fileName, manifest.version)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /**
     * Payload extraction and its small bookkeeping write are separate filesystem operations.
     * If Android kills FoldCode between them, the extracted runtime is still valid. Repair the
     * consumed marker instead of requiring the user to download and extract the same archive again.
     */
    private fun markPayloadConsumed(root: File, payload: File, fileName: String, version: String) {
        val consumed = File(root, "$CONSUMED_DIRECTORY/$fileName")
        consumed.parentFile?.mkdirs()
        consumed.writeText(version)
        payload.delete()
        invalidateInstalledSize(root.name)
    }

    private fun isMaterializedPayloadCurrent(
        extensionId: String,
        fileName: String,
        version: String,
    ): Boolean {
        if (extensionId != PICO_EXTENSION_ID || !fileName.startsWith("micropython-")) return false
        val board = PicoBoard.entries.firstOrNull { "micropython-${it.bundleId}.zip" == fileName }
            ?: return false
        val marker = File(
            context.filesDir,
            "micropython-firmware/${board.bundleId}/$PAYLOAD_MARKER",
        )
        return marker.takeIf(File::isFile)?.let { runCatching(it::readText).getOrNull() } ==
            "$version:$fileName"
    }

    private fun installedInfo(id: String): FoldCodeExtensionInfo? = runCatching {
        val directory = File(extensionsRoot, id)
        if (!directory.isDirectory) return null
        val manifest = parseAndValidate(directory, verifyChecksums = false)
        require(manifest.id != PICO_EXTENSION_ID || manifest.version == PicoSdkRelease.extensionVersion) {
            "Installed Pico extension must be updated"
        }
        require(File(directory, VALIDATION_MARKER).takeIf(File::isFile)?.readText() == manifest.version) {
            "Extension installation is incomplete"
        }
        val consumedRoot = File(directory, CONSUMED_DIRECTORY)
        val knownPayloads = manifest.payloads.keys
        fun consumed(name: String) = File(consumedRoot, name).isFile ||
            isMaterializedPayloadCurrent(id, name, manifest.version)
        val installedComponents = knownPayloads.count(::consumed)
        val components = if (id == PICO_EXTENSION_ID) PICO_COMPONENTS.map { (id, label) ->
            val payloads = when (id) {
                "core" -> setOf("pico-cmake-sdk.zip", "cmake-data.zip", PICO_TOOLS_PAYLOAD)
                MICROPYTHON_COMPONENT_ID -> {
                    PicoBoard.entries.mapTo(mutableSetOf()) { board ->
                        "micropython-${board.bundleId}.zip"
                    }
                }
                else -> {
                    val board = PicoBoard.entries.first { it.bundleId == id }
                    setOf(
                        "pico-profile-${board.bundleId}.zip",
                        if (board.chip.riscV) "pico-toolchain-riscv.zip" else "pico-toolchain-arm.zip",
                        "pico-cmake-sdk.zip",
                        "cmake-data.zip",
                        PICO_TOOLS_PAYLOAD,
                    )
                }
            }
            val installed = payloads.all(::consumed)
            val availableOffline = payloads.all { name ->
                consumed(name) || File(directory, "payload/$name").isFile
            }
            FoldCodeExtensionComponent(id, label, installed, availableOffline)
        } else listOf(
            FoldCodeExtensionComponent(
                id = "core",
                name = when (id) {
                    PYTHON_EXTENSION_ID -> "Python interpreter and standard library"
                    GIT_EXTENSION_ID -> "Obsolete Git extension runtime"
                    GNU_ARM_EXTENSION_ID -> "Official GNU Arm Embedded 15.2 toolchain"
                    RUST_EXTENSION_ID -> "rustc, Cargo, rust-analyzer and Pico targets"
                    GNU_LANGUAGES_EXTENSION_ID -> "GNU Fortran and GnuCOBOL compilers and runtimes"
                    WEB_EXTENSION_ID -> "Node.js, npm and JavaScript/TypeScript language tooling"
                    else -> "Clang, LLVM and Android C++ sysroot"
                },
                installed = consumed(runtimePayload(id)),
                availableOffline = consumed(runtimePayload(id)) || File(directory, "payload/${runtimePayload(id)}").isFile,
            ),
        )
        FoldCodeExtensionInfo(
            id = manifest.id,
            name = manifest.name,
            version = manifest.version,
            description = manifest.description,
            publisher = manifest.publisher,
            // Recursively measuring an installed SDK can touch tens of thousands
            // of files. Read the asynchronously maintained value on UI paths.
            installedBytes = installedSizeCache(id).takeIf(File::isFile)
                ?.readText()?.toLongOrNull() ?: 0L,
            installedComponents = installedComponents,
            totalComponents = knownPayloads.size,
            offlineReady = installedComponents == knownPayloads.size,
            components = components,
            provides = manifest.provides.ifEmpty { builtInCapabilities(manifest.id) },
        )
    }.getOrNull()

    private fun installedSizeCache(id: String): File =
        File(context.cacheDir, "extension-sizes/$id.bytes")

    private fun invalidateInstalledSize(id: String) {
        installedSizeCache(id).delete()
    }

    private fun refreshMissingInstalledSizeCaches() {
        listOf(CPP_EXTENSION_ID, PYTHON_EXTENSION_ID, PICO_EXTENSION_ID, GNU_ARM_EXTENSION_ID, RUST_EXTENSION_ID, GNU_LANGUAGES_EXTENSION_ID, WEB_EXTENSION_ID)
            .filter { File(extensionsRoot, it).isDirectory && !installedSizeCache(it).isFile }
            .forEach(::refreshInstalledSizeCache)
    }

    private fun refreshInstalledSizeCache(id: String) {
        val directory = File(extensionsRoot, id)
        if (!directory.isDirectory) return
        val runtimeDirectories = when (id) {
            PICO_EXTENSION_ID -> picoCacheDirectories()
            PYTHON_EXTENSION_ID -> listOf(File(context.filesDir, PYTHON_RUNTIME_DIRECTORY))
            GIT_EXTENSION_ID -> listOf(File(context.filesDir, GIT_RUNTIME_DIRECTORY))
            GNU_ARM_EXTENSION_ID -> listOf(File(context.filesDir, GNU_ARM_RUNTIME_DIRECTORY))
            RUST_EXTENSION_ID -> listOf(File(context.filesDir, RUST_RUNTIME_DIRECTORY))
            GNU_LANGUAGES_EXTENSION_ID -> listOf(File(context.filesDir, GNU_LANGUAGES_RUNTIME_DIRECTORY))
            WEB_EXTENSION_ID -> listOf(File(context.filesDir, WEB_RUNTIME_DIRECTORY))
            else -> listOf(File(context.filesDir, CPP_RUNTIME_DIRECTORY))
        }
        val bytes = directory.walkTopDown().filter(File::isFile).sumOf(File::length) +
            runtimeDirectories.filter(File::isDirectory).sumOf { runtime ->
                runtime.walkTopDown().filter(File::isFile).sumOf(File::length)
            }
        installedSizeCache(id).apply {
            parentFile?.mkdirs()
            writeText(bytes.toString())
        }
    }

    private fun extractPackage(input: InputStream, destinationRoot: File) {
        val canonicalRoot = destinationRoot.canonicalFile.toPath()
        var totalBytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val destination = File(destinationRoot, entry.name).canonicalFile
                require(destination.toPath().startsWith(canonicalRoot)) { "Unsafe extension entry: ${entry.name}" }
                require(entry.name == MANIFEST_FILE ||
                    (entry.name.startsWith("payload/") && PAYLOAD_FILE.matches(entry.name.removePrefix("payload/"))) ||
                    LEGAL_PACKAGE_FILE.matches(entry.name)) {
                    "Unexpected extension entry: ${entry.name}"
                }
                if (!entry.isDirectory) {
                    destination.parentFile?.mkdirs()
                    FileOutputStream(destination).use { output ->
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            totalBytes += count
                            require(totalBytes <= MAX_PACKAGE_BYTES) { "Extension package is too large" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun extractArchive(input: InputStream, destinationRoot: File, maximumBytes: Long) {
        val canonicalRoot = destinationRoot.canonicalFile.toPath()
        var totalBytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val destination = File(destinationRoot, entry.name).canonicalFile
                require(destination.toPath().startsWith(canonicalRoot)) { "Unsafe SDK payload entry: ${entry.name}" }
                if (entry.isDirectory) destination.mkdirs() else {
                    destination.parentFile?.mkdirs()
                    FileOutputStream(destination).use { output ->
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            totalBytes += count
                            require(totalBytes <= maximumBytes) { "SDK payload expands beyond its safety limit" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun parseAndValidate(directory: File, verifyChecksums: Boolean): ExtensionManifest {
        val manifestFile = File(directory, MANIFEST_FILE)
        require(manifestFile.isFile) { "Extension manifest is missing" }
        val json = JSONObject(manifestFile.readText())
        val schemaVersion = json.getInt("schemaVersion")
        require(schemaVersion in 1..3) { "Unsupported extension manifest version" }
        if (schemaVersion >= 3) {
            val legal = json.getJSONObject("legal")
            require(legal.getString("notices") == THIRD_PARTY_NOTICES_FILE) {
                "Extension notice path is invalid"
            }
            require(legal.getString("sources") == SOURCE_MANIFEST_FILE) {
                "Extension source-manifest path is invalid"
            }
            val notices = File(directory, THIRD_PARTY_NOTICES_FILE)
            require(notices.isFile && notices.length() > 0L) {
                "Extension third-party notices are missing"
            }
            val sourceManifest = File(directory, SOURCE_MANIFEST_FILE)
            require(sourceManifest.isFile) { "Extension source manifest is missing" }
            val sourceJson = JSONObject(sourceManifest.readText())
            require(sourceJson.getString("extensionId") == json.getString("id")) {
                "Extension source manifest belongs to another extension"
            }
            val components = sourceJson.getJSONArray("components")
            require(components.length() > 0) {
                "Extension source manifest is empty"
            }
            for (index in 0 until components.length()) {
                val component = components.getJSONObject(index)
                for (field in REQUIRED_SOURCE_COMPONENT_FIELDS) {
                    require(component.optString(field).isNotBlank()) {
                        "Extension source component $index has no $field"
                    }
                }
            }
        }
        require(json.optInt("minimumHostVersionCode", 1) <= HOST_VERSION_CODE) { "FoldCode must be updated before installing this extension" }
        val payloads = json.getJSONObject("payloads")
        val manifestId = json.getString("id")
        val expectedPayloads = when (manifestId) {
            PICO_EXTENSION_ID -> REQUIRED_PICO_PAYLOADS
            CPP_EXTENSION_ID -> setOf(CPP_PAYLOAD)
            PYTHON_EXTENSION_ID -> setOf(PYTHON_PAYLOAD)
            GIT_EXTENSION_ID -> setOf(GIT_PAYLOAD)
            GNU_ARM_EXTENSION_ID -> setOf(GNU_ARM_PAYLOAD)
            RUST_EXTENSION_ID -> setOf(RUST_PAYLOAD)
            GNU_LANGUAGES_EXTENSION_ID -> setOf(GNU_LANGUAGES_PAYLOAD)
            WEB_EXTENSION_ID -> setOf(WEB_PAYLOAD)
            else -> error("Unsupported extension: $manifestId")
        }
        require(payloads.keys().asSequence().toSet() == expectedPayloads) { "Extension payload list is incomplete" }
        val payloadHashes = expectedPayloads.associateWith { payloads.getString(it).lowercase() }
        payloadHashes.forEach { (name, hash) ->
            require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid payload checksum: $name" }
            val payload = File(directory, "payload/$name")
            val consumed = File(directory, "$CONSUMED_DIRECTORY/$name")
            if (verifyChecksums) {
                // Server core packages intentionally omit optional board payloads.
                if (payload.isFile) require(payload.sha256().equals(hash, ignoreCase = true)) {
                    "Extension payload checksum failed: $name"
                }
            } else if (!payload.isFile && !consumed.isFile) {
                // Valid component-delivery state: metadata is installed, while this
                // optional payload can be downloaded again from the server later.
            }
        }
        if (verifyChecksums) {
            val initialPayloads = when (manifestId) {
                PICO_EXTENSION_ID -> setOf("pico-cmake-sdk.zip", "cmake-data.zip", PICO_TOOLS_PAYLOAD)
                CPP_EXTENSION_ID -> setOf(CPP_PAYLOAD)
                PYTHON_EXTENSION_ID -> setOf(PYTHON_PAYLOAD)
                GIT_EXTENSION_ID -> setOf(GIT_PAYLOAD)
                GNU_ARM_EXTENSION_ID -> setOf(GNU_ARM_PAYLOAD)
                RUST_EXTENSION_ID -> setOf(RUST_PAYLOAD)
                GNU_LANGUAGES_EXTENSION_ID -> setOf(GNU_LANGUAGES_PAYLOAD)
                WEB_EXTENSION_ID -> setOf(WEB_PAYLOAD)
                else -> emptySet()
            }
            require(initialPayloads.all { File(directory, "payload/$it").isFile }) {
                "Extension core package is incomplete"
            }
        }
        return ExtensionManifest(
            id = manifestId,
            name = json.getString("name"),
            version = json.getString("version"),
            description = json.optString("description"),
            publisher = json.optString("publisher", "FoldCode"),
            payloads = payloadHashes,
            provides = json.optJSONArray("provides")?.let { values ->
                buildSet { for (index in 0 until values.length()) add(values.getString(index)) }
            }.orEmpty(),
        )
    }

    private fun removeExtractedPicoSdks() {
        listOf("rp2040", "rp2350", "rp2350-riscv")
            .forEach(::deleteGeneratedPicoSysrootSafely)
        picoCacheDirectories()
            .filterNot { it.name.startsWith("pico-cmake-sysroot-") }
            .forEach(File::deleteRecursively)
        File(context.filesDir, "pico-workspace").deleteRecursively()
    }

    /**
     * A consumed marker means the archive was successfully materialized, not
     * that its extracted cache is still healthy. Detect missing C headers and
     * return the shared component to Download required instead of leaving every
     * Arm or RISC-V board permanently marked as installed.
     */
    private fun invalidateDamagedPicoToolchains() {
        if (!isPicoInstalled()) return
        val extensionRoot = File(extensionsRoot, PICO_EXTENSION_ID)
        val consumedRoot = File(extensionRoot, CONSUMED_DIRECTORY)
        listOf(
            Triple("pico-toolchain-arm.zip", "pico-toolchain-arm", setOf("rp2040", "rp2350")),
            Triple("pico-toolchain-riscv.zip", "pico-toolchain-riscv", setOf("rp2350-riscv")),
        ).forEach { (payload, directoryName, platforms) ->
            val consumed = File(consumedRoot, payload)
            if (!consumed.isFile) return@forEach
            val toolchain = File(context.filesDir, directoryName)
            val valid = listOf(
                File(toolchain, "toolchain/sysroot/include"),
                File(toolchain, "toolchain/include"),
            ).any(::hasRequiredPicoCompilerHeaders)
            if (valid) return@forEach

            platforms.forEach(::deleteGeneratedPicoSysrootSafely)
            toolchain.deleteRecursively()
            consumed.delete()
            invalidateInstalledSize(PICO_EXTENSION_ID)
        }
    }

    private fun hasRequiredPicoCompilerHeaders(headers: File): Boolean =
        headers.isDirectory &&
            File(headers, "assert.h").isFile &&
            File(headers, "stdint.h").isFile

    private fun deleteGeneratedPicoSysrootSafely(platform: String) {
        val root = File(context.filesDir, "pico-cmake-sysroot-$platform")
        val include = File(root, "include")
        if (Files.isSymbolicLink(include.toPath())) Files.deleteIfExists(include.toPath())
        root.deleteRecursively()
    }

    private fun picoCacheDirectories(): List<File> = buildList {
        PicoBoard.entries.forEach { add(File(context.filesDir, "pico-sdk-${it.bundleId}")) }
        add(File(context.filesDir, "pico-sdk-shared"))
        add(File(context.filesDir, "pico-toolchain-arm"))
        add(File(context.filesDir, "pico-toolchain-riscv"))
        add(File(context.filesDir, "pico-cmake-sdk"))
        add(File(context.filesDir, PICO_TOOLS_RUNTIME_DIRECTORY))
        add(File(context.filesDir, "pico-cmake-sysroot-rp2040"))
        add(File(context.filesDir, "pico-cmake-sysroot-rp2350"))
        add(File(context.filesDir, "pico-cmake-sysroot-rp2350-riscv"))
        add(File(context.filesDir, "usr"))
    }.distinctBy(File::getAbsolutePath)

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class ExtensionManifest(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val publisher: String,
        val payloads: Map<String, String>,
        val provides: Set<String>,
    )

    private companion object {
        const val PICO_EXTENSION_ID = "dev.foldcode.pico"
        const val CPP_EXTENSION_ID = "dev.foldcode.cpp"
        const val PYTHON_EXTENSION_ID = "dev.foldcode.python"
        const val GIT_EXTENSION_ID = "dev.foldcode.git"
        const val GNU_ARM_EXTENSION_ID = "dev.foldcode.gnu-arm"
        const val RUST_EXTENSION_ID = "dev.foldcode.rust"
        const val GNU_LANGUAGES_EXTENSION_ID = "dev.foldcode.gnu-languages"
        const val WEB_EXTENSION_ID = "dev.foldcode.web"
        const val CPP_PAYLOAD = "cpp-runtime-arm64.zip"
        const val CPP_RUNTIME_DIRECTORY = "cpp-runtime"
        const val PYTHON_PAYLOAD = "python-runtime-arm64.zip"
        const val PYTHON_RUNTIME_DIRECTORY = "python-runtime"
        const val GIT_PAYLOAD = "git-runtime.zip"
        const val GIT_RUNTIME_DIRECTORY = "git-runtime"
        const val GNU_ARM_PAYLOAD = "gnu-arm-runtime.zip"
        const val GNU_ARM_RUNTIME_DIRECTORY = "gnu-arm-runtime"
        const val RUST_PAYLOAD = "rust-runtime-arm64.zip"
        const val RUST_RUNTIME_DIRECTORY = "rust-runtime"
        const val GNU_LANGUAGES_PAYLOAD = "gnu-languages-runtime-arm64.zip"
        const val GNU_LANGUAGES_RUNTIME_DIRECTORY = "gnu-languages-runtime"
        const val WEB_PAYLOAD = "web-runtime-arm64.zip"
        const val WEB_RUNTIME_DIRECTORY = "web-runtime"
        const val PICO_TOOLS_PAYLOAD = "picotool-runtime.zip"
        const val PICO_TOOLS_RUNTIME_DIRECTORY = "pico-tools-runtime"
        const val MICROPYTHON_COMPONENT_ID = "micropython"
        const val MANIFEST_FILE = "manifest.json"
        const val THIRD_PARTY_NOTICES_FILE = "licenses/THIRD_PARTY_NOTICES.md"
        const val SOURCE_MANIFEST_FILE = "licenses/SOURCES.json"
        val REQUIRED_SOURCE_COMPONENT_FIELDS = setOf("name", "version", "license", "upstream")
        const val VALIDATION_MARKER = ".validated"
        const val PAYLOAD_MARKER = ".foldcode-payload"
        const val CONSUMED_DIRECTORY = ".consumed"
        const val HOST_VERSION_CODE = 1
        const val MAX_PACKAGE_BYTES = 600L * 1024 * 1024
        const val MAX_EXTRACTED_PAYLOAD_BYTES = 800L * 1024 * 1024
        const val MAX_GNU_ARM_EXTRACTED_PAYLOAD_BYTES = 1536L * 1024 * 1024
        const val MAX_RUST_EXTRACTED_PAYLOAD_BYTES = 2048L * 1024 * 1024
        const val MAX_GNU_LANGUAGES_EXTRACTED_PAYLOAD_BYTES = 1536L * 1024 * 1024
        const val MAX_WEB_EXTRACTED_PAYLOAD_BYTES = 1024L * 1024 * 1024
        val REQUIRED_PICO_PAYLOADS = buildSet {
            add(PICO_TOOLS_PAYLOAD)
            add("pico-toolchain-arm.zip")
            add("pico-toolchain-riscv.zip")
            add("pico-cmake-sdk.zip")
            add("cmake-data.zip")
            PicoBoard.entries.forEach { add("pico-profile-${it.bundleId}.zip") }
            PicoBoard.entries.forEach { add("micropython-${it.bundleId}.zip") }
        }
        val PICO_COMPONENTS = listOf("core" to "Core SDK and CMake") +
            PicoBoard.entries.map { it.bundleId to it.displayName } +
            listOf(MICROPYTHON_COMPONENT_ID to "MicroPython firmware")
        val SUPPORTED_EXTENSIONS = setOf(PICO_EXTENSION_ID, CPP_EXTENSION_ID, PYTHON_EXTENSION_ID, GNU_ARM_EXTENSION_ID, RUST_EXTENSION_ID, GNU_LANGUAGES_EXTENSION_ID, WEB_EXTENSION_ID)
        val PAYLOAD_FILE = Regex("[A-Za-z0-9._-]+\\.zip")
        val LEGAL_PACKAGE_FILE = Regex("licenses/(THIRD_PARTY_NOTICES\\.md|SOURCES\\.json)")
    }

    private fun builtInCapabilities(extensionId: String): Set<String> = when (extensionId) {
        CPP_EXTENSION_ID -> setOf(BuildCapability.Clang, "command:clang++", "command:ld.lld", "toolchain:clang-21")
        PYTHON_EXTENSION_ID -> setOf(
            BuildCapability.Python3, "command:python", "command:pip", "language:python",
            "intelligence:python", "runtime:python:3.14",
        )
        GIT_EXTENSION_ID -> setOf(BuildCapability.Git, "transport:git", "scm:git")
        GNU_ARM_EXTENSION_ID -> setOf(BuildCapability.GnuArm, "command:arm-none-eabi-gcc", "command:arm-none-eabi-g++", "toolchain:gnu-arm:15.2.1")
        RUST_EXTENSION_ID -> setOf("command:rustc", "command:cargo", "command:rust-analyzer", "language:rust", "language-server:rust")
        GNU_LANGUAGES_EXTENSION_ID -> setOf(
            "command:gfortran", "language:fortran", "command:cobc", "command:cobcrun", "language:cobol",
            "intelligence:fortran", "intelligence:cobol",
        )
        WEB_EXTENSION_ID -> setOf(
            "command:node", "command:npm", "command:npx", "language:javascript", "language:typescript",
            "language:html", "language:css", "language:json", "language-server:typescript",
            "language-server:html", "language-server:css", "language-server:json",
        )
        PICO_EXTENSION_ID -> setOf(
            BuildCapability.CMake,
            BuildCapability.Ninja,
            "command:picotool",
            "command:pioasm",
            BuildCapability.PicoSdk,
        )
        else -> emptySet()
    }

    private fun isSupportedExtension(extensionId: String): Boolean = when (extensionId) {
        PICO_EXTENSION_ID, CPP_EXTENSION_ID, PYTHON_EXTENSION_ID, GNU_ARM_EXTENSION_ID, RUST_EXTENSION_ID, GNU_LANGUAGES_EXTENSION_ID, WEB_EXTENSION_ID -> true
        else -> false
    }

    private fun runtimePayload(extensionId: String): String = when (extensionId) {
        PYTHON_EXTENSION_ID -> PYTHON_PAYLOAD
        GIT_EXTENSION_ID -> GIT_PAYLOAD
        GNU_ARM_EXTENSION_ID -> GNU_ARM_PAYLOAD
        RUST_EXTENSION_ID -> RUST_PAYLOAD
        GNU_LANGUAGES_EXTENSION_ID -> GNU_LANGUAGES_PAYLOAD
        WEB_EXTENSION_ID -> WEB_PAYLOAD
        else -> CPP_PAYLOAD
    }

    private fun picoPayloadsForComponents(componentIds: Set<String>): Set<String> = buildSet {
        if ("core" in componentIds || PicoBoard.entries.any { it.bundleId in componentIds }) {
            add("pico-cmake-sdk.zip")
            add("cmake-data.zip")
            add(PICO_TOOLS_PAYLOAD)
        }
        PicoBoard.entries.filter { it.bundleId in componentIds }.forEach { board ->
            add("pico-profile-${board.bundleId}.zip")
            add(if (board.chip.riscV) "pico-toolchain-riscv.zip" else "pico-toolchain-arm.zip")
        }
        if (MICROPYTHON_COMPONENT_ID in componentIds) {
            PicoBoard.entries.forEach { board ->
                add("micropython-${board.bundleId}.zip")
            }
        }
    }
}
