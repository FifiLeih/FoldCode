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

/**
 * Persistent language-server facade delivered by the Web Development extension.
 *
 * Servers are created lazily by language family. Opening an HTML document does
 * not wake TypeScript, CSS or JSON, while returning to that document reuses its
 * existing process for the current project.
 */
internal class WebExtensionIntelligence(
    private val context: Context,
    private val projectRuntime: WebProjectRuntime = WebProjectRuntime(context),
) : Closeable {
    private val sessions = ConcurrentHashMap<WebLanguageServer, WebLspSession>()
    @Volatile var diagnosticsListener: (String, List<CodeDiagnostic>) -> Unit = { _, _ -> }

    fun requestCompletion(
        project: File,
        relativeFile: String,
        text: String,
        cursor: Int,
        callback: (List<CompletionItem>) -> Unit,
    ) {
        val server = webLanguageServer(relativeFile) ?: run {
            callback(emptyList())
            return
        }
        Log.d("FoldCodeWebLsp", "completion requested server=${server.id} file=$relativeFile cursor=$cursor")
        session(server).requestCompletion(
            projectRuntime.intelligenceDirectory(project),
            relativeFile,
            text,
            cursor,
            callback,
        )
    }

    fun requestDiagnostics(
        project: File,
        relativeFile: String,
        text: String,
        callback: (List<CodeDiagnostic>) -> Unit,
    ) {
        val server = webLanguageServer(relativeFile) ?: run {
            callback(emptyList())
            return
        }
        session(server).requestDiagnostics(
            projectRuntime.intelligenceDirectory(project),
            relativeFile,
            text,
            callback,
        )
    }

    private fun session(server: WebLanguageServer): WebLspSession =
        sessions.computeIfAbsent(server) {
            WebLspSession(context, server) { file, diagnostics ->
                diagnosticsListener(file, diagnostics)
            }
        }

    override fun close() {
        sessions.values.forEach(WebLspSession::close)
        sessions.clear()
    }
}

private enum class WebLanguageServer(
    val id: String,
    val languageId: String,
    val executableNames: List<String>,
) {
    TypeScript(
        "typescript",
        "typescript",
        listOf("lib/node_modules/typescript-language-server/lib/cli.mjs"),
    ),
    Html(
        "html",
        "html",
        extractedServerPaths("vscode-html-language-server"),
    ),
    Css(
        "css",
        "css",
        extractedServerPaths("vscode-css-language-server"),
    ),
    Json(
        "json",
        "json",
        extractedServerPaths("vscode-json-language-server"),
    ),
}

private fun extractedServerPaths(executable: String): List<String> = listOf(
    "lib/node_modules/@zed-industries/vscode-langservers-extracted/bin/$executable",
    // Accept packages prepared before the maintained Zed fork was adopted.
    "lib/node_modules/vscode-langservers-extracted/bin/$executable",
)

