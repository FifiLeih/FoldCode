package dev.foldcode.ide

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Active native build roots and their descendants. A session attaches the
 * Android launcher process; guest runtimes may additionally report processes
 * through an isolated event file. Closing the session prevents stale PIDs from
 * leaking into a later build.
 */
internal object BuildPidRegistry {
    private val sessions = ConcurrentHashMap<String, BuildPidSession>()

    fun open(cacheDirectory: File): BuildPidSession {
        val directory = File(cacheDirectory, "build-pids").apply { mkdirs() }
        directory.listFiles().orEmpty()
            .filter { it.isFile && System.currentTimeMillis() - it.lastModified() > STALE_SESSION_MILLIS }
            .forEach(File::delete)
        val file = File(directory, "${UUID.randomUUID()}.events").apply { writeText("") }
        return BuildPidSession(file) { closed -> sessions.remove(closed.file.absolutePath, closed) }
            .also { session -> sessions[file.absolutePath] = session }
    }

    fun activeProcessIds(): Set<Int> {
        val roots = buildSet {
            sessions.values.forEach { session -> addAll(session.activeProcessIds()) }
        }
        return expandBuildProcessTree(roots, ::readProcChildren)
    }

    private fun readProcChildren(pid: Int): Set<Int> = runCatching {
        File("/proc/$pid/task/$pid/children").readText()
            .splitToSequence(Regex("\\s+"))
            .mapNotNull(String::toIntOrNull)
            .filter { it > 0 }
            .toSet()
    }.getOrDefault(emptySet())
}

internal class BuildPidSession internal constructor(
    internal val file: File,
    private val onClose: (BuildPidSession) -> Unit,
) : AutoCloseable {
    @Volatile
    private var hostPid: Int? = null

    fun configure(environment: MutableMap<String, String>) {
        environment[BUILD_PID_FILE_ENVIRONMENT] = file.absolutePath
    }

    fun configureNative(
        environment: MutableMap<String, String>,
        nativeLibraryDirectory: File,
    ) {
        configure(environment)
        val tracker = File(nativeLibraryDirectory, "libfoldbuildpid.so")
        require(tracker.isFile) { "FoldCode build PID tracker is unavailable" }
        environment["LD_PRELOAD"] = listOf(
            tracker.absolutePath,
            environment["LD_PRELOAD"].orEmpty(),
        ).filter(String::isNotBlank).joinToString(":")
    }

    fun attach(process: Process) {
        hostPid = runCatching {
            (process.javaClass.getMethod("pid").invoke(process) as Number).toInt()
        }.recoverCatching {
            process.javaClass.getDeclaredField("pid").apply { isAccessible = true }.get(process) as Int
        }.getOrNull()
    }

    fun activeProcessIds(): Set<Int> = parseBuildPidEvents(
        runCatching { file.readText() }.getOrDefault(""),
        hostPid?.let(::setOf).orEmpty(),
    )

    override fun close() {
        onClose(this)
        runCatching { file.delete() }
    }
}

internal fun parseBuildPidEvents(contents: String, initial: Set<Int> = emptySet()): Set<Int> = buildSet {
    addAll(initial.filter { it > 0 })
    contents.lineSequence().take(MAX_BUILD_PID_EVENTS).forEach { line ->
        val event = line.firstOrNull() ?: return@forEach
        val pid = line.drop(1).trim().toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
        when (event) {
            '+' -> add(pid)
            '-' -> remove(pid)
        }
    }
}

internal fun expandBuildProcessTree(
    roots: Set<Int>,
    childrenOf: (Int) -> Set<Int>,
): Set<Int> {
    val discovered = linkedSetOf<Int>()
    val pending = ArrayDeque(roots.filter { it > 0 })
    while (pending.isNotEmpty() && discovered.size < MAX_TRACKED_BUILD_PROCESSES) {
        val pid = pending.removeFirst()
        if (!discovered.add(pid)) continue
        childrenOf(pid)
            .asSequence()
            .filter { it > 0 && it !in discovered }
            .take(MAX_TRACKED_BUILD_PROCESSES - discovered.size)
            .forEach(pending::addLast)
    }
    return discovered
}

private const val BUILD_PID_FILE_ENVIRONMENT = "FOLDCODE_BUILD_PID_FILE"
private const val MAX_BUILD_PID_EVENTS = 8_192
private const val MAX_TRACKED_BUILD_PROCESSES = 512
private const val STALE_SESSION_MILLIS = 24L * 60L * 60L * 1_000L
