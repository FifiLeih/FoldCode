package dev.foldcode.ide

import android.content.Context
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class BuildResult(
    val succeeded: Boolean,
    val output: String,
)

/**
 * Compiles entirely inside the Android application sandbox.
 *
 * Clang itself is shipped in the APK native library directory so Android labels it
 * executable. The generated ELF stays writable and is loaded via linker64, avoiding
 * a direct exec() of an app-data file.
 */
class CompilerEngine(private val context: Context) {
    private val cancelled = AtomicBoolean(false)
    private val activeProcess = AtomicReference<Process?>(null)
    internal val debugWorkspace = File(context.filesDir, "workspace")
    private val workspace = debugWorkspace
    private val toolchain = BundledToolchain(context)
    internal val debugExecutable = File(workspace, "program-debug.so")
    private val outputFile = File(workspace, "program")

    @Synchronized
    fun save(files: Map<String, String>) {
        // This directory is only a disposable compiler mirror. Rebuild it on every
        // sync so a path that changed from a file to a folder (for example `test`
        // -> `test/main.cpp`) cannot leave an ENOTDIR collision behind.
        if (workspace.exists() && !workspace.deleteRecursively()) {
            error("Could not reset compiler workspace")
        }
        check(workspace.mkdirs() || workspace.isDirectory) { "Could not create compiler workspace" }
        files.forEach { (name, source) ->
            val destination = File(workspace, name).canonicalFile
            if (!destination.toPath().startsWith(workspace.canonicalFile.toPath())) {
                error("Invalid project path: $name")
            }
            destination.parentFile?.mkdirs()
            destination.writeText(source)
        }
    }

