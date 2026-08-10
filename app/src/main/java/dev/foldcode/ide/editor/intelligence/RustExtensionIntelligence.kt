package dev.foldcode.ide

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal fun rustAnalyzerCargoTarget(project: File): String? {
    val configuration = rustCargoConfiguration(project) ?: return null
    var buildSection = false
    configuration.useLines { lines ->
        lines.forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.startsWith('[') && line.endsWith(']')) {
                buildSection = line.equals("[build]", ignoreCase = true)
            } else if (buildSection) {
                Regex("""^target\s*=\s*[\"']([^\"']+)[\"']""")
                    .find(line)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.takeIf(String::isNotBlank)
                    ?.let { return it }
            }
        }
    }
    return null
}

internal fun rustCargoConfiguration(project: File): File? =
    listOf(
        File(project, ".cargo/config.toml"),
        File(project, ".cargo/config"),
    ).firstOrNull(File::isFile)

internal fun isEmbeddedRustTarget(target: String?): Boolean =
    target?.contains("-none-", ignoreCase = true) == true

internal fun isPicoRustProjectDirectory(project: File): Boolean =
    File(project, "Cargo.toml").isFile && File(project, "foldcode-pico.json").isFile

internal fun requiresEmbeddedCargoDiagnostics(project: File): Boolean =
    isEmbeddedRustTarget(rustAnalyzerCargoTarget(project)) || isPicoRustProjectDirectory(project)

internal fun rustDocumentUri(project: File, relativeFile: String): String? {
    if (!relativeFile.endsWith(".rs", ignoreCase = true)) return null
    val root = runCatching { project.canonicalFile }.getOrNull() ?: return null
    val source = runCatching { File(root, relativeFile).canonicalFile }.getOrNull() ?: return null
    if (!source.toPath().startsWith(root.toPath())) return null
    return source.toURI().toString()
}

private val rustFallbackKeywords = listOf(
    "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else",
    "enum", "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop",
    "match", "mod", "move", "mut", "pub", "ref", "return", "self", "Self", "static",
    "struct", "super", "trait", "true", "type", "unsafe", "use", "where", "while",
).map { CompletionItem(it, detail = "Rust keyword", category = "keyword") }

private val rustFallbackSymbols = listOf(
    "Box", "Clone", "Copy", "Debug", "Default", "Display", "Drop", "Err", "Error",
    "From", "Into", "IntoIterator", "Iterator", "None", "Ok", "Option", "Result",
    "Some", "String", "ToString", "Vec", "assert!", "assert_eq!", "dbg!", "eprintln!",
    "format!", "panic!", "print!", "println!", "todo!", "unimplemented!", "vec!",
    "as_ref", "as_mut", "clone", "collect", "expect", "filter", "iter", "iter_mut",
    "map", "map_err", "unwrap", "unwrap_or", "unwrap_or_default",
).map { label ->
    CompletionItem(
        label = label,
        detail = if (label.endsWith('!')) "Rust macro" else "Rust standard symbol",
        category = if (label.endsWith('!')) "macro" else "symbol",
    )
}

private val picoRustFallbackSymbols = listOf(
    "block", "clocks", "cortex_m", "cortex_m_rt", "embedded_hal", "entry", "gpio", "hal",
    "i2c", "init_clocks_and_plls", "multicore", "new_timer0", "new_timer1", "pac", "Pins",
    "Peripherals", "pio", "pwm", "rp2040_hal", "rp235x_hal", "Sio", "spi", "Timer", "uart",
    "Watchdog", "ImageDef", "IMAGE_DEF", "XOSC_CRYSTAL_FREQ_HZ",
).map { CompletionItem(it, detail = "Pico Rust symbol", category = "symbol") }

/**
 * Useful candidates while rust-analyzer is loading Cargo metadata. Embedded
 * projects can need several seconds to index HAL/proc-macro dependencies, but
 * typing must still produce a completion popup during that period.
 */
internal fun rustFallbackCompletionItems(project: File, source: String): List<CompletionItem> {
    val sourceSymbols = Regex("\\b[A-Za-z_][A-Za-z0-9_]{1,}\\b")
        .findAll(source)
        .map { CompletionItem(it.value, detail = "Rust project symbol", category = "symbol") }
        .toList()
    val dependencySymbols = buildList {
        val cargoFile = File(project, "Cargo.toml").takeIf(File::isFile) ?: return@buildList
        runCatching {
            var dependencySection = false
            cargoFile.forEachLine { rawLine ->
                val line = rawLine.substringBefore('#').trim()
                if (line.startsWith('[') && line.endsWith(']')) {
                    val section = line.removeSurrounding("[", "]").lowercase()
                    dependencySection = section == "dependencies" ||
                        section == "dev-dependencies" ||
                        section == "build-dependencies" ||
                        section.endsWith(".dependencies")
                } else if (dependencySection) {
                    Regex("^([A-Za-z][A-Za-z0-9_-]*)\\s*=")
                        .find(line)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.let { dependency ->
                            add(
                                CompletionItem(
                                    dependency.replace('-', '_'),
                                    detail = "Rust crate",
                                    category = "module",
                                ),
                            )
                        }
                }
            }
        }
    }
    return (
        rustFallbackKeywords +
            rustFallbackSymbols +
            (if (isPicoRustProjectDirectory(project)) picoRustFallbackSymbols else emptyList()) +
            dependencySymbols +
            sourceSymbols
        )
        .distinctBy { it.label }
        .take(512)
}

