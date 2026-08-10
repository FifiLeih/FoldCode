package dev.foldcode.ide

import android.system.Os
import android.system.OsConstants
import java.io.File

/** Stops a CMake/Ninja/Clang process tree using Android's procfs child list. */
internal fun Process.terminateTree() {
    fun terminate(pid: Int) {
        val children = File("/proc/$pid/task/$pid/children")
            .takeIf(File::isFile)
            ?.readText()
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.mapNotNull(String::toIntOrNull)
            .orEmpty()
        children.forEach(::terminate)
        runCatching { Os.kill(pid, OsConstants.SIGKILL) }
    }
    val rootPid = runCatching {
        (javaClass.getMethod("pid").invoke(this) as Number).toInt()
    }.recoverCatching {
        javaClass.getDeclaredField("pid").apply { isAccessible = true }.get(this) as Int
    }.getOrNull()
    rootPid?.let(::terminate)
    runCatching { destroyForcibly() }
}