private class WebLspSession(
    private val context: Context,
    private val server: WebLanguageServer,
    private val diagnosticsListener: (String, List<CodeDiagnostic>) -> Unit,
) : Closeable {
    private companion object { const val TAG = "FoldCodeWebLsp" }

    private val lock = Any()
    private val ids = AtomicLong(1)
    private val generation = AtomicLong(0)
    private val diagnosticsGeneration = AtomicLong(0)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "FoldCode-${server.id}-intelligence").apply { isDaemon = true }
    }
    private val pending = ConcurrentHashMap<Long, (Any?) -> Unit>()
    private val versions = ConcurrentHashMap<String, AtomicInteger>()
    private val texts = ConcurrentHashMap<String, String>()
    private val publishedDiagnostics = ConcurrentHashMap<String, List<CodeDiagnostic>>()
    @Volatile private var process: Process? = null
    @Volatile private var output: BufferedOutputStream? = null
    @Volatile private var root: File? = null
    @Volatile private var completionRequest: Long? = null

    @Synchronized
    private fun start(project: File): Boolean {
        val canonical = runCatching { project.canonicalFile }.getOrNull() ?: return false
        if (process?.isAlive == true && root == canonical) return true
        stopProcess()
        val runtime = File(context.filesDir, "web-runtime")
        val serverScript = server.executableNames.asSequence()
            .map { File(runtime, it) }
            .firstOrNull(File::isFile)
            ?: run {
                Log.e(TAG, "Missing ${server.id} language-server script in ${runtime.absolutePath}")
                return false
            }
        // Android forbids executing downloaded ELF files from app data. The
        // APK-delivered launcher is executable and hands the readable Node ELF
        // to linker64. TypeScript Language Server subsequently uses
        // child_process.fork(tsserver.js), so make process.execPath point back
        // to that permitted launcher instead of linker64. The preload is also
        // inherited by tsserver and keeps any nested Node forks valid.
        val launcher = File(context.applicationInfo.nativeLibraryDir, "foldnode.so")
        if (!launcher.canExecute()) {
            Log.e(TAG, "Node launcher is not executable: ${launcher.absolutePath}")
            return false
        }
        val nodeBootstrap = File(context.cacheDir, "foldcode-node-bootstrap.cjs")
        runCatching {
            nodeBootstrap.writeText(
                "process.execPath = process.env.FOLDCODE_NODE_LAUNCHER || process.execPath;\n",
                Charsets.UTF_8,
            )
        }.onFailure {
            Log.e(TAG, "Could not prepare the Android Node bootstrap", it)
            return false
        }
        val child = runCatching {
            ProcessBuilder(launcher.absolutePath, serverScript.absolutePath, "--stdio")
                .directory(canonical)
                .redirectError(File(context.cacheDir, "${server.id}-language-server-stderr.log"))
                .apply {
                    environment()["FOLDCODE_WEB_ROOT"] = runtime.absolutePath
                    environment()["FOLDCODE_NODE_LAUNCHER"] = launcher.absolutePath
                    environment()["NODE_OPTIONS"] = "--require=${nodeBootstrap.absolutePath}"
                    environment()["NODE_PATH"] = File(runtime, "lib/node_modules").absolutePath
                    environment()["LD_LIBRARY_PATH"] = listOf(
                        File(runtime, "lib").absolutePath,
                        context.applicationInfo.nativeLibraryDir,
                    ).joinToString(":")
                    environment()["HOME"] = canonical.absolutePath
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                }
                .start()
        }.onFailure {
            Log.e(TAG, "Could not start ${server.id} language server", it)
        }.getOrNull() ?: return false
        process = child
        output = BufferedOutputStream(child.outputStream)
        root = canonical
        Thread({ readMessages(child) }, "FoldCode-${server.id}-language-server").apply {
            isDaemon = true
        }.start()

        val ready = CountDownLatch(1)
        request("initialize", initializeParams(canonical, runtime)) { ready.countDown() }
        val initialized = try {
            ready.await(8, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            // Closing/replacing a workspace interrupts the intelligence
            // executor. Treat that as a cancelled startup, not an uncaught
            // background-thread crash.
            Thread.currentThread().interrupt()
            false
        }
        if (!initialized || !child.isAlive) {
            Log.e(TAG, "${server.id} language server did not initialize (initialized=$initialized alive=${child.isAlive})")
            stopProcess()
            return false
        }
        Log.i(TAG, "${server.id} language server ready for ${canonical.absolutePath}")
        notify("initialized", JSONObject())
        notify(
            "workspace/didChangeConfiguration",
            JSONObject().put("settings", languageSettings()),
        )
        return true
    }

    private fun initializeParams(project: File, runtime: File): JSONObject {
        val capabilities = JSONObject().put(
            "textDocument",
            JSONObject()
                .put("synchronization", JSONObject().put("didSave", true))
                .put("publishDiagnostics", JSONObject().put("versionSupport", true))
                .put(
                    "completion",
                    JSONObject().put(
                        "completionItem",
                        JSONObject()
                            .put("snippetSupport", false)
                            .put("documentationFormat", JSONArray().put("markdown").put("plaintext")),
                    ),
                ),
        )
        val options = when (server) {
            WebLanguageServer.TypeScript -> {
                val tsserver = File(runtime, "lib/node_modules/typescript/lib/tsserver.js")
                JSONObject()
                    .put("tsserver", JSONObject().put("fallbackPath", tsserver.absolutePath))
                    .put(
                        "preferences",
                        JSONObject()
                            .put("includeCompletionsForModuleExports", true)
                            .put("includeCompletionsWithInsertText", true),
                    )
            }
            WebLanguageServer.Html -> JSONObject()
                .put("provideFormatter", true)
                .put(
                    "embeddedLanguages",
                    JSONObject().put("css", true).put("javascript", true),
                )
            WebLanguageServer.Css,
            WebLanguageServer.Json,
            -> JSONObject().put("provideFormatter", true)
        }
        return JSONObject()
            .put("processId", JSONObject.NULL)
            .put("rootUri", project.toURI().toString())
            .put(
                "workspaceFolders",
                JSONArray().put(
                    JSONObject().put("uri", project.toURI().toString()).put("name", project.name),
                ),
            )
            .put("capabilities", capabilities)
            .put("initializationOptions", options)
            .put("clientInfo", JSONObject().put("name", "FoldCode").put("version", "0.1"))
    }

    private fun languageSettings(): JSONObject = when (server) {
        WebLanguageServer.TypeScript -> JSONObject()
            .put("typescript", JSONObject().put("validate", JSONObject().put("enable", true)))
            .put("javascript", JSONObject().put("validate", JSONObject().put("enable", true)))
        WebLanguageServer.Html -> JSONObject().put("html", JSONObject().put("validate", true))
        WebLanguageServer.Css -> JSONObject()
            .put("css", JSONObject().put("validate", true))
            .put("scss", JSONObject().put("validate", true))
            .put("less", JSONObject().put("validate", true))
        WebLanguageServer.Json -> JSONObject().put("json", JSONObject().put("validate", JSONObject().put("enable", true)))
    }

    fun requestCompletion(
        project: File,
        relativeFile: String,
        text: String,
        cursor: Int,
        callback: (List<CompletionItem>) -> Unit,
    ) {
        val ticket = generation.incrementAndGet()
        cancelRequest(completionRequest)
        executor.execute {
            if (ticket != generation.get()) return@execute
            val uri = sync(project, relativeFile, text) ?: return@execute
            completionRequest = request(
                "textDocument/completion",
                JSONObject()
                    .put("textDocument", JSONObject().put("uri", uri))
                    .put("position", position(text, cursor)),
            ) { value ->
                if (ticket == generation.get()) callback(parseCompletions(value))
            }
        }
    }

    fun requestDiagnostics(
        project: File,
        relativeFile: String,
        text: String,
        callback: (List<CodeDiagnostic>) -> Unit,
    ) {
        val ticket = diagnosticsGeneration.incrementAndGet()
        executor.execute {
            if (ticket != diagnosticsGeneration.get()) return@execute
            val uri = sync(project, relativeFile, text) ?: run {
                callback(emptyList())
                return@execute
            }
            if (ticket != diagnosticsGeneration.get()) return@execute
            publishedDiagnostics[uri] = emptyList()
            callback(emptyList())
            notify(
                "textDocument/didSave",
                JSONObject()
                    .put("textDocument", JSONObject().put("uri", uri))
                    .put("text", text),
            )
        }
    }

    private fun sync(project: File, relativeFile: String, text: String): String? {
        if (webLanguageServer(relativeFile) != server || !start(project)) return null
        val file = File(project, relativeFile).canonicalFile
        val uri = file.toURI().toString()
        if (texts.put(uri, text) == text) return uri
        val language = webLanguageId(relativeFile)
        val version = versions[uri]
        if (version == null) {
            versions[uri] = AtomicInteger(1)
            notify(
                "textDocument/didOpen",
                JSONObject().put(
                    "textDocument",
                    JSONObject()
                        .put("uri", uri)
                        .put("languageId", language)
                        .put("version", 1)
                        .put("text", text),
                ),
            )
        } else {
            notify(
                "textDocument/didChange",
                JSONObject()
                    .put(
                        "textDocument",
                        JSONObject().put("uri", uri).put("version", version.incrementAndGet()),
                    )
                    .put("contentChanges", JSONArray().put(JSONObject().put("text", text))),
            )
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

    private fun cancelRequest(id: Long?) {
        if (id != null && pending.remove(id) != null) {
            notify("$/cancelRequest", JSONObject().put("id", id))
        }
    }

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
                    if (header.startsWith("Content-Length:", true)) {
                        length = header.substringAfter(':').trim().toIntOrNull() ?: -1
                    }
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
                    .onFailure { Log.e(TAG, "Invalid ${server.id} language-server response", it) }
            }
        } catch (error: java.io.IOException) {
            if (process === owner && owner.isAlive) {
                Log.e(TAG, "${server.id} language-server stream failed", error)
            }
        } finally {
            if (process === owner) stopProcess()
        }
    }

    private fun handle(message: JSONObject) {
        if (message.has("id") && message.has("method")) {
            val result: Any = when (message.optString("method")) {
                "workspace/configuration" -> JSONArray().apply {
                    val items = message.optJSONObject("params")?.optJSONArray("items") ?: JSONArray()
                    val settings = languageSettings()
                    repeat(items.length()) { index ->
                        val section = items.optJSONObject(index)?.optString("section").orEmpty()
                        val topLevel = section.substringBefore('.').takeIf(String::isNotBlank)
                        put(topLevel?.let { settings.opt(it) } ?: settings)
                    }
                }
                "workspace/workspaceFolders" -> root?.let {
                    JSONArray().put(JSONObject().put("uri", it.toURI().toString()).put("name", it.name))
                } ?: JSONArray()
                else -> JSONObject.NULL
            }
            send(
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", message.opt("id"))
                    .put("result", result),
            )
        } else if (message.has("id")) {
            pending.remove(message.optLong("id", -1))?.invoke(message.opt("result"))
        } else if (message.optString("method") == "textDocument/publishDiagnostics") {
            val params = message.optJSONObject("params") ?: return
            val uri = params.optString("uri")
            val publishedVersion = params.optInt("version", -1)
            val currentVersion = versions[uri]?.get()
            if (publishedVersion >= 0 && currentVersion != null && publishedVersion != currentVersion) return
            val relative = runCatching {
                root!!.toPath().relativize(File(java.net.URI(uri)).canonicalFile.toPath()).toString()
            }.getOrDefault(File(java.net.URI(uri).path).name)
            val values = parseDiagnostics(relative, params.optJSONArray("diagnostics") ?: JSONArray())
            publishedDiagnostics[uri] = values
            diagnosticsListener(relative, values)
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
                ?: item.optString("insertText").takeIf(String::isNotBlank)
                ?: label
            CompletionItem(
                label = label,
                insertion = plainLspInsertion(insertion),
                detail = item.optString("detail", "${server.id.uppercase()} symbol"),
                category = lspCompletionCategory(item.optInt("kind")),
            )
        }.distinctBy { it.label to it.detail }.take(512)
    }

    private fun parseDiagnostics(file: String, values: JSONArray): List<CodeDiagnostic> {
        val pendingDependencies = root?.let(::pendingWebDependencies).orEmpty()
        return (0 until values.length()).mapNotNull { index ->
            val item = values.optJSONObject(index) ?: return@mapNotNull null
            val start = item.optJSONObject("range")?.optJSONObject("start") ?: return@mapNotNull null
            val message = item.optString("message")
            if (isPendingWebDependencyDiagnostic(message, pendingDependencies)) return@mapNotNull null
            CodeDiagnostic(
                file = file,
                line = start.optInt("line") + 1,
                column = start.optInt("character") + 1,
                severity = when (item.optInt("severity", 1)) {
                    1 -> "error"
                    2 -> "warning"
                    3 -> "information"
                    else -> "hint"
                },
                message = message,
            )
        }
    }

    private fun position(text: String, cursor: Int): JSONObject {
        val safe = cursor.coerceIn(0, text.length)
        val before = text.substring(0, safe)
        val lineStart = before.lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
        return JSONObject()
            .put("line", before.count { it == '\n' })
            .put("character", safe - lineStart)
    }

    override fun close() {
        generation.incrementAndGet()
        diagnosticsGeneration.incrementAndGet()
        executor.shutdownNow()
        stopProcess()
    }

    @Synchronized
    private fun stopProcess() {
        val child = process
        process = null
        output = null
        root = null
        completionRequest = null
        pending.clear()
        versions.clear()
        texts.clear()
        publishedDiagnostics.clear()
        child?.destroy()
        if (child?.isAlive == true) child.destroyForcibly()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0) {
                return if (bytes.isEmpty()) null
                else bytes.toByteArray().toString(StandardCharsets.US_ASCII)
            }
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
    }
}

