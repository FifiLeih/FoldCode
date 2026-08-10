package dev.foldcode.ide

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class ClangIntelligenceResult(
    val completions: List<CompletionItem>,
    val diagnostics: List<CodeDiagnostic>,
)

/**
 * Semantic C/C++ intelligence backed by the same offline Clang frontend used to build.
 *
 * Clang's code-completion endpoint uses the real compile command, headers, defines and
 * target sysroot. Keeping this boundary small also lets us replace the process backend
 * with clangd/LSP later without changing the editor UI.
 */
internal class OfflineClangIntelligence(private val context: Context) {
    private companion object {
        val snapshotIds = AtomicLong(0)
    }
    private val toolchain = BundledToolchain(context)
    private val activeProcess = AtomicReference<Process?>(null)

    fun cancel() {
        activeProcess.getAndSet(null)?.terminateTree()
    }

    fun complete(project: File, relativeFile: String, source: String, cursor: Int): ClangIntelligenceResult {
        if (!isCppSource(relativeFile) || !project.isDirectory) return ClangIntelligenceResult(emptyList(), emptyList())
        val root = project.canonicalFile
        val sourceFile = File(root, relativeFile).canonicalFile
        require(sourceFile.toPath().startsWith(root.toPath())) { "Source file is outside the project" }
        toolchain.install()

        val storedCommand = compileCommand(root, sourceFile)
        // Pico's target flags and SDK include graph come from Full CMake. Until the
        // first configure has produced the database, retain the editor's lightweight
        // fallback suggestions rather than analyzing firmware as an Android program.
        if (storedCommand == null && root.isPicoProjectDirectory()) {
            return ClangIntelligenceResult(emptyList(), emptyList())
        }
        val invocation = storedCommand ?: CompileInvocation(defaultAndroidCommand(root, sourceFile), root)
        val snapshot = analysisSnapshot(sourceFile, source)
        val location = completionLocation(source, cursor, snapshot.file)
        val semanticCommand = sanitizeCompileCommand(invocation.arguments, invocation.directory, sourceFile) +
            analysisIncludeArguments(root, sourceFile) + listOf(
            "-fsyntax-only",
            "-fno-color-diagnostics",
            "-Xclang",
            "-code-completion-at=$location",
            snapshot.file.absolutePath,
        )
        val outputFile = File(snapshot.directory, "completion.log")
        outputFile.delete()
        val process = ProcessBuilder(semanticCommand)
            .directory(invocation.directory)
            .redirectErrorStream(true)
            .redirectOutput(outputFile)
            .apply {
                environment()["LD_LIBRARY_PATH"] =
                    "${toolchain.nativeDependencies.absolutePath}:${context.applicationInfo.nativeLibraryDir}"
                environment()["TMPDIR"] = context.cacheDir.absolutePath
                environment()["HOME"] = context.filesDir.absolutePath
            }
            .start()
        activeProcess.getAndSet(process)?.terminateTree()
        return try {
            if (!process.waitFor(12, TimeUnit.SECONDS)) process.terminateTree()
            val output = outputFile.takeIf(File::isFile)?.readText().orEmpty()
            ClangIntelligenceResult(
                completions = parseClangCompletions(output, completionPrefix(source, cursor)),
                diagnostics = parseDiagnostics(output),
            )
        } finally {
            activeProcess.compareAndSet(process, null)
            snapshot.directory.deleteRecursively()
        }
    }

