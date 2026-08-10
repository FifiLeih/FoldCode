package dev.foldcode.ide

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.key
import java.util.concurrent.atomic.AtomicLong
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun IdeWorkspace() {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val engine = remember { CompilerEngine(context.applicationContext) }
    val clangIntelligence = remember { ClangIntelligenceService(context.applicationContext) }
    val pythonIntelligence = remember { PythonExtensionIntelligence(context.applicationContext) }
    val rustIntelligence = remember { RustExtensionIntelligence(context.applicationContext) }
    val webProjectRuntime = remember { WebProjectRuntime(context.applicationContext) }
    val webIntelligence = remember(webProjectRuntime) {
        WebExtensionIntelligence(context.applicationContext, webProjectRuntime)
    }
    val gnuLanguageIntelligence = remember { GnuLanguageIntelligence(context.applicationContext) }
    val assemblyIntelligence = remember { AssemblyIntelligence(context.applicationContext) }
    val rustPicoCompiler = remember { RustPicoCompilerEngine(context.applicationContext) }
    val gnuLanguagesCompiler = remember { GnuLanguagesCompilerEngine(context.applicationContext) }
    val picoUsbFlasher = remember { PicoUsbFlasher(context.applicationContext) }
    val microPythonUsbRunner = remember { MicroPythonUsbRunner(context.applicationContext) }
    val picotoolRunner = remember { PicotoolCommandRunner(context.applicationContext) }
    val extensionManager = remember { FoldCodeExtensionManager(context.applicationContext) }
    val extensionMarketplace = remember { FoldCodeExtensionMarketplace() }
    val extensions = rememberWorkspaceExtensions(context, extensionManager, extensionMarketplace)
    val picoRuntimeDelivery = remember { PicoRuntimeDelivery(context.applicationContext) }
    val gitEngine = remember { GitEngine(context.applicationContext) }
    val debugController = remember { DebugSessionController(context.applicationContext) }
    var debugSnapshot by remember { mutableStateOf(debugController.snapshot) }
    val picoConfigurationStore = remember { PicoProjectConfigurationStore(context.applicationContext) }
    val cmakeDependencyResolver = remember { CMakeDependencyResolver(context.applicationContext) }
    val gitCredentialStore = remember { GitCredentialStore(context.applicationContext) }
    val projectPreferences = remember { context.getSharedPreferences("foldcode_projects", Context.MODE_PRIVATE) }
    val appearanceStore = remember { IdePreferencesStore(context.applicationContext) }
    var appearance by remember { mutableStateOf(appearanceStore.load()) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val savedProjectKey = remember { projectPreferences.getString("last_repository", null) }
    val storedGitHubCredentials = remember { gitCredentialStore.load() }
    val cppExtensionInfo = extensions.cppExtensionInfo
    val picoExtensionInfo = extensions.picoExtensionInfo
    val pythonExtensionInfo = extensions.pythonExtensionInfo
    val rustExtensionInfo = extensions.rustExtensionInfo
    val gnuLanguagesExtensionInfo = extensions.gnuLanguagesExtensionInfo
    val webExtensionInfo = extensions.webExtensionInfo
    val gitToolsExtensionInfo = extensions.gitToolsExtensionInfo
    val gnuArmExtensionInfo = extensions.gnuArmExtensionInfo
    val extensionRuntimeKey = extensionRuntimeKey(
        cppExtensionInfo,
        picoExtensionInfo,
        pythonExtensionInfo,
        rustExtensionInfo,
        gnuLanguagesExtensionInfo,
        webExtensionInfo,
        gitToolsExtensionInfo,
        gnuArmExtensionInfo,
    )
    val extensionBusy = extensions.extensionBusy
    val extensionNotice = extensions.extensionNotice
    // Do not render the bundled sample while the real last project is restored.
    // A new project can still explicitly use this template from Explorer.
    var files by remember { mutableStateOf<Map<String, ProjectFile>>(emptyMap()) }
    var savedProjectSources by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var folders by remember { mutableStateOf<Set<String>>(emptySet()) }
    var projectTree by remember { mutableStateOf<List<ProjectTreeEntry>>(emptyList()) }
    var picoCMakeTargets by remember { mutableStateOf<List<String>>(emptyList()) }
    var projectName by remember {
        mutableStateOf(savedProjectKey?.removePrefix("project:") ?: "NO PROJECT")
    }
    var projectKey by remember { mutableStateOf(savedProjectKey.orEmpty()) }
    var openTabs by remember { mutableStateOf(emptyList<String>()) }
    var activeFileName by rememberSaveable { mutableStateOf("") }
    var splitEditorVisible by rememberSaveable { mutableStateOf(false) }
    var secondaryActiveFileName by rememberSaveable { mutableStateOf("") }
    var secondaryOpenTabs by remember { mutableStateOf(emptyList<String>()) }
    var splitEditorRatio by rememberSaveable { mutableFloatStateOf(0.5f) }
    var dexEditorZoom by rememberSaveable { mutableFloatStateOf(1f) }
    var dexSecondaryEditorZoom by rememberSaveable { mutableFloatStateOf(1f) }
    var destination by remember { mutableStateOf(ActivityDestination.Files) }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var sidePanelVisible by rememberSaveable { mutableStateOf(true) }
    var terminalVisible by rememberSaveable {
        mutableStateOf(
            if (appearance.restoreTerminal) projectPreferences.getBoolean("session_terminal_visible", true) else true,
        )
    }
    var terminalHeightValue by rememberSaveable { mutableFloatStateOf(230f) }
    var terminalWidthValue by rememberSaveable { mutableFloatStateOf(360f) }
    // Terminal sessions are owned by WorkspaceTerminalController and are not
    // restored across Activity recreation. Keeping a second transcript in
    // SavedState made the first terminal composition lay out an obsolete,
    // potentially very large build log before the live shell replaced it.
    var terminal by remember { mutableStateOf("") }
    var buildOutput by rememberSaveable { mutableStateOf("No build output yet") }
    var diagnosticsOutput by rememberSaveable { mutableStateOf("") }
    var picotoolBusy by remember { mutableStateOf(false) }
    var semanticCompletions by remember { mutableStateOf<List<CompletionItem>>(emptyList()) }
    var semanticDiagnostics by remember { mutableStateOf<List<CodeDiagnostic>>(emptyList()) }
    var building by remember { mutableStateOf(false) }
    var showBuildStatistics by rememberSaveable {
        mutableStateOf(projectPreferences.getBoolean("show_build_statistics", true))
    }
    var buildResourceStats by remember { mutableStateOf(BuildResourceStats()) }
    // DeX always owns a browser pane. Web projects redirect this same virtual
    // document to their local preview URL when they start.
    var dexBrowserFileName by rememberSaveable { mutableStateOf(WEB_PREVIEW_TAB) }
    // The auxiliary browser is opt-in for every app/activity start. Do not restore
    // its previous visibility from SavedState after FoldCode is reopened.
    var dexBrowserVisible by remember { mutableStateOf(false) }
    var dexBrowserWidthValue by rememberSaveable { mutableFloatStateOf(420f) }
    var consoleActive by remember { mutableStateOf(false) }
    var terminalCommandRunning by remember { mutableStateOf(false) }
    var managedTerminalSessionId by remember { mutableStateOf<Int?>(null) }
    val terminalController = remember { WorkspaceTerminalController(context.applicationContext) }
    terminalController.onActiveTranscriptChanged = { terminal = boundedWorkspaceOutput(it) }
    terminalController.onActiveRunningChanged = { terminalCommandRunning = it }
    var cleaningPicoBuild by remember { mutableStateOf(false) }
    var picoConfigurationRevision by remember { mutableIntStateOf(0) }
    var gitState by remember { mutableStateOf(GitRepositoryState()) }
    var gitBusy by remember { mutableStateOf(false) }
    var gitNotice by remember { mutableStateOf<String?>(null) }
    var gitHubUsername by rememberSaveable { mutableStateOf(storedGitHubCredentials.username) }
    // Keep the decrypted token out of SavedState; the persisted copy is Keystore-encrypted.
    var gitHubToken by remember { mutableStateOf(storedGitHubCredentials.token) }
    var showProjectsDialog by rememberSaveable { mutableStateOf(false) }
    var dexWorkspaceActive by remember { mutableStateOf(false) }
    var desktopExplorerCommand by remember { mutableStateOf<DesktopExplorerCommand?>(null) }
    var editorDropBounds by remember { mutableStateOf<Rect?>(null) }
    var workspaceSurfaceBounds by remember { mutableStateOf<Rect?>(null) }
    var explorerDragPath by remember { mutableStateOf<String?>(null) }
    var explorerDragPosition by remember { mutableStateOf<Offset?>(null) }
    var editorDragSourceGroup by remember { mutableStateOf<EditorGroup?>(null) }
    var editorNavigation by remember { mutableStateOf<EditorNavigationRequest?>(null) }
    var editorNavigationRevision by remember { mutableIntStateOf(0) }
    var projectChoices by remember { mutableStateOf<List<GitRepositoryInfo>>(emptyList()) }
    var projectsLoading by remember { mutableStateOf(false) }
    var projectLoaded by remember { mutableStateOf(false) }
    var terminalBeforeEditing by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(appearance.theme) { applyIdeTheme(appearance.theme) }
    val imeInsets = WindowInsets.ime
    // IME insets are animated one pixel at a time. Reading the raw bottom inset
    // here made this entire workspace recompose for every animation frame. The
    // layout only needs to know whether the keyboard is open, so collapse those
    // updates into a stable boolean state.
    val imeVisible by remember(imeInsets, density) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }
    val latestImeVisible by rememberUpdatedState(imeVisible)
    var imeWasVisible by remember { mutableStateOf(false) }
    val currentActiveFileName by rememberUpdatedState(activeFileName)
    val lifecycleOwner = context as LifecycleOwner

    // Saved instance state can retain the name of a virtual documentation or
    // extension tab even though virtual files are intentionally rebuilt only
    // when opened. Never let that stale name leave the editor on a blank page
    // while the real project workspace is restored.
    LaunchedEffect(activeFileName, openTabs, files) {
        if (activeFileName.isNotBlank() && (activeFileName !in openTabs || activeFileName !in files)) {
            activeFileName = openTabs.firstOrNull { it in files }.orEmpty()
        }
    }
    LaunchedEffect(splitEditorVisible, secondaryOpenTabs, files) {
        if (!splitEditorVisible) return@LaunchedEffect
        secondaryOpenTabs = secondaryOpenTabs.filter { it in files }
        if (secondaryActiveFileName !in secondaryOpenTabs || secondaryActiveFileName !in files) {
            secondaryActiveFileName = secondaryOpenTabs.firstOrNull().orEmpty()
        }
        if (secondaryActiveFileName.isBlank()) splitEditorVisible = false
    }
    LaunchedEffect(projectKey) {
        splitEditorVisible = false
        secondaryOpenTabs = emptyList()
        secondaryActiveFileName = ""
    }

    DisposableEffect(debugController) {
        debugController.listener = { next ->
            debugSnapshot = next
        }
        debugController.outputListener = { appended ->
            terminal = appendWorkspaceOutput(terminal, appended)
            terminalController.syncActive(terminal)
        }
        onDispose {
            debugController.listener = {}
            debugController.outputListener = {}
            debugController.close()
        }
    }

    DisposableEffect(clangIntelligence, rustIntelligence, webIntelligence, gnuLanguageIntelligence, assemblyIntelligence, lifecycleOwner) {
        clangIntelligence.diagnosticsListener = { file, values ->
            postToMainThread {
                if (file == currentActiveFileName) {
                    semanticDiagnostics = values
                }
            }
        }
        rustIntelligence.diagnosticsListener = { file, values ->
            postToMainThread {
                if (file == currentActiveFileName) {
                    semanticDiagnostics = values
                }
            }
        }
        webIntelligence.diagnosticsListener = { file, values ->
            postToMainThread {
                if (file == currentActiveFileName) {
                    semanticDiagnostics = values
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> clangIntelligence.resume()
                Lifecycle.Event.ON_STOP -> {
                    clangIntelligence.pause()
                    pythonIntelligence.reset()
                    rustIntelligence.reset()
                    webIntelligence.close()
                    gnuLanguageIntelligence.reset()
                    assemblyIntelligence.reset()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            clangIntelligence.close()
            pythonIntelligence.close()
            rustIntelligence.close()
            webIntelligence.close()
            gnuLanguageIntelligence.close()
            assemblyIntelligence.close()
            picoRuntimeDelivery.runtimeOrNull()?.cancel()
            rustPicoCompiler.cancel()
            gnuLanguagesCompiler.cancel()
            engine.cancel()
            terminalController.closeAll()
        }
    }

    LaunchedEffect(projectLoaded, projectKey, extensionRuntimeKey) {
        if (!projectLoaded || !projectKey.startsWith("project:")) return@LaunchedEffect
        val terminalProject = withContext(Dispatchers.IO) { gitEngine.projectDirectoryFile(projectKey) }
        terminalCommandRunning = false
        terminal = ""
        terminalController.startProject(terminalProject)
    }

    // Android Back hides the IME without removing focus from a text field. Clear it
    // after the visible -> hidden transition so cursor/selection handles cannot leak
    // above the compact drawer or activity rail.
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            imeWasVisible = true
        } else if (imeWasVisible) {
            // Samsung IME can briefly report a zero inset while it is opening.
            // Clearing focus in that transient frame immediately closes the
            // keyboard. Only treat a sustained hidden state as an actual Back
            // dismissal; a renewed visible state cancels this effect.
            delay(300)
            if (!latestImeVisible) {
                focusManager.clearFocus(force = true)
                terminalBeforeEditing?.let { terminalVisible = it }
                terminalBeforeEditing = null
                imeWasVisible = false
            }
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            terminal = "Importing complete project into FoldCode/Projects…"
            Thread {
                val loaded = runCatching { gitEngine.importProject(uri) }
                postToMainThread {
                    loaded.onSuccess { project ->
                        val loadedFiles = upgradeGeneratedPicoExample(context, project.files)
                            .mapValues { (name, content) -> ProjectFile(name, content) }
                        files = loadedFiles
                        savedProjectSources = loadedFiles.filterValues { !it.readOnly }.mapValues { it.value.content }
                        folders = project.folders
                        projectTree = gitEngine.projectTree(project.projectKey)
                        projectName = project.projectName
                        projectKey = project.projectKey
                        gitState = project.state
                        gitNotice = project.state.message
                        val first = loadedFiles.keys.firstOrNull { it.substringAfterLast('/') == "main.rs" }
                            ?: loadedFiles.keys.firstOrNull { it.substringAfterLast('/') == "main.cpp" }
                            ?: loadedFiles.keys.firstOrNull { it.substringAfterLast('/') == "main.c" }
                            ?: loadedFiles.keys.firstOrNull { it.substringAfterLast('/') == "main.py" }
                            ?: loadedFiles.keys.first()
                        openTabs = listOf(first)
                        activeFileName = first
                        destination = ActivityDestination.Files
                        sidePanelVisible = true
                        drawerOpen = false
                        projectPreferences.edit { putString("last_repository", project.projectKey) }
                        terminal = project.state.message ?: "Imported ${project.projectName}"
                    }.onFailure { error ->
                        terminal = "Could not import project: ${error.message}"
                    }
                }
            }.start()
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val loaded = runCatching {
                val displayName = DocumentFile.fromSingleUri(context, uri)?.name ?: "untitled.txt"
                require(isSupportedSource(displayName)) { "Unsupported text/source file" }
                val content = context.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readUpTo(MAX_IMPORTED_EDITOR_FILE_BYTES + 1)
                    require(bytes.size <= MAX_IMPORTED_EDITOR_FILE_BYTES) {
                        "File is larger than the 5 MB editor limit"
                    }
                    require(bytes.none { it == 0.toByte() }) { "This file contains binary data" }
                    bytes.toString(Charsets.UTF_8)
                } ?: error("Cannot read selected file")
                displayName to content
            }
            loaded.onSuccess { (originalName, content) ->
                var name = originalName
                var suffix = 2
                while (name in files) {
                    val dot = originalName.lastIndexOf('.')
                    name = if (dot > 0) {
                        "${originalName.substring(0, dot)}-$suffix${originalName.substring(dot)}"
                    } else "$originalName-$suffix"
                    suffix++
                }
                files = files + (name to ProjectFile(name, content))
                if (name !in openTabs) openTabs = openTabs + name
                activeFileName = name
            }.onFailure { terminal = "Could not open file: ${it.message}" }
        }
    }


    val fileController = WorkspaceFileController(
        context = context,
        gitEngine = gitEngine,
        bindings = WorkspaceFileBindings(
            terminal = mutableWorkspaceValue({ terminal }, { terminal = boundedWorkspaceOutput(it) }),
            files = mutableWorkspaceValue({ files }, { files = it }),
            savedProjectSources = mutableWorkspaceValue({ savedProjectSources }, { savedProjectSources = it }),
            folders = mutableWorkspaceValue({ folders }, { folders = it }),
            openTabs = mutableWorkspaceValue({ openTabs }, { openTabs = it }),
            activeFileName = mutableWorkspaceValue({ activeFileName }, { activeFileName = it }),
            secondaryOpenTabs = mutableWorkspaceValue({ secondaryOpenTabs }, { secondaryOpenTabs = it }),
            secondaryActiveFileName = mutableWorkspaceValue(
                { secondaryActiveFileName },
                { secondaryActiveFileName = it },
            ),
            splitEditorVisible = mutableWorkspaceValue({ splitEditorVisible }, { splitEditorVisible = it }),
            drawerOpen = mutableWorkspaceValue({ drawerOpen }, { drawerOpen = it }),
            dexBrowserFileName = mutableWorkspaceValue({ dexBrowserFileName }, { dexBrowserFileName = it }),
            dexBrowserVisible = mutableWorkspaceValue({ dexBrowserVisible }, { dexBrowserVisible = it }),
            terminalVisible = mutableWorkspaceValue({ terminalVisible }, { terminalVisible = it }),
            editorNavigation = mutableWorkspaceValue({ editorNavigation }, { editorNavigation = it }),
            editorNavigationRevision = mutableWorkspaceValue(
                { editorNavigationRevision },
                { editorNavigationRevision = it },
            ),
            dexWorkspaceActive = { dexWorkspaceActive },
            projectKey = { projectKey },
        ),
    )

    fun openFile(name: String) = fileController.openFile(name)
    fun closeFile(name: String) = fileController.closeFile(name)
    fun openPicoExtensionDetails() = fileController.openPicoExtensionDetails()
    fun openPicoHardwareApis() = fileController.openPicoHardwareApis()
    fun openPicoHighLevelApis() = fileController.openPicoHighLevelApis()
    fun openPicoNetworkingLibraries() = fileController.openPicoNetworkingLibraries()
    fun openPicoRuntimeInfrastructure() = fileController.openPicoRuntimeInfrastructure()
    fun openPicoSdkReference() = fileController.openPicoSdkReference()
    fun openCppExtensionDetails() = fileController.openCppExtensionDetails()
    fun openPythonExtensionDetails() = fileController.openPythonExtensionDetails()
    fun openRustExtensionDetails() = fileController.openRustExtensionDetails()
    fun openGnuLanguagesExtensionDetails() = fileController.openGnuLanguagesExtensionDetails()
    fun openWebExtensionDetails() = fileController.openWebExtensionDetails()
    fun openWebPreview(url: String = "http://127.0.0.1:3000") = fileController.openWebPreview(url)
    terminalController.onWebServerRunningChanged = { running ->
        if (!running) fileController.disconnectWebPreview()
    }
    fun openGitExtensionDetails() = fileController.openGitExtensionDetails()
    fun createFile(path: String) = fileController.createFile(path)
    fun navigateToFile(fileName: String, line: Int, column: Int) =
        fileController.navigateToFile(fileName, line, column)
    fun createFolder(path: String) = fileController.createFolder(path)
    fun renamePath(source: String, name: String, folder: Boolean) =
        fileController.renamePath(source, name, folder)
    fun movePath(source: String, destination: String, folder: Boolean) =
        fileController.movePath(source, destination, folder)
    fun copyPath(source: String, destination: String, folder: Boolean) =
        fileController.copyPath(source, destination, folder)
    fun deletePath(path: String, folder: Boolean) = fileController.deletePath(path, folder)

    val projectSources = files.filterValues { !it.readOnly }.mapValues { it.value.content }
    val latestProjectSources = rememberUpdatedState(projectSources)
    val projectSaveLock = remember { Any() }
    val autosaveGeneration = remember { AtomicLong(0L) }
    fun isCurrentAnalysisRequest(fileName: String, content: String): Boolean =
        currentActiveFileName == fileName && projectSources[fileName] == content
    val dirtyFiles = remember(projectSources, savedProjectSources) {
        projectSources.keys.filterTo(linkedSetOf()) { savedProjectSources[it] != projectSources[it] }
    }
    val unsavedProjectChanges = dirtyFiles.isNotEmpty() || savedProjectSources.keys.any { it !in projectSources }

    fun currentPicoConfiguration(): PicoProjectConfiguration {
        // Make app-private configuration changes observable by Compose without
        // writing FoldCode metadata into an imported desktop repository.
        picoConfigurationRevision
        return picoConfigurationStore.load(projectKey) ?: picoProjectConfiguration(projectSources)
    }

    fun requestSemanticCompletion(fileName: String, content: String, cursor: Int) {
        if (!projectKey.startsWith("project:")) return
        semanticCompletions = emptyList()
        if (isAssemblySource(fileName)) {
            semanticCompletions = assemblyCompletionItems(projectSources)
            return
        }
        if (isFortranSource(fileName) || isCobolSource(fileName)) {
            if (gnuLanguagesExtensionInfo != null) {
                gnuLanguageIntelligence.requestCompletion(
                    fileName, content, cursor, projectSources,
                ) { completions ->
                    postToMainThread {
                        if (currentActiveFileName == fileName) semanticCompletions = completions
                    }
                }
            }
            return
        }
        if (fileName.endsWith(".py", ignoreCase = true)) {
            if (pythonExtensionInfo != null) {
                pythonIntelligence.requestCompletion(
                    gitEngine.projectDirectoryFile(projectKey), fileName, content, cursor, projectSources.keys,
                ) { completions ->
                    postToMainThread {
                        if (isCurrentAnalysisRequest(fileName, content)) semanticCompletions = completions
                    }
                }
            }
            return
        }
        if (fileName.endsWith(".rs", ignoreCase = true)) {
            if (rustExtensionInfo != null) {
                if (building) {
                    semanticCompletions = rustFallbackCompletionItems(
                        gitEngine.projectDirectoryFile(projectKey),
                        content,
                    )
                    return
                }
                rustIntelligence.requestCompletion(
                    gitEngine.projectDirectoryFile(projectKey), fileName, content, cursor,
                ) { completions ->
                    postToMainThread {
                        if (isCurrentAnalysisRequest(fileName, content)) semanticCompletions = completions
                    }
                }
            }
            return
        }
        if (isWebDocument(fileName)) {
            if (webExtensionInfo != null) {
                webIntelligence.requestCompletion(
                    gitEngine.projectDirectoryFile(projectKey), fileName, content, cursor,
                ) { completions ->
                    postToMainThread {
                        if (isCurrentAnalysisRequest(fileName, content)) semanticCompletions = completions
                    }
                }
            }
            return
        }
        // Clang accepts arbitrary text files and then reports C/C++ parser
        // errors. Keep unknown/editor-only formats out of that fallback.
        if (!isCppIntelligenceFile(fileName)) return
        if (cppExtensionInfo == null) return
        val localHeaders = projectHeaderCompletions(
            content,
            cursor,
            fileName,
            projectSources.keys,
        )
        val includedSymbols = includedCppSymbolCompletions(content, fileName, projectSources)
        val baseline = cFamilyCompletionItems(
            source = content,
            fileName = fileName,
            picoProject = isPicoProject(projectSources),
        )
        val emptyOperatorCompletion = isEmptyOperatorCompletion(content, cursor, fileName)
        // After `::`, `->`, or `.`, publishing the generic fallback first makes the
        // popup visibly switch lists when clangd responds. Wait for the semantic
        // result and publish one stable list instead.
        if (!emptyOperatorCompletion) {
            semanticCompletions = (localHeaders + includedSymbols + baseline)
                .distinctBy { it.label }
        }
        clangIntelligence.requestCompletion(
            gitEngine.projectDirectoryFile(projectKey), fileName, content, cursor,
        ) { result ->
            postToMainThread {
                // A completion requested for the prefix before `::` may finish during
                // the debounce for the new request. Never publish results for text that
                // is no longer in the editor.
                if (isCurrentAnalysisRequest(fileName, content)) {
                    semanticCompletions = if (emptyOperatorCompletion) {
                        // Namespace/member completion must come from clangd alone.
                        // Adding global keyword fallbacks here produces a second,
                        // semantically incorrect list after `std::` and can displace
                        // useful entries such as cout/cin.
                        result.completions.distinctBy { it.label }
                    } else {
                        (localHeaders + includedSymbols + baseline + result.completions)
                            .distinctBy { it.label }
                    }
                    if (result.diagnostics.isNotEmpty()) semanticDiagnostics = result.diagnostics
                }
            }
        }
    }

    fun requestSemanticDiagnostics(fileName: String, content: String) {
        if (!projectKey.startsWith("project:")) return
        if (isAssemblySource(fileName)) {
            if (cppExtensionInfo != null && isPicoProject(projectSources)) {
                assemblyIntelligence.requestDiagnostics(
                    gitEngine.projectDirectoryFile(projectKey), fileName, content,
                ) { diagnostics ->
                    postToMainThread {
                        if (isCurrentAnalysisRequest(fileName, content)) semanticDiagnostics = diagnostics
                    }
                }
            }
            return
        }
        if (isFortranSource(fileName) || isCobolSource(fileName)) {
            if (gnuLanguagesExtensionInfo != null) {
                gnuLanguageIntelligence.requestDiagnostics(
                    gitEngine.projectDirectoryFile(projectKey), fileName, content, projectSources,
                ) { diagnostics ->
                    postToMainThread {
                        if (isCurrentAnalysisRequest(fileName, content)) semanticDiagnostics = diagnostics
                    }
                }
            }
            return
        }
        if (fileName.endsWith(".py", ignoreCase = true)) {
            if (pythonExtensionInfo != null) {
                pythonIntelligence.requestDiagnostics(
                    gitEngine.projectDirectoryFile(projectKey), fileName, content,
                ) { diagnostics ->
                    postToMainThread {
                        if (isCurrentAnalysisRequest(fileName, content)) semanticDiagnostics = diagnostics
                    }
                }
            }
            return
        }
        if (fileName.endsWith(".rs", ignoreCase = true)) {
            if (rustExtensionInfo != null) {
                // The foreground Cargo build is already the authoritative
                // diagnostic pass. Do not restart analyzer flycheck beside it.
                if (building) return
                rustIntelligence.requestDiagnostics(
                    gitEngine.projectDirectoryFile(projectKey), fileName, content,
                ) { diagnostics ->
                    postToMainThread {
                        if (isCurrentAnalysisRequest(fileName, content)) semanticDiagnostics = diagnostics
                    }
                }
            }
            return
        }
        if (isWebDocument(fileName)) {
            if (webExtensionInfo != null) {
                webIntelligence.requestDiagnostics(
                    gitEngine.projectDirectoryFile(projectKey), fileName, content,
                ) { diagnostics ->
                    postToMainThread {
                        if (isCurrentAnalysisRequest(fileName, content)) semanticDiagnostics = diagnostics
                    }
                }
            } else {
                // Web documents never fall through to clangd, including when
                // their extension-delivered language server is unavailable.
                semanticDiagnostics = emptyList()
            }
            return
        }
        if (!isCppIntelligenceFile(fileName)) {
            semanticDiagnostics = emptyList()
            return
        }
        if (cppExtensionInfo == null) return
        clangIntelligence.requestDiagnostics(
            gitEngine.projectDirectoryFile(projectKey), fileName, content,
        ) { diagnostics ->
            postToMainThread {
                if (isCurrentAnalysisRequest(fileName, content)) semanticDiagnostics = diagnostics
            }
        }
    }

    LaunchedEffect(projectKey, extensionRuntimeKey) {
        clangIntelligence.pause()
        clangIntelligence.resume()
        pythonIntelligence.reset()
        rustIntelligence.reset()
        webIntelligence.close()
        gnuLanguageIntelligence.reset()
        assemblyIntelligence.reset()
    }

    LaunchedEffect(projectKey, activeFileName) {
        semanticCompletions = emptyList()
        semanticDiagnostics = emptyList()
    }

    LaunchedEffect(projectKey, projectTree) {
        picoCMakeTargets = withContext(Dispatchers.IO) {
            runCatching { gitEngine.cmakeExecutableTargets(projectKey) }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(projectLoaded, projectKey, projectSources, folders, appearance.autosaveDelayMs) {
        val generation = autosaveGeneration.incrementAndGet()
        if (!projectLoaded) return@LaunchedEffect
        if (appearance.autosaveDelayMs <= 0L) return@LaunchedEffect
        val snapshot = projectSources
        delay(appearance.autosaveDelayMs)
        var obsolete = false
        val failure = withContext(Dispatchers.IO) {
            synchronized(projectSaveLock) {
                if (generation != autosaveGeneration.get()) {
                    obsolete = true
                    null
                } else {
                    runCatching {
                        gitEngine.saveProject(projectKey, snapshot, folders)
                        webProjectRuntime.syncSavedFiles(
                            gitEngine.projectDirectoryFile(projectKey),
                            snapshot.keys,
                        )
                    }.exceptionOrNull()
                }
            }
        }
        if (obsolete) return@LaunchedEffect
        if (failure != null) {
            terminal = "Autosave failed: ${failure.message ?: "Unknown error"}"
        } else {
            val current = latestProjectSources.value
            savedProjectSources = savedProjectSources.toMutableMap().apply {
                snapshot.forEach { (name, text) -> if (current[name] == text) put(name, text) }
                keys.retainAll(current.keys)
            }
            if (projectKey.startsWith("project:")) {
                projectTree = withContext(Dispatchers.IO) { gitEngine.projectTree(projectKey) }
            }
        }
    }

    val buildController = WorkspaceBuildController(
        context = context,
        focusManager = focusManager,
        engine = engine,
        rustPicoCompiler = rustPicoCompiler,
        rustIntelligence = rustIntelligence,
        gnuLanguageIntelligence = gnuLanguageIntelligence,
        gnuLanguagesCompiler = gnuLanguagesCompiler,
        picoUsbFlasher = picoUsbFlasher,
        microPythonUsbRunner = microPythonUsbRunner,
        picotoolRunner = picotoolRunner,
        picoRuntimeDelivery = picoRuntimeDelivery,
        gitEngine = gitEngine,
        debugController = debugController,
        picoConfigurationStore = picoConfigurationStore,
        cmakeDependencyResolver = cmakeDependencyResolver,
        terminalController = terminalController,
        webProjectRuntime = webProjectRuntime,
        extensions = extensions,
        bindings = WorkspaceBuildBindings(
            terminal = mutableWorkspaceValue({ terminal }, { terminal = boundedWorkspaceOutput(it) }),
            terminalBeforeEditing = mutableWorkspaceValue({ terminalBeforeEditing }, { terminalBeforeEditing = it }),
            terminalVisible = mutableWorkspaceValue({ terminalVisible }, { terminalVisible = it }),
            destination = mutableWorkspaceValue({ destination }, { destination = it }),
            sidePanelVisible = mutableWorkspaceValue({ sidePanelVisible }, { sidePanelVisible = it }),
            drawerOpen = mutableWorkspaceValue({ drawerOpen }, { drawerOpen = it }),
            building = mutableWorkspaceValue({ building }, { building = it }),
            buildOutput = mutableWorkspaceValue({ buildOutput }, { buildOutput = boundedWorkspaceOutput(it) }),
            diagnosticsOutput = mutableWorkspaceValue({ diagnosticsOutput }, { diagnosticsOutput = it }),
            projectTree = mutableWorkspaceValue({ projectTree }, { projectTree = it }),
            managedTerminalSessionId = mutableWorkspaceValue({ managedTerminalSessionId }, { managedTerminalSessionId = it }),
            consoleActive = mutableWorkspaceValue({ consoleActive }, { consoleActive = it }),
            terminalCommandRunning = mutableWorkspaceValue({ terminalCommandRunning }, { terminalCommandRunning = it }),
            picotoolBusy = mutableWorkspaceValue({ picotoolBusy }, { picotoolBusy = it }),
            files = mutableWorkspaceValue({ files }, { files = it }),
            savedProjectSources = mutableWorkspaceValue(
                { savedProjectSources },
                { savedProjectSources = it },
            ),
            picoCMakeTargets = mutableWorkspaceValue({ picoCMakeTargets }, { picoCMakeTargets = it }),
            picoConfigurationRevision = mutableWorkspaceValue(
                { picoConfigurationRevision },
                { picoConfigurationRevision = it },
            ),
            cleaningPicoBuild = mutableWorkspaceValue({ cleaningPicoBuild }, { cleaningPicoBuild = it }),
            projectSources = { projectSources },
            projectKey = { projectKey },
            projectName = { projectName },
            folders = { folders },
            activeFileName = { activeFileName },
            appearance = { appearance },
            debugSnapshot = { debugSnapshot },
            currentPicoConfiguration = ::currentPicoConfiguration,
            openPicoExtensionDetails = ::openPicoExtensionDetails,
            openGnuLanguagesExtensionDetails = ::openGnuLanguagesExtensionDetails,
            openWebExtensionDetails = ::openWebExtensionDetails,
            openWebPreview = { openWebPreview() },
        ),
    )

    fun runCurrentProject(debug: Boolean = false, forcePicoBuild: Boolean = false) {
        buildController.runCurrentProject(debug, forcePicoBuild)
    }
    fun startDebugging() = buildController.startDebugging()
    fun stopCurrentOperation() = buildController.stopCurrentOperation()
    fun sendConsoleInput(value: String) = buildController.sendConsoleInput(value)
    fun configurePicoProject(configuration: PicoProjectConfiguration) =
        buildController.configurePicoProject(configuration)
    fun runPicoUsb() = buildController.runPicoUsb()
    fun configurePicoCMake() = buildController.configurePicoCMake()
    fun cleanPicoBuild() = buildController.cleanPicoBuild()

    fun openProjectsFolder() {
        // Open immediately so pointer feedback is never blocked by storage work.
        showProjectsDialog = true
        projectsLoading = true
        Thread {
            val available = gitEngine.listProjects()
            postToMainThread {
                projectChoices = available
                projectsLoading = false
            }
        }.start()
    }

    fun runGitOperation(operation: () -> GitRepositoryState) {
        if (gitBusy) return
        val operationProjectKey = projectKey
        gitBusy = true
        gitNotice = null
        Thread {
            val result = runCatching(operation)
            postToMainThread {
                result.onSuccess { state ->
                    gitState = state
                    gitNotice = state.message
                    if (state.initialized) {
                        projectPreferences.edit {
                            putString("last_repository", gitEngine.canonicalProjectKey(operationProjectKey))
                        }
                    }
                }.onFailure { error ->
                    gitNotice = error.message ?: "Git operation failed"
                }
                gitBusy = false
            }
        }.start()
    }

    fun runGitProjectOperation(
        replaceProject: Boolean,
        openDestination: ActivityDestination = ActivityDestination.SourceControl,
        operation: () -> GitProjectResult,
    ) {
        if (gitBusy) return
        gitBusy = true
        gitNotice = null
        Thread {
            val result = runCatching(operation)
            postToMainThread {
                result.onSuccess { project ->
                    val loadedFiles = upgradeGeneratedPicoExample(context, project.files)
                        .mapValues { (name, content) -> ProjectFile(name, content) }
                    files = loadedFiles
                    savedProjectSources = loadedFiles.filterValues { !it.readOnly }.mapValues { it.value.content }
                    folders = project.folders
                    projectTree = gitEngine.projectTree(project.projectKey)
                    if (replaceProject) {
                        projectName = project.projectName
                    projectKey = project.projectKey
                    }
                    val preferred = activeFileName.takeIf { it in loadedFiles }
                        ?: loadedFiles.keys.firstOrNull { it.substringAfterLast('/') == "main.rs" }
                        ?: loadedFiles.keys.firstOrNull { it.substringAfterLast('/') == "main.cpp" }
                        ?: loadedFiles.keys.firstOrNull { it.substringAfterLast('/') == "main.c" }
                        ?: loadedFiles.keys.firstOrNull { it.substringAfterLast('/') == "main.py" }
                        ?: loadedFiles.keys.first()
                    openTabs = if (replaceProject) listOf(preferred) else openTabs.filter { it in loadedFiles }.ifEmpty { listOf(preferred) }
                    activeFileName = preferred
                    gitState = project.state
                    gitNotice = project.state.message
                    drawerOpen = false
                    sidePanelVisible = true
                    destination = openDestination
                    projectPreferences.edit { putString("last_repository", project.projectKey) }
                }.onFailure { error ->
                    gitNotice = error.message ?: "Git operation failed"
                }
                gitBusy = false
            }
        }.start()
    }

    fun createGeneralProject(name: String, template: GeneralProjectTemplate) {
        val sources = when (template) {
            GeneralProjectTemplate.Cpp -> initialProject.mapValues { it.value.content }
            GeneralProjectTemplate.C -> cProject
            GeneralProjectTemplate.Python -> pythonProject
            GeneralProjectTemplate.Rust -> rustProject
            GeneralProjectTemplate.Fortran -> fortranProject
            GeneralProjectTemplate.Cobol -> cobolProject
            GeneralProjectTemplate.WebVanilla -> webProject
            GeneralProjectTemplate.WebViteTypeScript -> viteTypeScriptProject
            GeneralProjectTemplate.WebReactTypeScript -> reactTypeScriptProject
        }
        runGitProjectOperation(replaceProject = true, openDestination = ActivityDestination.Files) {
            gitEngine.createProject(name, sources)
        }
    }

    LaunchedEffect(Unit) {
        gitBusy = true
        Thread {
            // The saved project is overwhelmingly the common case. Opening it
            // directly avoids scanning and querying every repository before the
            // editor can restore one workspace.
            val openedFromSaved = savedProjectKey?.let { key ->
                runCatching { gitEngine.openProject(key) }.getOrNull()
            }
            val opened = openedFromSaved ?: gitEngine.listProjects().firstOrNull()?.let { project ->
                runCatching { gitEngine.openProject(project.projectKey) }.getOrNull()
            }
            val restoredTree = opened?.let { project ->
                runCatching { gitEngine.projectTree(project.projectKey) }.getOrDefault(emptyList())
            }.orEmpty()
            postToMainThread {
                if (opened != null) {
                    val loadedFiles = upgradeGeneratedPicoExample(context, opened.files)
                        .mapValues { (name, content) -> ProjectFile(name, content) }
                    files = loadedFiles
                    savedProjectSources = loadedFiles.filterValues { !it.readOnly }.mapValues { it.value.content }
                    folders = opened.folders
                    projectTree = restoredTree
                    projectName = opened.projectName
                    projectKey = opened.projectKey
                    val first = loadedFiles.keys.firstOrNull { it.substringAfterLast('/') == "main.rs" }
                        ?: loadedFiles.keys.firstOrNull { it.substringAfterLast('/') == "main.cpp" }
                        ?: loadedFiles.keys.firstOrNull { it.substringAfterLast('/') == "main.c" }
                        ?: loadedFiles.keys.firstOrNull { it.substringAfterLast('/') == "main.py" }
                        ?: loadedFiles.keys.first()
                    val restoredTabs = if (appearance.restoreSession) {
                        projectPreferences.getString("session_tabs:${opened.projectKey}", null)
                            ?.split('\u001F')
                            ?.filter { it in loadedFiles }
                            .orEmpty()
                    } else emptyList()
                    openTabs = restoredTabs.ifEmpty { listOf(first) }
                    activeFileName = if (appearance.restoreSession) {
                        projectPreferences.getString("session_active:${opened.projectKey}", null)
                            ?.takeIf { it in openTabs }
                            ?: openTabs.first()
                    } else first
                    gitState = opened.state
                    gitNotice = "Restored ${opened.projectName}"
                    projectPreferences.edit { putString("last_repository", opened.projectKey) }
                } else {
                    projectName = "NO PROJECT"
                    projectKey = ""
                    projectPreferences.edit { remove("last_repository") }
                }
                projectLoaded = true
                gitBusy = false
            }
        }.start()
    }

    LaunchedEffect(projectLoaded) {
        if (!projectLoaded) return@LaunchedEffect
        delay(2_000)
        withContext(Dispatchers.IO) { extensionManager.performDeferredMaintenance() }
        extensions.cppExtensionInfo = extensionManager.cppInfo()
        extensions.picoExtensionInfo = extensionManager.picoInfo()
        extensions.pythonExtensionInfo = extensionManager.pythonInfo()
        extensions.rustExtensionInfo = extensionManager.rustInfo()
        extensions.gnuLanguagesExtensionInfo = extensionManager.gnuLanguagesInfo()
        extensions.webExtensionInfo = extensionManager.webInfo()
        extensions.gitToolsExtensionInfo = extensionManager.gitInfo()
        extensions.gnuArmExtensionInfo = extensionManager.gnuArmInfo()
    }

    LaunchedEffect(projectLoaded, projectKey, openTabs, activeFileName, terminalVisible) {
        if (!projectLoaded || projectKey.isBlank()) return@LaunchedEffect
        projectPreferences.edit {
            putString("session_tabs:$projectKey", openTabs.joinToString("\u001F"))
            putString("session_active:$projectKey", activeFileName)
            putBoolean("session_terminal_visible", terminalVisible)
        }
    }

    LaunchedEffect(destination, projectKey) {
        if (destination == ActivityDestination.SourceControl) {
            runGitOperation { gitEngine.status(projectKey, projectSources) }
        }
    }

    if (showProjectsDialog) {
        ProjectsFolderDialog(
            projects = projectChoices,
            loading = projectsLoading,
            onDismiss = { showProjectsDialog = false },
            onOpen = { key ->
                showProjectsDialog = false
                runGitProjectOperation(replaceProject = true, openDestination = ActivityDestination.Files) { gitEngine.openProject(key) }
            },
            onDelete = { key ->
                Thread {
                    val result = runCatching { gitEngine.deleteProject(key) }
                    val remaining = gitEngine.listProjects()
                    postToMainThread {
                        result.onFailure { terminal = "Could not delete project: ${it.message}" }
                        projectChoices = remaining
                        val deletedOpenProject = key == projectKey ||
                            key.removePrefix("project:") == projectKey.removePrefix("project:")
                        if (result.isSuccess && deletedOpenProject) {
                            // Do not leave deleted files alive in editor state and do
                            // not silently switch the user to a different project.
                            projectLoaded = false
                            files = emptyMap()
                            savedProjectSources = emptyMap()
                            folders = emptySet()
                            projectTree = emptyList()
                            openTabs = emptyList()
                            activeFileName = ""
                            projectName = "NO PROJECT"
                            projectKey = ""
                            gitState = GitRepositoryState()
                            gitNotice = null
                            diagnosticsOutput = ""
                            buildOutput = ""
                            terminal = "Project deleted"
                            projectPreferences.edit { remove("last_repository") }
                        }
                    }
                }.start()
            },
        )
    }

    if (showSettings) {
        IdeSettingsDialog(
            preferences = appearance,
            dexWorkspace = dexWorkspaceActive,
            onChange = {
                appearance = it
                appearanceStore.save(it)
                applyIdeTheme(it.theme)
            },
            onDismiss = { showSettings = false },
        )
    }

    fun saveCurrent() {
        val name = activeFileName.takeIf { it in projectSources } ?: return
        val content = projectSources.getValue(name)
        autosaveGeneration.incrementAndGet()
        terminal = "Saving $name…"
        Thread {
            val failure = runCatching {
                synchronized(projectSaveLock) {
                    gitEngine.saveProjectFile(projectKey, name, content)
                    webProjectRuntime.syncSavedFiles(
                        gitEngine.projectDirectoryFile(projectKey),
                        listOf(name),
                    )
                }
            }.exceptionOrNull()
            postToMainThread {
                terminal = failure?.let { "Save failed: ${it.message ?: "Unknown error"}" }
                    ?: "Saved $name"
                if (failure == null && latestProjectSources.value[name] == content) {
                    savedProjectSources = savedProjectSources + (name to content)
                }
                if (failure == null && projectKey.startsWith("project:")) {
                    projectTree = gitEngine.projectTree(projectKey)
                }
            }
        }.start()
    }

    fun saveAll() {
        if (!projectLoaded || projectSources.isEmpty()) return
        val snapshot = projectSources
        autosaveGeneration.incrementAndGet()
        terminal = "Saving $projectName…"
        Thread {
            val failure = runCatching {
                synchronized(projectSaveLock) {
                    engine.save(snapshot)
                    gitEngine.saveProject(projectKey, snapshot, folders)
                    webProjectRuntime.syncSavedFiles(
                        gitEngine.projectDirectoryFile(projectKey),
                        snapshot.keys,
                    )
                }
            }.exceptionOrNull()
            postToMainThread {
                terminal = failure?.let { "Save all failed: ${it.message ?: "Unknown error"}" }
                    ?: "Saved all files"
                if (failure == null) {
                    val current = latestProjectSources.value
                    savedProjectSources = savedProjectSources.toMutableMap().apply {
                        snapshot.forEach { (name, text) -> if (current[name] == text) put(name, text) }
                        keys.retainAll(current.keys)
                    }
                }
                if (failure == null && projectKey.startsWith("project:")) {
                    projectTree = gitEngine.projectTree(projectKey)
                }
            }
        }.start()
    }

    val manualSaveActions = ManualSaveActions(
        enabled = appearance.autosaveDelayMs <= 0L,
        activeFileName = activeFileName,
        dirtyFiles = dirtyFiles,
        hasUnsavedChanges = unsavedProjectChanges,
        onSave = ::saveCurrent,
        onSaveAll = ::saveAll,
    )

    val toolbarOperationRunning = workspaceToolbarOperationRunning(
        building = building,
        terminalSessions = terminalController.sessionInfos,
    )

    LaunchedEffect(building, showBuildStatistics) {
        if (!building || !showBuildStatistics) {
            buildResourceStats = BuildResourceStats()
            return@LaunchedEffect
        }
        while (true) {
            buildResourceStats = withContext(Dispatchers.Default) {
                readBuildResourceStats(context.applicationContext)
            }
            delay(1_000)
        }
    }
    val buildStatisticsLabel = if (building && showBuildStatistics) {
        buildResourceStats.compactLabel()
    } else {
        ""
    }
    val toggleBuildStatistics = {
        val next = !showBuildStatistics
        showBuildStatistics = next
        projectPreferences.edit { putBoolean("show_build_statistics", next) }
    }

    val runDebugActions = RunDebugActions(
        compilePico = { runCurrentProject(forcePicoBuild = true) },
        run = { runCurrentProject() },
        debug = ::startDebugging,
        stop = ::stopCurrentOperation,
        snapshot = debugSnapshot,
        selectFrame = debugController::selectFrame,
        toggleVariable = debugController::toggleVariable,
        evaluate = debugController::evaluate,
    )
    val debugToolbarActions = DebugToolbarActions(
        status = debugSnapshot.status,
        saveActions = manualSaveActions,
        onContinue = debugController::continueExecution,
        onPause = debugController::pause,
        onStepOver = debugController::stepOver,
        onStepInto = debugController::stepInto,
        onStepOut = debugController::stepOut,
        onRestart = debugController::restart,
        onStop = debugController::stop,
    )

    WorkspaceLayoutScope(
        context = context,
        focusManager = focusManager,
        density = density,
        appearanceStore = appearanceStore,
        terminalController = terminalController,
        gitEngine = gitEngine,
        gitCredentialStore = gitCredentialStore,
        extensionMarketplace = extensionMarketplace,
        extensions = extensions,
        bindings = WorkspaceLayoutBindings(
            appearance = mutableWorkspaceValue({ appearance }, { appearance = it }),
            showSettings = mutableWorkspaceValue({ showSettings }, { showSettings = it }),
            files = mutableWorkspaceValue({ files }, { files = it }),
            folders = { folders },
            projectTree = { projectTree },
            picoCMakeTargets = { picoCMakeTargets },
            projectName = { projectName },
            projectKey = { projectKey },
            projectSources = { projectSources },
            openTabs = mutableWorkspaceValue({ openTabs }, { openTabs = it }),
            activeFileName = mutableWorkspaceValue({ activeFileName }, { activeFileName = it }),
            splitEditorVisible = mutableWorkspaceValue({ splitEditorVisible }, { splitEditorVisible = it }),
            secondaryActiveFileName = mutableWorkspaceValue(
                { secondaryActiveFileName },
                { secondaryActiveFileName = it },
            ),
            secondaryOpenTabs = mutableWorkspaceValue({ secondaryOpenTabs }, { secondaryOpenTabs = it }),
            splitEditorRatio = mutableWorkspaceValue({ splitEditorRatio }, { splitEditorRatio = it }),
            dexEditorZoom = mutableWorkspaceValue({ dexEditorZoom }, { dexEditorZoom = it }),
            dexSecondaryEditorZoom = mutableWorkspaceValue(
                { dexSecondaryEditorZoom },
                { dexSecondaryEditorZoom = it },
            ),
            destination = mutableWorkspaceValue({ destination }, { destination = it }),
            drawerOpen = mutableWorkspaceValue({ drawerOpen }, { drawerOpen = it }),
            sidePanelVisible = mutableWorkspaceValue({ sidePanelVisible }, { sidePanelVisible = it }),
            terminalVisible = mutableWorkspaceValue({ terminalVisible }, { terminalVisible = it }),
            terminalHeightValue = mutableWorkspaceValue({ terminalHeightValue }, { terminalHeightValue = it }),
            terminalWidthValue = mutableWorkspaceValue({ terminalWidthValue }, { terminalWidthValue = it }),
            terminal = { terminal },
            buildOutput = { buildOutput },
            diagnosticsOutput = { diagnosticsOutput },
            semanticCompletions = mutableWorkspaceValue(
                { semanticCompletions },
                { semanticCompletions = it },
            ),
            semanticDiagnostics = { semanticDiagnostics },
            building = { building },
            showBuildStatistics = { showBuildStatistics },
            dexBrowserFileName = mutableWorkspaceValue(
                { dexBrowserFileName },
                { dexBrowserFileName = it },
            ),
            dexBrowserVisible = mutableWorkspaceValue({ dexBrowserVisible }, { dexBrowserVisible = it }),
            dexBrowserWidthValue = mutableWorkspaceValue(
                { dexBrowserWidthValue },
                { dexBrowserWidthValue = it },
            ),
            consoleActive = { consoleActive },
            terminalCommandRunning = { terminalCommandRunning },
            cleaningPicoBuild = { cleaningPicoBuild },
            picotoolBusy = { picotoolBusy },
            gitState = { gitState },
            gitBusy = { gitBusy },
            gitNotice = { gitNotice },
            gitHubUsername = mutableWorkspaceValue({ gitHubUsername }, { gitHubUsername = it }),
            gitHubToken = mutableWorkspaceValue({ gitHubToken }, { gitHubToken = it }),
            dexWorkspaceActive = mutableWorkspaceValue(
                { dexWorkspaceActive },
                { dexWorkspaceActive = it },
            ),
            desktopExplorerCommand = mutableWorkspaceValue(
                { desktopExplorerCommand },
                { desktopExplorerCommand = it },
            ),
            editorDropBounds = mutableWorkspaceValue({ editorDropBounds }, { editorDropBounds = it }),
            workspaceSurfaceBounds = mutableWorkspaceValue(
                { workspaceSurfaceBounds },
                { workspaceSurfaceBounds = it },
            ),
            explorerDragPath = mutableWorkspaceValue({ explorerDragPath }, { explorerDragPath = it }),
            explorerDragPosition = mutableWorkspaceValue(
                { explorerDragPosition },
                { explorerDragPosition = it },
            ),
            editorDragSourceGroup = mutableWorkspaceValue(
                { editorDragSourceGroup },
                { editorDragSourceGroup = it },
            ),
            editorNavigation = { editorNavigation },
            terminalBeforeEditing = mutableWorkspaceValue(
                { terminalBeforeEditing },
                { terminalBeforeEditing = it },
            ),
            imeVisible = { imeVisible },
            debugSnapshot = { debugSnapshot },
            toolbarOperationRunning = { toolbarOperationRunning },
            buildStatisticsLabel = { buildStatisticsLabel },
            runDebugActions = { runDebugActions },
            debugToolbarActions = { debugToolbarActions },
        ),
        actions = WorkspaceLayoutActions(
            toggleBuildStatistics = toggleBuildStatistics,
            runCurrentProject = { runCurrentProject() },
            startDebugging = ::startDebugging,
            stopCurrentOperation = ::stopCurrentOperation,
            saveCurrent = ::saveCurrent,
            closeFile = ::closeFile,
            openProjectsFolder = ::openProjectsFolder,
            currentPicoConfiguration = ::currentPicoConfiguration,
            openFilePicker = {
                fileLauncher.launch(arrayOf("text/*", "application/json", "application/xml"))
            },
            openFile = ::openFile,
            navigateToFile = ::navigateToFile,
            createGeneralProject = ::createGeneralProject,
            createFile = ::createFile,
            createFolder = ::createFolder,
            renamePath = ::renamePath,
            movePath = ::movePath,
            copyPath = ::copyPath,
            deletePath = ::deletePath,
            openPicoExtensionDetails = ::openPicoExtensionDetails,
            openCppExtensionDetails = ::openCppExtensionDetails,
            openPythonExtensionDetails = ::openPythonExtensionDetails,
            openRustExtensionDetails = ::openRustExtensionDetails,
            openGnuLanguagesExtensionDetails = ::openGnuLanguagesExtensionDetails,
            openWebExtensionDetails = ::openWebExtensionDetails,
            openGitExtensionDetails = ::openGitExtensionDetails,
            openPicoHardwareApis = ::openPicoHardwareApis,
            openPicoHighLevelApis = ::openPicoHighLevelApis,
            openPicoNetworkingLibraries = ::openPicoNetworkingLibraries,
            openPicoRuntimeInfrastructure = ::openPicoRuntimeInfrastructure,
            openPicoSdkReference = ::openPicoSdkReference,
            createPicoProject = { name, board, language ->
                runGitProjectOperation(replaceProject = true, openDestination = ActivityDestination.Pico) {
                    gitEngine.createProject(name, picoEmptyProject(context, board, language))
                }
            },
            createPicoExample = { name, board, example, language ->
                runGitProjectOperation(replaceProject = true, openDestination = ActivityDestination.Pico) {
                    gitEngine.createProject(name, picoExampleProject(context, board, example, language))
                }
            },
            runPicoUsb = ::runPicoUsb,
            configurePicoProject = ::configurePicoProject,
            configurePicoCMake = ::configurePicoCMake,
            cleanPicoBuild = ::cleanPicoBuild,
            runGitOperation = ::runGitOperation,
            runGitProjectOperation = { replaceProject, destination, operation ->
                runGitProjectOperation(replaceProject, destination, operation)
            },
            sendConsoleInput = ::sendConsoleInput,
            requestSemanticCompletion = ::requestSemanticCompletion,
            requestSemanticDiagnostics = ::requestSemanticDiagnostics,
        ),
    ).RenderResponsiveWorkspace()

}