/**
 * Synchronize a Cargo project into app-private storage and overlay the current
 * unsaved editor buffer. Embedded Cargo diagnostics must not depend on shared
 * storage autosave winning a race with the diagnostic debounce.
 */
internal fun prepareRustDiagnosticSnapshot(
    project: File,
    destination: File,
    relativeFile: String,
    text: String,
): File? {
    val sourceRoot = runCatching { project.canonicalFile }.getOrNull()
        ?.takeIf(File::isDirectory)
        ?: return null
    val destinationRoot = runCatching {
        destination.mkdirs()
        destination.canonicalFile
    }.getOrNull()?.takeIf(File::isDirectory) ?: return null
    val relativeSource = runCatching { File(sourceRoot, relativeFile).canonicalFile }.getOrNull()
        ?.takeIf { it.toPath().startsWith(sourceRoot.toPath()) }
        ?: return null
    val normalizedRelativeFile = runCatching {
        sourceRoot.toPath().relativize(relativeSource.toPath()).toString()
    }.getOrNull() ?: return null
    val excludedDirectories = setOf(".git", ".foldcode", ".idea", "target", "build")
    val expectedFiles = mutableSetOf<String>()

    sourceRoot.walkTopDown()
        .onEnter { directory ->
            if (directory == sourceRoot) return@onEnter true
            if (directory.name in excludedDirectories || Files.isSymbolicLink(directory.toPath())) {
                return@onEnter false
            }
            runCatching { directory.canonicalFile.toPath().startsWith(sourceRoot.toPath()) }
                .getOrDefault(false)
        }
        .filter(File::isFile)
        .forEach { source ->
            val relative = runCatching {
                sourceRoot.toPath().relativize(source.canonicalFile.toPath()).toString()
            }.getOrNull() ?: return@forEach
            if (relative == normalizedRelativeFile || relative.startsWith("..")) return@forEach
            expectedFiles += relative
            val target = File(destinationRoot, relative)
            val needsCopy = !target.isFile || target.length() != source.length() ||
                target.lastModified() != source.lastModified()
            if (needsCopy) runCatching {
                target.parentFile?.mkdirs()
                source.copyTo(target, overwrite = true)
                target.setLastModified(source.lastModified())
            }
        }

    expectedFiles += normalizedRelativeFile
    val diagnosticSource = File(destinationRoot, normalizedRelativeFile)
    runCatching {
        diagnosticSource.parentFile?.mkdirs()
        if (!diagnosticSource.isFile || diagnosticSource.readText() != text) {
            diagnosticSource.writeText(text)
        }
    }.getOrElse { return null }

    // Remove files deleted or renamed in the real project. Leaving them in the
    // mirror can make build scripts or wildcard module discovery report errors
    // which no longer belong to the user's workspace.
    destinationRoot.walkBottomUp().forEach { candidate ->
        if (candidate == destinationRoot) return@forEach
        val relative = runCatching {
            destinationRoot.toPath().relativize(candidate.canonicalFile.toPath()).toString()
        }.getOrNull() ?: return@forEach
        if (candidate.isFile && relative !in expectedFiles) candidate.delete()
        if (candidate.isDirectory && candidate.list()?.isEmpty() == true) candidate.delete()
    }
    return diagnosticSource
}

internal fun rustAnalyzerSettings(project: File): JSONObject {
    val target = rustAnalyzerCargoTarget(project)
    val embeddedProject = isEmbeddedRustTarget(target) || isPicoRustProjectDirectory(project)
    return JSONObject().apply {
        if (target != null) put("cargo", JSONObject().put("target", target))
        if (embeddedProject) {
            // Pico entry points are procedural attributes. Disabling procedural
            // macros makes rust-analyzer treat the whole function body as opaque,
            // which hides unresolved names and type errors inside `#[hal::entry]`.
            // Cargo check is the semantic fallback for embedded entry-point
            // procedural macros. rust-analyzer can parse their body without it,
            // but cannot reliably report unresolved names inside the expanded
            // function on Android.
            put("cargo", optJSONObject("cargo")
                ?.put("buildScripts", JSONObject().put("enable", true))
                ?: JSONObject().put("buildScripts", JSONObject().put("enable", true)))
            put("check", JSONObject()
                .put("enable", true)
                .put("command", "check")
                .put("allTargets", false))
            put("procMacro", JSONObject().put("enable", true))
        }
    }
}

