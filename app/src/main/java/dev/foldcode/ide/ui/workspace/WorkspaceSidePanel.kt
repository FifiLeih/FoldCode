package dev.foldcode.ide

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

@Composable
private fun rememberMouseConnected(): Boolean {
    val context = LocalContext.current
    val inputManager = remember(context) { context.getSystemService(Context.INPUT_SERVICE) as InputManager }
    fun hasMouse(): Boolean = inputManager.inputDeviceIds.any { id ->
        InputDevice.getDevice(id)?.let { device ->
            device.supportsSource(InputDevice.SOURCE_MOUSE) &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || device.isExternal)
        } == true
    }
    var connected by remember(inputManager) { mutableStateOf(hasMouse()) }
    DisposableEffect(inputManager) {
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { connected = hasMouse() }
            override fun onInputDeviceRemoved(deviceId: Int) { connected = hasMouse() }
            override fun onInputDeviceChanged(deviceId: Int) { connected = hasMouse() }
        }
        inputManager.registerInputDeviceListener(listener, null)
        connected = hasMouse()
        onDispose { inputManager.unregisterInputDeviceListener(listener) }
    }
    return connected
}

internal data class RunDebugActions(
    val compilePico: () -> Unit,
    val run: () -> Unit,
    val debug: () -> Unit,
    val stop: () -> Unit,
    val snapshot: DebugSessionSnapshot,
    val selectFrame: (Int) -> Unit,
    val toggleVariable: (Int) -> Unit,
    val evaluate: (String) -> Unit,
)

internal data class ManualSaveActions(
    val enabled: Boolean,
    val activeFileName: String,
    val dirtyFiles: Set<String>,
    val hasUnsavedChanges: Boolean,
    val onSave: () -> Unit,
    val onSaveAll: () -> Unit,
) {
    val canSaveCurrent: Boolean get() = activeFileName in dirtyFiles
}

internal data class DebugToolbarActions(
    val status: DebugSessionStatus,
    val saveActions: ManualSaveActions,
    val onContinue: () -> Unit,
    val onPause: () -> Unit,
    val onStepOver: () -> Unit,
    val onStepInto: () -> Unit,
    val onStepOut: () -> Unit,
    val onRestart: () -> Unit,
    val onStop: () -> Unit,
) {
    val visible: Boolean
        get() = status == DebugSessionStatus.Starting ||
            status == DebugSessionStatus.Running ||
            status == DebugSessionStatus.Paused
}

@Composable
internal fun DebugControls(compact: Boolean, actions: DebugToolbarActions) {
    val enabled = actions.status == DebugSessionStatus.Running || actions.status == DebugSessionStatus.Paused
    val paused = actions.status == DebugSessionStatus.Paused
    val horizontalPadding = if (compact) 4.dp else 6.dp
    fun Modifier.debugButton(onClick: () -> Unit, isEnabled: Boolean = true): Modifier =
        pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HAND))
            .clickable(enabled = isEnabled, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 5.dp)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (paused) "▶" else "Ⅱ",
            color = if (enabled) Accent else Muted,
            fontSize = if (compact) 14.sp else 15.sp,
            modifier = Modifier.debugButton(if (paused) actions.onContinue else actions.onPause, enabled),
        )
        Text("↷", color = if (paused) Foreground else Muted, fontSize = 16.sp, modifier = Modifier.debugButton(actions.onStepOver, paused))
        Text("↓", color = if (paused) Foreground else Muted, fontSize = 16.sp, modifier = Modifier.debugButton(actions.onStepInto, paused))
        Text("↑", color = if (paused) Foreground else Muted, fontSize = 16.sp, modifier = Modifier.debugButton(actions.onStepOut, paused))
        Text("↻", color = Foreground, fontSize = 16.sp, modifier = Modifier.debugButton(actions.onRestart))
        Text("■", color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.debugButton(actions.onStop))
        Box(Modifier.width(1.dp).height(20.dp).background(Border).padding(horizontal = 2.dp))
    }
}

