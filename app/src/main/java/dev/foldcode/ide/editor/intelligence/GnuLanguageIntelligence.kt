package dev.foldcode.ide

import android.content.Context
import android.os.Process as AndroidProcess
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight project index for the GNU language extension.
 *
 * Fortran can later delegate to fortls without changing the editor contract.
 * COBOL intentionally uses the same contract because the available COBOL LSPs
 * require a Java/native runtime that is disproportionate on Android.
 */
internal class GnuLanguageIntelligence(context: Context) : Closeable {
    private companion object {
        const val TAG = "FoldCodeGnuIntelligence"
    }

    private val applicationContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FoldCode-gnu-language-intelligence").apply { isDaemon = true }
    }
    private val completionGeneration = AtomicLong(0)
    private val diagnosticsGeneration = AtomicLong(0)
    private val activeDiagnosticsProcess = AtomicReference<Process?>(null)
    private val diagnosticsCache = AtomicReference<CachedGnuDiagnostics?>(null)

    private val appFilesDirectory: File
        get() = File(
            "/data/user/${AndroidProcess.myUid() / 100_000}/${applicationContext.packageName}/files",
        )

    fun requestCompletion(
        relativeFile: String,
        source: String,
        cursor: Int,
        projectSources: Map<String, String>,
        callback: (List<CompletionItem>) -> Unit,
    ): Long {
        val ticket = completionGeneration.incrementAndGet()
        executor.execute {
            val sources = projectSources + (relativeFile to source)
            val result = gnuLanguageCompletions(relativeFile, source, cursor, sources)
            if (ticket == completionGeneration.get()) callback(result)
        }
        return ticket
    }

    fun requestDiagnostics(
        projectDirectory: File,
        relativeFile: String,
        source: String,
        projectSources: Map<String, String>,
        callback: (List<CodeDiagnostic>) -> Unit,
    ): Long {
        val ticket = diagnosticsGeneration.incrementAndGet()
        activeDiagnosticsProcess.getAndSet(null)?.terminateTree()
        executor.execute {
            if (ticket != diagnosticsGeneration.get()) return@execute
            val sources = projectSources + (relativeFile to source)
            val cacheKey = gnuDiagnosticsCacheKey(projectDirectory, relativeFile, sources)
            val structural = gnuLanguageDiagnostics(relativeFile, source)
            val result = try {
                val cached = diagnosticsCache.get()?.takeIf { it.key == cacheKey }
                cached?.diagnostics ?: runCompilerDiagnostics(
                    projectDirectory = projectDirectory,
                    relativeFile = relativeFile,
                    projectSources = sources,
                    ticket = ticket,
                ).also { diagnosticsCache.set(CachedGnuDiagnostics(cacheKey, it)) }
            } catch (error: Exception) {
                // Diagnostics are advisory. A compiler/runtime/filesystem failure
                // must never escape the executor and terminate the Android app.
                if (ticket == diagnosticsGeneration.get()) {
                    Log.w(TAG, "GNU language diagnostics failed", error)
                }
                structural
            }
            if (ticket == diagnosticsGeneration.get()) callback(result)
        }
        return ticket
    }

    fun reset() {
        completionGeneration.incrementAndGet()
        diagnosticsGeneration.incrementAndGet()
        activeDiagnosticsProcess.getAndSet(null)?.terminateTree()
        diagnosticsCache.set(null)
    }

    override fun close() {
        reset()
        executor.shutdownNow()
    }

    private fun runCompilerDiagnostics(
        projectDirectory: File,
        relativeFile: String,
        projectSources: Map<String, String>,
        ticket: Long,
    ): List<CodeDiagnostic> {
        val structural = gnuLanguageDiagnostics(relativeFile, projectSources[relativeFile].orEmpty())
        val language = when {
            isFortranSource(relativeFile) -> GnuProjectLanguage.Fortran
            isCobolSource(relativeFile) -> GnuProjectLanguage.Cobol
            else -> return structural
        }
        val runtime = File(appFilesDirectory, "gnu-languages-runtime")
        val launcher = File(applicationContext.applicationInfo.nativeLibraryDir, "foldgnulang.so")
        if (!launcher.canExecute() || !File(runtime, "rootfs/usr/bin/${if (language == GnuProjectLanguage.Fortran) "gfortran" else "cobc"}").isFile) {
            return structural
        }

        val mirror = File(
            applicationContext.cacheDir,
            "gnu-diagnostics/${projectDirectory.absolutePath.hashCode().toUInt().toString(16)}",
        )
        val mirroredSources = mirrorProjectSources(mirror, projectSources)
        val current = mirroredSources[relativeFile] ?: return structural
        val moduleDirectory = File(mirror, ".foldcode-modules").apply { mkdirs() }
        val tool: String
        val arguments: List<String>
        if (language == GnuProjectLanguage.Fortran) {
            tool = "gfortran"
            val fortranFiles = mirroredSources
                .filterKeys(::isFortranSource)
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, File>> {
                    fortranModule.containsMatchIn(projectSources[it.key].orEmpty())
                }.thenBy { it.key })
                .map { it.value.absolutePath }
            arguments = listOf(
                "-std=f2018", "-Wall", "-Wextra", "-fsyntax-only",
                "-J", moduleDirectory.absolutePath,
                "-I", moduleDirectory.absolutePath,
            ) + fortranFiles
        } else {
            tool = "cobc"
            arguments = cobolCompilerDiagnosticArguments(mirror, current)
        }

        if (ticket != diagnosticsGeneration.get()) return structural
        val process = runCatching {
            ProcessBuilder(listOf(launcher.absolutePath) + arguments)
                .directory(mirror)
                .redirectErrorStream(true)
                .apply {
                    environment()["FOLDCODE_GNU_LANGUAGES_ROOT"] = runtime.absolutePath
                    environment()["FOLDCODE_GNU_LANGUAGES_TOOL"] = tool
                    environment()["HOME"] = mirror.absolutePath
                    environment()["TMPDIR"] = File(runtime, "tmp").apply { mkdirs() }.absolutePath
                }
                .start()
        }.getOrElse { return structural }
        activeDiagnosticsProcess.set(process)
        return try {
            val output = readGnuCompilerOutput(
                readOutput = { process.inputStream.bufferedReader().use { it.readText() } },
                waitForExit = { process.waitFor() },
            ) ?: run {
                if (process.isAlive) process.terminateTree()
                return structural
            }
            if (ticket != diagnosticsGeneration.get()) structural
            else (structural + parseDiagnostics(output)).distinct()
        } finally {
            activeDiagnosticsProcess.compareAndSet(process, null)
        }
    }

    private fun mirrorProjectSources(
        mirror: File,
        projectSources: Map<String, String>,
    ): Map<String, File> {
        mirror.mkdirs()
        val accepted = projectSources.filterKeys { path ->
            !path.startsWith("build/") &&
                (isFortranSource(path) || isCobolSource(path) || path.endsWith(".inc", true))
        }
        val expected = accepted.keys.toSet()
        mirror.walkTopDown()
            .filter(File::isFile)
            .filterNot { it.relativeTo(mirror).invariantSeparatorsPath.startsWith(".foldcode-modules/") }
            .filter { it.relativeTo(mirror).invariantSeparatorsPath !in expected }
            .forEach(File::delete)
        return accepted.mapValues { (relative, content) ->
            val destination = File(mirror, relative).canonicalFile
            require(destination.toPath().startsWith(mirror.canonicalFile.toPath())) { "Invalid project path" }
            destination.parentFile?.mkdirs()
            // Editors commonly keep the final line without a newline. Add one
            // only in the disposable mirror so GNU's -Wmissing-newline does
            // not underline otherwise valid source while the user is typing.
            val compilerSource = if (content.endsWith('\n')) content else "$content\n"
            if (!destination.isFile || destination.readText() != compilerSource) destination.writeText(compilerSource)
            destination
        }
    }
}

