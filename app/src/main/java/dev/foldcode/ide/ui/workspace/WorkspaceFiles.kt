package dev.foldcode.ide

import android.content.Context

internal class WorkspaceFileBindings(
    val terminal: MutableWorkspaceValue<String>,
    val files: MutableWorkspaceValue<Map<String, ProjectFile>>,
    val savedProjectSources: MutableWorkspaceValue<Map<String, String>>,
    val folders: MutableWorkspaceValue<Set<String>>,
    val openTabs: MutableWorkspaceValue<List<String>>,
    val activeFileName: MutableWorkspaceValue<String>,
    val secondaryOpenTabs: MutableWorkspaceValue<List<String>>,
    val secondaryActiveFileName: MutableWorkspaceValue<String>,
    val splitEditorVisible: MutableWorkspaceValue<Boolean>,
    val drawerOpen: MutableWorkspaceValue<Boolean>,
    val dexBrowserFileName: MutableWorkspaceValue<String>,
    val dexBrowserVisible: MutableWorkspaceValue<Boolean>,
    val terminalVisible: MutableWorkspaceValue<Boolean>,
    val editorNavigation: MutableWorkspaceValue<EditorNavigationRequest?>,
    val editorNavigationRevision: MutableWorkspaceValue<Int>,
    val dexWorkspaceActive: () -> Boolean,
    val projectKey: () -> String,
)