internal class SidePanelState(
    val destination: ActivityDestination,
    val projectName: String,
    val files: List<ProjectFile>,
    val folders: Set<String>,
    val projectTree: List<ProjectTreeEntry>,
    val gitState: GitRepositoryState,
    val gitBusy: Boolean,
    val gitNotice: String?,
    val gitHubUsername: String,
    val gitHubToken: String,
    val picoProjectOpen: Boolean,
    val picoConfiguration: PicoProjectConfiguration?,
    val picoCMakeTargets: List<String>,
    val cppExtensionInfo: FoldCodeExtensionInfo?,
    val picoExtensionInfo: FoldCodeExtensionInfo?,
    val pythonExtensionInfo: FoldCodeExtensionInfo?,
    val rustExtensionInfo: FoldCodeExtensionInfo?,
    val gnuLanguagesExtensionInfo: FoldCodeExtensionInfo?,
    val webExtensionInfo: FoldCodeExtensionInfo?,
    val gitToolsExtensionInfo: FoldCodeExtensionInfo?,
    val gnuArmExtensionInfo: FoldCodeExtensionInfo?,
    val extensionBusy: Boolean,
    val extensionNotice: String?,
    val extensionServerConfigured: Boolean,
    val building: Boolean,
    val cleaningPicoBuild: Boolean,
    val activeFileName: String,
    val dirtyFiles: Set<String>,
    val width: Dp,
    val desktopMode: Boolean,
    val externalExplorerCommand: DesktopExplorerCommand?,
)

internal class SidePanelActions(
    val onExternalExplorerCommandHandled: () -> Unit,
    val onOpenFolder: () -> Unit,
    val onOpenFilePicker: () -> Unit,
    val onInstallExtension: () -> Unit,
    val onInstallCppExtension: () -> Unit,
    val onUninstallCppExtension: () -> Unit,
    val onInstallPythonExtension: () -> Unit,
    val onUninstallPythonExtension: () -> Unit,
    val onInstallRustExtension: () -> Unit,
    val onUninstallRustExtension: () -> Unit,
    val onInstallGnuLanguagesExtension: () -> Unit,
    val onUninstallGnuLanguagesExtension: () -> Unit,
    val onInstallWebExtension: () -> Unit,
    val onUninstallWebExtension: () -> Unit,
    val onInstallGitToolsExtension: () -> Unit,
    val onUninstallGitToolsExtension: () -> Unit,
    val onInstallGnuArmExtension: () -> Unit,
    val onUninstallGnuArmExtension: () -> Unit,
    val onInstallExtensionFromFile: () -> Unit,
    val onUninstallPicoExtension: () -> Unit,
    val onOpenExtensions: () -> Unit,
    val onOpenPicoExtensionDetails: () -> Unit,
    val onOpenCppExtensionDetails: () -> Unit,
    val onOpenPythonExtensionDetails: () -> Unit,
    val onOpenRustExtensionDetails: () -> Unit,
    val onOpenGnuLanguagesExtensionDetails: () -> Unit,
    val onOpenWebExtensionDetails: () -> Unit,
    val onOpenGitExtensionDetails: () -> Unit,
    val onClose: () -> Unit,
    val onOpenFile: (String) -> Unit,
    val onFileDragChanged: (String?, Offset?) -> Unit,
    val onOpenFileInSplit: (String, Offset) -> Boolean,
    val onNavigateToFile: (String, Int, Int) -> Unit,
    val onCreateGeneralProject: (String, GeneralProjectTemplate) -> Unit,
    val onCreateFile: (String) -> Unit,
    val onCreateFolder: (String) -> Unit,
    val onRenamePath: (String, String, Boolean) -> Unit,
    val onMovePath: (String, String, Boolean) -> Unit,
    val onCopyPath: (String, String, Boolean) -> Unit,
    val onDeletePath: (String, Boolean) -> Unit,
    val onCreatePicoProject: (String, PicoBoard, PicoProjectLanguage) -> Unit,
    val onCreatePicoExample: (String, PicoBoard, PicoExample, PicoProjectLanguage) -> Unit,
    val runDebugActions: RunDebugActions,
    val onRunPicoUsb: () -> Unit,
    val onConfigurePico: (PicoProjectConfiguration) -> Unit,
    val onConfigurePicoCMake: () -> Unit,
    val onCleanPico: () -> Unit,
    val onOpenPicoHardwareApis: () -> Unit,
    val onOpenPicoHighLevelApis: () -> Unit,
    val onOpenPicoNetworkingLibraries: () -> Unit,
    val onOpenPicoRuntimeInfrastructure: () -> Unit,
    val onOpenPicoSdkReference: () -> Unit,
    val onInitializeGit: () -> Unit,
    val onCommit: (String) -> Unit,
    val onRefreshGit: () -> Unit,
    val onGitHubCredentialsChange: (String, String) -> Unit,
    val onCloneGitHub: (String, String, String) -> Unit,
    val onPull: (String, String) -> Unit,
    val onPush: (String, String) -> Unit,
    val onPublish: (String, Boolean, String, String) -> Unit,
)