/** A diagnostics process being closed by reset/build is normal cancellation. */
internal fun readGnuCompilerOutput(
    readOutput: () -> String,
    waitForExit: () -> Unit,
): String? = try {
    val output = readOutput()
    waitForExit()
    output
} catch (_: IOException) {
    null
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    null
}

internal fun cobolCompilerDiagnosticArguments(mirror: File, current: File): List<String> = listOf(
    // FoldCode creates and builds free-format COBOL projects. Keep the live
    // checker in the same source mode; otherwise columns 1-6 are interpreted
    // as the fixed-format sequence area and every source line is rejected.
    "-free", "-fsyntax-only", "-Wall",
    "-I", mirror.absolutePath,
    "-I", current.parentFile?.absolutePath ?: mirror.absolutePath,
    current.absolutePath,
)

private data class CachedGnuDiagnostics(val key: Int, val diagnostics: List<CodeDiagnostic>)

private fun gnuDiagnosticsCacheKey(project: File, relativeFile: String, sources: Map<String, String>): Int {
    var result = 31 * project.absolutePath.hashCode() + relativeFile.hashCode()
    sources.entries.sortedBy { it.key }.forEach { (path, content) ->
        if (isFortranSource(path) || isCobolSource(path) || path.endsWith(".inc", true)) {
            result = 31 * result + path.hashCode()
            result = 31 * result + content.hashCode()
        }
    }
    return result
}

