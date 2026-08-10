package dev.foldcode.ide

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import androidx.compose.ui.focus.FocusManager

internal class WorkspaceLayoutScope(
    private val context: Context,
    private val focusManager: FocusManager,
    private val density: Density,
    private val appearanceStore: IdePreferencesStore,
    private val terminalController: WorkspaceTerminalController,
    private val gitEngine: GitEngine,
    private val gitCredentialStore: GitCredentialStore,
    private val extensionMarketplace: FoldCodeExtensionMarketplace,
    private val extensions: WorkspaceExtensionController,
    private val bindings: WorkspaceLayoutBindings,
    private val actions: WorkspaceLayoutActions,
) {
    private var appearance by bindings.appearance
    private var showSettings by bindings.showSettings
    private var files by bindings.files
    private var openTabs by bindings.openTabs
    private var activeFileName by bindings.activeFileName
    private var splitEditorVisible by bindings.splitEditorVisible
    private var secondaryActiveFileName by bindings.secondaryActiveFileName
    private var secondaryOpenTabs by bindings.secondaryOpenTabs
    private var splitEditorRatio by bindings.splitEditorRatio
    private var dexEditorZoom by bindings.dexEditorZoom
    private var dexSecondaryEditorZoom by bindings.dexSecondaryEditorZoom
    private var destination by bindings.destination
    private var drawerOpen by bindings.drawerOpen
    private var sidePanelVisible by bindings.sidePanelVisible
    private var terminalVisible by bindings.terminalVisible
    private var terminalHeightValue by bindings.terminalHeightValue
    private var terminalWidthValue by bindings.terminalWidthValue
    private var semanticCompletions by bindings.semanticCompletions
    private var dexBrowserFileName by bindings.dexBrowserFileName
    private var dexBrowserVisible by bindings.dexBrowserVisible
    private var dexBrowserWidthValue by bindings.dexBrowserWidthValue
    private var gitHubUsername by bindings.gitHubUsername
    private var gitHubToken by bindings.gitHubToken
    private var dexWorkspaceActive by bindings.dexWorkspaceActive
    private var desktopExplorerCommand by bindings.desktopExplorerCommand
    private var editorDropBounds by bindings.editorDropBounds
    private var workspaceSurfaceBounds by bindings.workspaceSurfaceBounds
    private var explorerDragPath by bindings.explorerDragPath
    private var explorerDragPosition by bindings.explorerDragPosition
    private var editorDragSourceGroup by bindings.editorDragSourceGroup
    private var terminalBeforeEditing by bindings.terminalBeforeEditing

    private val folders get() = bindings.folders()
    private val projectTree get() = bindings.projectTree()
    private val picoCMakeTargets get() = bindings.picoCMakeTargets()
    private val projectName get() = bindings.projectName()
    private val projectKey get() = bindings.projectKey()
    private val projectSources get() = bindings.projectSources()
    private val terminal get() = bindings.terminal()
    private val buildOutput get() = bindings.buildOutput()
    private val diagnosticsOutput get() = bindings.diagnosticsOutput()
    private val semanticDiagnostics get() = bindings.semanticDiagnostics()
    private val building get() = bindings.building()
    private val showBuildStatistics get() = bindings.showBuildStatistics()
    private val consoleActive get() = bindings.consoleActive()
    private val terminalCommandRunning get() = bindings.terminalCommandRunning()
    private val cleaningPicoBuild get() = bindings.cleaningPicoBuild()
    private val picotoolBusy get() = bindings.picotoolBusy()
    private val gitState get() = bindings.gitState()
    private val gitBusy get() = bindings.gitBusy()
    private val gitNotice get() = bindings.gitNotice()
    private val editorNavigation get() = bindings.editorNavigation()
    private val imeVisible get() = bindings.imeVisible()
    private val debugSnapshot get() = bindings.debugSnapshot()
    private val toolbarOperationRunning get() = bindings.toolbarOperationRunning()
    private val buildStatisticsLabel get() = bindings.buildStatisticsLabel()
    private val runDebugActions get() = bindings.runDebugActions()
    private val debugToolbarActions get() = bindings.debugToolbarActions()
    private val cppExtensionInfo get() = extensions.cppExtensionInfo
    private val picoExtensionInfo get() = extensions.picoExtensionInfo
    private val pythonExtensionInfo get() = extensions.pythonExtensionInfo
    private val rustExtensionInfo get() = extensions.rustExtensionInfo
    private val gnuLanguagesExtensionInfo get() = extensions.gnuLanguagesExtensionInfo
    private val webExtensionInfo get() = extensions.webExtensionInfo
    private val gitToolsExtensionInfo get() = extensions.gitToolsExtensionInfo
    private val gnuArmExtensionInfo get() = extensions.gnuArmExtensionInfo
    private val extensionBusy get() = extensions.extensionBusy
    private val extensionNotice get() = extensions.extensionNotice

    private fun toggleBuildStatistics() = actions.toggleBuildStatistics()
    private fun runCurrentProject() = actions.runCurrentProject()
    private fun startDebugging() = actions.startDebugging()
    private fun stopCurrentOperation() = actions.stopCurrentOperation()
    private fun saveCurrent() = actions.saveCurrent()
    private fun closeFile(name: String) = actions.closeFile(name)
    private fun openProjectsFolder() = actions.openProjectsFolder()
    private fun currentPicoConfiguration() = actions.currentPicoConfiguration()
    private fun openFile(name: String) = actions.openFile(name)
    private fun navigateToFile(name: String, line: Int, column: Int) =
        actions.navigateToFile(name, line, column)
    private fun createGeneralProject(name: String, template: GeneralProjectTemplate) =
        actions.createGeneralProject(name, template)
    private fun createFile(path: String) = actions.createFile(path)
    private fun createFolder(path: String) = actions.createFolder(path)
    private fun renamePath(source: String, name: String, folder: Boolean) =
        actions.renamePath(source, name, folder)
    private fun movePath(source: String, destination: String, folder: Boolean) =
        actions.movePath(source, destination, folder)
    private fun copyPath(source: String, destination: String, folder: Boolean) =
        actions.copyPath(source, destination, folder)
    private fun deletePath(path: String, folder: Boolean) = actions.deletePath(path, folder)
    private fun openPicoExtensionDetails() = actions.openPicoExtensionDetails()
    private fun openCppExtensionDetails() = actions.openCppExtensionDetails()
    private fun openPythonExtensionDetails() = actions.openPythonExtensionDetails()
    private fun openRustExtensionDetails() = actions.openRustExtensionDetails()
    private fun openGnuLanguagesExtensionDetails() = actions.openGnuLanguagesExtensionDetails()
    private fun openWebExtensionDetails() = actions.openWebExtensionDetails()
    private fun openGitExtensionDetails() = actions.openGitExtensionDetails()
    private fun openPicoHardwareApis() = actions.openPicoHardwareApis()
    private fun openPicoHighLevelApis() = actions.openPicoHighLevelApis()
    private fun openPicoNetworkingLibraries() = actions.openPicoNetworkingLibraries()
    private fun openPicoRuntimeInfrastructure() = actions.openPicoRuntimeInfrastructure()
    private fun openPicoSdkReference() = actions.openPicoSdkReference()
    private fun runPicoUsb() = actions.runPicoUsb()
    private fun configurePicoProject(configuration: PicoProjectConfiguration) =
        actions.configurePicoProject(configuration)
    private fun configurePicoCMake() = actions.configurePicoCMake()
    private fun cleanPicoBuild() = actions.cleanPicoBuild()
    private fun runGitOperation(operation: () -> GitRepositoryState) = actions.runGitOperation(operation)
    private fun runGitProjectOperation(
        replaceProject: Boolean,
        openDestination: ActivityDestination = ActivityDestination.SourceControl,
        operation: () -> GitProjectResult,
    ) = actions.runGitProjectOperation(replaceProject, openDestination, operation)
    private fun sendConsoleInput(value: String) = actions.sendConsoleInput(value)
    private fun requestSemanticCompletion(fileName: String, content: String, cursor: Int) =
        actions.requestSemanticCompletion(fileName, content, cursor)
    private fun requestSemanticDiagnostics(fileName: String, content: String) =
        actions.requestSemanticDiagnostics(fileName, content)

    @Composable
    internal fun RenderResponsiveWorkspace() {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.matchesShortcut(appearance.runShortcut) -> {
                        runCurrentProject()
                        true
                    }
                    event.matchesShortcut(appearance.sidebarShortcut) -> {
                        sidePanelVisible = !sidePanelVisible
                        drawerOpen = false
                        true
                    }
                    event.matchesShortcut(appearance.terminalShortcut) -> {
                        terminalVisible = !terminalVisible
                        true
                    }
                    event.matchesShortcut(appearance.saveShortcut) -> {
                        saveCurrent()
                        true
                    }
                    event.isCtrlPressed && event.key == Key.W -> {
                        if (activeFileName.isNotBlank()) closeFile(activeFileName)
                        true
                    }
                    event.isCtrlPressed && event.key == Key.Tab -> {
                        if (openTabs.isNotEmpty()) {
                            val current = openTabs.indexOf(activeFileName).coerceAtLeast(0)
                            val direction = if (event.isShiftPressed) -1 else 1
                            activeFileName = openTabs[(current + direction + openTabs.size) % openTabs.size]
                        }
                        true
                    }
                    event.isCtrlPressed && event.isShiftPressed && event.key == Key.F -> {
                        destination = ActivityDestination.Search
                        sidePanelVisible = true
                        drawerOpen = false
                        true
                    }
                    event.matchesShortcut(appearance.filesShortcut) -> {
                        destination = ActivityDestination.Files
                        sidePanelVisible = true
                        drawerOpen = false
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        val mode = when {
            maxWidth < 480.dp -> LayoutMode.Compact
            maxWidth < 720.dp -> LayoutMode.Medium
            else -> LayoutMode.Expanded
        }
        // A software keyboard must not restructure the workspace, but real DeX
        // freeform-window height changes must take effect immediately.
        var imeHiddenHeight by remember(maxWidth) { mutableStateOf(maxHeight) }
        LaunchedEffect(maxHeight, imeVisible) {
            if (!imeVisible) {
                // adjustResize can publish the reduced height one frame before
                // WindowInsets.ime becomes visible. Waiting briefly prevents that
                // transient frame from switching layout branches and destroying a
                // drawer-hosted dialog. Genuine DeX window resizing remains live.
                delay(250)
                if (!imeVisible) imeHiddenHeight = maxHeight
            }
        }
        val layoutHeight = if (imeVisible) imeHiddenHeight else maxHeight
        val desktopWorkspace = maxWidth >= 840.dp && layoutHeight >= 480.dp
        LaunchedEffect(desktopWorkspace) {
            dexWorkspaceActive = desktopWorkspace
            if (desktopWorkspace && dexBrowserFileName.isBlank()) {
                dexBrowserFileName = WEB_PREVIEW_TAB
                dexBrowserVisible = true
            } else if (!desktopWorkspace) {
                DesktopPointerFocusRouter.clearFocusedTarget()
                dexBrowserVisible = false
                if (dexBrowserFileName.isNotBlank()) {
                    val browserName = dexBrowserFileName
                    // Documentation and a running local preview remain usable as
                    // a regular tab after leaving DeX. The default web browser is
                    // a DeX-only virtual file and must not pollute phone tabs.
                    if (browserName in files) {
                        if (browserName !in openTabs) openTabs = openTabs + browserName
                        activeFileName = browserName
                    }
                    dexBrowserFileName = ""
                }
            }
        }
        val shortLandscape = maxWidth > layoutHeight * 1.35f && layoutHeight < 620.dp
        // Fold state / wide workspace is a width decision. Tying this to height
        // destroys drawer-hosted dialogs when a Dialog window opens its IME and
        // Android transiently reports a reduced activity height.
        val persistentSidebar = maxWidth >= 600.dp
        val compactToolbar = maxWidth < 600.dp
        var sidebarWidthValue by rememberSaveable { mutableFloatStateOf(220f) }
        val sidebarMax = (maxWidth * 0.48f).coerceAtLeast(220.dp)
        val panelWidth = sidebarWidthValue.dp.coerceIn(180.dp, sidebarMax)
        val dexBrowserMax = (maxWidth * 0.55f).coerceAtLeast(320.dp)
        val dexBrowserWidth = dexBrowserWidthValue.dp.coerceIn(320.dp, dexBrowserMax)
        val dexBrowserFile = dexBrowserFileName
            .takeIf { desktopWorkspace && dexBrowserVisible }
            ?.let { name ->
                files[name] ?: name.takeIf { it == WEB_PREVIEW_TAB }?.let {
                    ProjectFile(
                        name = WEB_PREVIEW_TAB,
                        content = DEFAULT_WEB_BROWSER_URL,
                        readOnly = true,
                        kind = ProjectFileKind.WebPreview,
                    )
                }
            }
        val drawerWidth = (maxWidth * 0.88f).coerceAtMost(340.dp)
        val dexScale = if (desktopWorkspace) appearance.dexUiScale else 1f
        val workspaceDensity = Density(density.density * dexScale, density.fontScale)
        // Keep phone and unfolded layouts at their native size. DeX receives a
        // separately persisted density so every Compose control scales together.
        val measurements = WorkspaceLayoutMeasurements(
            mode = mode,
            shortLandscape = shortLandscape,
            sidebarWidthValue = mutableWorkspaceValue(
                { sidebarWidthValue },
                { sidebarWidthValue = it },
            ),
            sidebarMax = sidebarMax,
            panelWidth = panelWidth,
            dexBrowserMax = dexBrowserMax,
            dexBrowserWidth = dexBrowserWidth,
            drawerWidth = drawerWidth,
            baseDensity = density,
            dexDensity = Density(
                density.density * appearance.dexUiScale,
                density.fontScale,
            ),
            dexScale = appearance.dexUiScale,
            dexBrowserFile = dexBrowserFile,
        )
        val variant = when (workspaceDeviceMode(maxWidth.value, layoutHeight.value)) {
            WorkspaceDeviceMode.Dex -> DexWorkspaceLayout(measurements)
            WorkspaceDeviceMode.Unfolded -> UnfoldedWorkspaceLayout(measurements)
            WorkspaceDeviceMode.Folded -> FoldedWorkspaceLayout(measurements)
        }
        // Keep one stable Compose call site while the physical device changes mode.
        // Editor and terminal remembers therefore survive fold/unfold and DeX transitions.
        RenderWorkspaceLayout(measurements, variant)
}
    }

    @Composable
    internal fun RenderWorkspaceLayout(
        measurements: WorkspaceLayoutMeasurements,
        variant: WorkspaceLayoutVariant,
    ) {
        val mode = measurements.mode
        val shortLandscape = measurements.shortLandscape
        var sidebarWidthValue by measurements.sidebarWidthValue
        val sidebarMax = measurements.sidebarMax
        val panelWidth = measurements.panelWidth
        val dexBrowserMax = measurements.dexBrowserMax
        val dexBrowserWidth = measurements.dexBrowserWidth
        val drawerWidth = measurements.drawerWidth
        val desktopWorkspace = variant.desktopWorkspace
        val persistentSidebar = variant.persistentSidebar
        val compactToolbar = variant.compactToolbar
        val dexScale = variant.uiScale
        val dexBrowserFile = variant.dexBrowserFile
        val workspaceDensity = variant.workspaceDensity
        val dropPreviewGroup = editorDropBounds?.let { bounds ->
            val pointer = explorerDragPosition ?: return@let null
            editorDropTarget(pointer, bounds, splitEditorVisible, splitEditorRatio)
                ?.takeIf { target ->
                    desktopWorkspace &&
                        explorerDragPath != null &&
                        !splitEditorVisible &&
                        target == EditorGroup.Secondary &&
                        target != editorDragSourceGroup
                }
        }

        CompositionLocalProvider(LocalDensity provides workspaceDensity) {

        Column(Modifier.fillMaxSize()) {
            // The unfolded device already has a permanent activity rail, so a
            // second branded header only wastes vertical editor space. Compact
            // phones still need the hamburger bar; DeX keeps desktop branding.
            if (!persistentSidebar || desktopWorkspace) {
                TopBar(
                    mode = mode,
                    compactControls = compactToolbar,
                    desktopWorkspace = desktopWorkspace,
                    sidePanelVisible = sidePanelVisible,
                    showMenu = !persistentSidebar,
                    terminalVisible = terminalVisible,
                    building = toolbarOperationRunning,
                    showBuildStatistics = showBuildStatistics,
                    buildStatistics = buildStatisticsLabel,
                    onToggleBuildStatistics = ::toggleBuildStatistics,
                    debugActions = debugToolbarActions,
                    onMenu = {
                        focusManager.clearFocus(force = true)
                        drawerOpen = !drawerOpen
                    },
                    onToggleSidebar = {
                        focusManager.clearFocus(force = true)
                        sidePanelVisible = !sidePanelVisible
                    },
                    onToggleTerminal = {
                        focusManager.clearFocus(force = true)
                        terminalVisible = !terminalVisible
                    },
                    onRun = { runCurrentProject() },
                    onDebug = ::startDebugging,
                    onStop = ::stopCurrentOperation,
                    onNewTerminal = { terminalController.create(terminal) },
                    onSettings = { showSettings = true },
                    wordWrap = appearance.wordWrap,
                    onToggleWordWrap = {
                        appearance = appearance.copy(wordWrap = !appearance.wordWrap)
                        appearanceStore.save(appearance)
                    },
                    onCloseEditor = {
                        activeFileName.takeIf { it.isNotBlank() }?.let(::closeFile)
                    },
                    onShowDestination = { selected ->
                        destination = selected
                        sidePanelVisible = true
                        drawerOpen = false
                    },
                    onSwitchEditor = { direction ->
                        if (openTabs.isNotEmpty()) {
                            val current = openTabs.indexOf(activeFileName).coerceAtLeast(0)
                            activeFileName = openTabs[(current + direction + openTabs.size) % openTabs.size]
                        }
                    },
                    splitEditorVisible = splitEditorVisible,
                    canSplitEditor = openTabs.any { it in files && it != activeFileName },
                    onToggleSplitEditor = {
                        if (splitEditorVisible) {
                            splitEditorVisible = false
                        } else {
                            secondaryActiveFileName = openTabs.firstOrNull { it in files && it != activeFileName }.orEmpty()
                            secondaryOpenTabs = listOfNotNull(secondaryActiveFileName.takeIf { it.isNotBlank() })
                            splitEditorVisible = secondaryActiveFileName.isNotBlank()
                        }
                    },
                    onDesktopExplorerCommand = { command ->
                        destination = ActivityDestination.Files
                        sidePanelVisible = true
                        desktopExplorerCommand = command
                    },
                    // The browser is a permanent DeX capability, not something
                    // unlocked only after a web project has been run.
                    auxiliaryPaneAvailable = desktopWorkspace,
                    auxiliaryPaneVisible = dexBrowserVisible && dexBrowserFileName.isNotBlank(),
                    onToggleAuxiliaryPane = {
                        focusManager.clearFocus(force = true)
                        DesktopPointerFocusRouter.clearFocusedTarget()
                        if (
                            dexBrowserFileName.isBlank() ||
                            (dexBrowserFileName !in files && dexBrowserFileName != WEB_PREVIEW_TAB)
                        ) {
                            dexBrowserFileName = WEB_PREVIEW_TAB
                            dexBrowserVisible = true
                        } else {
                            dexBrowserVisible = !dexBrowserVisible
                        }
                    },
                )
            }
            FloatingBuildActions(
                visible = persistentSidebar && !desktopWorkspace,
                compact = false,
                context = workspaceToolbarContext(
                    projectSources,
                    activeFileName,
                    building,
                    consoleActive || terminalCommandRunning,
                ),
                building = toolbarOperationRunning,
                showBuildStatistics = showBuildStatistics,
                buildStatistics = buildStatisticsLabel,
                onToggleBuildStatistics = ::toggleBuildStatistics,
                terminalVisible = terminalVisible,
                sidePanelVisible = sidePanelVisible,
                debugActions = debugToolbarActions,
                onRun = { runCurrentProject() },
                onStop = ::stopCurrentOperation,
                onToggleTerminal = {
                    focusManager.clearFocus(force = true)
                    terminalVisible = !terminalVisible
                },
                onToggleSidebar = {
                    focusManager.clearFocus(force = true)
                    sidePanelVisible = !sidePanelVisible
                },
            )

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        workspaceSurfaceBounds = Rect(
                            offset = coordinates.positionInRoot(),
                            size = androidx.compose.ui.geometry.Size(
                                coordinates.size.width.toFloat(),
                                coordinates.size.height.toFloat(),
                            ),
                        )
                    },
            ) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .then(if (desktopWorkspace) Modifier.padding(4.dp) else Modifier),
                ) {
                    if (persistentSidebar) {
                        ActivityRail(
                            selected = destination,
                            desktopMode = desktopWorkspace,
                            terminalVisible = terminalVisible,
                            onToggleTerminal = {
                                focusManager.clearFocus(force = true)
                                terminalVisible = !terminalVisible
                            },
                            onSettings = { showSettings = true },
                            onSelect = {
                                focusManager.clearFocus(force = true)
                                if (destination == it) {
                                    sidePanelVisible = !sidePanelVisible
                                } else {
                                    destination = it
                                    sidePanelVisible = true
                                }
                            },
                        )
                        if (desktopWorkspace) Spacer(Modifier.width(4.dp))
                        if (sidePanelVisible) {
                            DeferredCompose {
                                SidePanel(
                                state = SidePanelState(
                                destination = destination,
                                projectName = projectName,
                                files = files.values.filter { it.kind == ProjectFileKind.Source }.sortedBy { it.name },
                                folders = folders,
                                projectTree = projectTree,
                                gitState = gitState,
                                gitBusy = gitBusy,
                                gitNotice = gitNotice,
                                gitHubUsername = gitHubUsername,
                                gitHubToken = gitHubToken,
                                picoProjectOpen = isPicoProject(projectSources),
                                picoConfiguration = if (isPicoProject(projectSources)) currentPicoConfiguration() else null,
                                picoCMakeTargets = picoCMakeTargets,
                                cppExtensionInfo = cppExtensionInfo,
                                picoExtensionInfo = picoExtensionInfo,
                                pythonExtensionInfo = pythonExtensionInfo,
                                rustExtensionInfo = rustExtensionInfo,
                                gnuLanguagesExtensionInfo = gnuLanguagesExtensionInfo,
                                webExtensionInfo = webExtensionInfo,
                                gitToolsExtensionInfo = gitToolsExtensionInfo,
                                gnuArmExtensionInfo = gnuArmExtensionInfo,
                                extensionServerConfigured = extensionMarketplace.configured,
                                extensionBusy = extensionBusy,
                                extensionNotice = extensionNotice,
                                building = building,
                                cleaningPicoBuild = cleaningPicoBuild,
                                activeFileName = activeFileName,
                                dirtyFiles = debugToolbarActions.saveActions.dirtyFiles,
                                width = panelWidth,
                                desktopMode = desktopWorkspace,
                                externalExplorerCommand = desktopExplorerCommand,
                                ),
                                actions = SidePanelActions(
                                onExternalExplorerCommandHandled = { desktopExplorerCommand = null },
                                onOpenFolder = {
                                    focusManager.clearFocus(force = true)
                                    openProjectsFolder()
                                },
                                onOpenFilePicker = actions.openFilePicker,
                                onInstallExtension = extensions::installPicoFromMarketplace,
                                onInstallCppExtension = extensions::installCppFromMarketplace,
                                onUninstallCppExtension = extensions::uninstallCppExtension,
                                onInstallPythonExtension = extensions::installPythonFromMarketplace,
                                onUninstallPythonExtension = extensions::uninstallPythonExtension,
                                onInstallRustExtension = extensions::installRustFromMarketplace,
                                onUninstallRustExtension = extensions::uninstallRustExtension,
                                onInstallGnuLanguagesExtension = extensions::installGnuLanguagesFromMarketplace,
                                onInstallWebExtension = extensions::installWebFromMarketplace,
                                onUninstallWebExtension = extensions::uninstallWebExtension,
                                onUninstallGnuLanguagesExtension = extensions::uninstallGnuLanguagesExtension,
                                onInstallGitToolsExtension = extensions::installGitToolsFromMarketplace,
                                onUninstallGitToolsExtension = extensions::uninstallGitToolsExtension,
                                onInstallGnuArmExtension = extensions::openInstaller,
                                onUninstallGnuArmExtension = extensions::uninstallGnuArmExtension,
                                onInstallExtensionFromFile = { extensions.openInstaller() },
                                onUninstallPicoExtension = extensions::uninstallPicoExtension,
                                onOpenExtensions = { destination = ActivityDestination.Extensions },
                                onOpenPicoExtensionDetails = ::openPicoExtensionDetails,
                                onOpenCppExtensionDetails = ::openCppExtensionDetails,
                                onOpenPythonExtensionDetails = ::openPythonExtensionDetails,
                                onOpenRustExtensionDetails = ::openRustExtensionDetails,
                                onOpenGnuLanguagesExtensionDetails = ::openGnuLanguagesExtensionDetails,
                                onOpenWebExtensionDetails = ::openWebExtensionDetails,
                                onOpenGitExtensionDetails = ::openGitExtensionDetails,
                                onClose = {
                                    focusManager.clearFocus(force = true)
                                    sidePanelVisible = false
                                },
                                onOpenFile = ::openFile,
                                onFileDragChanged = { path, position ->
                                    explorerDragPath = path
                                    explorerDragPosition = position
                                    editorDragSourceGroup = null
                                },
                                onOpenFileInSplit = { fileName, position ->
                                    val bounds = editorDropBounds
                                    val target = if (desktopWorkspace && bounds != null) {
                                        editorDropTarget(position, bounds, splitEditorVisible, splitEditorRatio)
                                    } else null
                                    if (target != null && fileName in files) {
                                        if (target == EditorGroup.Primary) {
                                            if (fileName !in openTabs) openTabs = openTabs + fileName
                                            activeFileName = fileName
                                        } else {
                                            if (fileName !in secondaryOpenTabs) secondaryOpenTabs = secondaryOpenTabs + fileName
                                            secondaryActiveFileName = fileName
                                            splitEditorVisible = true
                                        }
                                    }
                                    target != null
                                },
                                onNavigateToFile = ::navigateToFile,
                                onCreateGeneralProject = ::createGeneralProject,
                                onCreateFile = ::createFile,
                                onCreateFolder = ::createFolder,
                                onRenamePath = ::renamePath,
                                onMovePath = ::movePath,
                                onCopyPath = ::copyPath,
                                onDeletePath = ::deletePath,
                                onCreatePicoProject = { name, board, language ->
                                    runGitProjectOperation(replaceProject = true, openDestination = ActivityDestination.Pico) {
                                        gitEngine.createProject(name, picoEmptyProject(context, board, language))
                                    }
                                },
                                onCreatePicoExample = { name, board, example, language ->
                                    runGitProjectOperation(replaceProject = true, openDestination = ActivityDestination.Pico) {
                                        gitEngine.createProject(name, picoExampleProject(context, board, example, language))
                                    }
                                },
                                runDebugActions = runDebugActions,
                                onRunPicoUsb = ::runPicoUsb,
                                onConfigurePico = ::configurePicoProject,
                                onConfigurePicoCMake = ::configurePicoCMake,
                                onCleanPico = ::cleanPicoBuild,
                                onOpenPicoHardwareApis = ::openPicoHardwareApis,
                                onOpenPicoHighLevelApis = ::openPicoHighLevelApis,
                                onOpenPicoNetworkingLibraries = ::openPicoNetworkingLibraries,
                                onOpenPicoRuntimeInfrastructure = ::openPicoRuntimeInfrastructure,
                                onOpenPicoSdkReference = ::openPicoSdkReference,
                                onInitializeGit = {
                                    runGitOperation { gitEngine.initialize(projectKey, projectSources) }
                                },
                                onCommit = { message ->
                                    runGitOperation {
                                        gitEngine.stageAll(projectKey, projectSources)
                                        gitEngine.commit(projectKey, projectSources, message)
                                    }
                                },
                                onRefreshGit = {
                                    runGitOperation { gitEngine.status(projectKey, projectSources) }
                                },
                                onGitHubCredentialsChange = { username, token ->
                                    gitHubUsername = username
                                    gitHubToken = token
                                },
                                onCloneGitHub = { url, username, token ->
                                    runGitProjectOperation(replaceProject = true) {
                                        gitCredentialStore.save(username, token)
                                        gitEngine.clone(url, username, token)
                                    }
                                },
                                onPull = { username, token ->
                                    runGitProjectOperation(replaceProject = false) {
                                        gitCredentialStore.save(username, token)
                                        gitEngine.pull(projectKey, projectSources, username, token)
                                    }
                                },
                                onPush = { username, token ->
                                    runGitOperation {
                                        gitCredentialStore.save(username, token)
                                        gitEngine.push(projectKey, projectSources, username, token)
                                    }
                                },
                                onPublish = { name, privateRepository, username, token ->
                                    runGitOperation {
                                        gitCredentialStore.save(username, token)
                                        gitEngine.publish(
                                            projectKey,
                                            projectSources,
                                            username,
                                            token,
                                            name,
                                            privateRepository,
                                        )
                                    }
                                },
                                ),
                                )
                            }
                            Box(
                                Modifier
                                    .width(7.dp)
                                    .fillMaxHeight()
                                    .background(if (desktopWorkspace) Background else Border.copy(alpha = 0.35f))
                                    .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
                                    .pointerInput(sidebarMax, density) {
                                        detectHorizontalDragGestures { _, dragAmount ->
                                            sidebarWidthValue = (sidebarWidthValue + dragAmount / this@WorkspaceLayoutScope.density.density)
                                                .coerceIn(180f, sidebarMax.value)
                                        }
                                    },
                            )
                        }
                    }

                    WorkArea(
                        files = files,
                        dirtyFiles = debugToolbarActions.saveActions.dirtyFiles,
                        openTabs = openTabs,
                        activeFileName = activeFileName,
                        splitEditor = desktopWorkspace && splitEditorVisible,
                        secondaryActiveFileName = secondaryActiveFileName,
                        secondaryOpenTabs = secondaryOpenTabs,
                        navigationRequest = editorNavigation,
                        terminal = terminal,
                        buildOutput = buildOutput,
                        diagnosticsOutput = diagnosticsOutput,
                        terminalPrompt = terminalController.activePrompt,
                        terminalSessions = terminalController.sessionInfos,
                        selectedTerminalId = terminalController.selectedId,
                        semanticCompletions = semanticCompletions,
                        semanticDiagnostics = semanticDiagnostics,
                        onRequestSemanticCompletion = ::requestSemanticCompletion,
                        onRequestSemanticDiagnostics = ::requestSemanticDiagnostics,
                        cppExtensionInfo = cppExtensionInfo,
                        picoExtensionInfo = picoExtensionInfo,
                        pythonExtensionInfo = pythonExtensionInfo,
                        rustExtensionInfo = rustExtensionInfo,
                        gnuLanguagesExtensionInfo = gnuLanguagesExtensionInfo,
                        webExtensionInfo = webExtensionInfo,
                        gitExtensionInfo = gitToolsExtensionInfo,
                        gnuArmExtensionInfo = gnuArmExtensionInfo,
                        extensionBusy = extensionBusy,
                        extensionNotice = extensionNotice,
                        mode = mode,
                        // The Sora editor is an Android View, so unlike Compose UI it does
                        // not inherit the DeX density override above. Keep its zoom fully
                        // independent and let Ctrl+wheel change only this multiplier.
                        editorScale = if (desktopWorkspace) dexEditorZoom else 1f,
                        secondaryEditorScale = if (desktopWorkspace) dexSecondaryEditorZoom else 1f,
                        terminalScale = dexScale,
                        splitRatio = splitEditorRatio,
                        onSplitRatioChange = { splitEditorRatio = it },
                        onEditorZoomDelta = { group, delta ->
                            if (desktopWorkspace) {
                                if (group == EditorGroup.Primary) {
                                    dexEditorZoom = (dexEditorZoom + delta).coerceIn(0.55f, 2.25f)
                                } else {
                                    dexSecondaryEditorZoom = (dexSecondaryEditorZoom + delta).coerceIn(0.55f, 2.25f)
                                }
                            }
                        },
                        preferences = appearance,
                        shortLandscape = shortLandscape,
                        desktopWorkspace = desktopWorkspace,
                        terminalVisible = terminalVisible,
                        terminalHeightValue = terminalHeightValue,
                        terminalWidthValue = terminalWidthValue,
                        onTerminalHeightChange = { terminalHeightValue = it },
                        onTerminalWidthChange = { terminalWidthValue = it },
                        consoleActive = consoleActive || terminalCommandRunning ||
                            debugSnapshot.status == DebugSessionStatus.Running,
                        terminalInputVisible = consoleActive || terminalCommandRunning ||
                            debugSnapshot.status == DebugSessionStatus.Running ||
                            (!building && !cleaningPicoBuild && !picotoolBusy &&
                                debugSnapshot.status != DebugSessionStatus.Starting &&
                                debugSnapshot.status != DebugSessionStatus.Paused),
                        onConsoleInput = ::sendConsoleInput,
                        onNewTerminal = { terminalController.create(terminal) },
                        onSelectTerminal = { terminalController.select(it, terminal) },
                        onDeleteTerminal = { terminalController.delete(it, terminal) },
                        onActivateFile = { activeFileName = it },
                        onActivateSecondaryFile = { secondaryActiveFileName = it },
                        onCloseSecondaryFile = { fileName ->
                            val closingIndex = secondaryOpenTabs.indexOf(fileName).coerceAtLeast(0)
                            secondaryOpenTabs = secondaryOpenTabs - fileName
                            if (secondaryActiveFileName == fileName) {
                                secondaryActiveFileName = secondaryOpenTabs.getOrNull(
                                    closingIndex.coerceAtMost((secondaryOpenTabs.size - 1).coerceAtLeast(0)),
                                ).orEmpty()
                            }
                            if (secondaryOpenTabs.isEmpty()) splitEditorVisible = false
                        },
                        onFileDragChanged = { path, position, sourceGroup ->
                            explorerDragPath = path
                            explorerDragPosition = position
                            editorDragSourceGroup = sourceGroup
                        },
                        onRequestSplitFile = requestSplit@{ fileName, position, sourceGroup ->
                            if (!desktopWorkspace || fileName !in files || position == null) return@requestSplit
                            val bounds = editorDropBounds ?: return@requestSplit
                            val targetGroup = editorDropTarget(
                                position,
                                bounds,
                                splitEditorVisible,
                                splitEditorRatio,
                            ) ?: return@requestSplit
                            if (sourceGroup == targetGroup) return@requestSplit

                            if (sourceGroup == EditorGroup.Primary && targetGroup == EditorGroup.Secondary) {
                                if (openTabs.size <= 1 && !splitEditorVisible) return@requestSplit
                                val primaryRemaining = openTabs - fileName
                                val targetTabs = (secondaryOpenTabs + fileName).distinct()
                                if (primaryRemaining.isEmpty()) {
                                    // Moving the final tab out of a group collapses the
                                    // split instead of leaving an empty editor pane.
                                    openTabs = targetTabs
                                    activeFileName = fileName
                                    secondaryOpenTabs = emptyList()
                                    secondaryActiveFileName = ""
                                    splitEditorVisible = false
                                } else {
                                    openTabs = primaryRemaining
                                    if (activeFileName == fileName) activeFileName = primaryRemaining.last()
                                    secondaryOpenTabs = targetTabs
                                    secondaryActiveFileName = fileName
                                    splitEditorVisible = true
                                }
                            } else if (sourceGroup == EditorGroup.Secondary && targetGroup == EditorGroup.Primary) {
                                val secondaryRemaining = secondaryOpenTabs - fileName
                                openTabs = (openTabs + fileName).distinct()
                                activeFileName = fileName
                                secondaryOpenTabs = secondaryRemaining
                                if (secondaryActiveFileName == fileName) secondaryActiveFileName = secondaryRemaining.lastOrNull().orEmpty()
                                if (secondaryRemaining.isEmpty()) {
                                    secondaryActiveFileName = ""
                                    splitEditorVisible = false
                                }
                            }
                        },
                        dropPreviewGroup = dropPreviewGroup,
                        onEditorBoundsChanged = { editorDropBounds = it },
                        onCloseFile = ::closeFile,
                        onFileChange = { name, content ->
                            val existing = files.getValue(name)
                            files = files + (name to existing.copy(content = content))
                            // Never show a completion response belonging to the
                            // previous cursor/source after the user types again.
                            semanticCompletions = emptyList()
                        },
                        onEditorFocusChanged = { focused ->
                            if (focused && !consoleActive && !desktopWorkspace) {
                                if (terminalBeforeEditing == null) terminalBeforeEditing = terminalVisible
                                terminalVisible = false
                            } else if (!imeVisible) {
                                // Switching tabs or rebuilding the side panel can
                                // briefly detach the editor while the IME stays
                                // open. That is not the end of an editing session.
                                terminalBeforeEditing?.let { terminalVisible = it }
                                terminalBeforeEditing = null
                            }
                        },
                        extensionServerConfigured = extensionMarketplace.configured,
                        onInstallPicoExtension = extensions::installPicoFromMarketplace,
                        onInstallCppExtension = extensions::installCppFromMarketplace,
                        onInstallPythonExtension = extensions::installPythonFromMarketplace,
                        onInstallRustExtension = extensions::installRustFromMarketplace,
                        onInstallGnuLanguagesExtension = extensions::installGnuLanguagesFromMarketplace,
                        onInstallWebExtension = extensions::installWebFromMarketplace,
                        onInstallGitExtension = extensions::installGitToolsFromMarketplace,
                        onInstallGnuArmExtension = extensions::openInstaller,
                        onInstallPicoExtensionFromFile = { extensions.openInstaller() },
                        onPreparePicoComponents = extensions::preparePicoComponents,
                        onDeletePicoComponents = extensions::deletePicoComponents,
                        onUninstallPicoExtension = extensions::uninstallPicoExtension,
                        onUninstallCppExtension = extensions::uninstallCppExtension,
                        onUninstallPythonExtension = extensions::uninstallPythonExtension,
                        onUninstallRustExtension = extensions::uninstallRustExtension,
                        onUninstallGnuLanguagesExtension = extensions::uninstallGnuLanguagesExtension,
                        onUninstallWebExtension = extensions::uninstallWebExtension,
                        onUninstallGitExtension = extensions::uninstallGitToolsExtension,
                        onUninstallGnuArmExtension = extensions::uninstallGnuArmExtension,
                        modifier = Modifier.weight(1f),
                    )
                    if (dexBrowserFile != null) {
                        Box(
                            Modifier
                                .width(7.dp)
                                .fillMaxHeight()
                                .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
                                .pointerInput(dexBrowserMax, density) {
                                    detectHorizontalDragGestures { _, dragAmount ->
                                        dexBrowserWidthValue = (dexBrowserWidthValue - dragAmount / this@WorkspaceLayoutScope.density.density)
                                            .coerceIn(320f, dexBrowserMax.value)
                                    }
                                },
                        )
                        DexBrowserPane(
                            file = dexBrowserFile,
                            mode = mode,
                            onClose = {
                                focusManager.clearFocus(force = true)
                                DesktopPointerFocusRouter.clearFocusedTarget()
                                dexBrowserVisible = false
                            },
                            modifier = Modifier.width(dexBrowserWidth).fillMaxHeight(),
                        )
                    }
                }
                val draggedPath = explorerDragPath
                val pointer = explorerDragPosition
                val workspaceBounds = workspaceSurfaceBounds
                if (desktopWorkspace && draggedPath != null && pointer != null && workspaceBounds != null) {
                    val chipWidthPx = with(density) { 190.dp.toPx() }
                    val chipHeightPx = with(density) { 34.dp.toPx() }
                    val chipX = (pointer.x - workspaceBounds.left + with(density) { 14.dp.toPx() })
                        .coerceIn(0f, (workspaceBounds.width - chipWidthPx).coerceAtLeast(0f))
                    val chipY = (pointer.y - workspaceBounds.top - chipHeightPx / 2f)
                        .coerceIn(0f, (workspaceBounds.height - chipHeightPx).coerceAtLeast(0f))
                    Box(
                        Modifier
                            .widthIn(max = 190.dp)
                            .graphicsLayer {
                                translationX = chipX
                                translationY = chipY
                                alpha = 0.96f
                            }
                            .zIndex(41f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Panel)
                            .border(1.dp, Accent, RoundedCornerShape(4.dp))
                            .padding(horizontal = 9.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "${fileGlyph(draggedPath)}  ${draggedPath.substringAfterLast('/')}",
                            color = Foreground,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!persistentSidebar && drawerOpen) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.48f))
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val primary = down.type != PointerType.Mouse || currentEvent.buttons.isPrimaryPressed
                                    if (primary && waitForUpOrCancellation() != null) drawerOpen = false
                                }
                            },
                    )
                    Row(
                        Modifier
                            .width(drawerWidth)
                            .fillMaxHeight()
                            .background(Panel)
                            .zIndex(2f)
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.buttons.isSecondaryPressed) {
                                            // Keep secondary presses inside the drawer;
                                            // child rows still use them for context menus.
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                }
                            }
                            .clickable { },
                    ) {
                        ActivityRail(
                            selected = destination,
                            desktopMode = false,
                            terminalVisible = terminalVisible,
                            onToggleTerminal = {
                                focusManager.clearFocus(force = true)
                                terminalVisible = !terminalVisible
                            },
                            onSettings = { showSettings = true },
                            onSelect = {
                                focusManager.clearFocus(force = true)
                                destination = it
                            },
                        )
                        DeferredCompose {
                            SidePanel(
                            state = SidePanelState(
                            destination = destination,
                            projectName = projectName,
                            files = files.values.filter { it.kind == ProjectFileKind.Source }.sortedBy { it.name },
                            folders = folders,
                            projectTree = projectTree,
                            gitState = gitState,
                            gitBusy = gitBusy,
                            gitNotice = gitNotice,
                            gitHubUsername = gitHubUsername,
                            gitHubToken = gitHubToken,
                            picoProjectOpen = isPicoProject(projectSources),
                            picoConfiguration = if (isPicoProject(projectSources)) currentPicoConfiguration() else null,
                            picoCMakeTargets = picoCMakeTargets,
                            cppExtensionInfo = cppExtensionInfo,
                            picoExtensionInfo = picoExtensionInfo,
                            pythonExtensionInfo = pythonExtensionInfo,
                            rustExtensionInfo = rustExtensionInfo,
                            gnuLanguagesExtensionInfo = gnuLanguagesExtensionInfo,
                            webExtensionInfo = webExtensionInfo,
                            gitToolsExtensionInfo = gitToolsExtensionInfo,
                            gnuArmExtensionInfo = gnuArmExtensionInfo,
                            extensionServerConfigured = extensionMarketplace.configured,
                            extensionBusy = extensionBusy,
                            extensionNotice = extensionNotice,
                            building = building,
                            cleaningPicoBuild = cleaningPicoBuild,
                            activeFileName = activeFileName,
                            dirtyFiles = debugToolbarActions.saveActions.dirtyFiles,
                            width = drawerWidth - 46.dp,
                            desktopMode = false,
                            externalExplorerCommand = null,
                            ),
                            actions = SidePanelActions(
                            onExternalExplorerCommandHandled = {},
                            onOpenFolder = {
                                focusManager.clearFocus(force = true)
                                openProjectsFolder()
                            },
                            onOpenFilePicker = actions.openFilePicker,
                            onInstallExtension = extensions::installPicoFromMarketplace,
                            onInstallCppExtension = extensions::installCppFromMarketplace,
                            onUninstallCppExtension = extensions::uninstallCppExtension,
                            onInstallPythonExtension = extensions::installPythonFromMarketplace,
                            onUninstallPythonExtension = extensions::uninstallPythonExtension,
                            onInstallRustExtension = extensions::installRustFromMarketplace,
                            onUninstallRustExtension = extensions::uninstallRustExtension,
                            onInstallGnuLanguagesExtension = extensions::installGnuLanguagesFromMarketplace,
                            onInstallWebExtension = extensions::installWebFromMarketplace,
                            onUninstallWebExtension = extensions::uninstallWebExtension,
                            onUninstallGnuLanguagesExtension = extensions::uninstallGnuLanguagesExtension,
                            onInstallGitToolsExtension = extensions::installGitToolsFromMarketplace,
                            onUninstallGitToolsExtension = extensions::uninstallGitToolsExtension,
                            onInstallGnuArmExtension = extensions::openInstaller,
                            onUninstallGnuArmExtension = extensions::uninstallGnuArmExtension,
                            onInstallExtensionFromFile = { extensions.openInstaller() },
                            onUninstallPicoExtension = extensions::uninstallPicoExtension,
                            onOpenExtensions = { destination = ActivityDestination.Extensions },
                            onOpenPicoExtensionDetails = ::openPicoExtensionDetails,
                            onOpenCppExtensionDetails = ::openCppExtensionDetails,
                            onOpenPythonExtensionDetails = ::openPythonExtensionDetails,
                            onOpenRustExtensionDetails = ::openRustExtensionDetails,
                            onOpenGnuLanguagesExtensionDetails = ::openGnuLanguagesExtensionDetails,
                            onOpenWebExtensionDetails = ::openWebExtensionDetails,
                            onOpenGitExtensionDetails = ::openGitExtensionDetails,
                            onClose = { drawerOpen = false },
                            onOpenFile = {
                                openFile(it)
                                drawerOpen = false
                            },
                            onFileDragChanged = { _, _ -> },
                            onOpenFileInSplit = { _, _ -> false },
                            onNavigateToFile = { fileName, line, column ->
                                navigateToFile(fileName, line, column)
                                drawerOpen = false
                            },
                            onCreateGeneralProject = ::createGeneralProject,
                            onCreateFile = ::createFile,
                            onCreateFolder = ::createFolder,
                            onRenamePath = ::renamePath,
                            onMovePath = ::movePath,
                            onCopyPath = ::copyPath,
                            onDeletePath = ::deletePath,
                            onCreatePicoProject = { name, board, language ->
                                runGitProjectOperation(replaceProject = true, openDestination = ActivityDestination.Pico) {
                                    gitEngine.createProject(name, picoEmptyProject(context, board, language))
                                }
                            },
                            onCreatePicoExample = { name, board, example, language ->
                                runGitProjectOperation(replaceProject = true, openDestination = ActivityDestination.Pico) {
                                    gitEngine.createProject(name, picoExampleProject(context, board, example, language))
                                }
                            },
                            runDebugActions = runDebugActions,
                            onRunPicoUsb = ::runPicoUsb,
                            onConfigurePico = ::configurePicoProject,
                            onConfigurePicoCMake = ::configurePicoCMake,
                            onCleanPico = ::cleanPicoBuild,
                            onOpenPicoHardwareApis = ::openPicoHardwareApis,
                            onOpenPicoHighLevelApis = ::openPicoHighLevelApis,
                            onOpenPicoNetworkingLibraries = ::openPicoNetworkingLibraries,
                            onOpenPicoRuntimeInfrastructure = ::openPicoRuntimeInfrastructure,
                            onOpenPicoSdkReference = ::openPicoSdkReference,
                            onInitializeGit = {
                                runGitOperation { gitEngine.initialize(projectKey, projectSources) }
                            },
                            onCommit = { message ->
                                runGitOperation {
                                    gitEngine.stageAll(projectKey, projectSources)
                                    gitEngine.commit(projectKey, projectSources, message)
                                }
                            },
                            onRefreshGit = {
                                runGitOperation { gitEngine.status(projectKey, projectSources) }
                            },
                            onGitHubCredentialsChange = { username, token ->
                                gitHubUsername = username
                                gitHubToken = token
                            },
                            onCloneGitHub = { url, username, token ->
                                runGitProjectOperation(replaceProject = true) {
                                    gitCredentialStore.save(username, token)
                                    gitEngine.clone(url, username, token)
                                }
                            },
                            onPull = { username, token ->
                                runGitProjectOperation(replaceProject = false) {
                                    gitCredentialStore.save(username, token)
                                    gitEngine.pull(projectKey, projectSources, username, token)
                                }
                            },
                            onPush = { username, token ->
                                runGitOperation {
                                    gitCredentialStore.save(username, token)
                                    gitEngine.push(projectKey, projectSources, username, token)
                                }
                            },
                            onPublish = { name, privateRepository, username, token ->
                                runGitOperation {
                                    gitCredentialStore.save(username, token)
                                    gitEngine.publish(
                                        projectKey,
                                        projectSources,
                                        username,
                                        token,
                                        name,
                                        privateRepository,
                                    )
                                }
                            },
                            ),
                            )
                        }
                    }
                }
            }
        }
    }
    }

}