@Composable
internal fun SidePanel(state: SidePanelState, actions: SidePanelActions) = with(state) {
with(actions) {
    val mouseConnected = rememberMouseConnected()
    var showCloneDialog by rememberSaveable { mutableStateOf(false) }
    var showExplorerMenu by remember { mutableStateOf(false) }
    var explorerAction by remember { mutableStateOf<ExplorerAction?>(null) }
    var itemMenu by remember { mutableStateOf<ExplorerEntry?>(null) }
    var rootMenu by remember { mutableStateOf(false) }
    var clipboardEntry by remember { mutableStateOf<ExplorerEntry?>(null) }
    var clipboardCut by remember { mutableStateOf(false) }
    var draggingEntry by remember { mutableStateOf<ExplorerEntry?>(null) }
    var dragPosition by remember { mutableStateOf<Offset?>(null) }
    var hoveredEntry by remember { mutableStateOf<ExplorerEntry?>(null) }
    var explorerMenuPosition by remember { mutableStateOf(Offset.Zero) }
    var explorerBounds by remember { mutableStateOf<Rect?>(null) }
    val entryBounds = remember { mutableStateMapOf<ExplorerEntry, Rect>() }
    fun updateExplorerDrag(entry: ExplorerEntry?, position: Offset?) {
        draggingEntry = entry
        dragPosition = position
        onFileDragChanged(entry?.path, position)
    }
    val allFolders = remember(files, folders) {
        folders + files.flatMap { parentFolderPaths(it.name) }
    }
    val picoSourceContents = remember(files) { files.associate { it.name to it.content } }
    val rustProjectOpen = remember(picoSourceContents) { isPicoRustProject(picoSourceContents) }
    val microPythonProjectOpen = remember(picoSourceContents) { isPicoMicroPythonProject(picoSourceContents) }
    val explorerEntries = remember(files, allFolders, projectTree) {
        val disk = projectTree.map { ExplorerEntry(it.path, it.folder, it.editable) }
        val live = allFolders.map { ExplorerEntry(it, true) } + files.map { ExplorerEntry(it.name, false) }
        // Prefer the live model so newly created nested entries retain their actions.
        (live + disk).distinctBy { it.path to it.folder }
            .sortedWith(compareBy<ExplorerEntry> { it.path.lowercase() }.thenByDescending { it.folder })
    }
    var expandedFolders by remember(projectName) { mutableStateOf(setOf<String>()) }
    LaunchedEffect(activeFileName) {
        expandedFolders = expandedFolders + parentFolderPaths(activeFileName)
    }
    LaunchedEffect(externalExplorerCommand) {
        when (externalExplorerCommand) {
            DesktopExplorerCommand.CreateProject -> explorerAction = ExplorerAction(ExplorerActionType.CreateProject)
            DesktopExplorerCommand.CreateFile -> explorerAction = ExplorerAction(ExplorerActionType.CreateFile)
            DesktopExplorerCommand.CreateFolder -> explorerAction = ExplorerAction(ExplorerActionType.CreateFolder)
            DesktopExplorerCommand.OpenProject -> onOpenFolder()
            DesktopExplorerCommand.OpenFile -> onOpenFilePicker()
            DesktopExplorerCommand.CloneRepository -> showCloneDialog = true
            null -> return@LaunchedEffect
        }
        onExternalExplorerCommandHandled()
    }
    val visibleEntries = explorerEntries.filter { entry ->
        parentFolderPaths(entry.path).all { it in expandedFolders }
    }
    fun folderDropTargetAt(position: Offset?, dragged: ExplorerEntry?): ExplorerEntry? {
        if (position == null || dragged == null) return null
        val entryUnderPointer = entryBounds.entries
            .asSequence()
            .filter { (_, bounds) -> bounds.contains(position) }
            .maxByOrNull { it.key.path.length }
            ?.key
            ?: return null
        val candidatePaths = buildList {
            if (entryUnderPointer.folder) add(entryUnderPointer.path)
            var parent = entryUnderPointer.path.substringBeforeLast('/', "")
            while (parent.isNotBlank()) {
                add(parent)
                parent = parent.substringBeforeLast('/', "")
            }
        }
        return candidatePaths.firstNotNullOfOrNull { candidatePath ->
            entryBounds.keys.firstOrNull { candidate ->
                candidate.folder &&
                    candidate.path == candidatePath &&
                    candidate != dragged &&
                    (!dragged.folder || !candidate.path.startsWith("${dragged.path}/"))
            }
        }
    }
    fun rootDropTargetAt(position: Offset?): Boolean {
        if (position == null || explorerBounds?.contains(position) != true) return false
        val entryUnderPointer = entryBounds.entries.firstOrNull { (_, bounds) -> bounds.contains(position) }?.key
        return entryUnderPointer == null || (!entryUnderPointer.folder && '/' !in entryUnderPointer.path)
    }
    val dropTargetFolder = folderDropTargetAt(dragPosition, draggingEntry)
    val rootDropTarget = draggingEntry != null && rootDropTargetAt(dragPosition)
    LaunchedEffect(draggingEntry?.path, dropTargetFolder?.path) {
        val target = dropTargetFolder ?: return@LaunchedEffect
        if (draggingEntry == null || target.path in expandedFolders) return@LaunchedEffect
        delay(650)
        if (draggingEntry != null && folderDropTargetAt(dragPosition, draggingEntry)?.path == target.path) {
            expandedFolders = expandedFolders + target.path
        }
    }
    explorerAction?.takeIf { it.type == ExplorerActionType.CreateProject }?.let {
        NewProjectDialog(
            onDismiss = { explorerAction = null },
            onConfirm = { name, template ->
                onCreateGeneralProject(name, template)
                explorerAction = null
            },
        )
    }
    explorerAction?.takeIf { it.type != ExplorerActionType.CreateProject }?.let { action ->
        ExplorerPathDialog(
            action = action,
            onDismiss = { explorerAction = null },
            onConfirm = { value ->
                when (action.type) {
                    ExplorerActionType.CreateProject -> Unit
                    ExplorerActionType.CreateFile -> onCreateFile(value)
                    ExplorerActionType.CreateFolder -> onCreateFolder(value)
                    ExplorerActionType.Rename -> onRenamePath(action.path, value, action.folder)
                    ExplorerActionType.Move -> onMovePath(action.path, value, action.folder)
                }
                explorerAction = null
            },
        )
    }
    if (showCloneDialog) {
        CloneRepositoryDialog(
            username = gitHubUsername,
            token = gitHubToken,
            busy = gitBusy,
            error = gitNotice,
            onCredentialsChange = onGitHubCredentialsChange,
            onDismiss = { if (!gitBusy) showCloneDialog = false },
            onClone = onCloneGitHub,
        )
    }
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .clip(if (desktopMode) DexPaneShape else RoundedCornerShape(0.dp))
            .background(Panel)
            .border(
                1.dp,
                Border,
                if (desktopMode) DexPaneShape else RoundedCornerShape(0.dp),
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(destination.label.uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (destination == ActivityDestination.Files && !desktopMode) {
                Box {
                    Text(
                        "•••",
                        color = Foreground,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { showExplorerMenu = true }.padding(5.dp),
                    )
                    DropdownMenu(
                        expanded = showExplorerMenu,
                        onDismissRequest = { showExplorerMenu = false },
                        containerColor = Panel,
                    ) {
                        DropdownMenuItem(
                            text = { Text("Create Project", color = Foreground, fontSize = 12.sp) },
                            onClick = {
                                showExplorerMenu = false
                                explorerAction = ExplorerAction(ExplorerActionType.CreateProject)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Open Project", color = Foreground, fontSize = 12.sp) },
                            onClick = { showExplorerMenu = false; onOpenFolder() },
                        )
                        DropdownMenuItem(
                            text = { Text("Open File", color = Foreground, fontSize = 12.sp) },
                            onClick = { showExplorerMenu = false; onOpenFilePicker() },
                        )
                        DropdownMenuItem(
                            text = { Text("Clone Repository", color = Foreground, fontSize = 12.sp) },
                            onClick = { showExplorerMenu = false; showCloneDialog = true },
                        )
                    }
                }
                Spacer(Modifier.width(7.dp))
            }
            Text("‹", color = Foreground, fontSize = 22.sp, modifier = Modifier.clickable(onClick = onClose))
        }

        when (destination) {
            ActivityDestination.Files -> {
                Box(
                    Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                        val topLeft = coordinates.positionInRoot()
                        explorerBounds = Rect(
                            offset = topLeft,
                            size = androidx.compose.ui.geometry.Size(
                                coordinates.size.width.toFloat(),
                                coordinates.size.height.toFloat(),
                            ),
                        )
                    },
                ) {
                LazyColumn(
                    Modifier.fillMaxSize().pointerInput(desktopMode) {
                        if (!desktopMode) return@pointerInput
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                if (event.type == PointerEventType.Press &&
                                    event.buttons.isSecondaryPressed &&
                                    event.changes.any { !it.isConsumed }
                                ) {
                                    explorerMenuPosition = event.changes.firstOrNull()?.position ?: Offset.Zero
                                    rootMenu = true
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    },
                ) {
                    item(key = "project-header") {
                        val headerColor by animateColorAsState(
                            if (rootDropTarget) Accent.copy(alpha = 0.30f) else Color.Transparent,
                            label = "explorer-root-drop",
                        )
                        Row(
                        Modifier.fillMaxWidth().height(38.dp)
                            .background(headerColor)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "⌄  ${projectName.uppercase()}",
                            color = Foreground,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (!desktopMode) {
                            Text("＋F", color = Muted, fontSize = 11.sp, modifier = Modifier.clickable {
                                explorerAction = ExplorerAction(ExplorerActionType.CreateFile)
                            }.padding(4.dp))
                            Text("＋▱", color = Muted, fontSize = 11.sp, modifier = Modifier.clickable {
                                explorerAction = ExplorerAction(ExplorerActionType.CreateFolder)
                            }.padding(4.dp))
                        }
                    } }
                    items(
                        items = visibleEntries,
                        key = { entry -> "${entry.path}|${entry.folder}" },
                    ) { entry ->
                        DisposableEffect(entry) {
                            onDispose { entryBounds.remove(entry) }
                        }
                        val selected = !entry.folder && entry.path == activeFileName
                        val depth = entry.path.count { it == '/' }
                        val dropTargetRow = draggingEntry != null && dropTargetFolder == entry
                        val dropAreaHighlighted = dropTargetFolder?.let { target ->
                            dropTargetRow ||
                                (target.path in expandedFolders && entry.path.startsWith("${target.path}/"))
                        } == true
                        val rowColor by animateColorAsState(
                            targetValue = when {
                                dropTargetRow -> Accent.copy(alpha = 0.20f)
                                dropAreaHighlighted -> Accent.copy(alpha = 0.10f)
                                selected -> Border
                                hoveredEntry == entry -> Foreground.copy(alpha = 0.07f)
                                else -> Color.Transparent
                            },
                            label = "explorer-row-hover",
                        )
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .background(rowColor)
                                .then(if (dropTargetRow) Modifier.border(1.dp, Accent) else Modifier)
                                .pointerInput("hover", entry) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (!desktopMode || event.changes.none { it.type == PointerType.Mouse }) continue
                                            when (event.type) {
                                                PointerEventType.Enter -> hoveredEntry = entry
                                                PointerEventType.Exit -> if (hoveredEntry == entry) hoveredEntry = null
                                                else -> Unit
                                            }
                                        }
                                    }
                                }
                                .graphicsLayer { alpha = if (draggingEntry == entry) 0.55f else 1f }
                                .onGloballyPositioned { coordinates ->
                                    val topLeft = coordinates.positionInRoot()
                                    entryBounds[entry] = Rect(
                                        offset = topLeft,
                                        size = androidx.compose.ui.geometry.Size(
                                            coordinates.size.width.toFloat(),
                                            coordinates.size.height.toFloat(),
                                        ),
                                    )
                                }
                                .pointerInput(entry) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { localPosition ->
                                            if (entry.editable) {
                                                updateExplorerDrag(
                                                    entry,
                                                    (entryBounds[entry]?.topLeft ?: Offset.Zero) + localPosition,
                                                )
                                            }
                                        },
                                        onDrag = { change, dragAmount ->
                                            if (draggingEntry != null) {
                                                change.consume()
                                                updateExplorerDrag(entry, (dragPosition ?: Offset.Zero) + dragAmount)
                                            }
                                        },
                                        onDragCancel = {
                                            updateExplorerDrag(null, null)
                                        },
                                        onDragEnd = {
                                            val position = dragPosition
                                            val target = folderDropTargetAt(position, entry)
                                            if (draggingEntry != null && target != null) {
                                                onMovePath(entry.path, target.path, entry.folder)
                                            } else if (
                                                draggingEntry != null && !entry.folder && position != null &&
                                                onOpenFileInSplit(entry.path, position)
                                            ) {
                                                // Dropping a source file into the editor creates
                                                // a secondary editor group instead of moving it.
                                            } else if (
                                                draggingEntry != null && position != null && entry.path.contains('/') &&
                                                rootDropTargetAt(position)
                                            ) {
                                                onMovePath(entry.path, "", entry.folder)
                                            }
                                            updateExplorerDrag(null, null)
                                        },
                                    )
                                }
                                // Mouse dragging is recognized by pointer type instead
                                // of connection state. A connected mouse must not stop a
                                // finger from scrolling the Explorer on the phone screen.
                                .pointerInput(entry) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        if (down.type != PointerType.Mouse || !entry.editable) {
                                            waitForUpOrCancellation()
                                            return@awaitEachGesture
                                        }
                                        var lastPosition = down.position
                                        var mouseDrag = false
                                        entryBounds[entry]?.let { dragPosition = it.topLeft + down.position }
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Main)
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) break
                                            val dragAmount = change.position - lastPosition
                                            lastPosition = change.position
                                            if (dragAmount.getDistance() > 0f) {
                                                mouseDrag = true
                                                change.consume()
                                                updateExplorerDrag(entry, (dragPosition ?: Offset.Zero) + dragAmount)
                                            }
                                        }
                                        val position = dragPosition
                                        val target = if (mouseDrag) folderDropTargetAt(position, entry) else null
                                        if (target != null) {
                                            onMovePath(entry.path, target.path, entry.folder)
                                        } else if (
                                            mouseDrag && !entry.folder && position != null &&
                                            onOpenFileInSplit(entry.path, position)
                                        ) {
                                            // Handled by the workspace split editor.
                                        } else if (
                                            mouseDrag && position != null && entry.path.contains('/') &&
                                            rootDropTargetAt(position)
                                        ) {
                                            onMovePath(entry.path, "", entry.folder)
                                        }
                                        updateExplorerDrag(null, null)
                                    }
                                }
                                .pointerInput(entry) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (entry.editable && event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                                val rowTopLeft = entryBounds[entry]?.topLeft ?: Offset.Zero
                                                val panelTopLeft = explorerBounds?.topLeft ?: Offset.Zero
                                                explorerMenuPosition = rowTopLeft +
                                                    (event.changes.firstOrNull()?.position ?: Offset.Zero) - panelTopLeft
                                                itemMenu = entry
                                                event.changes.forEach { it.consume() }
                                            }
                                        }
                                    }
                                }
                                .combinedClickable(
                                    enabled = entry.folder || entry.editable || isInspectableArtifact(entry.path),
                                    onDoubleClick = {
                                        if (entry.editable && !entry.folder) {
                                            explorerAction = ExplorerAction(ExplorerActionType.Rename, entry.path, false)
                                        }
                                    },
                                    onClick = {
                                        if (entry.folder) {
                                            expandedFolders = if (entry.path in expandedFolders) expandedFolders - entry.path else expandedFolders + entry.path
                                        } else onOpenFile(entry.path)
                                    },
                                )
                                .padding(start = (14 + depth * 13).dp, end = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (entry.folder) (if (entry.path in expandedFolders) "⌄ ▱" else "› ▱") else fileGlyph(entry.path),
                                color = if (dropTargetRow || entry.folder) Accent else fileColor(entry.path),
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                entry.path.substringAfterLast('/'),
                                color = Foreground,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (!entry.folder && entry.path in dirtyFiles) {
                                Text("●", color = Color.White, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 5.dp))
                            }
                            if (dropTargetRow) {
                                Text(
                                    if (desktopMode) "MOVE HERE" else "↳",
                                    color = Accent,
                                    fontSize = if (desktopMode) 9.sp else 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp),
                                )
                            }
                            if (entry.editable) Box {
                                if (!mouseConnected) {
                                    Text(
                                        "⋮",
                                        color = Muted,
                                        fontSize = 16.sp,
                                        modifier = Modifier.clickable { itemMenu = entry }.padding(horizontal = 7.dp),
                                    )
                                }
                                if (!desktopMode) {
                                    DropdownMenu(
                                        expanded = itemMenu == entry,
                                        onDismissRequest = { itemMenu = null },
                                        containerColor = Panel,
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Copy", color = Foreground, fontSize = 12.sp) },
                                            onClick = { itemMenu = null; clipboardEntry = entry; clipboardCut = false },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Cut", color = Foreground, fontSize = 12.sp) },
                                            onClick = { itemMenu = null; clipboardEntry = entry; clipboardCut = true },
                                        )
                                        if (entry.folder && clipboardEntry != null) {
                                            DropdownMenuItem(
                                                text = { Text("Paste", color = Foreground, fontSize = 12.sp) },
                                                onClick = {
                                                    val copied = clipboardEntry ?: return@DropdownMenuItem
                                                    itemMenu = null
                                                    if (clipboardCut) onMovePath(copied.path, entry.path, copied.folder)
                                                    else onCopyPath(copied.path, entry.path, copied.folder)
                                                    if (clipboardCut) clipboardEntry = null
                                                },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Rename", color = Foreground, fontSize = 12.sp) },
                                            onClick = {
                                                itemMenu = null
                                                explorerAction = ExplorerAction(ExplorerActionType.Rename, entry.path, entry.folder)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete", color = Color(0xFFFF8A80), fontSize = 12.sp) },
                                            onClick = { itemMenu = null; onDeletePath(entry.path, entry.folder) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (rootMenu) {
                    ExplorerPointerMenu(
                        position = explorerMenuPosition,
                        onDismiss = { rootMenu = false },
                        entries = buildList {
                            add(ExplorerPointerMenuEntry("New File") {
                                rootMenu = false
                                explorerAction = ExplorerAction(ExplorerActionType.CreateFile)
                            })
                            add(ExplorerPointerMenuEntry("New Folder") {
                                rootMenu = false
                                explorerAction = ExplorerAction(ExplorerActionType.CreateFolder)
                            })
                            clipboardEntry?.let { copied ->
                                add(ExplorerPointerMenuEntry("Paste") {
                                    rootMenu = false
                                    if (clipboardCut) onMovePath(copied.path, "", copied.folder)
                                    else onCopyPath(copied.path, "", copied.folder)
                                    if (clipboardCut) clipboardEntry = null
                                })
                            }
                        },
                    )
                }
                if (desktopMode) itemMenu?.let { entry ->
                    ExplorerPointerMenu(
                        position = explorerMenuPosition,
                        onDismiss = { itemMenu = null },
                        entries = buildList {
                            add(ExplorerPointerMenuEntry("Copy") {
                                itemMenu = null
                                clipboardEntry = entry
                                clipboardCut = false
                            })
                            add(ExplorerPointerMenuEntry("Cut") {
                                itemMenu = null
                                clipboardEntry = entry
                                clipboardCut = true
                            })
                            if (entry.folder && clipboardEntry != null) add(ExplorerPointerMenuEntry("Paste") {
                                val copied = clipboardEntry ?: return@ExplorerPointerMenuEntry
                                itemMenu = null
                                if (clipboardCut) onMovePath(copied.path, entry.path, copied.folder)
                                else onCopyPath(copied.path, entry.path, copied.folder)
                                if (clipboardCut) clipboardEntry = null
                            })
                            add(ExplorerPointerMenuEntry("Rename") {
                                itemMenu = null
                                explorerAction = ExplorerAction(ExplorerActionType.Rename, entry.path, entry.folder)
                            })
                            add(ExplorerPointerMenuEntry("Delete", destructive = true) {
                                itemMenu = null
                                onDeletePath(entry.path, entry.folder)
                            })
                        },
                    )
                }
                }
            }

            ActivityDestination.Extensions -> ExtensionsPanel(
                cppInfo = cppExtensionInfo,
                picoInfo = picoExtensionInfo,
                pythonInfo = pythonExtensionInfo,
                rustInfo = rustExtensionInfo,
                gnuLanguagesInfo = gnuLanguagesExtensionInfo,
                webInfo = webExtensionInfo,
                gitInfo = gitToolsExtensionInfo,
                busy = extensionBusy,
                notice = extensionNotice,
                serverConfigured = extensionServerConfigured,
                onInstallFromServer = onInstallExtension,
                onInstallCppFromServer = onInstallCppExtension,
                onInstallPythonFromServer = onInstallPythonExtension,
                onInstallRustFromServer = onInstallRustExtension,
                onInstallGnuLanguagesFromServer = onInstallGnuLanguagesExtension,
                onInstallWebFromServer = onInstallWebExtension,
                onInstallGitFromServer = onInstallGitToolsExtension,
                onInstallFromFile = onInstallExtensionFromFile,
                onOpenDetails = onOpenPicoExtensionDetails,
                onOpenCppDetails = onOpenCppExtensionDetails,
                onOpenPythonDetails = onOpenPythonExtensionDetails,
                onOpenRustDetails = onOpenRustExtensionDetails,
                onOpenGnuLanguagesDetails = onOpenGnuLanguagesExtensionDetails,
                onOpenWebDetails = onOpenWebExtensionDetails,
                onOpenGitDetails = onOpenGitExtensionDetails,
            )

            ActivityDestination.Pico -> if (picoExtensionInfo == null) {
                PicoExtensionRequiredPanel(onOpenExtensions)
            } else {
                PicoQuickAccessPanel(
                    picoProjectOpen = picoProjectOpen,
                    rustProjectOpen = rustProjectOpen,
                    microPythonProjectOpen = microPythonProjectOpen,
                    configuration = picoConfiguration ?: PicoProjectConfiguration(PicoBoard.Pico),
                    cmakeAnalysis = analyzePicoCMake(files.firstOrNull { it.name == "CMakeLists.txt" }?.content.orEmpty()).let { analysis ->
                        analysis.copy(targets = (analysis.targets + picoCMakeTargets).distinct())
                    },
                    pythonAvailable = pythonExtensionInfo != null,
                    rustAvailable = rustExtensionInfo != null,
                    building = building,
                    cleaning = cleaningPicoBuild,
                    onCreateProject = onCreatePicoProject,
                    onCreateExample = onCreatePicoExample,
                    onImportProject = onOpenFolder,
                    onCompile = runDebugActions.compilePico,
                    onStop = runDebugActions.stop,
                    onRunUsb = onRunPicoUsb,
                    onClean = onCleanPico,
                    onConfigureCMake = onConfigurePicoCMake,
                    onConfigure = onConfigurePico,
                    onManageComponents = onOpenPicoExtensionDetails,
                    onOpenHardwareApis = onOpenPicoHardwareApis,
                    onOpenHighLevelApis = onOpenPicoHighLevelApis,
                    onOpenNetworkingLibraries = onOpenPicoNetworkingLibraries,
                    onOpenRuntimeInfrastructure = onOpenPicoRuntimeInfrastructure,
                    onOpenSdkReference = onOpenPicoSdkReference,
                )
            }

            ActivityDestination.Search -> SearchPanel(files = files, onNavigateToFile = onNavigateToFile)
            ActivityDestination.SourceControl -> SourceControlPanel(
                projectName = projectName,
                state = gitState,
                busy = gitBusy,
                notice = gitNotice,
                username = gitHubUsername,
                token = gitHubToken,
                onOpenFile = onOpenFile,
                onInitialize = onInitializeGit,
                onCommit = onCommit,
                onRefresh = onRefreshGit,
                onCredentialsChange = onGitHubCredentialsChange,
                onPull = onPull,
                onPush = onPush,
                onPublish = onPublish,
            )
            ActivityDestination.Run -> RunDebugPanel(
                projectName = projectName,
                files = files,
                building = building,
                picoProject = picoProjectOpen,
                actions = runDebugActions,
            )
        }
    }
}
}

private data class ExplorerPointerMenuEntry(
    val label: String,
    val destructive: Boolean = false,
    val action: () -> Unit,
)

@Composable
private fun ExplorerPointerMenu(
    position: Offset,
    entries: List<ExplorerPointerMenuEntry>,
    onDismiss: () -> Unit,
) {
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(position.x.toInt(), position.y.toInt()),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.width(180.dp).background(Panel).border(1.dp, Border).padding(vertical = 4.dp),
        ) {
            entries.forEach { entry ->
                Text(
                    entry.label,
                    color = if (entry.destructive) Color(0xFFFF8A80) else Foreground,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth()
                        .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HAND))
                        .clickable(onClick = entry.action)
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                )
            }
        }
    }
}
