package dev.foldcode.ide

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Removes web servers left behind when Android kills FoldCode without giving
 * the terminal controller a chance to close its child process tree.
 *
 * The match deliberately requires both FoldCode's packaged Node executable
 * and a working directory inside the private web-project mirror. Language
 * servers use the same Node runtime, but never run from that directory.
 */
internal object ManagedWebServerProcesses {
    fun terminateStale(filesDirectory: File, procDirectory: File = File("/proc")): Int {
        val processIds = procDirectory.listFiles().orEmpty()
            .asSequence()
            .mapNotNull { entry -> entry.name.toIntOrNull()?.takeIf { it > 0 }?.let { it to entry } }
            .filter { (_, entry) ->
                val commandLine = runCatching { File(entry, "cmdline").readBytes() }
                    .getOrDefault(ByteArray(0))
                    .toNullSeparatedArguments()
                val workingDirectory = runCatching { File(entry, "cwd").canonicalPath }.getOrNull()
                isManagedWebServerProcess(commandLine, workingDirectory, filesDirectory)
            }
            .map(Pair<Int, File>::first)
            .toSet()

        processIds.forEach { pid -> runCatching { Os.kill(pid, OsConstants.SIGKILL) } }
        return processIds.size
    }
}

internal fun isManagedWebServerProcess(
    commandLine: List<String>,
    workingDirectory: String?,
    filesDirectory: File,
): Boolean {
    val privateRoot = runCatching { filesDirectory.canonicalFile }.getOrElse { filesDirectory.absoluteFile }
    val webProjectsRoot = File(privateRoot, "web-projects").path.trimEnd(File.separatorChar)
    val workingPath = workingDirectory?.trimEnd(File.separatorChar) ?: return false
    if (workingPath != webProjectsRoot && !workingPath.startsWith("$webProjectsRoot${File.separator}")) return false

    val packagedNode = File(privateRoot, "web-runtime/bin/node").path
    return commandLine.any { argument ->
        argument == packagedNode || runCatching { File(argument).canonicalPath == packagedNode }.getOrDefault(false)
    }
}

private fun ByteArray.toNullSeparatedArguments(): List<String> =
    toString(Charsets.UTF_8).split('\u0000').filter(String::isNotBlank)