internal class WorkspaceFileController(
    private val context: Context,
    private val gitEngine: GitEngine,
    private val bindings: WorkspaceFileBindings,
) {
    private var terminal by bindings.terminal
    private var files by bindings.files
    private var savedProjectSources by bindings.savedProjectSources
    private var folders by bindings.folders
    private var openTabs by bindings.openTabs
    private var activeFileName by bindings.activeFileName
    private var secondaryOpenTabs by bindings.secondaryOpenTabs
    private var secondaryActiveFileName by bindings.secondaryActiveFileName
    private var splitEditorVisible by bindings.splitEditorVisible
    private var drawerOpen by bindings.drawerOpen
    private var dexBrowserFileName by bindings.dexBrowserFileName
    private var dexBrowserVisible by bindings.dexBrowserVisible
    private var terminalVisible by bindings.terminalVisible
    private var editorNavigation by bindings.editorNavigation
    private var editorNavigationRevision by bindings.editorNavigationRevision

    private val dexWorkspaceActive get() = bindings.dexWorkspaceActive()
    private val projectKey get() = bindings.projectKey()

    fun openFile(name: String) {
        if (name !in files && isEditableProjectTextFile(name) && projectKey.startsWith("project:")) {
            terminal = "Opening ${name.substringAfterLast('/')}…"
            Thread {
                val loaded = runCatching { gitEngine.readProjectTextFile(projectKey, name) }
                postToMainThread {
                    loaded.onSuccess { content ->
                        files = files + (name to ProjectFile(name, content))
                        savedProjectSources = savedProjectSources + (name to content)
                        folders = folders + parentFolderPaths(name)
                        if (name !in openTabs) openTabs = openTabs + name
                        activeFileName = name
                        terminal = "Opened $name"
                    }.onFailure { terminal = "Could not open text file: ${it.message}" }
                }
            }.start()
            return
        }
        if (name !in files && isInspectableArtifact(name) && projectKey.startsWith("project:")) {
            terminal = "Opening ${name.substringAfterLast('/')}…"
            Thread {
                val preview = runCatching { gitEngine.artifactPreview(projectKey, name) }
                postToMainThread {
                    preview.onSuccess { content ->
                        files = files + (name to ProjectFile(name, content, readOnly = true))
                        if (name !in openTabs) openTabs = openTabs + name
                        activeFileName = name
                        terminal = "Opened ${name.substringAfterLast('/')} as a read-only binary preview"
                    }.onFailure { terminal = "Could not open artifact: ${it.message}" }
                }
            }.start()
            return
        }
        if (name !in files) return
        if (name !in openTabs) openTabs = openTabs + name
        activeFileName = name
    }

    fun closeFile(name: String) {
        val oldIndex = openTabs.indexOf(name)
        val remaining = openTabs - name
        openTabs = remaining
        if (activeFileName == name) {
            activeFileName = if (remaining.isEmpty()) "" else remaining[oldIndex.coerceAtMost(remaining.lastIndex)]
        }
    }

    fun openAuxiliaryOrTab(name: String, hideTerminalOnPhone: Boolean = false) {
        val auxiliaryKind = files[name]?.kind
        val opensInDexAuxiliaryPane = auxiliaryKind == ProjectFileKind.Documentation ||
            auxiliaryKind == ProjectFileKind.WebPreview
        if (dexWorkspaceActive && opensInDexAuxiliaryPane) {
            dexBrowserFileName = name
            dexBrowserVisible = true
            openTabs = openTabs - name
            secondaryOpenTabs = secondaryOpenTabs - name
        } else {
            if (name !in openTabs) openTabs = openTabs + name
            activeFileName = name
            if (hideTerminalOnPhone) terminalVisible = false
        }
        drawerOpen = false
    }

    fun openPicoExtensionDetails() {
        if (PICO_EXTENSION_TAB !in files) {
            files = files + (
                PICO_EXTENSION_TAB to ProjectFile(
                    name = PICO_EXTENSION_TAB,
                    content = "",
                    readOnly = true,
                    kind = ProjectFileKind.PicoExtensionDetails,
                )
            )
        }
        openAuxiliaryOrTab(PICO_EXTENSION_TAB)
    }

    fun openPicoDocumentation(name: String, content: () -> String) {
        val document = ProjectFile(
            name = name,
            content = content(),
            readOnly = true,
            kind = ProjectFileKind.Documentation,
        )
        files = files + (name to document)
        openAuxiliaryOrTab(name, hideTerminalOnPhone = true)
    }

    fun openPicoHardwareApis() {
        val sdkRoot = PicoCMakeSdkBundle(context).sdkRoot
        openPicoDocumentation(PICO_HARDWARE_APIS_TAB) { PicoDocumentation.hardwareApis(sdkRoot) }
    }

    fun openPicoHighLevelApis() {
        openPicoDocumentation(PICO_HIGH_LEVEL_APIS_TAB) { PicoDocumentation.highLevelApis() }
    }

    fun openPicoNetworkingLibraries() {
        openPicoDocumentation(PICO_NETWORKING_LIBRARIES_TAB) { PicoDocumentation.networkingLibraries() }
    }

    fun openPicoRuntimeInfrastructure() {
        openPicoDocumentation(PICO_RUNTIME_INFRASTRUCTURE_TAB) { PicoDocumentation.runtimeInfrastructure() }
    }

    fun openPicoSdkReference() {
        val sdkRoot = PicoCMakeSdkBundle(context).sdkRoot
        openPicoDocumentation(PICO_SDK_REFERENCE_TAB) { PicoDocumentation.sdkReference(sdkRoot) }
    }

    fun openCppExtensionDetails() {
        if (CPP_EXTENSION_TAB !in files) {
            files = files + (
                CPP_EXTENSION_TAB to ProjectFile(
                    name = CPP_EXTENSION_TAB,
                    content = "",
                    readOnly = true,
                    kind = ProjectFileKind.CppExtensionDetails,
                )
            )
        }
        openAuxiliaryOrTab(CPP_EXTENSION_TAB)
    }

    fun openPythonExtensionDetails() {
        if (PYTHON_EXTENSION_TAB !in files) {
            files = files + (
                PYTHON_EXTENSION_TAB to ProjectFile(
                    name = PYTHON_EXTENSION_TAB,
                    content = "",
                    readOnly = true,
                    kind = ProjectFileKind.PythonExtensionDetails,
                )
            )
        }
        openAuxiliaryOrTab(PYTHON_EXTENSION_TAB)
    }

    fun openRustExtensionDetails() {
        if (RUST_EXTENSION_TAB !in files) {
            files = files + (
                RUST_EXTENSION_TAB to ProjectFile(
                    name = RUST_EXTENSION_TAB,
                    content = "",
                    readOnly = true,
                    kind = ProjectFileKind.RustExtensionDetails,
                )
            )
        }
        openAuxiliaryOrTab(RUST_EXTENSION_TAB)
    }

    fun openGnuLanguagesExtensionDetails() {
        if (GNU_LANGUAGES_EXTENSION_TAB !in files) {
            files = files + (
                GNU_LANGUAGES_EXTENSION_TAB to ProjectFile(
                    name = GNU_LANGUAGES_EXTENSION_TAB,
                    content = "",
                    readOnly = true,
                    kind = ProjectFileKind.GnuLanguagesExtensionDetails,
                )
            )
        }
        openAuxiliaryOrTab(GNU_LANGUAGES_EXTENSION_TAB)
    }

    fun openWebExtensionDetails() {
        if (WEB_EXTENSION_TAB !in files) {
            files = files + (
                WEB_EXTENSION_TAB to ProjectFile(
                    name = WEB_EXTENSION_TAB,
                    content = "",
                    readOnly = true,
                    kind = ProjectFileKind.WebExtensionDetails,
                )
            )
        }
        openAuxiliaryOrTab(WEB_EXTENSION_TAB)
    }

    fun openWebPreview(url: String = "http://127.0.0.1:3000") {
        files = files + (
            WEB_PREVIEW_TAB to ProjectFile(
                name = WEB_PREVIEW_TAB,
                content = url,
                readOnly = true,
                kind = ProjectFileKind.WebPreview,
            )
        )
        openAuxiliaryOrTab(WEB_PREVIEW_TAB)
    }

    /** Unload Vite's reconnecting client when its owned development server stops. */
    fun disconnectWebPreview() {
        files = disconnectedWebPreviewFiles(files)
    }

    fun openGitExtensionDetails() {
        if (GIT_EXTENSION_TAB !in files) {
            files = files + (
                GIT_EXTENSION_TAB to ProjectFile(
                    name = GIT_EXTENSION_TAB,
                    content = "",
                    readOnly = true,
                    kind = ProjectFileKind.GitExtensionDetails,
                )
            )
        }
        openAuxiliaryOrTab(GIT_EXTENSION_TAB)
    }

    fun createFile(pathValue: String) {
        runCatching {
            val path = validatedEntryName(pathValue)
            require(path !in files && path !in folders) { "$path already exists" }
            files = files + (path to ProjectFile(path, ""))
            folders = folders + parentFolderPaths(path)
            openTabs = openTabs + path
            activeFileName = path
        }.onFailure { terminal = "Could not create file: ${it.message}" }
    }

    fun navigateToFile(fileName: String, line: Int, column: Int) {
        openFile(fileName)
        editorNavigationRevision += 1
        editorNavigation = EditorNavigationRequest(fileName, line, column, editorNavigationRevision)
    }

    fun createFolder(pathValue: String) {
        runCatching {
            val path = validatedEntryName(pathValue)
            require(path !in files && path !in folders) { "$path already exists" }
            folders = folders + path + parentFolderPaths(path)
        }.onFailure { terminal = "Could not create folder: ${it.message}" }
    }

    fun relocatePath(source: String, targetValue: String, folder: Boolean) {
        runCatching {
            val target = validatedProjectPath(targetValue)
            require(target != source) { "Choose a different name" }
            require(target !in files && target !in folders) { "$target already exists" }
            if (folder) {
                require(!target.startsWith("$source/")) { "A folder cannot be moved inside itself" }
                val prefix = "$source/"
                files = files.mapKeys { (name, _) -> if (name.startsWith(prefix)) "$target/${name.removePrefix(prefix)}" else name }
                    .mapValues { (name, file) -> file.copy(name = name) }
                folders = folders.map { name ->
                    when {
                        name == source -> target
                        name.startsWith(prefix) -> "$target/${name.removePrefix(prefix)}"
                        else -> name
                    }
                }.toSet() + parentFolderPaths(target)
                openTabs = openTabs.map { if (it.startsWith(prefix)) "$target/${it.removePrefix(prefix)}" else it }
                if (activeFileName.startsWith(prefix)) activeFileName = "$target/${activeFileName.removePrefix(prefix)}"
            } else {
                val file = files[source] ?: error("File is no longer available")
                files = files - source + (target to file.copy(name = target))
                folders = folders + parentFolderPaths(target)
                openTabs = openTabs.map { if (it == source) target else it }
                if (activeFileName == source) activeFileName = target
            }
        }.onFailure { terminal = "Could not rename item: ${it.message}" }
    }

    fun renamePath(source: String, nameValue: String, folder: Boolean) {
        val name = runCatching { validatedEntryName(nameValue) }
            .onFailure { terminal = "Could not rename item: ${it.message}" }
            .getOrNull() ?: return
        val parent = source.substringBeforeLast('/', "")
        relocatePath(source, if (parent.isBlank()) name else "$parent/$name", folder)
    }

    fun movePath(source: String, destinationFolder: String, folder: Boolean) {
        val destination = destinationFolder.trim().trim('/')
        if (destination.isNotBlank() && destination !in folders) {
            terminal = "Could not move item: Select an existing folder"
            return
        }
        val target = if (destination.isBlank()) source.substringAfterLast('/') else "$destination/${source.substringAfterLast('/')}"
        relocatePath(source, target, folder)
    }

    fun copyPath(source: String, destinationFolder: String, folder: Boolean) {
        runCatching {
            val destination = destinationFolder.trim().trim('/')
            require(destination.isBlank() || destination in folders) { "Select an existing folder" }
            val originalName = source.substringAfterLast('/')
            val requested = if (destination.isBlank()) originalName else "$destination/$originalName"
            fun availableTarget(): String {
                if (requested !in files && requested !in folders) return requested
                val extension = if (!folder && '.' in originalName) ".${originalName.substringAfterLast('.')}" else ""
                val stem = originalName.removeSuffix(extension)
                var index = 1
                while (true) {
                    val suffix = if (index == 1) " copy" else " copy $index"
                    val candidateName = "$stem$suffix$extension"
                    val candidate = if (destination.isBlank()) candidateName else "$destination/$candidateName"
                    if (candidate !in files && candidate !in folders) return candidate
                    index++
                }
            }
            val target = availableTarget()
            if (folder) {
                val prefix = "$source/"
                val copiedFiles = files.filterKeys { it.startsWith(prefix) }.mapKeys { (name, _) ->
                    "$target/${name.removePrefix(prefix)}"
                }.mapValues { (name, file) -> file.copy(name = name) }
                val copiedFolders = folders.filter { it == source || it.startsWith(prefix) }.map { name ->
                    if (name == source) target else "$target/${name.removePrefix(prefix)}"
                }
                files = files + copiedFiles
                folders = folders + copiedFolders + parentFolderPaths(target)
            } else {
                val file = files[source] ?: error("File is no longer available")
                files = files + (target to file.copy(name = target))
                folders = folders + parentFolderPaths(target)
            }
        }.onFailure { terminal = "Could not copy item: ${it.message}" }
    }

    fun deletePath(path: String, folder: Boolean) {
        if (folder) {
            val prefix = "$path/"
            val removedFiles = files.keys.filter { it.startsWith(prefix) }.toSet()
            files = files.filterKeys { it !in removedFiles }
            folders = folders.filterNot { it == path || it.startsWith(prefix) }.toSet()
            openTabs = openTabs.filterNot { it in removedFiles }
            secondaryOpenTabs = secondaryOpenTabs.filterNot { it in removedFiles }
            if (activeFileName in removedFiles) activeFileName = openTabs.firstOrNull().orEmpty()
            if (secondaryActiveFileName in removedFiles) {
                secondaryActiveFileName = secondaryOpenTabs.firstOrNull().orEmpty()
                if (secondaryActiveFileName.isBlank()) splitEditorVisible = false
            }
        } else {
            files = files - path
            closeFile(path)
            secondaryOpenTabs = secondaryOpenTabs - path
            if (secondaryActiveFileName == path) {
                secondaryActiveFileName = secondaryOpenTabs.firstOrNull().orEmpty()
                if (secondaryActiveFileName.isBlank()) splitEditorVisible = false
            }
        }
    }

}

internal fun disconnectedWebPreviewFiles(files: Map<String, ProjectFile>): Map<String, ProjectFile> {
    val preview = files[WEB_PREVIEW_TAB]
        ?.takeIf { it.kind == ProjectFileKind.WebPreview && isLocalWebAddress(it.content) }
        ?: return files
    return files + (WEB_PREVIEW_TAB to preview.copy(content = DEFAULT_WEB_BROWSER_URL))
}