private data class GnuSymbol(
    val name: String,
    val insertion: String = name,
    val detail: String,
    val category: String,
    val owner: String? = null,
)

internal fun gnuLanguageCompletions(
    relativeFile: String,
    source: String,
    cursor: Int,
    projectSources: Map<String, String>,
): List<CompletionItem> = when {
    isFortranSource(relativeFile) -> fortranProjectCompletions(source, cursor, projectSources)
    isCobolSource(relativeFile) -> cobolProjectCompletions(source, cursor, projectSources)
    else -> emptyList()
}

internal fun gnuLanguageDiagnostics(relativeFile: String, source: String): List<CodeDiagnostic> = when {
    isFortranSource(relativeFile) -> fortranStructuralDiagnostics(relativeFile, source)
    isCobolSource(relativeFile) -> cobolStructuralDiagnostics(relativeFile, source)
    else -> emptyList()
}

private val fortranModule = Regex("(?im)^\\s*module\\s+(?!procedure\\b)([a-z_][a-z0-9_]*)")
private val fortranProcedure = Regex(
    "(?im)^\\s*(?:(?:pure|elemental|recursive|impure)\\s+)*(?:[a-z][a-z0-9_]*(?:\\s*\\([^)]*\\))?\\s+)?(subroutine|function)\\s+([a-z_][a-z0-9_]*)\\s*(?:\\(([^)]*)\\))?",
)
private val fortranType = Regex("(?im)^\\s*type\\s*(?:,\\s*[^:]*)?::\\s*([a-z_][a-z0-9_]*)")
private val fortranTypeBlock = Regex(
    "(?ims)^\\s*type\\s*(?:,\\s*[^:]*)?::\\s*([a-z_][a-z0-9_]*)[^\\n]*\\n(.*?)^\\s*end\\s*type\\b",
)
private val fortranDeclaration = Regex(
    "(?im)^\\s*(integer|real|double\\s+precision|complex|logical|character(?:\\s*\\([^)]*\\))?|type\\s*\\([^)]+\\)|class\\s*\\([^)]+\\))\\s*(?:,\\s*[^:]*)?\\s*(?:::\\s*|\\s+)([^!\\n]+)$",
)