    @Synchronized
    fun compileAndRun(
        files: Map<String, String>,
        debug: Boolean = false,
        runAfterBuild: Boolean = true,
        onProgramStarted: () -> Unit = {},
        onProgramOutput: (String) -> Unit = {},
    ): BuildResult {
        cancelled.set(false)
        try {
            save(files)
        } catch (error: Exception) {
            return BuildResult(false, "Could not prepare project files: ${error.message}")
        }

        val cSources = files.keys
            .filter { it.endsWith(".c") }
            .sorted()
            .map { File(workspace, it).absolutePath }
        val cppSources = files.keys
            .filter { it.endsWith(".cpp") || it.endsWith(".cc") || it.endsWith(".cxx") }
            .sorted()
            .map { File(workspace, it).absolutePath }
        val sources = cSources + cppSources
        if (sources.isEmpty()) {
            return BuildResult(false, "No C or C++ source files found in the project")
        }

        if (!toolchain.clang.canExecute()) {
            return BuildResult(
                succeeded = false,
                output = buildString {
                    appendLine("$ clang++ main.cpp -o program")
                    appendLine("toolchain bootstrap required")
                    appendLine()
                    appendLine("Missing APK payload:")
                    appendLine("  ${toolchain.clang.absolutePath}")
                    appendLine()
                    appendLine("The editor, workspace, runner boundary and native ABI are ready.")
                    append("Next milestone: package the ARM64 Clang/sysroot payload.")
                },
            )
        }

        if (!toolchain.lld.canExecute()) {
            return BuildResult(false, "Bundled linker is missing: ${toolchain.lld.absolutePath}")
        }

        try {
            toolchain.install()
        } catch (error: Exception) {
            return BuildResult(false, "Toolchain installation failed: ${error.message}")
        }

        val sysroot = toolchain.sysroot
        if (!sysroot.isDirectory) {
            return BuildResult(false, "Clang is present, but the sysroot is missing at ${sysroot.absolutePath}")
        }

        val buildSharedDebugModule = debug && !runAfterBuild
        val buildOutputFile = if (buildSharedDebugModule) debugExecutable else outputFile
        buildOutputFile.delete()
        val commonCompileArguments = buildList {
            addAll(listOf(
                "--target=aarch64-linux-android26",
                "--sysroot=${sysroot.absolutePath}",
                "-resource-dir=${toolchain.resourceDir.absolutePath}",
            ))
            projectIncludeDirectories(files).forEach { add("-I${it.absolutePath}") }
            if (debug) addAll(listOf("-O0", "-g", "-fno-omit-frame-pointer"))
            if (buildSharedDebugModule) add("-fPIC")
        }
        val objectDirectory = File(workspace, ".foldcode-objects").apply {
            deleteRecursively()
            mkdirs()
        }
        val objects = mutableListOf<File>()
        val compilerOutput = StringBuilder()
        sources.forEachIndexed { index, source ->
            val objectFile = File(objectDirectory, "$index.o")
            val isCpp = source in cppSources
            val compile = runCommand(
                listOf(toolchain.clang.absolutePath) + commonCompileArguments + listOf(
                    if (isCpp) "-std=c++20" else "-std=c17",
                    "-c",
                    source,
                    "-o",
                    objectFile.absolutePath,
                ),
                timeoutSeconds = 60,
            )
            if (compile.output.isNotBlank()) compilerOutput.appendLine(compile.output.trimEnd())
            if (!compile.succeeded) return BuildResult(false, compilerOutput.toString().trimEnd())
            objects += objectFile
        }
        val linkCommand = buildList {
            addAll(listOf(
                toolchain.clang.absolutePath,
                "--target=aarch64-linux-android26",
                "--sysroot=${sysroot.absolutePath}",
                "-resource-dir=${toolchain.resourceDir.absolutePath}",
                "-fuse-ld=${toolchain.lld.absolutePath}",
                "-L${context.applicationInfo.nativeLibraryDir}",
            ))
            addAll(objects.map(File::getAbsolutePath))
            if (buildSharedDebugModule) {
                add("-shared")
                add("-Wl,-soname,foldcode-debug-program.so")
            }
            if (cppSources.isNotEmpty()) {
                add("-nostdlib++")
                add(File(sysroot, "usr/lib/aarch64-linux-android/libc++_static.a").absolutePath)
                add(File(sysroot, "usr/lib/aarch64-linux-android/libc++abi.a").absolutePath)
            }
            // libc++ and ordinary C programs may both use Android's math library.
            addAll(listOf("-lm", "-o", buildOutputFile.absolutePath))
        }
        val link = runCommand(linkCommand, timeoutSeconds = 60)
        if (link.output.isNotBlank()) compilerOutput.appendLine(link.output.trimEnd())
        if (!link.succeeded) return BuildResult(false, compilerOutput.toString().trimEnd())
        if (cancelled.get()) return BuildResult(false, "Build cancelled")

        if (!runAfterBuild) {
            return BuildResult(
                succeeded = true,
                output = buildString {
                    appendLine(if (cppSources.isNotEmpty()) "$ clang/clang++ sources -o program -O0 -g" else "$ clang *.c -o program -O0 -g")
                    if (compilerOutput.isNotBlank()) append(compilerOutput.toString().trimEnd())
                }.trimEnd(),
            )
        }

        val linker = File("/system/bin/linker64")
        if (!linker.canExecute()) {
            return BuildResult(false, "Compilation succeeded, but /system/bin/linker64 is unavailable")
        }

        val run = runInteractiveCommand(
            command = listOf(linker.absolutePath, outputFile.absolutePath),
            onStarted = onProgramStarted,
            onOutput = onProgramOutput,
        )
        return BuildResult(
            succeeded = run.succeeded,
            output = buildString {
                appendLine(if (cppSources.isNotEmpty()) "$ clang/clang++ sources -o program${if (debug) " -O0 -g" else ""}" else "$ clang *.c -o program${if (debug) " -O0 -g" else ""}")
                if (compilerOutput.isNotBlank()) appendLine(compilerOutput.toString().trimEnd())
                appendLine("$ ./program")
                append(run.output)
            },
        )
    }

