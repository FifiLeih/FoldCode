package dev.foldcode.ide

import android.content.Context
import java.io.File
import java.security.MessageDigest

internal data class ResolvedCMakeDependencies(
    val sourceDirectories: Map<String, String>,
    val messages: List<String>,
)

/** Resolves public FetchContent inputs with the same upstream Git used by Source Control. */
internal class CMakeDependencyResolver(context: Context) {
    private val git = NativeGit(context)
    fun resolve(
        project: File,
        allowDownload: Boolean = true,
        onProgress: (String) -> Unit,
    ): ResolvedCMakeDependencies {
        val declarations = cmakeFiles(project).flatMap(::parseDeclarations).distinctBy { it.name.lowercase() }.toList()
        if (declarations.isEmpty()) return ResolvedCMakeDependencies(emptyMap(), emptyList())
        // Keep fetched source with the project instead of hiding it in the
        // application's private storage. It can now be inspected, copied with
        // the project, backed up, and removed on a per-project basis.
        val root = File(project, ".foldcode/dependencies").apply { mkdirs() }
        val sources = linkedMapOf<String, String>()
        val messages = mutableListOf<String>()
        declarations.forEachIndexed { index, dependency ->
            require(dependency.repository.startsWith("https://") || dependency.repository.startsWith("http://")) {
                "FetchContent ${dependency.name} uses an unsupported Git URL: ${dependency.repository}. Use HTTPS."
            }
            onProgress("Dependency ${index + 1}/${declarations.size}: ${dependency.name}")
            val identity = "${dependency.repository}\n${dependency.tag.orEmpty()}"
            val directory = File(root, sha256(identity))
            val marker = File(directory, ".foldcode-dependency")
            val cached = File(directory, ".git").isDirectory && marker.takeIf(File::isFile)?.readText() == identity
            if (!cached && !allowDownload) {
                error(
                    "Dependency ${dependency.name}${dependency.tag?.let { " ($it)" }.orEmpty()} is not cached. " +
                        "Choose Allow during configuration once, or vendor it into the project.",
                )
            }
            if (!cached) {
                val staging = File(root, ".${directory.name}-${System.nanoTime()}")
                staging.deleteRecursively()
                try {
                    val arguments = buildList {
                        // CMake invokes `git submodule update` unconditionally
                        // for its own clone path, even when the repository has
                        // no .gitmodules file. Resolve the primary checkout here
                        // with the native Git engine and hand the source path to
                        // FetchContent explicitly.
                        add("clone")
                        dependency.tag?.takeIf(String::isNotBlank)?.let {
                            addAll(listOf("--branch", it))
                        }
                        add(dependency.repository)
                        add(staging.absolutePath)
                    }
                    git.requireSuccess(root, arguments)
                    marker.parentFile?.mkdirs()
                    File(staging, ".foldcode-dependency").writeText(identity)
                    directory.deleteRecursively()
                    require(staging.renameTo(directory)) { "Could not activate ${dependency.name}" }
                } finally {
                    staging.deleteRecursively()
                }
                messages += "Downloaded ${dependency.name}${dependency.tag?.let { " ($it)" }.orEmpty()}"
            } else {
                messages += "Reusing cached ${dependency.name}${dependency.tag?.let { " ($it)" }.orEmpty()}"
            }
            sources[dependency.name] = directory.absolutePath
        }
        return ResolvedCMakeDependencies(sources, messages)
    }

    private fun cmakeFiles(project: File): Sequence<String> = project.walkTopDown()
        .onEnter { directory -> directory == project || directory.name !in setOf("build", ".git", ".cxx", ".foldcode") }
        .filter {
            it.isFile && it.name != "pico_sdk_import.cmake" &&
                (it.name == "CMakeLists.txt" || it.extension.equals("cmake", true)) && it.length() <= 2L * 1024 * 1024
        }
        .take(256)
        .mapNotNull { runCatching { it.readText() }.getOrNull() }

    private fun parseDeclarations(cmake: String): List<GitDependency> {
        val variables = Regex("set\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s+([^\\s)]+)", RegexOption.IGNORE_CASE)
            .findAll(cmake).associate { it.groupValues[1] to it.groupValues[2].trim('"', '\'') }
        return Regex("FetchContent_Declare\\s*\\(\\s*([A-Za-z0-9_.+-]+)([\\s\\S]*?)\\)", RegexOption.IGNORE_CASE)
            .findAll(cmake)
            .mapNotNull { match ->
                val body = match.groupValues[2]
                val repository = option(body, "GIT_REPOSITORY")?.resolveVariables(variables) ?: return@mapNotNull null
                GitDependency(match.groupValues[1], repository, option(body, "GIT_TAG")?.resolveVariables(variables))
            }.toList()
    }

    private fun option(body: String, name: String): String? =
        Regex("\\b${Regex.escape(name)}\\s+([^\\s)]+)", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)?.trim('"', '\'')

    private fun String.resolveVariables(variables: Map<String, String>): String =
        Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}").replace(this) { variables[it.groupValues[1]] ?: it.value }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private data class GitDependency(val name: String, val repository: String, val tag: String?)
}
