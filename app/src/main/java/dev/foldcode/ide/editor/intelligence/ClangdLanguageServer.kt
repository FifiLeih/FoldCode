package dev.foldcode.ide

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Reduce clangd's overload signatures to the symbol users search for. */
internal fun normalizeClangdCompletionLabel(rawLabel: String): String {
    val trimmed = rawLabel.trim().trimEnd('"', '>')
    Regex("(?:^|::)(operator\\(\\)|operator\\[\\])").find(trimmed)?.let { return it.groupValues[1] }
    var angleDepth = 0
    var nameEnd = trimmed.length
    for (index in trimmed.indices) {
        when (trimmed[index]) {
            '<' -> {
                val operatorToken = Regex("operator[<>=!&|+*/%^~\\-]*$").containsMatchIn(trimmed.take(index))
                if (!operatorToken) angleDepth++
            }
            '>' -> if (angleDepth > 0) angleDepth--
            '(' -> if (angleDepth == 0) {
                nameEnd = index
                break
            }
        }
    }
    return trimmed.substring(0, nameEnd).substringAfterLast("::").trim()
}

private val preferredClangdCompletions = listOf(
    "cout", "cin", "cerr", "clog", "endl", "flush",
    "string", "string_view", "vector", "array", "span",
    "map", "unordered_map", "set", "unordered_set",
    "optional", "variant", "tuple", "pair",
    "unique_ptr", "shared_ptr", "make_unique", "make_shared",
    "move", "forward", "swap", "sort", "find", "begin", "end",
).withIndex().associate { it.value to it.index }

private fun clangdCompletionPriority(label: String): Int = when {
    label in preferredClangdCompletions -> preferredClangdCompletions.getValue(label)
    label.startsWith("operator") -> 20_000
    label.startsWith('_') -> 30_000
    else -> 1_000
}

/**
 * CMake records the flags for the real GNU RISC-V compiler. The Android clangd
 * process does not infer that target from a `riscv32-unknown-elf-g++` basename,
 * and consequently rejects both the GNU ISA extension string and `ilp32`
 * before it can create an AST. Give only clangd an explicit, conservative
 * target; the firmware compile database itself is never modified.
 */
internal fun sanitizePicoRiscvArgumentsForClangd(arguments: List<String>): List<String> {
    val isRiscv = arguments.any {
        it.contains("riscv32", ignoreCase = true) ||
            it.startsWith("-march=rv32", ignoreCase = true) ||
            it.equals("rv32", ignoreCase = true)
    }
    if (!isRiscv) return arguments
    val sanitized = mutableListOf<String>()
    var skipTargetValue = false
    arguments.forEach { argument ->
        if (skipTargetValue) {
            skipTargetValue = false
            return@forEach
        }
        if (argument.equals("-march", ignoreCase = true) || argument.equals("-mabi", ignoreCase = true)) {
            skipTargetValue = true
            return@forEach
        }
        if (
            argument.startsWith("-march=rv32", ignoreCase = true) ||
            argument.startsWith("-mabi=ilp32", ignoreCase = true)
        ) return@forEach
        sanitized += argument
    }
    if (sanitized.none { it.startsWith("--target=") || it.startsWith("-target=") }) {
        sanitized.add(1.coerceAtMost(sanitized.size), "--target=riscv32-unknown-elf")
    }
    return sanitized
}