    /** Syntax-checks and runs a Python file with the server-delivered CPython runtime. */
    @Synchronized
    fun runPython(
        files: Map<String, String>,
        entryPath: String,
        projectDirectory: File? = null,
        debug: Boolean = false,
        onProgramStarted: () -> Unit = {},
        onProgramOutput: (String) -> Unit = {},
    ): BuildResult {
        cancelled.set(false)
        val runDirectory = projectDirectory?.canonicalFile ?: workspace.canonicalFile
        if (projectDirectory == null) {
            try {
                save(files)
            } catch (error: Exception) {
                return BuildResult(false, "Could not prepare project files: ${error.message}")
            }
        }
        val entry = File(runDirectory, entryPath).canonicalFile
        if (!entry.toPath().startsWith(runDirectory.toPath()) || !entry.isFile) {
            return BuildResult(false, "Python entry file is unavailable: $entryPath")
        }

        val extensionManager = FoldCodeExtensionManager(context)
        if (!extensionManager.isPythonInstalled()) {
            return BuildResult(false, "Install the Python extension before running Python scripts")
        }
        try {
            extensionManager.installPythonRuntime()
        } catch (error: Exception) {
            return BuildResult(false, "Python runtime installation failed: ${error.message}")
        }
        val python = File(context.applicationInfo.nativeLibraryDir, "foldpython.so")
        if (!python.canExecute()) return BuildResult(false, "Python launcher is unavailable: ${python.absolutePath}")

        val syntax = runCommand(
            listOf(python.absolutePath, "-m", "py_compile", entry.absolutePath),
            timeoutSeconds = 30,
            workingDirectory = runDirectory,
            configureEnvironment = { configurePythonEnvironment(it, runDirectory) },
        )
        if (!syntax.succeeded) {
            return BuildResult(false, buildString {
                appendLine("$ python -m py_compile $entryPath")
                append(syntax.output)
            })
        }
        if (cancelled.get()) return BuildResult(false, "Run cancelled")

        val runCommand = if (debug) {
            listOf(python.absolutePath, "-u", "-m", "pdb", entry.absolutePath)
        } else {
            listOf(python.absolutePath, "-u", entry.absolutePath)
        }
        val run = runInteractiveCommand(
            command = runCommand,
            onStarted = onProgramStarted,
            onOutput = onProgramOutput,
            workingDirectory = runDirectory,
            configureEnvironment = { configurePythonEnvironment(it, runDirectory) },
        )
        return BuildResult(
            succeeded = run.succeeded,
            output = buildString {
                appendLine("$ python -m py_compile $entryPath")
                appendLine("Syntax check succeeded")
                appendLine(if (debug) "$ python -m pdb $entryPath" else "$ python $entryPath")
                append(run.output)
            },
        )
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

    fun cancel() {
        cancelled.set(true)
        activeProcess.get()?.terminateTree()
    }

    @Synchronized
    fun diagnose(files: Map<String, String>): String {
        return runCatching {
            save(files)
            toolchain.install()
            val sources = files.keys
                .filter { it.endsWith(".c") || it.endsWith(".cpp") || it.endsWith(".cc") || it.endsWith(".cxx") }
                .sorted()
            if (sources.isEmpty()) return ""
            val common = buildList {
                addAll(listOf(
                    toolchain.clang.absolutePath,
                    "--target=aarch64-linux-android26",
                    "--sysroot=${toolchain.sysroot.absolutePath}",
                    "-resource-dir=${toolchain.resourceDir.absolutePath}",
                    "-fsyntax-only",
                ))
                projectIncludeDirectories(files).forEach { add("-I${it.absolutePath}") }
            }
            sources.joinToString("\n") { source ->
                val standard = if (source.endsWith(".c")) "-std=c17" else "-std=c++20"
                runCommand(
                    common + listOf(standard, File(workspace, source).absolutePath),
                    timeoutSeconds = 20,
                ).output.trimEnd()
            }.trim()
        }.getOrElse { "Diagnostics failed: ${it.message}" }
    }

    /**
     * A plain C/C++ workspace does not necessarily have a generated
     * compile_commands.json yet. Make its conventional and discovered header
     * directories visible to Clang so layouts such as include/foo.hpp plus
     * src/foo.cpp work before introducing a full CMake configure step.
     */
    private fun projectIncludeDirectories(files: Map<String, String>): List<File> {
        val codeExtensions = setOf("c", "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx", "inc")
        return buildList {
            add(workspace)
            files.keys
                .map { File(workspace, it) }
                .filter { it.extension.lowercase() in codeExtensions }
                .mapNotNullTo(this) { it.parentFile }
            File(workspace, "include").takeIf { it.isDirectory }?.let(::add)
        }.map { it.canonicalFile }.distinctBy { it.absolutePath }
    }

    private fun configurePythonEnvironment(environment: MutableMap<String, String>, workingDirectory: File) {
        val root = File(context.filesDir, "python-runtime")
        environment["FOLDCODE_PYTHON_ROOT"] = root.absolutePath
        environment["PYTHONPYCACHEPREFIX"] = File(context.cacheDir, "python-pycache").apply { mkdirs() }.absolutePath
        environment["TMPDIR"] = context.cacheDir.absolutePath
        environment["HOME"] = workingDirectory.absolutePath
        environment["PYTHONPATH"] = File(workingDirectory, ".foldcode/python").absolutePath
    }

    private fun runInteractiveCommand(
        command: List<String>,
        onStarted: () -> Unit,
        onOutput: (String) -> Unit,
        workingDirectory: File = workspace,
        configureEnvironment: (MutableMap<String, String>) -> Unit = {},
    ): BuildResult {
        if (cancelled.get()) return BuildResult(false, "Run cancelled")
        val pidSession = BuildPidRegistry.open(context.cacheDir)
        return try {
            val process = ProcessBuilder(command)
                .directory(workingDirectory)
                .redirectErrorStream(true)
                .apply {
                    environment()["LD_LIBRARY_PATH"] =
                        "${toolchain.nativeDependencies.absolutePath}:${context.applicationInfo.nativeLibraryDir}"
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                    environment()["HOME"] = workspace.absolutePath
                    configureEnvironment(environment())
                    pidSession.configureNative(
                        environment(),
                        File(context.applicationInfo.nativeLibraryDir),
                    )
                }
                .start()
                .also(pidSession::attach)
            activeProcess.set(process)
            onStarted()
            val completeOutput = StringBuilder()
            try {
                val reader = InputStreamReader(process.inputStream, Charsets.UTF_8)
                val buffer = CharArray(1024)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    val chunk = String(buffer, 0, count)
                    completeOutput.append(chunk)
                    onOutput(chunk)
                }
                val exitCode = process.waitFor()
                when {
                    cancelled.get() -> BuildResult(false, "Run cancelled")
                    exitCode == 0 -> BuildResult(true, completeOutput.toString())
                    completeOutput.isBlank() -> BuildResult(false, "Process exited with $exitCode")
                    else -> BuildResult(false, completeOutput.toString())
                }
            } finally {
                activeProcess.compareAndSet(process, null)
            }
        } catch (error: Exception) {
            BuildResult(false, "${error.javaClass.simpleName}: ${error.message}")
        } finally {
            pidSession.close()
        }
    }