internal fun rustAnalyzerConfigurationValue(settings: JSONObject, requestedSection: String): Any {
    val section = requestedSection
        .removePrefix("rust-analyzer")
        .removePrefix(".")
    if (section.isBlank()) return JSONObject(settings.toString())
    var current: Any = settings
    section.split('.').filter(String::isNotBlank).forEach { key ->
        current = (current as? JSONObject)?.opt(key) ?: return JSONObject.NULL
    }
    return when (current) {
        is JSONObject -> JSONObject(current.toString())
        is JSONArray -> JSONArray(current.toString())
        else -> current
    }
}

/** Persistent rust-analyzer LSP client. The executable is delivered only by the Rust extension. */
internal class RustExtensionIntelligence(private val context: Context) : Closeable {
    private companion object {
        const val TAG = "FoldCodeRustLsp"
        val RETRY_REQUEST = Any()
    }
    private val lock = Any()
    private val ids = AtomicLong(1)
    private val completionGeneration = AtomicLong(0)
    private val diagnosticsGeneration = AtomicLong(0)
    private val cargoDiagnosticsGeneration = AtomicLong(0)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FoldCode-rust-intelligence").apply { isDaemon = true }
    }
    private val cargoExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FoldCode-rust-cargo-diagnostics").apply { isDaemon = true }
    }
    private val pending = ConcurrentHashMap<Long, (Any?) -> Unit>()
    private val versions = ConcurrentHashMap<String, AtomicInteger>()
    private val texts = ConcurrentHashMap<String, String>()
    private val syntaxDiagnostics = ConcurrentHashMap<String, List<CodeDiagnostic>>()
    private val analyzerDiagnostics = ConcurrentHashMap<String, List<CodeDiagnostic>>()
    private val cargoDiagnostics = ConcurrentHashMap<String, List<CodeDiagnostic>>()
    private val cargoProcess = AtomicReference<Process?>(null)
    @Volatile private var process: Process? = null
    @Volatile private var output: BufferedOutputStream? = null
    @Volatile private var root: File? = null
    @Volatile private var analyzerSettings = JSONObject()
    @Volatile private var analyzerConfigurationKey: String? = null
    @Volatile private var completionRequest: Long? = null
    @Volatile private var diagnosticsRequest: Long? = null
    @Volatile var diagnosticsListener: (String, List<CodeDiagnostic>) -> Unit = { _, _ -> }

    @Synchronized
    private fun start(project: File): Boolean {
        val canonical = runCatching { project.canonicalFile }.getOrNull() ?: return false
        val cargoConfiguration = rustCargoConfiguration(canonical)
        val target = rustAnalyzerCargoTarget(canonical)
        val picoMarker = File(canonical, "foldcode-pico.json")
        val picoProject = isPicoRustProjectDirectory(canonical)
        val configurationKey = buildString {
            append(target)
            append(':').append(cargoConfiguration?.lastModified() ?: 0L)
            append(':').append(cargoConfiguration?.length() ?: 0L)
            append(':').append(picoMarker.lastModified())
            append(':').append(picoMarker.length())
            append(':').append(picoProject)
        }
        if (
            process?.isAlive == true &&
            root == canonical &&
            analyzerConfigurationKey == configurationKey
        ) return true
        stopProcess()
        analyzerSettings = rustAnalyzerSettings(canonical)
        analyzerConfigurationKey = configurationKey
        val runtime = runCatching { ensureRustRuntimeInstalled(context) }
            .onFailure { Log.e(TAG, "Could not prepare the Rust extension runtime", it) }
            .getOrNull() ?: return false
        val analyzer = File(runtime, "toolchain/bin/rust-analyzer")
        val launcher = File(context.applicationInfo.nativeLibraryDir, "foldrust.so")
        if (!analyzer.isFile || !launcher.canExecute()) return false
        val cargoTargetDirectory = File(
            context.filesDir,
            "rust-analyzer-targets/${canonical.absolutePath.hashCode().toUInt().toString(16)}",
        ).apply { mkdirs() }
        val child = runCatching {
            ProcessBuilder(launcher.absolutePath)
                .directory(canonical)
                .redirectError(File(context.cacheDir, "rust-analyzer-stderr.log"))
                .apply {
                    environment()["PATH"] = "${File(runtime, "bin").absolutePath}:${System.getenv("PATH").orEmpty()}"
                    environment()["FOLDCODE_RUST_ROOT"] = runtime.absolutePath
                    environment()["FOLDCODE_RUST_TOOL"] = "rust-analyzer"
                    environment()["RUSTUP_HOME"] = File(runtime, "toolchain").absolutePath
                    environment()["RUST_SRC_PATH"] = "/opt/rust/lib/rustlib/src/rust/library"
                    environment()["CARGO_HOME"] = File(canonical, ".foldcode/cargo").apply { mkdirs() }.absolutePath
                    // Android shared storage does not implement the file locks
                    // used by rustc incremental compilation. Keep analyzer
                    // flycheck artifacts app-private, but never share them with
                    // real Cargo builds: rust-analyzer may run concurrently and
                    // replace host proc-macro libraries while Cargo is loading
                    // them. Disable incremental sessions as well.
                    environment()["CARGO_TARGET_DIR"] = cargoTargetDirectory.absolutePath
                    environment()["CARGO_INCREMENTAL"] = "0"
                    environment()["RUSTC"] = "/opt/rust/bin/rustc"
                    environment()["RUSTDOC"] = "/opt/rust/bin/rustdoc"
                    environment()["RUSTFMT"] = "/opt/rust/bin/rustfmt"
                    environment()["CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER"] = "/usr/bin/cc"
                    configureRustCargoHostBuildEnvironment(environment())
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                    environment()["HOME"] = canonical.absolutePath
                }
                .start()
        }.onFailure { Log.e(TAG, "Could not start rust-analyzer", it) }.getOrNull() ?: return false
        process = child
        output = BufferedOutputStream(child.outputStream)
        root = canonical
        Thread({ readMessages(child) }, "FoldCode-rust-analyzer").apply { isDaemon = true }.start()
        val ready = CountDownLatch(1)
        request("initialize", JSONObject()
            .put("processId", JSONObject.NULL)
            .put("rootUri", canonical.toURI().toString())
            .put("capabilities", JSONObject().put("textDocument", JSONObject()
                .put("synchronization", JSONObject().put("didSave", true))
                .put("publishDiagnostics", JSONObject().put("versionSupport", true))
                .put("completion", JSONObject().put("completionItem", JSONObject().put("snippetSupport", false)))))
            .put("initializationOptions", JSONObject(analyzerSettings.toString()))
            .put("clientInfo", JSONObject().put("name", "FoldCode").put("version", "0.1"))) { ready.countDown() }
        if (!ready.await(8, TimeUnit.SECONDS) || !child.isAlive) {
            Log.e(TAG, "rust-analyzer initialization timed out or exited")
            stopProcess()
            return false
        }
        notify("initialized", JSONObject())
        notify(
            "workspace/didChangeConfiguration",
            JSONObject().put("settings", JSONObject(analyzerSettings.toString())),
        )
        Log.i(TAG, "rust-analyzer initialized for ${canonical.absolutePath}")
        return true
    }

    fun requestCompletion(project: File, relativeFile: String, text: String, cursor: Int, callback: (List<CompletionItem>) -> Unit): Long? {
        val ticket = completionGeneration.incrementAndGet()
        cancelLsp(completionRequest)
        executor.execute {
            if (ticket != completionGeneration.get()) return@execute
            val fallback = rustFallbackCompletionItems(project, text)
            // Do not leave the popup empty while a Pico HAL dependency graph is
            // still being indexed. The semantic result below replaces/extends
            // these candidates as soon as rust-analyzer responds.
            callback(fallback)
            val uri = sync(project, relativeFile, text) ?: return@execute
            if (ticket != completionGeneration.get()) return@execute
            completionRequest = request("textDocument/completion", JSONObject()
                .put("textDocument", JSONObject().put("uri", uri))
                .put("position", position(text, cursor))) { value ->
                    if (ticket == completionGeneration.get()) {
                        callback((parseCompletions(value) + fallback).distinctBy { it.label }.take(512))
                    }
                }
        }
        return ticket
    }

    fun requestDiagnostics(
        project: File,
        relativeFile: String,
        text: String,
        callback: (List<CodeDiagnostic>) -> Unit,
    ) {
        val ticket = diagnosticsGeneration.incrementAndGet()
        cancelLsp(diagnosticsRequest)
        executor.execute {
            if (ticket != diagnosticsGeneration.get()) return@execute

            // Pico code must still receive compiler diagnostics if rust-analyzer
            // cannot initialize the embedded target or expand `#[hal::entry]`.
            // Queue Cargo before LSP synchronization so an analyzer timeout does
            // not silently disable all errors in the function body.
            val documentUri = rustDocumentUri(project, relativeFile)
            if (documentUri != null && requiresEmbeddedCargoDiagnostics(project)) {
                requestEmbeddedCargoDiagnostics(
                    ticket,
                    project,
                    relativeFile,
                    text,
                    documentUri,
                    callback,
                )
            }

            val uri = sync(project, relativeFile, text) ?: return@execute
            if (ticket != diagnosticsGeneration.get()) return@execute
            // Never merge a result from the previous buffer version into the
            // freshly parsed document. This was the source of Rust errors which
            // remained after the offending text had already been removed.
            analyzerDiagnostics.remove(uri)
            // The editor autosaves before this separately debounced diagnostic
            // request. didSave is what asks rust-analyzer to run Cargo flycheck;
            // didChange alone only guarantees parser-level diagnostics.
            notify(
                "textDocument/didSave",
                JSONObject()
                    .put("textDocument", JSONObject().put("uri", uri))
                    .put("text", text),
            )
            request(
                "rust-analyzer/viewSyntaxTree",
                JSONObject().put("textDocument", JSONObject().put("uri", uri)),
            ) { tree ->
                if (ticket == diagnosticsGeneration.get()) {
                    syntaxDiagnostics[uri] = parseSyntaxDiagnostics(relativeFile, text, tree as? String)
                    callback(emitDiagnostics(uri, relativeFile))
                }
            }
            pullDiagnostics(ticket, relativeFile, uri, callback = callback)
        }
    }

    /**
     * rust-analyzer cannot always expand the Pico HAL entry-point procedural
     * macro on Android. Cargo uses the real project compiler pipeline, so it
     * remains the authoritative semantic fallback for code inside
     * `#[hal::entry]` while LSP continues to provide fast syntax/completion.
     */
    private fun requestEmbeddedCargoDiagnostics(
        ticket: Long,
        project: File,
        relativeFile: String,
        text: String,
        uri: String,
        callback: (List<CodeDiagnostic>) -> Unit,
    ) {
        val cargoTicket = cargoDiagnosticsGeneration.incrementAndGet()
        cargoProcess.getAndSet(null)?.terminateTree()
        cargoDiagnostics.remove(uri)
        cargoExecutor.execute {
            if (ticket != diagnosticsGeneration.get() || cargoTicket != cargoDiagnosticsGeneration.get()) {
                return@execute
            }
            val runtime = runCatching { ensureRustRuntimeInstalled(context) }
                .onFailure { Log.e(TAG, "Could not prepare Rust diagnostics runtime", it) }
                .getOrNull() ?: return@execute
            val canonicalProject = runCatching { project.canonicalFile }.getOrNull() ?: return@execute
            val projectKey = canonicalProject.absolutePath.hashCode().toUInt().toString(16)
            val cargoTargetDirectory = File(
                context.filesDir,
                "rust-diagnostic-targets/$projectKey",
            ).apply { mkdirs() }
            rustAnalyzerCargoTarget(canonicalProject)?.let { target ->
                val preparationOutput = StringBuilder()
                val prepared = prepareRustCargoCompatibility(
                    project = canonicalProject,
                    target = target,
                    cargoTargetDirectory = cargoTargetDirectory,
                    output = preparationOutput,
                    onProgress = { message -> Log.i(TAG, message) },
                ) { arguments, consumeOutput ->
                    runEmbeddedCargoCommand(
                        ticket = ticket,
                        cargoTicket = cargoTicket,
                        workingDirectory = canonicalProject,
                        cargoHomeProject = canonicalProject,
                        runtime = runtime,
                        cargoTargetDirectory = cargoTargetDirectory,
                        arguments = arguments,
                        consumeOutput = consumeOutput,
                    )
                }
                if (!prepared) {
                    if (preparationOutput.isNotBlank()) {
                        Log.w(TAG, "Could not prepare Rust editor dependencies: ${preparationOutput.toString().trimEnd()}")
                    }
                    return@execute
                }
            }
            if (ticket != diagnosticsGeneration.get() || cargoTicket != cargoDiagnosticsGeneration.get()) {
                return@execute
            }
            val diagnosticProject = File(context.cacheDir, "rust-diagnostic-projects/$projectKey")
            val diagnosticSource = prepareRustDiagnosticSnapshot(
                canonicalProject,
                diagnosticProject,
                relativeFile,
                text,
            ) ?: return@execute
            val collected = mutableListOf<CodeDiagnostic>()
            runEmbeddedCargoCommand(
                ticket = ticket,
                cargoTicket = cargoTicket,
                workingDirectory = diagnosticProject,
                cargoHomeProject = canonicalProject,
                runtime = runtime,
                cargoTargetDirectory = cargoTargetDirectory,
                arguments = listOf("check", "--message-format=json", "--offline", "-j1"),
            ) { line ->
                parseCargoDiagnostic(
                    diagnosticProject,
                    diagnosticSource,
                    relativeFile,
                    line,
                )?.let(collected::add)
            }
            if (ticket == diagnosticsGeneration.get() && cargoTicket == cargoDiagnosticsGeneration.get()) {
                cargoDiagnostics[uri] = collected.distinct()
                callback(emitDiagnostics(uri, relativeFile))
            }
        }
    }

    private fun runEmbeddedCargoCommand(
        ticket: Long,
        cargoTicket: Long,
        workingDirectory: File,
        cargoHomeProject: File,
        runtime: File,
        cargoTargetDirectory: File,
        arguments: List<String>,
        consumeOutput: (String) -> Unit,
    ): Boolean {
        if (ticket != diagnosticsGeneration.get() || cargoTicket != cargoDiagnosticsGeneration.get()) return false
        val launcher = File(context.applicationInfo.nativeLibraryDir, "foldrust.so")
        if (!launcher.canExecute()) return false
        val child = runCatching {
            ProcessBuilder(listOf(launcher.absolutePath) + arguments)
                .directory(workingDirectory)
                .redirectErrorStream(true)
                .apply {
                    environment()["FOLDCODE_RUST_ROOT"] = runtime.absolutePath
                    environment()["FOLDCODE_RUST_TOOL"] = "cargo"
                    environment()["CARGO_HOME"] = File(cargoHomeProject, ".foldcode/cargo")
                        .apply { mkdirs() }
                        .absolutePath
                    environment()["RUSTUP_HOME"] = File(runtime, "toolchain").absolutePath
                    environment()["RUSTC"] = "/opt/rust/bin/rustc"
                    environment()["RUSTDOC"] = "/opt/rust/bin/rustdoc"
                    environment()["RUSTFMT"] = "/opt/rust/bin/rustfmt"
                    environment()["CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER"] = "/usr/bin/cc"
                    configureRustCargoHostBuildEnvironment(environment())
                    environment()["HOME"] = workingDirectory.absolutePath
                    environment()["TMPDIR"] = File(context.cacheDir, "rust").apply { mkdirs() }.absolutePath
                    environment()["CARGO_TARGET_DIR"] = cargoTargetDirectory.absolutePath
                    environment()["CARGO_INCREMENTAL"] = "0"
                }
                .start()
        }.onFailure { Log.e(TAG, "Could not start embedded Cargo command", it) }
            .getOrNull() ?: return false
        cargoProcess.set(child)
        var exitCode = -1
        try {
            child.inputStream.bufferedReader().use { reader ->
                while (ticket == diagnosticsGeneration.get() &&
                    cargoTicket == cargoDiagnosticsGeneration.get()
                ) {
                    val line = try {
                        reader.readLine()
                    } catch (error: IOException) {
                        if (ticket == diagnosticsGeneration.get() &&
                            cargoTicket == cargoDiagnosticsGeneration.get()
                        ) Log.w(TAG, "Embedded Cargo output closed", error)
                        break
                    } ?: break
                    consumeOutput(line)
                }
            }
            if (ticket != diagnosticsGeneration.get() || cargoTicket != cargoDiagnosticsGeneration.get()) {
                child.terminateTree()
            } else {
                exitCode = child.waitFor()
            }
        } finally {
            cargoProcess.compareAndSet(child, null)
        }
        return exitCode == 0 &&
            ticket == diagnosticsGeneration.get() &&
            cargoTicket == cargoDiagnosticsGeneration.get()
    }

    private fun parseCargoDiagnostic(
        project: File,
        source: File,
        relativeFile: String,
        line: String,
    ): CodeDiagnostic? {
        val envelope = runCatching { JSONObject(line) }.getOrNull() ?: return null
        if (envelope.optString("reason") != "compiler-message") return null
        val message = envelope.optJSONObject("message") ?: return null
        val severity = when (message.optString("level")) {
            "error", "failure-note" -> "error"
            "warning" -> "warning"
            else -> return null
        }
        val spans = message.optJSONArray("spans") ?: return null
        val span = (0 until spans.length())
            .mapNotNull { index -> spans.optJSONObject(index) }
            .firstOrNull { candidate ->
                if (!candidate.optBoolean("is_primary")) return@firstOrNull false
                val name = candidate.optString("file_name")
                val candidateFile = File(name).let { if (it.isAbsolute) it else File(project, name) }
                runCatching { candidateFile.canonicalFile == source }.getOrDefault(false)
            } ?: return null
        val code = message.optJSONObject("code")?.optString("code")?.takeIf(String::isNotBlank)
        val detail = message.optString("message").ifBlank { "Rust compiler error" }
        return CodeDiagnostic(
            file = relativeFile,
            line = span.optInt("line_start", 1).coerceAtLeast(1),
            column = span.optInt("column_start", 1).coerceAtLeast(1),
            severity = severity,
            message = if (code == null) detail else "$detail ($code)",
        )
    }

    private fun pullDiagnostics(
        ticket: Long,
        relativeFile: String,
        uri: String,
        attempt: Int = 0,
        callback: (List<CodeDiagnostic>) -> Unit,
    ) {
        diagnosticsRequest = request(
            "textDocument/diagnostic",
            JSONObject().put("textDocument", JSONObject().put("uri", uri)),
        ) { value ->
            if (ticket != diagnosticsGeneration.get()) return@request
            val report = value as? JSONObject
            val items = report?.optJSONArray("items")
            if (value === RETRY_REQUEST && attempt < 3) {
                executor.execute {
                    Thread.sleep(250L * (attempt + 1))
                    if (ticket == diagnosticsGeneration.get()) {
                        pullDiagnostics(ticket, relativeFile, uri, attempt + 1, callback)
                    }
                }
            } else if (items != null) {
                analyzerDiagnostics[uri] = parseDiagnostics(relativeFile, items)
                callback(emitDiagnostics(uri, relativeFile))
            }
        }
    }

    private fun emitDiagnostics(uri: String, relativeFile: String): List<CodeDiagnostic> {
        val combined = (
            syntaxDiagnostics[uri].orEmpty() +
                analyzerDiagnostics[uri].orEmpty() +
                cargoDiagnostics[uri].orEmpty()
            ).distinct()
        diagnosticsListener(relativeFile, combined)
        return combined
    }

    private fun parseSyntaxDiagnostics(file: String, text: String, tree: String?): List<CodeDiagnostic> {
        if (tree.isNullOrBlank()) return emptyList()
        val offsets = mutableListOf<Int>()
        fun visit(node: JSONObject) {
            if (node.optString("kind") == "ERROR") {
                offsets += node.optJSONArray("start")?.optInt(0, 0) ?: 0
                return
            }
            val children = node.optJSONArray("children") ?: return
            repeat(children.length()) { index -> children.optJSONObject(index)?.let(::visit) }
        }
        runCatching { visit(JSONObject(tree)) }
        if (offsets.isEmpty()) {
            Regex("(?m)^\\s*ERROR@(\\d+)\\.\\.(\\d+)").findAll(tree).forEach { match ->
                match.groupValues[1].toIntOrNull()?.let(offsets::add)
            }
        }
        return offsets.distinct().map { rawOffset ->
            val offset = rawOffset.coerceIn(0, text.length)
            val before = text.substring(0, offset)
            val lineStart = before.lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
            CodeDiagnostic(
                file = file,
                line = before.count { it == '\n' } + 1,
                column = offset - lineStart + 1,
                severity = "error",
                message = "Rust syntax error",
            )
        }.toList()
    }

    fun cancel(ticket: Long?) {
        if (ticket != null && ticket == completionGeneration.get()) {
            completionGeneration.incrementAndGet()
            cancelLsp(completionRequest)
        }
    }

    private fun cancelLsp(id: Long?) {
        if (id != null && pending.remove(id) != null) notify("$/cancelRequest", JSONObject().put("id", id))
    }

    private fun sync(project: File, relativeFile: String, text: String): String? {
        if (!relativeFile.endsWith(".rs", true) || !start(project)) return null
        val uri = File(project, relativeFile).canonicalFile.toURI().toString()
        if (texts.put(uri, text) == text) return uri
        val version = versions[uri]
        if (version == null) {
            versions[uri] = AtomicInteger(1)
            notify("textDocument/didOpen", JSONObject().put("textDocument", JSONObject()
                .put("uri", uri).put("languageId", "rust").put("version", 1).put("text", text)))
        } else {
            notify("textDocument/didChange", JSONObject()
                .put("textDocument", JSONObject().put("uri", uri).put("version", version.incrementAndGet()))
                .put("contentChanges", JSONArray().put(JSONObject().put("text", text))))
        }
        return uri
    }

    private fun request(method: String, params: JSONObject, callback: (Any?) -> Unit): Long {
        val id = ids.getAndIncrement()
        pending[id] = callback
        send(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
        return id
    }

    private fun notify(method: String, params: JSONObject) =
        send(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params))

    private fun send(message: JSONObject) {
        val bytes = message.toString().toByteArray(StandardCharsets.UTF_8)
        synchronized(lock) {
            output?.apply {
                write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                write(bytes)
                flush()
            }
        }
    }

    private fun readMessages(owner: Process) {
        val input = BufferedInputStream(owner.inputStream)
        try {
            while (owner.isAlive) {
                var length = -1
                while (true) {
                    val header = readLine(input) ?: return
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toIntOrNull() ?: -1
                }
                if (length < 0) continue
                val bytes = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val count = input.read(bytes, offset, length - offset)
                    if (count < 0) return
                    offset += count
                }
                runCatching { handle(JSONObject(String(bytes, StandardCharsets.UTF_8))) }
                    .onFailure { Log.e(TAG, "Invalid rust-analyzer response", it) }
            }
        } catch (error: java.io.IOException) {
            if (process === owner && owner.isAlive) Log.e(TAG, "rust-analyzer stream failed", error)
        } finally {
            if (process === owner) stopProcess()
        }
    }

    private fun handle(message: JSONObject) {
        if (message.has("id") && message.has("method")) {
            send(JSONObject().put("jsonrpc", "2.0").put("id", message.opt("id")).put("result", when (message.optString("method")) {
                "workspace/configuration" -> JSONArray().apply {
                    val items = message.optJSONObject("params")?.optJSONArray("items") ?: JSONArray()
                    repeat(items.length()) { index ->
                        val section = items.optJSONObject(index)?.optString("section").orEmpty()
                        put(rustAnalyzerConfigurationValue(analyzerSettings, section))
                    }
                }
                else -> JSONObject.NULL
            }))
        } else if (message.has("id")) {
            val callback = pending.remove(message.optLong("id", -1))
            if (message.has("error")) {
                val error = message.optJSONObject("error")
                if (error?.optJSONObject("data")?.optBoolean("retriggerRequest") == true) {
                    callback?.invoke(RETRY_REQUEST)
                } else {
                    Log.e(TAG, "rust-analyzer request failed: $error")
                    callback?.invoke(null)
                }
            } else {
                callback?.invoke(message.opt("result"))
            }
        } else if (message.optString("method") == "textDocument/publishDiagnostics") {
            val params = message.optJSONObject("params") ?: return
            val uri = params.optString("uri")
            val publishedVersion = params.optInt("version", -1)
            val currentVersion = versions[uri]?.get()
            if (publishedVersion >= 0 && currentVersion != null && publishedVersion != currentVersion) return
            val relative = runCatching { root!!.toPath().relativize(File(java.net.URI(uri)).canonicalFile.toPath()).toString() }
                .getOrDefault(File(java.net.URI(uri).path).name)
            analyzerDiagnostics[uri] = parseDiagnostics(relative, params.optJSONArray("diagnostics") ?: JSONArray())
            emitDiagnostics(uri, relative)
        }
    }

    private fun parseCompletions(value: Any?): List<CompletionItem> {
        val items = when (value) {
            is JSONArray -> value
            is JSONObject -> value.optJSONArray("items") ?: JSONArray()
            else -> JSONArray()
        }
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val label = item.optString("label").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val insertion = item.optJSONObject("textEdit")?.optString("newText")
                ?: item.optString("insertText").takeIf(String::isNotBlank) ?: label
            CompletionItem(
                label,
                insertion.replace(Regex("\\$\\{\\d+:([^}]*)\\}"), "$1").replace(Regex("\\$\\d+"), ""),
                item.optString("detail", "Rust symbol"),
                lspCompletionCategory(item.optInt("kind")),
            )
        }.distinctBy(CompletionItem::label).take(512)
    }

    private fun parseDiagnostics(file: String, values: JSONArray): List<CodeDiagnostic> =
        (0 until values.length()).mapNotNull { index ->
            val item = values.optJSONObject(index) ?: return@mapNotNull null
            val start = item.optJSONObject("range")?.optJSONObject("start") ?: return@mapNotNull null
            val severity = when (item.optInt("severity", 1)) {
                1 -> "error"
                2 -> "warning"
                3 -> "information"
                else -> "hint"
            }
            CodeDiagnostic(file, start.optInt("line") + 1, start.optInt("character") + 1,
                severity, item.optString("message"))
        }

    private fun position(text: String, cursor: Int): JSONObject {
        val safe = cursor.coerceIn(0, text.length)
        val before = text.substring(0, safe)
        val start = before.lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
        return JSONObject().put("line", before.count { it == '\n' }).put("character", safe - start)
    }

    fun reset() {
        completionGeneration.incrementAndGet()
        diagnosticsGeneration.incrementAndGet()
        cargoDiagnosticsGeneration.incrementAndGet()
        cargoProcess.getAndSet(null)?.terminateTree()
        stopProcess()
    }

    override fun close() {
        reset()
        executor.shutdownNow()
        cargoExecutor.shutdownNow()
    }

    @Synchronized
    private fun stopProcess() {
        val child = process
        process = null
        output = null
        root = null
        analyzerSettings = JSONObject()
        analyzerConfigurationKey = null
        completionRequest = null
        diagnosticsRequest = null
        pending.clear()
        versions.clear()
        texts.clear()
        syntaxDiagnostics.clear()
        analyzerDiagnostics.clear()
        cargoDiagnostics.clear()
        // rust-analyzer may own a Cargo flycheck plus rustc/linker descendants.
        // Killing only the LSP parent leaves those processes orphaned and lets
        // them compete with a foreground firmware build for memory.
        child?.terminateTree()
    }

    private fun readLine(input: BufferedInputStream): String? {
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
