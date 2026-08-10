package dev.foldcode.ide

import java.io.File
import java.io.Closeable

internal enum class DebugSessionStatus {
    Idle,
    Starting,
    Running,
    Paused,
    Stopped,
    Failed,
}

internal data class DebugBreakpoint(
    val file: String,
    val line: Int,
    val verified: Boolean = false,
    val message: String? = null,
)

internal data class DebugStackFrame(
    val id: Int,
    val name: String,
    val file: String?,
    val line: Int,
    val external: Boolean = false,
)

internal data class DebugVariable(
    val name: String,
    val value: String,
    val type: String? = null,
    val variablesReference: Int = 0,
    val children: List<DebugVariable> = emptyList(),
    val childrenLoaded: Boolean = false,
    val expanded: Boolean = false,
)

internal data class DebugSessionSnapshot(
    val status: DebugSessionStatus = DebugSessionStatus.Idle,
    val title: String = "No active debug session",
    val message: String = "Start debugging to inspect breakpoints, call stack and variables.",
    val activeThreadId: Int? = null,
    val activeFrameId: Int? = null,
    val activeFile: String? = null,
    val activeLine: Int? = null,
    val breakpoints: List<DebugBreakpoint> = emptyList(),
    val frames: List<DebugStackFrame> = emptyList(),
    val variables: List<DebugVariable> = emptyList(),
    val console: String = "",
)

/** Workspace breakpoint registry. Editors update it from gutter taps; DAP consumes it at launch. */
internal object DebugBreakpointStore {
    private val values = linkedMapOf<String, MutableSet<Int>>()
    private val observers = linkedSetOf<() -> Unit>()

    @Synchronized
    fun toggle(file: String, line: Int) {
        val lines = values.getOrPut(file) { linkedSetOf() }
        if (!lines.add(line)) lines.remove(line)
        if (lines.isEmpty()) values.remove(file)
        observers.toList().forEach { it() }
    }

    @Synchronized
    fun lines(file: String): Set<Int> = values[file]?.toSet().orEmpty()

    @Synchronized
    fun all(): List<DebugBreakpoint> = values.flatMap { (file, lines) ->
        lines.sorted().map { DebugBreakpoint(file, it) }
    }

    @Synchronized
    fun observe(observer: () -> Unit): Closeable {
        observers += observer
        return Closeable { synchronized(this) { observers -= observer } }
    }

    @Synchronized
    fun clear() {
        values.clear()
        observers.toList().forEach { it() }
    }
}

internal sealed interface DebugLaunchTarget {
    val project: File
    val displayName: String

    data class AndroidHost(
        override val project: File,
        override val displayName: String,
        val executable: File,
        val arguments: List<String> = emptyList(),
    ) : DebugLaunchTarget

    data class PicoSwd(
        override val project: File,
        override val displayName: String,
        val elf: File,
        val openOcdInterface: String,
        val openOcdTarget: String,
    ) : DebugLaunchTarget

    data class WebNode(
        override val project: File,
        override val displayName: String,
        val entryFile: File,
        val nodeExecutable: File,
        val adapterScript: File,
        val environment: Map<String, String> = emptyMap(),
    ) : DebugLaunchTarget

    data class WebBrowser(
        override val project: File,
        override val displayName: String,
        val url: String,
        val nodeExecutable: File,
        val adapterScript: File,
        val environment: Map<String, String> = emptyMap(),
    ) : DebugLaunchTarget
}