private fun pendingWebDependencies(project: File): Set<String> {
    if (File(project, "node_modules").isDirectory) return emptySet()
    val manifest = File(project, "package.json").takeIf(File::isFile) ?: return emptySet()
    val json = runCatching { JSONObject(manifest.readText()) }.getOrNull() ?: return emptySet()
    return buildSet {
        listOf("dependencies", "devDependencies", "peerDependencies", "optionalDependencies")
            .mapNotNull(json::optJSONObject)
            .forEach { dependencies -> dependencies.keys().forEachRemaining(::add) }
    }
}

internal fun isPendingWebDependencyDiagnostic(
    message: String,
    pendingDependencies: Set<String>,
): Boolean {
    if (pendingDependencies.isEmpty()) return false
    val specifier = Regex(
        "(?:module|module path)\\s+['\"]([^'\"]+)['\"]",
        RegexOption.IGNORE_CASE,
    ).find(message)?.groupValues?.getOrNull(1) ?: return false
    if (specifier.startsWith('.') || specifier.startsWith('/')) return false
    val segments = specifier.split('/')
    val packageName = if (specifier.startsWith('@') && segments.size >= 2) {
        segments.take(2).joinToString("/")
    } else {
        segments.first()
    }
    return packageName in pendingDependencies
}

/**
 * Converts the small LSP snippet subset used by the bundled web servers into
 * text Sora can insert directly. The closing brace is deliberately escaped:
 * Android's ICU regex engine rejects the otherwise-tolerated JVM pattern and
 * used to discard every web completion response.
 */
