package dev.foldcode.ide

import android.content.Context
import android.os.Process as AndroidProcess
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class GnuProjectLanguage(val displayName: String) {
    Fortran("Fortran"),
    Cobol("COBOL"),
}

/** Builds GNU Fortran and GnuCOBOL console programs inside the extension rootfs. */
internal class GnuLanguagesCompilerEngine(private val context: Context) {
    private val cancelled = AtomicBoolean(false)
    private val activeProcess = AtomicReference<Process?>(null)
    // context.filesDir may be reported through Android's legacy /data/data
    // alias. PRoot and generated ELF interpreters need the canonical app data
    // mount. Some Samsung releases also return the alias from ApplicationInfo,
    // so derive Android's real per-user data mount explicitly.
    private val appFilesDirectory: File
        get() = File("/data/user/${AndroidProcess.myUid() / 100_000}/${context.packageName}/files")

    fun detect(files: Map<String, String>): GnuProjectLanguage? = when {
        files.keys.any(::isFortranSource) -> GnuProjectLanguage.Fortran
        files.keys.any(::isCobolSource) -> GnuProjectLanguage.Cobol
        else -> null
    }

    fun compileAndRun(
        project: File,
        language: GnuProjectLanguage,
        debug: Boolean = false,
        onProgramStarted: () -> Unit = {},
        onProgramOutput: (String) -> Unit = {},
    ): BuildResult {
        cancelled.set(false)
        val manager = FoldCodeExtensionManager(context)
        if (!manager.isGnuLanguagesInstalled()) {
            return BuildResult(false, "Install the Fortran & COBOL extension first")
        }
        runCatching { manager.installGnuLanguagesRuntime() }.onFailure {
            return BuildResult(false, "${language.displayName} runtime installation failed: ${it.message}")
        }
        val sources = project.walkTopDown()
            .filter(File::isFile)
            .filter { file ->
                val relative = file.relativeTo(project).invariantSeparatorsPath
                !relative.startsWith("build/") && when (language) {
                    GnuProjectLanguage.Fortran -> isFortranSource(relative)
                    GnuProjectLanguage.Cobol -> isCobolSource(relative)
                }
            }
            .map(File::getAbsolutePath)
            .sorted()
            .toList()
        if (sources.isEmpty()) return BuildResult(false, "No ${language.displayName} source files found")

        val buildDirectory = File(
            appFilesDirectory,
            "gnu-language-builds/${project.absolutePath.hashCode().toUInt().toString(16)}",
        ).apply { mkdirs() }
        val program = File(buildDirectory, "program").apply { delete() }
        val compiler = when (language) {
            GnuProjectLanguage.Fortran -> "gfortran"
            GnuProjectLanguage.Cobol -> "cobc"
        }
        val arguments = when (language) {
            GnuProjectLanguage.Fortran -> listOf("-std=f2018", "-Wall", "-Wextra") +
                (if (debug) listOf("-O0", "-g") else emptyList()) + sources + listOf("-o", program.absolutePath)
            // FoldCode COBOL projects use modern free-format source. Pass the
            // mode explicitly instead of relying on a source directive that
            // older GnuCOBOL releases may parse as fixed-column input.
            GnuProjectLanguage.Cobol -> listOf("-x", "-free", "-Wall") +
                (if (debug) listOf("-O0", "-g") else emptyList()) + sources + listOf("-o", program.absolutePath)
        }
        val firstCompile = runTool(project, compiler, arguments)
        val retriedInternalCompiler = !firstCompile.succeeded &&
            isTransientGnuInternalCompilerLaunchFailure(firstCompile.output)
        if (retriedInternalCompiler) program.delete()
        val compile = if (retriedInternalCompiler) {
            runTool(project, compiler, arguments)
        } else {
            firstCompile
        }
        if (!compile.succeeded || !program.isFile) {
            return BuildResult(false, buildString {
                append("$ ").append(compiler).append(' ').append(arguments.joinToString(" ")).append('\n')
                if (retriedInternalCompiler) {
                    appendLine(firstCompile.output.trimEnd())
                    appendLine("GCC internal compiler failed to start; automatic retry also failed:")
                }
                append(compile.output.ifBlank { "$compiler did not produce an executable" })
            })
        }
        if (cancelled.get()) return BuildResult(false, "Build cancelled")

        val process = start(project, "run", listOf(program.absolutePath))
            .getOrElse { return BuildResult(false, "Could not run ${language.displayName} program: ${it.message}") }
        activeProcess.set(process)
        onProgramStarted()
        val output = StringBuilder()
        return try {
            val reader = InputStreamReader(process.inputStream, Charsets.UTF_8)
            val buffer = CharArray(1024)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                val chunk = String(buffer, 0, count)
                output.append(chunk)
                onProgramOutput(chunk)
            }
            val exitCode = process.waitFor()
            BuildResult(
                exitCode == 0 && !cancelled.get(),
                buildString {
                    append("$ ").append(compiler).append(" … -o program\n$ ./program\n")
                    if (retriedInternalCompiler) {
                        appendLine("Recovered a transient GCC internal-compiler launch failure automatically.")
                    }
                    append(
                        when {
                            cancelled.get() -> "Run cancelled"
                            output.isNotEmpty() -> output
                            exitCode != 0 -> "Program exited with $exitCode"
                            else -> "Program finished"
                        },
                    )
                },
            )
        } finally {
            activeProcess.compareAndSet(process, null)
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

    fun cancel() {
        cancelled.set(true)
        activeProcess.get()?.terminateTree()
    }

    private fun runTool(project: File, tool: String, arguments: List<String>): BuildResult {
        val process = start(project, tool, arguments)
            .getOrElse { return BuildResult(false, "Could not start $tool: ${it.message}") }
        activeProcess.set(process)
        return try {
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            BuildResult(exitCode == 0 && !cancelled.get(), output.trimEnd())
        } finally {
            activeProcess.compareAndSet(process, null)
        }
    }

    private fun start(project: File, tool: String, arguments: List<String>): Result<Process> = runCatching {
        val runtime = File(appFilesDirectory, "gnu-languages-runtime")
        val launcher = File(context.applicationInfo.nativeLibraryDir, "foldgnulang.so")
        require(launcher.canExecute()) { "FoldCode GNU Languages launcher is unavailable" }
        ProcessBuilder(listOf(launcher.absolutePath) + arguments)
            .directory(project)
            .redirectErrorStream(true)
            .apply {
                environment()["FOLDCODE_GNU_LANGUAGES_ROOT"] = runtime.absolutePath
                environment()["FOLDCODE_GNU_LANGUAGES_TOOL"] = tool
                environment()["HOME"] = project.absolutePath
                environment()["TMPDIR"] = File(runtime, "tmp").apply { mkdirs() }.absolutePath
            }
            .start()
    }
}

internal fun isFortranSource(path: String): Boolean = path.substringAfterLast('.').lowercase() in
    setOf("f", "for", "f77", "f90", "f95", "f03", "f08", "f18")

internal fun isCobolSource(path: String): Boolean = path.substringAfterLast('.').lowercase() in
    setOf("cob", "cbl", "cpy")

internal fun isTransientGnuInternalCompilerLaunchFailure(output: String): Boolean = Regex(
    "cannot execute ['\"][^'\"]*/(?:cc1|cc1plus|f951|collect2|lto1|lto-wrapper)['\"]: execv: No such file or directory",
    RegexOption.IGNORE_CASE,
).containsMatchIn(output)
