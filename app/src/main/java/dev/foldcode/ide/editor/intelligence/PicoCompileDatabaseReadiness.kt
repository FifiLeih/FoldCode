package dev.foldcode.ide

import org.json.JSONArray
import java.io.File

/**
 * A Pico build directory lives in shared storage and can survive an app uninstall,
 * while the SDK and compiler sysroots referenced by its compile database do not.
 * Do not start clangd with those stale private paths: it would report missing C/C++
 * standard headers until the project is configured again.
 */
internal fun isPicoCompileDatabaseUsable(
    database: File,
    privateDataRoots: List<File>,
): Boolean = runCatching {
    if (!database.isFile) return@runCatching false
    val roots = privateDataRoots
        .map { it.absoluteFile.normalize().path.trimEnd(File.separatorChar) }
        .filter(String::isNotBlank)
        .distinct()
    val entries = JSONArray(database.readText())
    if (entries.length() == 0) return@runCatching false

    repeat(entries.length()) { index ->
        val entry = entries.optJSONObject(index) ?: return@repeat
        val directory = File(entry.optString("directory", database.parentFile?.path.orEmpty()))
        val arguments = entry.optJSONArray("arguments")?.let { values ->
            (0 until values.length()).map(values::optString)
        } ?: entry.optString("command").takeIf(String::isNotBlank)?.let(::shellWords).orEmpty()

        compileSearchPaths(arguments, directory).forEach { path ->
            val absolute = path.absoluteFile.normalize()
            val privatePath = roots.any { root ->
                absolute.path == root || absolute.path.startsWith("$root${File.separator}")
            }
            if (privatePath && (!absolute.exists() || !privatePicoRuntimeIsReady(absolute))) {
                return@runCatching false
            }
        }
    }
    true
}.getOrDefault(false)

private fun privatePicoRuntimeIsReady(path: File): Boolean {
    var current: File? = path
    while (current != null) {
        when {
            current.name.startsWith("pico-cmake-sysroot-") ->
                return File(current, ".version").isFile
            current.name == "pico-cmake-sdk" ->
                return File(current, "sdk/pico_sdk_init.cmake").isFile
        }
        current = current.parentFile
    }
    return true
}

private fun compileSearchPaths(arguments: List<String>, directory: File): Sequence<File> = sequence {
    val separateValueFlags = setOf(
        "-I",
        "-isystem",
        "-iquote",
        "-idirafter",
        "-include",
        "-imacros",
        "--sysroot",
        "-isysroot",
        "-resource-dir",
        "--gcc-toolchain",
    )
    val joinedValueFlags = listOf(
        "--sysroot=",
        "-resource-dir=",
        "--gcc-toolchain=",
        "-isystem",
        "-iquote",
        "-idirafter",
        "-isysroot",
        "-include",
        "-imacros",
        "-I",
    )
    var index = 0
    while (index < arguments.size) {
        val argument = arguments[index]
        val value = when {
            argument in separateValueFlags -> arguments.getOrNull(++index)
            else -> joinedValueFlags.firstNotNullOfOrNull { prefix ->
                argument.removePrefix(prefix).takeIf { argument.startsWith(prefix) && it.isNotBlank() }
            }
        }
        if (!value.isNullOrBlank()) {
            val candidate = File(value)
            yield(if (candidate.isAbsolute) candidate else File(directory, value))
        }
        index++
    }
}

/** Small POSIX command parser for CMake's compile_commands.json `command` field. */
internal fun shellWords(command: String): List<String> {
    val words = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false
    command.forEach { char ->
        when {
            escaped -> { current.append(char); escaped = false }
            char == '\\' && quote != '\'' -> escaped = true
            quote != null && char == quote -> quote = null
            quote == null && (char == '\'' || char == '"') -> quote = char
            quote == null && char.isWhitespace() -> if (current.isNotEmpty()) {
                words += current.toString()
                current.clear()
            }
            else -> current.append(char)
        }
    }
    if (escaped) current.append('\\')
    if (current.isNotEmpty()) words += current.toString()
    return words
}