internal fun plainLspInsertion(value: String): String = value
    .replace(Regex("\\$\\{\\d+:([^}]*)\\}"), "$1")
    .replace(Regex("\\$\\d+"), "")

internal fun isWebIntelligenceFile(name: String): Boolean = webLanguageServer(name) != null

internal fun isWebDocument(name: String): Boolean = isWebIntelligenceFile(name)

internal fun webLanguageServerId(name: String): String? = webLanguageServer(name)?.id

private fun webLanguageServer(name: String): WebLanguageServer? = when {
    name.endsWith(".tsx", true) || name.endsWith(".ts", true) ||
        name.endsWith(".jsx", true) || name.endsWith(".js", true) ||
        name.endsWith(".mjs", true) || name.endsWith(".cjs", true) -> WebLanguageServer.TypeScript
    name.endsWith(".html", true) || name.endsWith(".htm", true) -> WebLanguageServer.Html
    name.endsWith(".css", true) -> WebLanguageServer.Css
    name.endsWith(".json", true) -> WebLanguageServer.Json
    else -> null
}

private fun webLanguageId(name: String): String = when {
    name.endsWith(".tsx", true) -> "typescriptreact"
    name.endsWith(".ts", true) -> "typescript"
    name.endsWith(".jsx", true) -> "javascriptreact"
    name.endsWith(".html", true) || name.endsWith(".htm", true) -> "html"
    name.endsWith(".css", true) -> "css"
    name.endsWith(".json", true) -> "json"
    else -> "javascript"
}