private fun fortranProjectCompletions(
    source: String,
    cursor: Int,
    projectSources: Map<String, String>,
): List<CompletionItem> {
    val symbols = buildList {
        projectSources.filterKeys(::isFortranSource).forEach { (file, text) ->
            fortranModule.findAll(text).forEach { match ->
                add(GnuSymbol(match.groupValues[1], detail = "module · $file", category = "module"))
            }
            fortranType.findAll(text).forEach { match ->
                add(GnuSymbol(match.groupValues[1], detail = "derived type · $file", category = "type"))
            }
            fortranTypeBlock.findAll(text).forEach { typeMatch ->
                val owner = typeMatch.groupValues[1]
                fortranDeclaration.findAll(typeMatch.groupValues[2]).forEach { declaration ->
                    val declaredType = declaration.groupValues[1].trim().replace(Regex("\\s+"), " ")
                    declarationNames(declaration.groupValues[2]).forEach { name ->
                        add(GnuSymbol(name, detail = "$declaredType field · $owner", category = "field", owner = owner))
                    }
                }
            }
            fortranProcedure.findAll(text).forEach { match ->
                val kind = match.groupValues[1].lowercase()
                val name = match.groupValues[2]
                val arguments = match.groupValues[3].trim()
                add(
                    GnuSymbol(
                        name,
                        "$name(",
                        "$kind $name(${arguments}) · $file",
                        "procedure",
                    ),
                )
            }
            fortranDeclaration.findAll(text).forEach { match ->
                val declaredType = match.groupValues[1].trim().replace(Regex("\\s+"), " ")
                val owner = Regex("(?i)^(?:type|class)\\s*\\(([^)]+)\\)$")
                    .matchEntire(declaredType)?.groupValues?.get(1)
                declarationNames(match.groupValues[2]).forEach { name ->
                    add(GnuSymbol(name, detail = "$declaredType · $file", category = "variable", owner = owner))
                }
            }
        }
    }.distinctBy { "${it.category}:${it.name.lowercase()}" }

    val beforeCursor = source.take(cursor.coerceIn(0, source.length))
    val currentLine = beforeCursor.substringAfterLast('\n')
    val member = Regex("([a-z_][a-z0-9_]*)%[a-z0-9_]*$", RegexOption.IGNORE_CASE)
        .find(currentLine)?.groupValues?.get(1)
    val variableType = member?.let { variable ->
        symbols.firstOrNull { it.category == "variable" && it.name.equals(variable, true) }?.owner
    }
    val preferred = when {
        Regex("(?i)\\buse\\s+[a-z0-9_]*$").containsMatchIn(currentLine) -> symbols.filter { it.category == "module" }
        Regex("(?i)\\bcall\\s+[a-z0-9_]*$").containsMatchIn(currentLine) -> symbols.filter { it.category == "procedure" }
        variableType != null -> symbols.filter { it.category == "field" && it.owner?.equals(variableType, true) == true }
        else -> symbols
    }
    val indexed = (preferred + symbols).distinctBy { it.name.lowercase() }.map {
        CompletionItem(it.name, it.insertion, it.detail)
    }
    return (indexed + completionItems(source, "file.f90"))
        .distinctBy { it.label.lowercase() }
        .take(512)
}

private fun declarationNames(value: String): List<String> = value.split(',').mapNotNull { item ->
    Regex("^\\s*([a-z_][a-z0-9_]*)", RegexOption.IGNORE_CASE).find(item.substringBefore('='))?.groupValues?.get(1)
}

private val cobolDataItem = Regex(
    "(?im)^\\s*(?:0?[1-9]|[1-4][0-9]|66|77|88)\\s+([a-z][a-z0-9-]*)(?:\\s+[^.\\n]*?\\b(?:pic|picture)\\s+([^\\s.]+))?",
)
private val cobolProgram = Regex("(?im)^\\s*program-id\\.\\s*([a-z][a-z0-9-]*)")
private val cobolSection = Regex("(?im)^\\s*([a-z][a-z0-9-]*)\\s+section\\s*\\.")
private val cobolParagraph = Regex("(?im)^\\s*([a-z][a-z0-9-]*)\\s*\\.\\s*$")
private val cobolReservedParagraphs = setOf(
    "identification", "environment", "data", "procedure", "configuration", "input-output",
    "file", "working-storage", "local-storage", "linkage", "screen", "report",
)

