package dev.foldcode.ide

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class RustPicoCompilerEngine(private val context: Context) {
    private val cancelled = AtomicBoolean(false)
    private val activeProcess = AtomicReference<Process?>(null)

    fun build(
        project: File,
        board: PicoBoard,
        debug: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): PicoBuildResult {
        cancelled.set(false)
        val manager = FoldCodeExtensionManager(context)
        if (!manager.isRustInstalled()) return PicoBuildResult(false, "Install the Rust extension first")
        runCatching { ensureRustRuntimeInstalled(context) }.onFailure {
            return PicoBuildResult(false, "Rust runtime installation failed: ${it.message}")
        }
        val manifest = File(project, "Cargo.toml")
        if (!manifest.isFile) return PicoBuildResult(false, "Cargo.toml is missing")
        val target = targetFor(board)
        val output = StringBuilder()
        val cargoTargetDirectory = cargoTargetDirectory(project).apply { mkdirs() }
        val compatibilityReady = prepareRustCargoCompatibility(
            project = project,
            target = target,
            cargoTargetDirectory = cargoTargetDirectory,
            output = output,
            onProgress = onProgress,
        ) { arguments, consumeOutput ->
            runTool(project, "cargo", arguments, cargoTargetDirectory, consumeOutput)
        }
        if (!compatibilityReady) {
            return PicoBuildResult(
                false,
                output.toString().trimEnd().ifBlank { "Could not prepare Cargo dependencies" },
            )
        }
        onProgress("Starting Cargo build for $target…")
        val cargoArguments = buildList {
            add("build")
            if (!debug) add("--release")
            addAll(listOf("--target", target, "--message-format=short", "-j1"))
        }
        fun cargoBuild(): Boolean = runTool(
            project,
            "cargo",
            // Multiple concurrent Linux LLD processes are unstable under the
            // Android PRoot bridge and also create avoidable thermal load.
            cargoArguments,
            cargoTargetDirectory,
        ) { line ->
            output.appendLine(line)
            onProgress(line)
        }
        var cargo = cargoBuild()
        val compilerCrash = isRustHostCompilerCrash(output)
        val linkerCrash = isRustHostLinkerCrash(output)
        if (!cargo && !cancelled.get() && (compilerCrash || linkerCrash)) {
            // A compiler crash can leave a corrupt proc-macro in the host cache;
            // a failed LLD command is atomic and only needs to be retried. Target
            // firmware artifacts live under the target triple and stay incremental.
            val recoveryMessage = if (compilerCrash) {
                val hostProfile = File(cargoTargetDirectory, if (debug) "debug" else "release")
                if (!hostProfile.deleteRecursively()) {
                    return PicoBuildResult(false, output.toString().trimEnd())
                }
                "Rust host compiler crashed; rebuilding host tools once…"
            } else {
                "Rust host linker crashed; retrying the failed host build once…"
            }
            output.appendLine(recoveryMessage)
            onProgress(recoveryMessage)
            cargo = cargoBuild()
        }
        if (!cargo) return PicoBuildResult(false, output.toString().trimEnd().ifBlank { "Cargo build failed" })
        if (cancelled.get()) return PicoBuildResult(false, "Rust build cancelled")

        val binary = findCargoBinary(cargoTargetDirectory, target, manifest.readText(), debug)
            ?: return PicoBuildResult(false, output.append("Cargo succeeded, but no firmware ELF was found").toString())
        val buildDirectory = File(project, "build").apply { mkdirs() }
        File(buildDirectory, ".nomedia").createNewFile()
        val elf = File(buildDirectory, "${binary.name}.elf")
        binary.copyTo(elf, overwrite = true)
        val uf2 = File(buildDirectory, "${binary.name}.uf2")
        onProgress("Converting ${elf.name} to UF2 with picotool…")
        val converted = runPicotool(
            project,
            listOf(
                "uf2", "convert",
                elf.absolutePath, "-t", "elf",
                uf2.absolutePath, "-t", "uf2",
                "--family", when (board.chip) {
                    PicoChip.Rp2040 -> "rp2040"
                    PicoChip.Rp2350 -> "rp2350-arm-s"
                    PicoChip.Rp2350RiscV -> "rp2350-riscv"
                },
                "--platform", if (board.chip == PicoChip.Rp2040) "rp2040" else "rp2350",
            ),
        ) { line ->
            output.appendLine(line)
            onProgress(line)
        }
        if (!converted || !uf2.isFile) {
            return PicoBuildResult(false, output.append("picotool did not produce a UF2 file").toString())
        }
        output.appendLine("Build succeeded")
        output.append("UF2: ${uf2.absolutePath}")
        return PicoBuildResult(true, output.toString(), uf2)
    }

    /** Builds and runs an ordinary host Rust Cargo project inside the extension runtime. */
    fun runProject(
        project: File,
        debug: Boolean = false,
        onProgramStarted: () -> Unit = {},
        onProgramOutput: (String) -> Unit = {},
    ): BuildResult {
        cancelled.set(false)
        val manager = FoldCodeExtensionManager(context)
        if (!manager.isRustInstalled()) return BuildResult(false, "Install the Rust extension first")
        runCatching { ensureRustRuntimeInstalled(context) }.onFailure {
            return BuildResult(false, "Rust runtime installation failed: ${it.message}")
        }
        val manifest = File(project, "Cargo.toml")
        if (!manifest.isFile) return BuildResult(false, "Cargo.toml is missing")

        val runtime = File(context.filesDir, "rust-runtime")
        val launcher = File(context.applicationInfo.nativeLibraryDir, "foldrust.so")
        if (!launcher.canExecute()) return BuildResult(false, "FoldCode Rust launcher is unavailable")
        val targetDirectory = cargoTargetDirectory(project).apply { mkdirs() }
        val pidSession = BuildPidRegistry.open(context.cacheDir)
        val process = runCatching {
            ProcessBuilder(buildList {
                add(launcher.absolutePath)
                add("run")
                if (!debug) add("--release")
                addAll(listOf("--message-format=short", "-j1"))
            })
                .directory(project)
                .redirectErrorStream(true)
                .apply {
                    configureRustEnvironment(environment(), project, runtime, targetDirectory)
                    environment()["FOLDCODE_RUST_TOOL"] = "cargo"
                    pidSession.configure(environment())
                }
                .start()
                .also(pidSession::attach)
        }.getOrElse {
            pidSession.close()
            return BuildResult(false, "Could not start Cargo: ${it.message}")
        }
        activeProcess.set(process)
        onProgramStarted()
        val completeOutput = StringBuilder()
        return try {
            val reader = InputStreamReader(process.inputStream, Charsets.UTF_8)
            val buffer = CharArray(1024)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                val chunk = String(buffer, 0, count)
                completeOutput.append(chunk)
                onProgramOutput(chunk)
            }
            val exitCode = process.waitFor()
            BuildResult(
                succeeded = exitCode == 0 && !cancelled.get(),
                output = buildString {
                    appendLine(if (debug) "$ cargo run" else "$ cargo run --release")
                    append(
                        when {
                            cancelled.get() -> "Run cancelled"
                            completeOutput.isNotBlank() -> completeOutput.toString()
                            exitCode != 0 -> "Cargo exited with $exitCode"
                            else -> "Rust program finished"
                        },
                    )
                },
            )
        } finally {
            activeProcess.compareAndSet(process, null)
            pidSession.close()
        }
    }

    fun sendInput(text: String): Boolean {
        val process = activeProcess.get() ?: return false
        return runCatching {
            process.outputStream.bufferedWriter().apply {
                write(text)
                newLine()
                flush()
            }
            true
        }.getOrDefault(false)
    }

    /** Validates Cargo metadata and the selected Pico target without compiling firmware. */
    fun configure(
        project: File,
        board: PicoBoard,
        onProgress: (String) -> Unit = {},
    ): PicoBuildResult {
        cancelled.set(false)
        val manager = FoldCodeExtensionManager(context)
        if (!manager.isRustInstalled()) return PicoBuildResult(false, "Install the Rust extension first")
        runCatching { ensureRustRuntimeInstalled(context) }.onFailure {
            return PicoBuildResult(false, "Rust runtime installation failed: ${it.message}")
        }
        if (!File(project, "Cargo.toml").isFile) return PicoBuildResult(false, "Cargo.toml is missing")
        val target = targetFor(board)
        val output = StringBuilder()
        onProgress("Reading Cargo metadata for $target…")
        val configured = runTool(
            project,
            "cargo",
            listOf("metadata", "--format-version=1", "--no-deps"),
            cargoTargetDirectory(project).apply { mkdirs() },
        ) { line ->
            // Cargo writes the successful metadata document as one large JSON
            // line. It is useful to the IDE but not readable terminal output.
            if (!line.trimStart().startsWith("{")) {
                output.appendLine(line)
                onProgress(line)
            }
        }
        if (!configured) {
            return PicoBuildResult(false, output.toString().trimEnd().ifBlank { "Cargo configuration failed" })
        }
        return PicoBuildResult(
            true,
            buildString {
                append("Cargo project configured\nTarget: ").append(target)
                if (output.isNotBlank()) append('\n').append(output.toString().trimEnd())
            },
        )
    }

    /** Removes both Cargo's private target cache and FoldCode's visible firmware outputs. */
    fun clean(project: File): PicoBuildResult {
        cancel()
        val targetDirectory = cargoTargetDirectory(project)
        if (targetDirectory.exists() && !targetDirectory.deleteRecursively()) {
            return PicoBuildResult(false, "Could not remove Cargo target cache")
        }
        val projectTarget = File(project, "target")
        if (projectTarget.exists() && !projectTarget.deleteRecursively()) {
            return PicoBuildResult(false, "Private Cargo cache was removed, but the project target directory could not be deleted")
        }
        val outputs = File(project, "build")
        if (outputs.exists() && !outputs.deleteRecursively()) {
            return PicoBuildResult(false, "Cargo cache was removed, but project build outputs could not be deleted")
        }
        return PicoBuildResult(true, "Cargo build outputs and target cache were cleaned")
    }

    fun cancel() {
        cancelled.set(true)
        activeProcess.get()?.terminateTree()
    }

    private fun cargoTargetDirectory(project: File): File = File(
        context.filesDir,
        "rust-targets/${project.absolutePath.hashCode().toUInt().toString(16)}",
    )

    private fun targetFor(board: PicoBoard): String = when (board.chip) {
        PicoChip.Rp2040 -> "thumbv6m-none-eabi"
        PicoChip.Rp2350 -> "thumbv8m.main-none-eabihf"
        PicoChip.Rp2350RiscV -> "riscv32imac-unknown-none-elf"
    }

    private fun runTool(
        project: File,
        tool: String,
        arguments: List<String>,
        cargoTargetDirectory: File,
        output: (String) -> Unit,
    ): Boolean {
        if (cancelled.get()) return false
        val runtime = File(context.filesDir, "rust-runtime")
        val launcher = File(context.applicationInfo.nativeLibraryDir, "foldrust.so")
        if (!launcher.canExecute()) error("FoldCode Rust launcher is unavailable")
        val pidSession = BuildPidRegistry.open(context.cacheDir)
        val process = try {
            ProcessBuilder(listOf(launcher.absolutePath) + arguments)
                .directory(project)
                .redirectErrorStream(true)
                .apply {
                    configureRustEnvironment(environment(), project, runtime, cargoTargetDirectory)
                    environment()["FOLDCODE_RUST_TOOL"] = tool
                    pidSession.configure(environment())
                }
                .start()
                .also(pidSession::attach)
        } catch (error: Exception) {
            pidSession.close()
            throw error
        }
        activeProcess.set(process)
        return try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (cancelled.get()) return@forEach
                    output(line)
                }
            }
            process.waitFor() == 0 && !cancelled.get()
        } finally {
            activeProcess.compareAndSet(process, null)
            pidSession.close()
        }
    }

    private fun configureRustEnvironment(
        environment: MutableMap<String, String>,
        project: File,
        runtime: File,
        cargoTargetDirectory: File,
    ) {
        environment["FOLDCODE_RUST_ROOT"] = runtime.absolutePath
        environment["CARGO_HOME"] = File(project, ".foldcode/cargo").apply { mkdirs() }.absolutePath
        environment["RUSTUP_HOME"] = File(runtime, "toolchain").absolutePath
        environment["RUSTC"] = "/opt/rust/bin/rustc"
        environment["RUSTDOC"] = "/opt/rust/bin/rustdoc"
        environment["RUSTFMT"] = "/opt/rust/bin/rustfmt"
        environment["CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER"] = "/usr/bin/cc"
        configureRustCargoHostBuildEnvironment(environment)
        environment["HOME"] = project.absolutePath
        environment["TMPDIR"] = File(context.cacheDir, "rust").apply { mkdirs() }.absolutePath
        // Shared storage is mounted noexec on Android. Cargo build scripts,
        // procedural macros and host executables must stay in app-private storage.
        environment["CARGO_TARGET_DIR"] = cargoTargetDirectory.absolutePath
    }

    private fun runPicotool(
        project: File,
        arguments: List<String>,
        output: (String) -> Unit,
    ): Boolean {
        if (cancelled.get()) return false
        val runtime = File(context.filesDir, "pico-tools-runtime")
        val picotool = File(runtime, "bin/picotool")
        val library = File(runtime, "lib/libusb1.0.so")
        if (!picotool.isFile || !library.isFile) {
            output("Install the Pico extension's Core SDK and CMake component to create UF2 files")
            return false
        }
        val launcher = File(context.applicationInfo.nativeLibraryDir, "foldpicotool.so")
        if (!launcher.canExecute()) error("FoldCode picotool launcher is unavailable")
        val pidSession = BuildPidRegistry.open(context.cacheDir)
        val process = try {
            ProcessBuilder(listOf(launcher.absolutePath) + arguments)
                .directory(project)
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] = context.filesDir.absolutePath
                    environment()["FOLDCODE_PICOTOOL_ROOT"] = runtime.absolutePath
                    environment()["FOLDCODE_PROJECT_ROOT"] = project.absolutePath
                    environment()["LD_LIBRARY_PATH"] = listOf(
                        library.parentFile?.absolutePath.orEmpty(),
                        File(context.filesDir, "usr/lib").absolutePath,
                    ).joinToString(":")
                    pidSession.configure(environment())
                }
                .start()
                .also(pidSession::attach)
        } catch (error: Exception) {
            pidSession.close()
            throw error
        }
        activeProcess.set(process)
        return try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (cancelled.get()) return@forEach
                    output(line)
                }
            }
            process.waitFor() == 0 && !cancelled.get()
        } finally {
            activeProcess.compareAndSet(process, null)
            pidSession.close()
        }
    }

    private fun findCargoBinary(cargoTargetDirectory: File, target: String, cargoToml: String, debug: Boolean = false): File? {
        val packageName = Regex("(?m)^\\s*name\\s*=\\s*\"([^\"]+)\"")
            .find(cargoToml)?.groupValues?.get(1)
        val release = File(cargoTargetDirectory, "$target/${if (debug) "debug" else "release"}")
        packageName?.let { name ->
            listOf(name, name.replace('-', '_')).map { File(release, it) }
                .firstOrNull { it.isElfFile() }?.let { return it }
        }
        return release.listFiles()?.filter { it.isElfFile() }?.maxByOrNull(File::lastModified)
    }

    private fun File.isElfFile(): Boolean = isFile && runCatching {
        FileInputStream(this).use { input ->
            val magic = ByteArray(4)
            input.read(magic) == 4 && magic.contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        }
    }.getOrDefault(false)
}