internal fun sanitizePicoRiscvCommandForClangd(command: String): String {
    if (!command.contains("riscv32", ignoreCase = true) &&
        !command.contains("-march=rv32", ignoreCase = true)
    ) return command
    val withoutGnuIsa = command
        .replace(Regex("(?:(?<=\\s)|^)-march=(?:['\"])?rv32\\S+", RegexOption.IGNORE_CASE), "")
        .replace(Regex("(?:(?<=\\s)|^)-mabi=(?:['\"])?ilp32\\S*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (withoutGnuIsa.contains(Regex("(?:^|\\s)--?target="))) {
        withoutGnuIsa
    } else {
        "$withoutGnuIsa --target=riscv32-unknown-elf"
    }
}

/** clangd's include cleaner does not understand Pico SDK umbrella headers. */
internal fun isClangdUnusedIncludeDiagnostic(message: String): Boolean =
    message.startsWith("Included header ") && message.contains(" is not used directly")

/** Minimal JSON-RPC/LSP transport for the Android clangd supplied by the C/C++ extension. */
internal class ClangdLanguageServer(private val context: Context) : Closeable {
    private val toolchain = BundledToolchain(context)
    private val writeLock = Any()
    private val requestIds = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, (Any?) -> Unit>()
    private val documentVersions = ConcurrentHashMap<String, AtomicInteger>()
    private val documentTexts = ConcurrentHashMap<String, String>()
    private val documentReady = ConcurrentHashMap<String, CountDownLatch>()
    @Volatile private var process: Process? = null
    @Volatile private var output: BufferedOutputStream? = null
    @Volatile private var projectRoot: File? = null
    @Volatile private var compileConfigurationKey: String? = null
    @Volatile var diagnosticsListener: (String, List<CodeDiagnostic>) -> Unit = { _, _ -> }

    val available: Boolean
        get() = runCatching {
            toolchain.install()
            toolchain.hasLanguageServer
        }.getOrDefault(false)

    @Synchronized
    fun start(project: File): Boolean {
        val canonical = project.canonicalFile
        val buildDirectory = File(canonical, "build")
        val compileDatabase = File(buildDirectory, "compile_commands.json")
        val picoProject = canonical.isPicoProjectDirectory()
        val usableCompileDatabase = compileDatabase.isFile && (
            !picoProject || isPicoCompileDatabaseUsable(compileDatabase, privateDataRoots())
        )
        val compileDirectory = when {
            usableCompileDatabase -> writeClangdCompileDatabase(canonical, compileDatabase)
            // Pico flags, SDK headers and target architecture must come from CMake.
            // Treating an unconfigured firmware project as Android C++ creates false
            // diagnostics, so wait until Configure/Build has produced its database.
            picoProject -> {
                if (projectRoot == canonical) stop()
                return false
            }
            else -> writeAndroidFallbackDatabase(canonical)
        }
        val configurationKey = if (usableCompileDatabase) {
            "sanitized-v5:${compileDatabase.canonicalPath}:${compileDatabase.lastModified()}:${compileDatabase.length()}"
        } else {
            "android:${compileDirectory.canonicalPath}"
        }
        if (
            process?.isAlive == true &&
            projectRoot == canonical &&
            compileConfigurationKey == configurationKey
        ) return true
        stop()
        if (!available) return false
        val command = listOf(
            toolchain.clangd.absolutePath,
            // Foreground AST/preamble analysis is enough for responsive editing.
            // Background-indexing every SDK/system header at the same time caused
            // sustained CPU use and unnecessary heat on phones.
            "--background-index=false",
            "--clang-tidy=false",
            "--completion-style=detailed",
            // Include completion can contain hundreds of implementation headers.
            // Keep enough results for FoldCode to filter internals afterward.
            "--limit-results=500",
            "--header-insertion=never",
            "--log=error",
            "--compile-commands-dir=${compileDirectory.absolutePath}",
        )
        val started = runCatching {
            ProcessBuilder(command)
                .directory(canonical)
                .redirectError(File(context.cacheDir, "clangd-stderr.log"))
                .apply {
                    environment()["FOLDCODE_CLANGD_CORE"] = toolchain.clangdCore.absolutePath
                    environment()["LD_LIBRARY_PATH"] =
                        "${toolchain.nativeDependencies.absolutePath}:${context.applicationInfo.nativeLibraryDir}"
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                    environment()["HOME"] = context.filesDir.absolutePath
                }
                .start()
        }.getOrElse { return false }
        process = started
        output = BufferedOutputStream(started.outputStream)
        projectRoot = canonical
        compileConfigurationKey = configurationKey
        Thread({ readMessages(started) }, "FoldCode-clangd-reader").apply { isDaemon = true }.start()

        val ready = CountDownLatch(1)
        request(
            "initialize",
            JSONObject()
                .put("processId", JSONObject.NULL)
                .put("rootUri", canonical.toURI().toString())
                .put("capabilities", JSONObject()
                    .put("textDocument", JSONObject()
                        .put("synchronization", JSONObject().put("didSave", false))
                        .put("completion", JSONObject().put("completionItem", JSONObject()
                            .put("snippetSupport", false)
                            .put("documentationFormat", JSONArray().put("plaintext"))))))
                .put("clientInfo", JSONObject().put("name", "FoldCode").put("version", "0.1")),
        ) { ready.countDown() }
        if (!ready.await(8, TimeUnit.SECONDS) || started.isAlive.not()) {
            stop()
            return false
        }
        notify("initialized", JSONObject())
        return true
    }

    /**
     * Give ordinary C/C++ folders real FoldCode target flags before their first
     * CMake build. Without this database clangd cannot find libc++ and reports
     * valid names such as std::cout as undeclared.
     */
    private fun writeAndroidFallbackDatabase(project: File): File {
        val directory = File(
            context.cacheDir,
            "clangd-compile-commands/${project.absolutePath.hashCode().toUInt().toString(16)}",
        ).apply { mkdirs() }
        val codeFiles = project.walkTopDown()
            .onEnter { folder ->
                folder == project || folder.name !in setOf("build", ".git", ".foldcode", ".gradle")
            }
            .filter {
                it.isFile && it.extension.lowercase() in
                    setOf("c", "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx", "inc")
            }
            .take(512)
            .toList()
        val sources = codeFiles
            .filter { it.extension.lowercase() in setOf("c", "cc", "cpp", "cxx") }
            .ifEmpty { listOf(File(project, "main.cpp")) }
        val includeDirectories = buildList {
            add(project)
            codeFiles.mapNotNullTo(this) { it.parentFile }
            File(project, "include").takeIf { it.isDirectory }?.let(::add)
        }.map { it.canonicalFile }.distinctBy { it.absolutePath }
        val entries = JSONArray()
        sources.forEach { source ->
            val cpp = source.extension.lowercase() != "c"
            val arguments = JSONArray()
                .put(toolchain.clang.absolutePath)
                .put(if (cpp) "--driver-mode=g++" else "--driver-mode=gcc")
                .put("--target=aarch64-linux-android26")
                .put("--sysroot=${toolchain.sysroot.absolutePath}")
                .put("-resource-dir=${toolchain.resourceDir.absolutePath}")
                .put(if (cpp) "-std=c++20" else "-std=c17")
            includeDirectories.forEach { arguments.put("-I${it.absolutePath}") }
            arguments.put("-c").put(source.absolutePath)
            entries.put(JSONObject()
                .put("directory", project.absolutePath)
                .put("file", source.absolutePath)
                .put("arguments", arguments))
        }
        File(directory, "compile_commands.json").writeText(entries.toString())
        return directory
    }

    /**
     * clangd is an editor parser, not the firmware compiler. Some Pico compile
     * databases contain GCC-only RISC-V ISA strings which the bundled clangd
     * rejects before it can parse the source. Keep the real CMake database
     * untouched and give clangd a compatible copy. The project root is also an
     * explicit include directory because Pico W projects commonly keep
     * lwipopts.h beside CMakeLists.txt.
     */
    private fun writeClangdCompileDatabase(project: File, source: File): File {
        val directory = File(
            context.cacheDir,
            "clangd-project-commands/${project.absolutePath.hashCode().toUInt().toString(16)}",
        ).apply { mkdirs() }
        val output = File(directory, "compile_commands.json")
        val entries = runCatching { JSONArray(source.readText()) }.getOrElse { return source.parentFile!! }
        val projectInclude = "-I${project.absolutePath}"
        repeat(entries.length()) { index ->
            val entry = entries.optJSONObject(index) ?: return@repeat
            val arguments = entry.optJSONArray("arguments")
            if (arguments != null) {
                val original = (0 until arguments.length()).map(arguments::optString)
                val sanitized = sanitizePicoRiscvArgumentsForClangd(original).toMutableList()
                if (sanitized.none { it == projectInclude || it == "-I" + project.absolutePath }) {
                    sanitized += projectInclude
                }
                entry.put("arguments", JSONArray(sanitized))
            } else if (entry.has("command")) {
                val command = sanitizePicoRiscvCommandForClangd(entry.optString("command"))
                entry.put("command", "$command ${shellQuote(projectInclude)}")
            }
        }
        output.writeText(entries.toString())
        return directory
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun privateDataRoots(): List<File> = listOf(
        File(context.applicationInfo.dataDir),
        File("/data/data/${context.packageName}"),
    )

    fun syncDocument(project: File, relativeFile: String, text: String): Boolean {
        if (!start(project)) return false
        val file = File(project, relativeFile).canonicalFile
        val uri = file.toURI().toString()
        if (documentTexts.put(uri, text) == text) return true
        documentReady[uri] = CountDownLatch(1)
        val counter = documentVersions[uri]
        if (counter == null) {
            documentVersions[uri] = AtomicInteger(1)
            notify("textDocument/didOpen", JSONObject().put("textDocument", JSONObject()
                .put("uri", uri)
                .put("languageId", if (File(relativeFile).extension.equals("c", ignoreCase = true)) "c" else "cpp")
                .put("version", 1)
                .put("text", text)))
        } else {
            val version = counter.incrementAndGet()
            notify("textDocument/didChange", JSONObject()
                .put("textDocument", JSONObject().put("uri", uri).put("version", version))
                .put("contentChanges", JSONArray().put(JSONObject().put("text", text))))
        }
        return true
    }

    fun requestCompletion(
        project: File,
        relativeFile: String,
        text: String,
        cursor: Int,
        callback: (List<CompletionItem>) -> Unit,
    ): Long? {
        val uri = File(project, relativeFile).canonicalFile.toURI().toString()
        val alreadySynchronized = documentTexts[uri] == text
        if (!syncDocument(project, relativeFile, text)) return null
        // Wait for clangd's AST worker to publish diagnostics for this version.
        // This is a readiness signal, not a fixed delay: small files proceed in
        // milliseconds, while a cold preamble gets a bounded window to finish.
        // Repeated completion requests on an indexed document remain immediate.
        if (!alreadySynchronized) documentReady[uri]?.await(900, TimeUnit.MILLISECONDS)
        val location = lspPosition(text, cursor)
        val params = JSONObject()
            .put("textDocument", JSONObject().put("uri", uri))
            .put("position", location)
        completionTrigger(text, cursor)?.let { trigger ->
            params.put("context", JSONObject()
                .put("triggerKind", 2)
                .put("triggerCharacter", trigger.toString()))
        }
        return request("textDocument/completion", params) { result ->
            callback(parseCompletions(result))
        }
    }

    fun cancel(requestId: Long?) {
        if (requestId != null && pending.remove(requestId) != null) {
            notify("$/cancelRequest", JSONObject().put("id", requestId))
        }
    }

    @Synchronized
    fun stop() {
        val running = process
        if (running != null) {
            runCatching { request("shutdown", JSONObject()) { } }
            runCatching { notify("exit", JSONObject()) }
            runCatching { output?.flush() }
            if (!runCatching { running.waitFor(400, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
                running.destroy()
            }
            if (running.isAlive) running.destroyForcibly()
        }
        process = null
        output = null
        projectRoot = null
        compileConfigurationKey = null
        pending.clear()
        documentVersions.clear()
        documentTexts.clear()
        documentReady.values.forEach(CountDownLatch::countDown)
        documentReady.clear()
    }

    override fun close() = stop()

    private fun request(method: String, params: JSONObject, callback: (Any?) -> Unit): Long {
        val id = requestIds.getAndIncrement()
        pending[id] = callback
        send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
        return id
    }

    private fun notify(method: String, params: JSONObject) {
        send(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params))
    }

    private fun send(message: JSONObject) {
        val bytes = message.toString().toByteArray(StandardCharsets.UTF_8)
        synchronized(writeLock) {
            val stream = output ?: return
            stream.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            stream.write(bytes)
            stream.flush()
        }
    }

    private fun readMessages(owner: Process) {
        val input = BufferedInputStream(owner.inputStream)
        try {
            while (owner.isAlive) {
                var contentLength = -1
                while (true) {
                    val header = readAsciiLine(input) ?: return
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = header.substringAfter(':').trim().toIntOrNull() ?: -1
                    }
                }
                if (contentLength < 0) continue
                val body = ByteArray(contentLength)
                var offset = 0
                while (offset < body.size) {
                    val count = input.read(body, offset, body.size - offset)
                    if (count < 0) return
                    offset += count
                }
                runCatching { handle(JSONObject(String(body, StandardCharsets.UTF_8))) }
                    .onFailure { error ->
                        // A malformed or unexpected language-server payload must
                        // never terminate FoldCode's process. Keep the transport
                        // alive and allow later diagnostics/completions to proceed.
                        Log.e("FoldCodeClangd", "Ignoring invalid clangd message", error)
                    }
            }
        } finally {
            if (process === owner) stop()
        }
    }

    private fun handle(message: JSONObject) {
        if (message.has("id") && message.has("method")) {
            respondToServerRequest(message)
            return
        }
        if (message.has("id") && !message.has("method")) {
            val id = message.optLong("id", -1)
            pending.remove(id)?.invoke(message.opt("result"))
            return
        }
        if (message.optString("method") == "textDocument/publishDiagnostics") {
            val params = message.optJSONObject("params") ?: return
            val uri = params.optString("uri")
            val publishedVersion = params.optInt("version", -1)
            val currentVersion = documentVersions[uri]?.get() ?: -1
            if (publishedVersion >= 0 && currentVersion >= 0 && publishedVersion < currentVersion) return
            documentReady.remove(uri)?.countDown()
            val relative = runCatching {
                projectRoot?.toPath()?.relativize(File(java.net.URI(uri)).canonicalFile.toPath())?.toString()
            }.getOrNull() ?: File(runCatching { java.net.URI(uri) }.getOrNull()?.path.orEmpty()).name
            val diagnostics = parseDiagnostics(relative, params.optJSONArray("diagnostics") ?: JSONArray())
                .filterNot { diagnostic ->
                    projectRoot?.isPicoProjectDirectory() == true &&
                        isClangdUnusedIncludeDiagnostic(diagnostic.message)
                }
            diagnosticsListener(relative, diagnostics)
        }
    }

    /**
     * clangd can ask a small number of client-side questions while starting or
     * indexing. Answer them instead of leaving the server waiting forever.
     */
    private fun respondToServerRequest(message: JSONObject) {
        val result: Any = when (message.optString("method")) {
            "workspace/configuration" -> JSONArray()
            "window/workDoneProgress/create" -> JSONObject.NULL
            else -> JSONObject.NULL
        }
        send(JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", message.opt("id"))
            .put("result", result))
    }

    private fun parseCompletions(value: Any?): List<CompletionItem> {
        val items = when (value) {
            is JSONArray -> value
            is JSONObject -> value.optJSONArray("items") ?: JSONArray()
            else -> JSONArray()
        }
        val parsed = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val rawLabel = item.optString("label").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val editText = item.optJSONObject("textEdit")?.optString("newText")
            // textEdit is clangd's authoritative replacement. insertText is only
            // a fallback and can be incomplete for preprocessor/header items.
            val insertion = editText
                ?: item.optString("insertText").takeIf(String::isNotBlank)
                ?: rawLabel
            val label = normalizeClangdCompletionLabel(rawLabel)
            CompletionItem(
                label,
                stripSnippet(insertion),
                item.optString("detail", "C/C++ symbol"),
                lspCompletionCategory(item.optInt("kind")),
            )
        }
        return parsed
            .groupBy { it.label }
            .map { (_, overloads) ->
                val first = overloads.first()
                if (overloads.size == 1) first
                else first.copy(category = "${first.displayCategory()} · ${overloads.size}")
            }
            .sortedWith(compareBy<CompletionItem> { clangdCompletionPriority(it.label) }
                .thenBy { it.label.lowercase() })
            .take(512)
    }

    private fun parseDiagnostics(file: String, values: JSONArray): List<CodeDiagnostic> =
        (0 until values.length()).mapNotNull { index ->
            val value = values.optJSONObject(index) ?: return@mapNotNull null
            val start = value.optJSONObject("range")?.optJSONObject("start") ?: return@mapNotNull null
            val severity = when (value.optInt("severity", 1)) {
                1 -> "error"
                2 -> "warning"
                else -> "note"
            }
            CodeDiagnostic(file, start.optInt("line") + 1, start.optInt("character") + 1, severity, value.optString("message"))
        }

    private fun lspPosition(text: String, cursor: Int): JSONObject {
        val safe = cursor.coerceIn(0, text.length)
        val before = text.substring(0, safe)
        val lineStart = before.lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
        return JSONObject().put("line", before.count { it == '\n' }).put("character", safe - lineStart)
    }

    private fun completionTrigger(text: String, cursor: Int): Char? {
        val safe = cursor.coerceIn(0, text.length)
        if (safe == 0) return null
        val last = text[safe - 1]
        if (last in listOf('.', '>', ':', '<', '"', '/')) return last
        return null
    }

    private fun stripSnippet(value: String): String = runCatching {
        value
            // ICU's regex engine requires the closing brace to be escaped.
            .replace(Regex("\\$\\{\\d+:([^}]*)\\}"), "$1")
            .replace(Regex("\\$\\{\\d+\\}"), "")
            .replace(Regex("\\$\\d+"), "")
    }.getOrDefault(value)

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(StandardCharsets.US_ASCII)
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
    }
}