private fun cobolProjectCompletions(
    source: String,
    cursor: Int,
    projectSources: Map<String, String>,
): List<CompletionItem> {
    val symbols = buildList {
        projectSources.filterKeys(::isCobolSource).forEach { (file, text) ->
            cobolProgram.findAll(text).forEach { match ->
                add(GnuSymbol(match.groupValues[1], detail = "COBOL program · $file", category = "program"))
            }
            cobolDataItem.findAll(text).forEach { match ->
                val picture = match.groupValues[2].ifBlank { "data item" }
                add(GnuSymbol(match.groupValues[1], detail = "$picture · $file", category = "data"))
            }
            cobolSection.findAll(text).forEach { match ->
                add(GnuSymbol(match.groupValues[1], detail = "section · $file", category = "paragraph"))
            }
            cobolParagraph.findAll(text).forEach { match ->
                val name = match.groupValues[1]
                if (name.lowercase() !in cobolReservedParagraphs) {
                    add(GnuSymbol(name, detail = "paragraph · $file", category = "paragraph"))
                }
            }
        }
    }.distinctBy { "${it.category}:${it.name.lowercase()}" }
    val currentLine = source.take(cursor.coerceIn(0, source.length)).substringAfterLast('\n')
    val preferred = when {
        Regex("(?i)\\bperform\\s+[a-z0-9-]*$").containsMatchIn(currentLine) -> symbols.filter { it.category == "paragraph" }
        Regex("(?i)\\bcall\\s+[\"']?[a-z0-9-]*$").containsMatchIn(currentLine) -> symbols.filter { it.category == "program" }
        Regex("(?i)\\b(?:accept|display|move|compute|add|subtract|multiply|divide|string|unstring|inspect)\\b").containsMatchIn(currentLine) ->
            symbols.filter { it.category == "data" }
        else -> symbols
    }
    val indexed = (preferred + symbols).distinctBy { it.name.lowercase() }.map {
        CompletionItem(it.name, it.insertion, it.detail)
    }
    return (indexed + completionItems(source, "file.cob"))
        .distinctBy { it.label.lowercase() }
        .take(512)
}

private data class BlockStart(val kind: String, val line: Int, val column: Int)

private fun fortranStructuralDiagnostics(file: String, source: String): List<CodeDiagnostic> {
    val stack = ArrayDeque<BlockStart>()
    val diagnostics = mutableListOf<CodeDiagnostic>()
    source.lineSequence().forEachIndexed { index, raw ->
        val line = stripFortranComment(raw)
        val normalized = line.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        val closeKind = when {
            Regex("^end ?if\\b").containsMatchIn(normalized) -> "if"
            Regex("^end ?do\\b").containsMatchIn(normalized) -> "do"
            Regex("^end ?select\\b").containsMatchIn(normalized) -> "select"
            Regex("^end ?where\\b").containsMatchIn(normalized) -> "where"
            Regex("^end ?associate\\b").containsMatchIn(normalized) -> "associate"
            Regex("^end ?block\\b").containsMatchIn(normalized) -> "block"
            else -> null
        }
        if (closeKind != null) {
            val found = stack.indexOfLast { it.kind == closeKind }
            if (found < 0) diagnostics += diagnostic(file, index + 1, firstColumn(raw), "Unexpected END ${closeKind.uppercase()}")
            else while (stack.size > found) stack.removeLast()
            return@forEachIndexed
        }
        val openKind = when {
            Regex("^if\\s*\\(.*\\)\\s*then\\b").containsMatchIn(normalized) -> "if"
            Regex("^(?:[a-z_][a-z0-9_]*:\\s*)?do\\b").containsMatchIn(normalized) -> "do"
            Regex("^select\\s+(?:case|type|rank)\\b").containsMatchIn(normalized) -> "select"
            Regex("^where\\s*\\(.*\\)\\s*$").containsMatchIn(normalized) -> "where"
            Regex("^associate\\s*\\(").containsMatchIn(normalized) -> "associate"
            normalized == "block" -> "block"
            else -> null
        }
        if (openKind != null) stack.addLast(BlockStart(openKind, index + 1, firstColumn(raw)))
        unclosedQuoteColumn(line)?.let { column ->
            diagnostics += diagnostic(file, index + 1, column, "Unterminated character literal")
        }
    }
    stack.forEach { start ->
        diagnostics += diagnostic(file, start.line, start.column, "Missing END ${start.kind.uppercase()}")
    }
    return diagnostics.distinct()
}

