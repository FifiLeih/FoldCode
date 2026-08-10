package dev.foldcode.ide

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Persistent project-aware Python intelligence delivered by the Python extension. */
internal class PythonExtensionIntelligence(private val context: Context) : Closeable {
    private companion object { const val TAG = "FoldCodePython" }

    private val nextId = AtomicLong(1)
    private val generation = AtomicLong(0)
    private val completionCallbacks = ConcurrentHashMap<Long, (List<CompletionItem>) -> Unit>()
    private val diagnosticsCallbacks = ConcurrentHashMap<Long, (List<CodeDiagnostic>) -> Unit>()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FoldCode-python-intelligence").apply { isDaemon = true }
    }
    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var project: File? = null
    @Volatile private var latestCompletion = -1L
    @Volatile private var latestDiagnostics = -1L

    fun requestCompletion(
        projectDirectory: File,
        relativeFile: String,
        source: String,
        cursor: Int,
        projectFiles: Set<String>,
        callback: (List<CompletionItem>) -> Unit,
    ): Long {
        val id = nextId.getAndIncrement()
        val ticket = generation.get()
        completionCallbacks.remove(latestCompletion)
        latestCompletion = id
        completionCallbacks[id] = callback
        executor.execute {
            if (ticket != generation.get() || id != latestCompletion) return@execute
            send(
                projectDirectory,
                JSONObject()
                    .put("id", id)
                    .put("operation", "complete")
                    .put("relativeFile", relativeFile)
                    .put("source", source)
                    .put("cursor", cursor)
                    .put("projectFiles", JSONArray(projectFiles.toList())),
            ) { completionCallbacks.remove(id)?.invoke(emptyList()) }
        }
        return id
    }

    fun requestDiagnostics(
        projectDirectory: File,
        relativeFile: String,
        source: String,
        callback: (List<CodeDiagnostic>) -> Unit,
    ): Long {
        val id = nextId.getAndIncrement()
        val ticket = generation.get()
        diagnosticsCallbacks.remove(latestDiagnostics)
        latestDiagnostics = id
        diagnosticsCallbacks[id] = callback
        executor.execute {
            if (ticket != generation.get() || id != latestDiagnostics) return@execute
            send(
                projectDirectory,
                JSONObject()
                    .put("id", id)
                    .put("operation", "diagnose")
                    .put("relativeFile", relativeFile)
                    .put("source", source),
            ) {
                // A temporarily unavailable extension must not erase the last
                // valid result while the user is still editing this revision.
                diagnosticsCallbacks.remove(id)
            }
        }
        return id
    }

    fun cancel(id: Long?) {
        if (id == null) return
        completionCallbacks.remove(id)
        diagnosticsCallbacks.remove(id)
    }

    private fun send(directory: File, request: JSONObject, onFailure: () -> Unit) {
        val service = File(context.filesDir, "python-runtime/foldcode/python_completion_server.py")
        if (!service.isFile || !ensureStarted(directory, service)) {
            onFailure()
            return
        }
        runCatching {
            synchronized(this) {
                writer?.apply {
                    write(request.toString())
                    newLine()
                    flush()
                } ?: error("Python intelligence stream is unavailable")
            }
        }.onFailure {
            Log.e(TAG, "Could not send Python intelligence request", it)
            onFailure()
            stop()
        }
    }

    @Synchronized
    private fun ensureStarted(directory: File, service: File): Boolean {
        val canonical = runCatching { directory.canonicalFile }.getOrNull() ?: return false
        if (process?.isAlive == true && project == canonical) return true
        stop()
        val python = File(context.applicationInfo.nativeLibraryDir, "foldpython.so")
        if (!python.canExecute()) return false
        val runtime = File(context.filesDir, "python-runtime")
        val child = runCatching {
            ProcessBuilder(python.absolutePath, "-u", service.absolutePath)
                .directory(canonical)
                .redirectError(File(context.cacheDir, "python-intelligence-stderr.log"))
                .apply {
                    environment()["FOLDCODE_PYTHON_ROOT"] = runtime.absolutePath
                    environment()["PYTHONPATH"] = listOf(
                        File(runtime, "foldcode/vendor").absolutePath,
                        File(canonical, ".foldcode/python").absolutePath,
                    ).joinToString(":")
                    environment()["PYTHONPYCACHEPREFIX"] =
                        File(context.cacheDir, "python-pycache").apply { mkdirs() }.absolutePath
                    environment()["FOLDCODE_JEDI_CACHE"] =
                        File(context.cacheDir, "jedi").apply { mkdirs() }.absolutePath
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                    environment()["HOME"] = canonical.absolutePath
                }
                .start()
        }.onFailure { Log.e(TAG, "Could not start Python intelligence", it) }.getOrNull() ?: return false
        process = child
        writer = child.outputStream.bufferedWriter()
        project = canonical
        Thread({ readResponses(child) }, "FoldCode-python-responses").apply { isDaemon = true }.start()
        return true
    }

    private fun readResponses(child: Process) {
        try {
            child.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val response = runCatching { JSONObject(line) }
                        .onFailure { Log.e(TAG, "Invalid Python intelligence response", it) }
                        .getOrNull() ?: return@forEach
                    val id = response.optLong("id", -1)
                    when (response.optString("operation")) {
                        "diagnose" -> diagnosticsCallbacks.remove(id)?.invoke(parseDiagnostics(response))
                        else -> completionCallbacks.remove(id)?.invoke(parseCompletions(response))
                    }
                }
            }
        } catch (error: java.io.IOException) {
            if (process === child && child.isAlive) Log.e(TAG, "Python intelligence stream failed", error)
        } finally {
            if (process === child) stop()
        }
    }

    private fun parseCompletions(response: JSONObject): List<CompletionItem> {
        val values = response.optJSONArray("items") ?: JSONArray()
        return (0 until values.length()).mapNotNull { index ->
            val value = values.optJSONObject(index) ?: return@mapNotNull null
            val label = value.optString("label").takeIf(String::isNotBlank) ?: return@mapNotNull null
            CompletionItem(
                label = label,
                insertion = value.optString("insertion", label),
                detail = value.optString("detail", "Python symbol"),
            )
        }.distinctBy(CompletionItem::label).take(512)
    }

    private fun parseDiagnostics(response: JSONObject): List<CodeDiagnostic> {
        val values = response.optJSONArray("diagnostics") ?: JSONArray()
        return (0 until values.length()).mapNotNull { index ->
            val value = values.optJSONObject(index) ?: return@mapNotNull null
            CodeDiagnostic(
                file = value.optString("file"),
                line = value.optInt("line", 1).coerceAtLeast(1),
                column = value.optInt("column", 1).coerceAtLeast(1),
                severity = value.optString("severity", "error"),
                message = value.optString("message", "Python syntax error"),
            )
        }
    }

    @Synchronized
    private fun stop() {
        val child = process
        process = null
        writer = null
        project = null
        child?.destroy()
        if (child?.isAlive == true) child.destroyForcibly()
    }

    fun reset() {
        generation.incrementAndGet()
        latestCompletion = -1
        latestDiagnostics = -1
        val completionPending = completionCallbacks.values.toList()
        completionCallbacks.clear()
        diagnosticsCallbacks.clear()
        stop()
        completionPending.forEach { it(emptyList()) }
        // Closing or restarting the analyzer is not an authoritative "zero
        // diagnostics" response. Keep the last markers until the replacement
        // process has analyzed the current document revision.
    }

    override fun close() {
        reset()
        executor.shutdownNow()
    }
}
