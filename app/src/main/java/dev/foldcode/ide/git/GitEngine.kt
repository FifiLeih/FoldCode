package dev.foldcode.ide

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GitFileChange(
    val path: String,
    val code: String,
    val staged: Boolean,
)

data class GitRepositoryState(
    val initialized: Boolean = false,
    val branch: String = "main",
    val remoteUrl: String? = null,
    val changes: List<GitFileChange> = emptyList(),
    val message: String? = null,
) {
    val stagedCount: Int get() = changes.count { it.staged }
}

data class GitProjectResult(
    val projectKey: String,
    val projectName: String,
    val files: Map<String, String>,
    val folders: Set<String> = emptySet(),
    val state: GitRepositoryState,
)

data class GitRepositoryInfo(
    val projectKey: String,
    val name: String,
    val branch: String,
    val remoteUrl: String?,
    val lastUsed: Long,
)

data class ProjectTreeEntry(
    val path: String,
    val folder: Boolean,
    val editable: Boolean,
)

/** Real, on-device Git operations in Internal storage/FoldCode/Projects. */
class GitEngine(private val context: Context) {
    private val git = NativeGit(context)
    private val projectsRoot = File(
        Environment.getExternalStorageDirectory(),
        "FoldCode/Projects",
    )
    @Volatile private var migrationChecked = false

    init {
        check(projectsRoot.exists() || projectsRoot.mkdirs()) {
            "Storage access is required for ${projectsRoot.absolutePath}"
        }
    }