    private fun runCommand(
        command: List<String>,
        timeoutSeconds: Long,
        workingDirectory: File = workspace,
        configureEnvironment: (MutableMap<String, String>) -> Unit = {},
    ): BuildResult {
        if (cancelled.get()) return BuildResult(false, "Build cancelled")
        val pidSession = BuildPidRegistry.open(context.cacheDir)
        return try {
            val commandLog = File(workspace, ".last-command.log")
            commandLog.delete()
            val process = ProcessBuilder(command)
                .directory(workingDirectory)
                .redirectErrorStream(true)
                .redirectOutput(commandLog)
                .apply {
                    environment()["LD_LIBRARY_PATH"] =
                        "${toolchain.nativeDependencies.absolutePath}:${context.applicationInfo.nativeLibraryDir}"
                    environment()["FOLDCODE_LLD_CORE"] =
                        File(toolchain.nativeDependencies, "libfoldlldcore.so").absolutePath
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                    environment()["HOME"] = workspace.absolutePath
                    configureEnvironment(environment())
                    pidSession.configureNative(
                        environment(),
                        File(context.applicationInfo.nativeLibraryDir),
                    )
                }
                .start()
                .also(pidSession::attach)
            activeProcess.set(process)
            try {
                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (cancelled.get()) {
                    BuildResult(false, "Build cancelled")
                } else if (!finished) {
                    process.destroyForcibly()
                    BuildResult(false, "Process stopped after ${timeoutSeconds}s timeout")
                } else {
                    val output = commandLog.takeIf { it.isFile }?.readText().orEmpty()
                    val exitCode = process.exitValue()
                    val renderedOutput = if (output.isBlank() && exitCode != 0) "Process exited with $exitCode" else output
                    BuildResult(exitCode == 0, renderedOutput)
                }
            } finally {
                activeProcess.compareAndSet(process, null)
            }
        } catch (error: Exception) {
            BuildResult(false, "${error.javaClass.simpleName}: ${error.message}")
        } finally {
            pidSession.close()
        }
    }
}