    fun diagnose(project: File, relativeFile: String, source: String): ClangIntelligenceResult {
        if (!isCppSource(relativeFile) || !project.isDirectory) return ClangIntelligenceResult(emptyList(), emptyList())
        val root = project.canonicalFile
        val sourceFile = File(root, relativeFile).canonicalFile
        require(sourceFile.toPath().startsWith(root.toPath())) { "Source file is outside the project" }
        toolchain.install()
        val storedCommand = compileCommand(root, sourceFile)
        if (storedCommand == null && root.isPicoProjectDirectory()) {
            return ClangIntelligenceResult(emptyList(), emptyList())
        }
        val invocation = storedCommand ?: CompileInvocation(defaultAndroidCommand(root, sourceFile), root)
        val snapshot = analysisSnapshot(sourceFile, source)
        val command = sanitizeCompileCommand(invocation.arguments, invocation.directory, sourceFile) +
            analysisIncludeArguments(root, sourceFile) + listOf(
            "-fsyntax-only",
            "-fno-color-diagnostics",
            snapshot.file.absolutePath,
        )
        val outputFile = File(snapshot.directory, "diagnostics.log")
        outputFile.delete()
        val process = ProcessBuilder(command)
            .directory(invocation.directory)
            .redirectErrorStream(true)
            .redirectOutput(outputFile)
            .apply {
                environment()["LD_LIBRARY_PATH"] =
                    "${toolchain.nativeDependencies.absolutePath}:${context.applicationInfo.nativeLibraryDir}"
                environment()["TMPDIR"] = context.cacheDir.absolutePath
                environment()["HOME"] = context.filesDir.absolutePath
            }
            .start()
        activeProcess.getAndSet(process)?.terminateTree()
        return try {
            if (!process.waitFor(12, TimeUnit.SECONDS)) process.terminateTree()
            val output = outputFile.takeIf(File::isFile)?.readText().orEmpty()
            ClangIntelligenceResult(emptyList(), parseDiagnostics(output))
        } finally {
            activeProcess.compareAndSet(process, null)
            snapshot.directory.deleteRecursively()
        }
    }

    /**
     * Language analysis must never write an editor buffer into the user's project.
     * Autosave is the only owner of project-file writes. A private, per-request
     * snapshot also prevents an obsolete completion task from overwriting newer text.
     */
    private fun analysisSnapshot(sourceFile: File, source: String): AnalysisSnapshot {
        val directory = File(
            context.cacheDir,
            "clang-analysis/${snapshotIds.incrementAndGet().toString(16)}",
        ).apply { mkdirs() }
        val file = File(directory, sourceFile.name)
        file.writeText(source)
        return AnalysisSnapshot(directory, file)
    }

    private fun analysisIncludeArguments(project: File, sourceFile: File): List<String> = buildList {
        add("-I${project.absolutePath}")
        sourceFile.parentFile?.let { add("-I${it.absolutePath}") }
    }.distinct()

    private fun compileCommand(project: File, sourceFile: File): CompileInvocation? {
        val database = File(project, "build/compile_commands.json").takeIf(File::isFile) ?: return null
        if (project.isPicoProjectDirectory() && !isPicoCompileDatabaseUsable(database, privateDataRoots())) {
            return null
        }
        val entries = JSONArray(database.readText())
        var headerFallback: CompileInvocation? = null
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            val directory = File(entry.optString("directory", project.absolutePath)).canonicalFile
            val file = File(entry.optString("file"))
            val resolved = if (file.isAbsolute) file else File(directory, file.path)
            val arguments = entry.optJSONArray("arguments")
            val parsed = if (arguments != null) {
                (0 until arguments.length()).map(arguments::getString)
            } else shellWords(entry.getString("command"))
            if (parsed.isEmpty()) continue
            val invocation = CompileInvocation(listOf(toolchain.clang.absolutePath) + parsed.drop(1), directory)
            if (headerFallback == null) headerFallback = invocation
            if (runCatching { resolved.canonicalFile == sourceFile }.getOrDefault(false)) {
                return invocation
            }
        }
        return headerFallback.takeIf { sourceFile.extension.lowercase() in setOf("h", "hh", "hpp", "hxx") }
    }

    private fun privateDataRoots(): List<File> = listOf(
        File(context.applicationInfo.dataDir),
        File("/data/data/${context.packageName}"),
    )

    private fun defaultAndroidCommand(project: File, sourceFile: File): List<String> = buildList {
        val cpp = sourceFile.extension.lowercase() != "c"
        add(toolchain.clang.absolutePath)
        add(if (cpp) "--driver-mode=g++" else "--driver-mode=gcc")
        add("--target=aarch64-linux-android26")
        add("--sysroot=${toolchain.sysroot.absolutePath}")
        add("-resource-dir=${toolchain.resourceDir.absolutePath}")
        if (cpp) add("-nostdlib++")
        add(if (cpp) "-std=c++20" else "-std=c17")
        add("-I${project.absolutePath}")
        sourceFile.parentFile?.let { add("-I${it.absolutePath}") }
    }

    private fun sanitizeCompileCommand(command: List<String>, directory: File, sourceFile: File): List<String> {
        val result = mutableListOf<String>()
        var skipNext = false
        sanitizePicoRiscvArgumentsForClangd(command).forEachIndexed { index, argument ->
            if (skipNext) {
                skipNext = false
                return@forEachIndexed
            }
            if (index == 0) {
                result += toolchain.clang.absolutePath
                return@forEachIndexed
            }
            if (argument in setOf("-o", "-MF", "-MT", "-MQ", "--serialize-diagnostics")) {
                skipNext = true
                return@forEachIndexed
            }
            if (argument in setOf("-c", "-MMD", "-MD", "-MP")) return@forEachIndexed
            val candidate = if (File(argument).isAbsolute) File(argument) else File(directory, argument)
            val sameSource = runCatching { candidate.canonicalFile == sourceFile }.getOrDefault(false)
            val otherTranslationUnit = !argument.startsWith("-") && candidate.extension.lowercase() in setOf("c", "cc", "cpp", "cxx")
            if (!sameSource && !otherTranslationUnit) result += argument
        }
        return result
    }

    private data class CompileInvocation(val arguments: List<String>, val directory: File)
    private data class AnalysisSnapshot(val directory: File, val file: File)
}