    /** Legacy discovery can walk several project trees; never run it on first-frame construction. */
    @Synchronized
    private fun ensureLegacyProjectsMigrated() {
        if (migrationChecked) return
        val obsoleteDocumentsRoot = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "FoldCode/Projects",
        )
        val migrationMarker = File(projectsRoot, ".private-storage-migrated")
        if (!migrationMarker.exists()) {
            listOf(
                obsoleteDocumentsRoot,
                File(context.filesDir, "projects"),
                File(context.filesDir, "git-projects"),
            )
                .flatMap { it.listFiles().orEmpty().toList() }
                .filter(File::isDirectory)
                .forEach { legacyProject ->
                    val legacyNameIsHash = legacyProject.name.matches(Regex("[a-f0-9]{64}"))
                    val desiredName = if (legacyNameIsHash) projectDisplayName(legacyProject) else legacyProject.name
                    val namedDestination = File(projectsRoot, sanitizeProjectName(desiredName))
                    val existingMatch = projectsRoot.listFiles().orEmpty().firstOrNull { candidate ->
                        candidate.isDirectory && runCatching {
                            readEditableFiles(candidate) == readEditableFiles(legacyProject)
                        }.getOrDefault(false)
                    }
                    if (namedDestination.isDirectory) {
                        // Merge only missing files. This restores files absent from an
                        // older shared copy without overwriting edits made by the user.
                        legacyProject.copyRecursively(namedDestination, overwrite = false)
                    } else if (existingMatch == null) {
                        val destination = uniqueProjectDirectory(desiredName)
                        legacyProject.copyRecursively(destination, overwrite = false)
                    }
                }
            migrationMarker.writeText("FoldCode shared projects migration completed")
        }
        if (obsoleteDocumentsRoot.isDirectory) obsoleteDocumentsRoot.deleteRecursively()
        obsoleteDocumentsRoot.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
        migrationChecked = true
    }

    fun status(projectKey: String, files: Map<String, String>): GitRepositoryState {
        val directory = syncProject(projectKey, files)
        if (!File(directory, ".git").isDirectory) return GitRepositoryState()

        return repositoryState(directory)
    }

    fun listProjects(): List<GitRepositoryInfo> {
        ensureLegacyProjectsMigrated()
        return projectsRoot.listFiles().orEmpty()
            .filter(File::isDirectory)
            .mapNotNull { directory ->
                runCatching {
                    val hasGit = File(directory, ".git").exists()
                    GitRepositoryInfo(
                        projectKey = "project:${directory.name}",
                        name = directory.name,
                        // The project picker only needs a label. Running `git status`
                        // here recursively examined every repository (including large
                        // generated trees) and could saturate the phone for seconds.
                        branch = if (hasGit) quickBranch(directory) else "local",
                        remoteUrl = null,
                        lastUsed = File(directory, ".git/logs/HEAD").takeIf(File::isFile)?.lastModified()
                            ?: directory.lastModified(),
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.lastUsed }
    }

    private fun quickBranch(directory: File): String {
        val gitEntry = File(directory, ".git")
        val gitDirectory = when {
            gitEntry.isDirectory -> gitEntry
            gitEntry.isFile -> gitEntry.readLines()
                .firstOrNull()
                ?.substringAfter("gitdir:", "")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { path -> File(path).let { if (it.isAbsolute) it else File(directory, path) } }
            else -> null
        } ?: return "Git"
        val head = File(gitDirectory, "HEAD").takeIf(File::isFile)?.readText()?.trim().orEmpty()
        return head.removePrefix("ref: refs/heads/").takeIf { head.startsWith("ref: refs/heads/") }
            ?: head.take(8).ifBlank { "Git" }
    }

    fun openProject(projectKey: String): GitProjectResult {
        val directory = projectDirectory(projectKey)
        require(directory.isDirectory) { "Project is no longer available" }
        val files = readEditableFiles(directory)
        require(files.isNotEmpty()) { "Project has no supported text/source files" }
        return GitProjectResult(
            projectKey = canonicalProjectKey(projectKey),
            projectName = directory.name,
            files = files,
            folders = readProjectFolders(directory),
            state = status(projectKey, files).copy(message = "Project opened"),
        )
    }

    /**
     * Imports an Android document tree into FoldCode's real build workspace.
     *
     * This deliberately copies unknown and binary files too: CMake projects can
     * depend on signing keys, firmware blobs, linker inputs, generated tables, or
     * assets that the text editor neither understands nor should modify. Host build
     * caches are the only exclusions because their absolute paths and object files
     * cannot be reused on Android.
     */
    fun importProject(treeUri: Uri): GitProjectResult {
        val source = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("The selected project folder is unavailable")
        val projectName = source.name?.takeIf(String::isNotBlank) ?: "IMPORTED_PROJECT"
        val directory = uniqueProjectDirectory(projectName)
        var copiedFiles = 0

        fun ignoredHostBuildDirectory(name: String): Boolean =
            name == "build" || name == "target" || name == ".cxx" ||
                name.startsWith("cmake-build-", ignoreCase = true)

        fun copyTree(document: DocumentFile, destination: File, depth: Int) {
            require(depth <= 64) { "Project directory nesting is too deep" }
            document.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                require(name != "." && name != ".." && '/' !in name && '\\' !in name) {
                    "Unsupported project entry name: $name"
                }
                if (child.isDirectory && ignoredHostBuildDirectory(name)) return@forEach
                val target = File(destination, name)
                if (child.isDirectory) {
                    require(target.mkdirs() || target.isDirectory) { "Could not create ${target.name}" }
                    copyTree(child, target, depth + 1)
                } else if (child.isFile) {
                    target.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        target.outputStream().use(input::copyTo)
                    } ?: error("Could not read $name")
                    child.lastModified().takeIf { it > 0L }?.let(target::setLastModified)
                    copiedFiles++
                }
            }
        }

        try {
            require(directory.mkdirs() || directory.isDirectory) { "Could not create ${directory.name}" }
            copyTree(source, directory, 0)
            require(copiedFiles > 0) { "The selected project folder is empty" }
            require(File(directory, "CMakeLists.txt").isFile) {
                "The imported folder has no top-level CMakeLists.txt"
            }
            val files = readEditableFiles(directory)
            require(files.isNotEmpty()) { "The imported project has no editable source files" }
            val key = "project:${directory.name}"
            val hasGit = File(directory, ".git").isDirectory
            return GitProjectResult(
                projectKey = key,
                projectName = directory.name,
                files = files,
                folders = readProjectFolders(directory),
                state = if (hasGit) status(key, files).copy(message = "Imported $copiedFiles files")
                    else GitRepositoryState(message = "Imported $copiedFiles files"),
            )
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun saveProject(projectKey: String, files: Map<String, String>, folders: Set<String> = emptySet()) {
        syncProject(projectKey, files, folders, removeMissing = true)
    }

    /** Writes one editor buffer without treating absent buffers as deleted project files. */
    fun saveProjectFile(projectKey: String, relativePath: String, content: String) {
        val root = projectDirectory(projectKey).canonicalFile
        val target = File(root, relativePath).canonicalFile
        require(target != root && target.toPath().startsWith(root.toPath())) { "Invalid project file path" }
        require(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true) {
            "Could not create ${target.parentFile?.absolutePath}"
        }
        // Build systems use source mtimes for incremental freshness. Saving an
        // unchanged buffer must not make Cargo or Ninja rebuild that file.
        if (!target.isFile || target.readText() != content) target.writeText(content)
    }

    fun createProject(name: String, files: Map<String, String>, folders: Set<String> = emptySet()): GitProjectResult {
        require(files.isNotEmpty()) { "A project needs at least one file" }
        val directory = uniqueProjectDirectory(name)
        val key = "project:${directory.name}"
        syncProject(key, files, folders, removeMissing = true)
        return GitProjectResult(
            projectKey = key,
            projectName = directory.name,
            files = files,
            folders = folders,
            state = GitRepositoryState(message = "Project created"),
        )
    }

    fun projectDirectoryFile(projectKey: String): File = projectDirectory(projectKey)

    fun cmakeExecutableTargets(projectKey: String): List<String> =
        listOf(
            File(projectDirectory(projectKey), "build/CMakeFiles/FoldCode/targets"),
            File(projectDirectory(projectKey), "build/.foldcode-targets"),
        ).firstOrNull(File::isFile)
            ?.readLines()?.map(String::trim)?.filter(String::isNotBlank)?.distinct()
            .orEmpty()

    /** Human-readable, bounded metadata/hex view for generated firmware artifacts. */
    fun artifactPreview(projectKey: String, relativePath: String): String {
        require(isInspectableArtifact(relativePath)) { "Unsupported binary artifact" }
        val root = projectDirectory(projectKey).canonicalFile
        val file = File(root, relativePath).canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file.isFile) { "Artifact is no longer available" }
        val bytes = file.inputStream().use { it.readUpTo(4096) }
        val header = buildString {
            appendLine("${file.name} · read-only binary preview")
            appendLine("Path: ${file.relativeTo(root).invariantSeparatorsPath}")
            appendLine("Size: ${file.length()} bytes")
            if (file.extension.equals("uf2", true)) append(uf2Summary(bytes, file.length()))
            if (file.extension.equals("elf", true)) append(elfSummary(bytes))
            appendLine()
            appendLine("First ${bytes.size.coerceAtMost(512)} bytes:")
        }
        val hex = bytes.take(512).chunked(16).mapIndexed { row, chunk ->
            val offset = "%08x".format(row * 16)
            val values = chunk.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
            val ascii = chunk.joinToString("") { byte -> (byte.toInt() and 0xff).let { if (it in 32..126) it.toChar().toString() else "." } }
            "$offset  ${values.padEnd(47)}  |$ascii|"
        }.joinToString("\n")
        return header + hex
    }

    /** Opens a bounded text file directly from the real project tree, including build output. */
    fun readProjectTextFile(projectKey: String, relativePath: String): String {
        require(isEditableProjectTextFile(relativePath)) { "Unsupported text file" }
        val root = projectDirectory(projectKey).canonicalFile
        val file = File(root, relativePath).canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file.isFile) { "File is no longer available" }
        require(file.length() <= 5_000_000L) { "File is larger than the 5 MB editor limit" }
        val bytes = file.readBytes()
        require(bytes.none { it == 0.toByte() }) { "This file contains binary data" }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun uf2Summary(bytes: ByteArray, fileSize: Long): String {
        if (bytes.size < 32) return "Format: truncated UF2\n"
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = data.getInt(0).toUInt()
        if (magic != 0x0A324655u) return "Format: invalid UF2 header\n"
        return buildString {
            appendLine("Format: UF2 firmware")
            appendLine("Blocks: ${fileSize / 512}")
            appendLine("Target address: 0x${data.getInt(12).toUInt().toString(16)}")
            appendLine("Payload per first block: ${data.getInt(16).toUInt()} bytes")
            appendLine("Family ID: 0x${data.getInt(28).toUInt().toString(16)}")
        }
    }

    private fun elfSummary(bytes: ByteArray): String {
        if (bytes.size < 20 || bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() || bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()) {
            return "Format: invalid ELF header\n"
        }
        val order = if (bytes[5].toInt() == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val data = ByteBuffer.wrap(bytes).order(order)
        val machine = data.getShort(18).toInt() and 0xffff
        val machineName = when (machine) { 40 -> "ARM"; 243 -> "RISC-V"; else -> "machine $machine" }
        return "Format: ELF${if (bytes[4].toInt() == 2) "64" else "32"} · $machineName · ${if (order == ByteOrder.LITTLE_ENDIAN) "little" else "big"}-endian\n"
    }

    /** A real disk view for Explorer. Text outputs are editable; binary outputs are previews. */
    fun projectTree(projectKey: String): List<ProjectTreeEntry> {
        val root = projectDirectory(projectKey)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .onEnter { directory ->
                if (directory == root) return@onEnter true
                val path = directory.relativeTo(root).invariantSeparatorsPath
                directory.name !in setOf(".git", ".gradle", ".idea", ".foldcode", "target") &&
                    path !in setOf("build/.cmake", "build/CMakeFiles/FoldCode")
            }
            .drop(1)
            .take(10_000)
            .mapNotNull { entry ->
                val path = entry.relativeTo(root).invariantSeparatorsPath
                if (path == ".last-command.log") return@mapNotNull null
                ProjectTreeEntry(path, entry.isDirectory, entry.isDirectory || isEditableFile(entry.name))
            }
            .toList()
    }

    fun deleteProject(projectKey: String) {
        val directory = projectDirectory(projectKey)
        require(directory.parentFile?.canonicalFile == projectsRoot.canonicalFile) { "Invalid project" }
        require(directory.deleteRecursively()) { "Could not delete ${directory.name}" }
    }

    fun canonicalProjectKey(projectKey: String): String = "project:${projectDirectory(projectKey).name}"

    fun initialize(projectKey: String, files: Map<String, String>): GitRepositoryState {
        val directory = syncProject(projectKey, files)
        if (!File(directory, ".git").isDirectory) {
            git.requireSuccess(directory, listOf("init", "--initial-branch=main"))
            configureIdentity(directory)
        }
        return status(projectKey, files)
    }

    fun clone(url: String, username: String, token: String): GitProjectResult {
        val normalizedUrl = normalizeGitHubUrl(url)
        val projectKey = normalizedUrl
        var directory = projectDirectory(projectKey)
        if (File(directory, ".git").isDirectory) {
            val existingFiles = readEditableFiles(directory)
            require(existingFiles.isNotEmpty()) { "The existing clone has no supported text/source files" }
            return GitProjectResult(
                projectKey = "project:${directory.name}",
                projectName = normalizedUrl.substringAfterLast('/').removeSuffix(".git"),
                files = existingFiles,
                folders = readProjectFolders(directory),
                state = status(projectKey, existingFiles).copy(message = "Repository opened"),
            )
        }
        if (directory.exists()) directory = uniqueProjectDirectory(directory.name)
        directory.parentFile?.mkdirs()

        try {
            git.requireSuccess(
                directory.parentFile ?: projectsRoot,
                listOf("clone", normalizedUrl, directory.absolutePath),
                username,
                token,
            )
            configureIdentity(directory)
            val files = readEditableFiles(directory)
            require(files.isNotEmpty()) { "The repository cloned, but it has no supported text/source files" }
            val clonedProjectKey = "project:${directory.name}"
            return GitProjectResult(
                projectKey = clonedProjectKey,
                projectName = normalizedUrl.substringAfterLast('/').removeSuffix(".git"),
                files = files,
                folders = readProjectFolders(directory),
                state = status(clonedProjectKey, files).copy(message = "Repository cloned"),
            )
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    fun setRemote(projectKey: String, files: Map<String, String>, url: String): GitRepositoryState {
        val normalizedUrl = normalizeGitHubUrl(url)
        val directory = requireRepository(projectKey, files)
        val branch = currentBranch(directory)
        val existing = git.run(directory, listOf("remote", "get-url", "origin"))
        if (existing.exitCode == 0) git.requireSuccess(directory, listOf("remote", "set-url", "origin", normalizedUrl))
        else git.requireSuccess(directory, listOf("remote", "add", "origin", normalizedUrl))
        git.requireSuccess(directory, listOf("config", "branch.$branch.remote", "origin"))
        git.requireSuccess(directory, listOf("config", "branch.$branch.merge", "refs/heads/$branch"))
        return status(projectKey, files).copy(message = "GitHub remote configured")
    }

    fun stageAll(projectKey: String, files: Map<String, String>): GitRepositoryState {
        val directory = requireRepository(projectKey, files)
        git.requireSuccess(directory, listOf("add", "--all"))
        return status(projectKey, files)
    }

    fun commit(projectKey: String, files: Map<String, String>, message: String): GitRepositoryState {
        require(message.isNotBlank()) { "Enter a commit message" }
        val directory = requireRepository(projectKey, files)
        require(repositoryState(directory).stagedCount > 0) { "Stage at least one change before committing" }
        git.requireSuccess(directory, listOf("commit", "-m", message.trim()))
        return status(projectKey, files).copy(message = "Commit created")
    }

    fun pull(projectKey: String, files: Map<String, String>, username: String, token: String): GitProjectResult {
        val directory = requireRepository(projectKey, files)
        require(repositoryState(directory).changes.isEmpty()) { "Commit or discard local changes before pulling" }
        requireRemote(directory)
        git.requireSuccess(directory, listOf("pull", "--ff-only"), username, token)
        val updatedFiles = readEditableFiles(directory)
        return GitProjectResult(
            projectKey = canonicalProjectKey(projectKey),
            projectName = directory.name,
            files = updatedFiles,
            folders = readProjectFolders(directory),
            state = status(projectKey, updatedFiles).copy(message = "Pull completed"),
        )
    }

    fun push(projectKey: String, files: Map<String, String>, username: String, token: String): GitRepositoryState {
        val directory = requireRepository(projectKey, files)
        requireRemote(directory)
        require(username.isNotBlank()) { "Enter your GitHub username" }
        require(token.isNotBlank()) { "Enter a GitHub personal access token" }
        git.requireSuccess(directory, listOf("push", "--all", "origin"), username, token)
        return status(projectKey, files).copy(message = "Push completed")
    }

    fun publish(
        projectKey: String,
        files: Map<String, String>,
        username: String,
        token: String,
        repositoryName: String,
        privateRepository: Boolean,
    ): GitRepositoryState {
        require(username.isNotBlank()) { "Enter your GitHub username" }
        require(token.isNotBlank()) { "Enter a GitHub personal access token" }
        val name = repositoryName.trim()
        require(name.matches(Regex("[A-Za-z0-9._-]{1,100}"))) {
            "Repository name may contain letters, numbers, dots, hyphens, and underscores"
        }
        val directory = requireRepository(projectKey, files)
        val state = repositoryState(directory)
        require(state.changes.isEmpty()) { "Commit local changes before publishing" }
        require(state.remoteUrl == null) { "This branch already has an origin remote" }

        createGitHubRepository(name, privateRepository, token)
        val remoteUrl = "https://github.com/${username.trim()}/$name.git"
        setRemote(projectKey, files, remoteUrl)
        return push(projectKey, files, username, token).copy(message = "Branch published to GitHub")
    }

    private fun requireRepository(projectKey: String, files: Map<String, String>): File {
        val directory = syncProject(projectKey, files)
        require(File(directory, ".git").isDirectory) { "Initialize the repository first" }
        ensureFoldCodeExcludes(directory)
        return directory
    }

    private fun requireRemote(directory: File): String =
        git.requireSuccess(directory, listOf("remote", "get-url", "origin"))
            .takeIf(String::isNotBlank) ?: error("Configure a GitHub origin remote first")

    private fun syncProject(
        projectKey: String,
        files: Map<String, String>,
        folders: Set<String> = emptySet(),
        removeMissing: Boolean = false,
    ): File {
        val directory = projectDirectory(projectKey).apply { mkdirs() }
        val canonicalRoot = directory.canonicalFile.toPath()
        folders.forEach { path ->
            val destination = File(directory, path).canonicalFile
            require(destination.toPath().startsWith(canonicalRoot) && destination != directory.canonicalFile) {
                "Invalid project folder: $path"
            }
            destination.mkdirs()
        }
        files.forEach { (path, content) ->
            val destination = File(directory, path).canonicalFile
            require(destination.toPath().startsWith(canonicalRoot)) { "Invalid project path: $path" }
            destination.parentFile?.mkdirs()
            if (!destination.isFile || destination.readText() != content) destination.writeText(content)
        }
        if (removeMissing) {
            val expected = files.keys.map { it.replace('\\', '/') }.toSet()
            val expectedFolders = folders.map { it.replace('\\', '/').trim('/') }.toSet()
            readEditableFiles(directory).keys.filterNot(expected::contains).forEach { path ->
                File(directory, path).delete()
            }
            directory.walkBottomUp()
                .filter { candidate ->
                    candidate.isDirectory && candidate != directory && candidate.name != ".git" &&
                        candidate.listFiles().isNullOrEmpty() &&
                        canonicalRoot.relativize(candidate.canonicalFile.toPath()).toString()
                            .replace(File.separatorChar, '/') !in expectedFolders
                }
                .forEach(File::delete)
        }
        return directory
    }

    private fun readProjectFolders(directory: File): Set<String> {
        val rootPath = directory.canonicalFile.toPath()
        return directory.walkTopDown()
            .onEnter {
                it == directory || it.name !in setOf(
                    ".git", "build", "target", ".foldcode", ".gradle", ".idea", ".cxx", "node_modules",
                )
            }
            .filter { it.isDirectory && it != directory }
            .map { rootPath.relativize(it.canonicalFile.toPath()).toString().replace(File.separatorChar, '/') }
            .toSet()
    }

    private fun readEditableFiles(directory: File): Map<String, String> {
        val rootPath = directory.canonicalFile.toPath()
        val candidates = directory.walkTopDown()
            .onEnter {
                it == directory || it.name !in setOf(
                    ".git", "build", "target", ".foldcode", ".gradle", ".idea", ".cxx", "node_modules",
                )
            }
            .filter { it.isFile && isEditableFile(it.name) && it.length() <= 5_000_000L }
            .sortedWith(compareBy<File>(
                { it.parentFile != directory },
                { it.name != "CMakeLists.txt" },
                { it.relativeTo(directory).invariantSeparatorsPath.lowercase() },
            ))
            .iterator()
        val loaded = linkedMapOf<String, String>()
        var totalBytes = 0L
        while (candidates.hasNext() && loaded.size < 500 && totalBytes < 20_000_000L) {
            val file = candidates.next()
            if (totalBytes + file.length() > 20_000_000L) continue
            val content = file.readText()
            totalBytes += content.toByteArray().size
            loaded[rootPath.relativize(file.canonicalFile.toPath()).toString().replace(File.separatorChar, '/')] = content
        }
        return loaded
    }

    private fun isEditableFile(name: String): Boolean {
        return isEditableProjectTextFile(name)
    }

    private fun inferProjectName(directory: File): String {
        return when {
            File(directory, "main.cpp").isFile && File(directory, "greetings.cpp").isFile -> "HELLO_CPP"
            File(directory, "CMakeLists.txt").isFile -> "C++ Project"
            File(directory, "build.gradle.kts").isFile || File(directory, "build.gradle").isFile -> "Gradle Project"
            else -> "Local ${directory.name.take(8)}"
        }
    }

    private fun projectDisplayName(directory: File): String {
        if (File(directory, ".git").isDirectory) {
            val remoteName = runCatching {
                repositoryState(directory).remoteUrl?.substringAfterLast('/')?.removeSuffix(".git")
            }.getOrNull()
            if (!remoteName.isNullOrBlank()) return remoteName
        }
        return inferProjectName(directory)
    }

    private fun uniqueProjectDirectory(requestedName: String): File {
        val base = sanitizeProjectName(requestedName)
        var candidate = File(projectsRoot, base)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(projectsRoot, "$base-$suffix")
            suffix++
        }
        return candidate
    }

    private fun sanitizeProjectName(value: String): String = value.trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-', '.')
        .ifBlank { "Project" }

    private fun configureIdentity(directory: File) {
        ensureFoldCodeExcludes(directory)
        if (git.run(directory, listOf("config", "--get", "user.name")).output.isBlank()) {
            git.requireSuccess(directory, listOf("config", "user.name", "FoldCode User"))
        }
        if (git.run(directory, listOf("config", "--get", "user.email")).output.isBlank()) {
            git.requireSuccess(directory, listOf("config", "user.email", "foldcode@device.local"))
        }
    }

    /**
     * Keep generated files out of Source Control without changing the project's
     * tracked .gitignore. `.git/info/exclude` is repository-local, so this also
     * works for imported repositories whose ignore rules FoldCode must preserve.
     */
    private fun ensureFoldCodeExcludes(directory: File) {
        val gitDirectory = File(directory, ".git")
        if (!gitDirectory.isDirectory) return
        val exclude = File(gitDirectory, "info/exclude")
        val existing = exclude.takeIf(File::isFile)?.readText().orEmpty()
        val missing = listOf("/build/", "/target/", "/.foldcode/").filter { pattern ->
            existing.lineSequence().none { it.trim() == pattern }
        }
        if (missing.isEmpty()) return
        exclude.parentFile?.mkdirs()
        exclude.appendText(buildString {
            if (existing.isNotEmpty() && !existing.endsWith('\n')) appendLine()
            if (existing.lineSequence().none { it.trim() == "# FoldCode generated files" }) {
                appendLine("# FoldCode generated files")
            }
            missing.forEach(::appendLine)
        })
    }

    private fun createGitHubRepository(name: String, privateRepository: Boolean, token: String) {
        val connection = URL("https://api.github.com/user/repos").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = "{\"name\":\"$name\",\"private\":$privateRepository}"
            connection.outputStream.bufferedWriter().use { it.write(body) }
            val responseCode = connection.responseCode
            val response = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            require(responseCode == 201 || (responseCode == 422 && response.contains("name already exists", ignoreCase = true))) {
                "GitHub could not create the repository (HTTP $responseCode)"
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeGitHubUrl(value: String): String {
        val candidate = value.trim().let { if (it.endsWith(".git")) it else "$it.git" }
        val uri = runCatching { URI(candidate) }.getOrElse { error("Enter a valid GitHub HTTPS URL") }
        require(uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true)) {
            "Only https://github.com/OWNER/REPOSITORY URLs are supported"
        }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "Do not put credentials or parameters in the repository URL" }
        val segments = uri.path.trim('/').split('/').filter(String::isNotBlank)
        require(segments.size == 2) { "Use https://github.com/OWNER/REPOSITORY" }
        return "https://github.com/${segments[0]}/${segments[1].removeSuffix(".git")}.git"
    }

    private fun projectDirectory(projectKey: String): File {
        if (projectKey.startsWith("project:")) {
            val name = projectKey.removePrefix("project:")
            require(name == sanitizeProjectName(name)) { "Invalid project identifier" }
            return File(projectsRoot, name)
        }
        if (projectKey.startsWith("workspace:")) {
            val id = projectKey.removePrefix("workspace:")
            require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid workspace identifier" }
            return File(projectsRoot, id)
        }
        if (projectKey == "foldcode-sample-project") return File(projectsRoot, "HELLO_CPP")
        if (projectKey.startsWith("https://github.com/")) {
            return File(projectsRoot, sanitizeProjectName(projectKey.substringAfterLast('/').removeSuffix(".git")))
        }
        return File(projectsRoot, sanitizeProjectName("Project-${sha256(projectKey).take(8)}"))
    }

    private fun currentBranch(directory: File): String =
        git.requireSuccess(directory, listOf("branch", "--show-current")).ifBlank { "main" }

    private fun repositoryState(directory: File): GitRepositoryState {
        ensureFoldCodeExcludes(directory)
        val branch = currentBranch(directory)
        val remote = git.run(directory, listOf("remote", "get-url", "origin")).let {
            if (it.exitCode == 0) it.output.takeIf(String::isNotBlank) else null
        }
        val status = git.requireSuccess(directory, listOf("status", "--porcelain=v1", "-z"))
        val changes = buildList {
            val records = status.split('\u0000')
            var position = 0
            while (position < records.size) {
                val record = records[position++]
                if (record.length < 4) continue
                val index = record[0]
                val workTree = record[1]
                val path = record.substring(3)
                // In porcelain -z mode a rename/copy stores the destination in
                // this record and the source as the following NUL record.
                if (index in "RC" || workTree in "RC") position++
                if (index == '?' && workTree == '?') {
                    add(GitFileChange(path, "U", false))
                } else {
                    if (index != ' ') add(GitFileChange(path, index.toString(), true))
                    if (workTree != ' ') add(GitFileChange(path, workTree.toString(), false))
                }
            }
        }.sortedWith(compareBy<GitFileChange>({ !it.staged }, { it.path }))
        return GitRepositoryState(true, branch, remote, changes)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