/** Recognize imported desktop Pico projects even before FoldCode metadata exists. */
internal fun File.isPicoProjectDirectory(): Boolean {
    if (File(this, PICO_PROJECT_MARKER).isFile) return true
    val cmake = File(this, "CMakeLists.txt").takeIf(File::isFile)?.runCatching { readText() }?.getOrNull()
        ?: return false
    return isPicoSdkProject(mapOf("CMakeLists.txt" to cmake))
}

/** Owns one language-server session and a single-flight compiler fallback. */
internal class ClangIntelligenceService(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val clangd = ClangdLanguageServer(appContext)
    private val fallback = OfflineClangIntelligence(appContext)
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "FoldCode-intelligence") }
    private val generation = AtomicInteger()
    @Volatile private var paused = false
    @Volatile private var completionRequest: Long? = null
    @Volatile private var activeProject: File? = null
    @Volatile private var lastDiagnosticsKey: String? = null
    @Volatile private var lastDiagnostics: List<CodeDiagnostic> = emptyList()
    @Volatile private var inFlightKey: String? = null
    @Volatile var diagnosticsListener: (String, List<CodeDiagnostic>) -> Unit = { _, _ -> }

    init {
        clangd.diagnosticsListener = { file, diagnostics ->
            if (!paused) {
                // Published diagnostics are authoritative for the currently
                // synchronized document. Do not let an old fallback cache
                // replace them on the next request.
                lastDiagnosticsKey = null
                lastDiagnostics = diagnostics
                diagnosticsListener(file, diagnostics)
            }
        }
    }

    fun requestCompletion(
        project: File,
        relativeFile: String,
        source: String,
        cursor: Int,
        callback: (ClangIntelligenceResult) -> Unit,
    ) {
        if (paused) return
        val key = analysisKey(project, relativeFile, source)
        val requestGeneration = generation.incrementAndGet()
        inFlightKey = key
        clangd.cancel(completionRequest)
        fallback.cancel()
        executor.execute {
            if (paused || requestGeneration != generation.get()) return@execute
            switchProject(project)
            if (clangd.start(project)) {
                completionRequest = clangd.requestCompletion(project, relativeFile, source, cursor) { completions ->
                    if (!paused && requestGeneration == generation.get()) {
                        val result = ClangIntelligenceResult(completions, emptyList())
                        inFlightKey = null
                        callback(result)
                    }
                }
            } else {
                val result = runCatching { fallback.complete(project, relativeFile, source, cursor) }.getOrNull() ?: return@execute
                if (!paused && requestGeneration == generation.get()) {
                    lastDiagnosticsKey = key
                    lastDiagnostics = result.diagnostics
                    inFlightKey = null
                    callback(result)
                }
            }
        }
    }

    fun requestDiagnostics(
        project: File,
        relativeFile: String,
        source: String,
        callback: (List<CodeDiagnostic>) -> Unit,
    ) {
        if (paused) return
        val key = analysisKey(project, relativeFile, source)
        if (key == lastDiagnosticsKey) {
            callback(lastDiagnostics)
            return
        }
        if (key == inFlightKey) {
            // The completion request already synchronized this exact document.
            // clangd will publish versioned diagnostics independently; returning
            // an empty completion result here used to erase the valid marker.
            return
        }
        val requestGeneration = generation.incrementAndGet()
        clangd.cancel(completionRequest)
        fallback.cancel()
        executor.execute {
            if (paused || requestGeneration != generation.get()) return@execute
            switchProject(project)
            if (clangd.syncDocument(project, relativeFile, source)) {
                // Keep the previous markers visible until clangd publishes the
                // result for this exact document version. Returning an empty
                // list here caused autosave to make real errors disappear.
                return@execute
            }
            val result = runCatching { fallback.diagnose(project, relativeFile, source) }.getOrNull() ?: return@execute
            if (!paused && requestGeneration == generation.get()) {
                lastDiagnosticsKey = key
                lastDiagnostics = result.diagnostics
                callback(result.diagnostics)
            }
        }
    }

    fun pause() {
        paused = true
        generation.incrementAndGet()
        inFlightKey = null
        fallback.cancel()
        clangd.stop()
    }

    fun resume() { paused = false }

    private fun switchProject(project: File) {
        val canonical = project.canonicalFile
        if (activeProject != canonical) {
            clangd.stop()
            activeProject = canonical
            lastDiagnosticsKey = null
            lastDiagnostics = emptyList()
        }
    }

    private fun analysisKey(project: File, file: String, source: String) =
        "${project.absolutePath}\u0000$file\u0000${source.hashCode()}\u0000${source.length}"

    override fun close() {
        pause()
        executor.shutdownNow()
    }
}