internal fun parseClangCompletions(output: String, prefix: String = ""): List<CompletionItem> {
    val publicCppPriority = listOf(
        "cout", "cin", "cerr", "clog", "endl", "flush", "string", "string_view",
        "vector", "array", "map", "unordered_map", "set", "unordered_set", "optional",
        "variant", "tuple", "pair", "unique_ptr", "shared_ptr", "make_unique", "make_shared",
        "move", "forward", "swap", "sort", "find", "function", "thread", "mutex", "size_t",
    ).withIndex().associate { it.value to it.index }
    return output.lineSequence()
    .mapNotNull { raw ->
        val line = raw.trim()
        if (!line.startsWith("COMPLETION: ")) return@mapNotNull null
        val body = line.removePrefix("COMPLETION: ")
        val separator = body.indexOf(" : ")
        val label = (if (separator >= 0) body.substring(0, separator) else body)
            .removePrefix("Pattern")
            .trim()
        if (!label.matches(Regex("[A-Za-z_~][A-Za-z0-9_:~]*"))) return@mapNotNull null
        val signature = if (separator >= 0) body.substring(separator + 3) else label
        val readable = signature
            .replace("[#", "")
            .replace("#]", "")
            .replace("<#", "")
            .replace("#>", "")
            .replace("{#", "")
            .replace("#}", "")
            .trim()
        val insertion = if ('(' in signature && !label.startsWith("operator")) "$label(" else label
        CompletionItem(label = label.substringAfterLast("::"), insertion = insertion.substringAfterLast("::"), detail = readable)
    }
    .distinctBy(CompletionItem::label)
    .filter { completionMatchScore(it.label, prefix) != null }
    .sortedWith(
        compareBy<CompletionItem> {
            when {
                it.label.equals(prefix, ignoreCase = true) -> 0
                publicCppPriority.containsKey(it.label) -> 1
                it.label.startsWith("_") -> 3
                else -> 2
            }
        }.thenBy { completionMatchScore(it.label, prefix) ?: Int.MAX_VALUE }
            .thenBy { publicCppPriority[it.label] ?: Int.MAX_VALUE }
            .thenBy { it.label.lowercase() },
    )
    .take(80)
    .toList()
}

private fun completionPrefix(source: String, cursor: Int): String {
    val before = source.take(cursor.coerceIn(0, source.length))
    return Regex("[A-Za-z_][A-Za-z0-9_]*$").find(before)?.value.orEmpty()
}

private fun completionLocation(source: String, cursor: Int, file: File): String {
    val safe = cursor.coerceIn(0, source.length)
    val line = source.take(safe).count { it == '\n' } + 1
    val lineStart = source.lastIndexOf('\n', (safe - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val byteColumn = source.substring(lineStart, safe).toByteArray(Charsets.UTF_8).size + 1
    return "${file.absolutePath}:$line:$byteColumn"
}

private fun isCppSource(name: String) = name.lowercase().let {
    it.endsWith(".c") || it.endsWith(".cc") || it.endsWith(".cpp") || it.endsWith(".cxx") ||
        it.endsWith(".h") || it.endsWith(".hh") || it.endsWith(".hpp") || it.endsWith(".hxx")
}