private fun cobolStructuralDiagnostics(file: String, source: String): List<CodeDiagnostic> {
    val diagnostics = mutableListOf<CodeDiagnostic>()
    val upper = source.uppercase(Locale.ROOT)
    listOf("IDENTIFICATION DIVISION" to "IDENTIFICATION DIVISION is missing", "PROCEDURE DIVISION" to "PROCEDURE DIVISION is missing")
        .forEach { (required, message) ->
            if (required !in upper) diagnostics += diagnostic(file, 1, 1, message, "warning")
        }
    val blocks = ArrayDeque<BlockStart>()
    source.lineSequence().forEachIndexed { index, raw ->
        val line = raw.substringBefore("*>")
        val normalized = line.trim().uppercase(Locale.ROOT)
        if (Regex("^IF\\b").containsMatchIn(normalized) && Regex("\\bEND-IF\\b").containsMatchIn(normalized) ||
            Regex("^EVALUATE\\b").containsMatchIn(normalized) && Regex("\\bEND-EVALUATE\\b").containsMatchIn(normalized)
        ) return@forEachIndexed
        val closeKind = when {
            Regex("\\bEND-IF\\b").containsMatchIn(normalized) -> "IF"
            Regex("\\bEND-EVALUATE\\b").containsMatchIn(normalized) -> "EVALUATE"
            Regex("\\bEND-PERFORM\\b").containsMatchIn(normalized) -> "PERFORM"
            else -> null
        }
        if (closeKind != null) {
            val found = blocks.indexOfLast { it.kind == closeKind }
            if (found < 0) diagnostics += diagnostic(file, index + 1, firstColumn(raw), "Unexpected END-$closeKind")
            else while (blocks.size > found) blocks.removeLast()
        } else {
            val openKind = when {
                Regex("^IF\\b").containsMatchIn(normalized) -> "IF"
                Regex("^EVALUATE\\b").containsMatchIn(normalized) -> "EVALUATE"
                Regex("^PERFORM\\s+(?:UNTIL|VARYING)\\b").containsMatchIn(normalized) -> "PERFORM"
                else -> null
            }
            if (openKind != null) blocks.addLast(BlockStart(openKind, index + 1, firstColumn(raw)))
        }
        unclosedQuoteColumn(line)?.let { column ->
            diagnostics += diagnostic(file, index + 1, column, "Unterminated string literal")
        }
        // A period ends every open implicit scope in a COBOL sentence.
        if (normalized.endsWith('.')) blocks.clear()
    }
    blocks.forEach { start ->
        diagnostics += diagnostic(file, start.line, start.column, "Missing END-${start.kind}")
    }
    return diagnostics.distinct()
}

private fun stripFortranComment(line: String): String {
    var quote: Char? = null
    line.forEachIndexed { index, value ->
        if (value == '\'' || value == '"') quote = if (quote == value) null else if (quote == null) value else quote
        if (value == '!' && quote == null) return line.substring(0, index)
    }
    return line
}

private fun unclosedQuoteColumn(line: String): Int? {
    var quote: Char? = null
    var start = -1
    var index = 0
    while (index < line.length) {
        val value = line[index]
        if ((value == '\'' || value == '"') && (index == 0 || line[index - 1] != '\\')) {
            if (quote == value && line.getOrNull(index + 1) == value) {
                index += 2
                continue
            }
            if (quote == value) quote = null else if (quote == null) { quote = value; start = index }
        }
        index++
    }
    return if (quote != null) start + 1 else null
}

private fun firstColumn(line: String): Int = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 1 else it + 1 }

private fun diagnostic(
    file: String,
    line: Int,
    column: Int,
    message: String,
    severity: String = "error",
) = CodeDiagnostic(file, line, column, severity, message)
