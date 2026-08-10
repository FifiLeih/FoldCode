package dev.foldcode.ide

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
internal fun TopBar(
    mode: LayoutMode,
    compactControls: Boolean,
    desktopWorkspace: Boolean,
    sidePanelVisible: Boolean,
    showMenu: Boolean,
    terminalVisible: Boolean,
    building: Boolean,
    showBuildStatistics: Boolean,
    buildStatistics: String,
    onToggleBuildStatistics: () -> Unit,
    debugActions: DebugToolbarActions,
    onMenu: () -> Unit,
    onToggleSidebar: () -> Unit,
    onToggleTerminal: () -> Unit,
    onRun: () -> Unit,
    onDebug: () -> Unit,
    onStop: () -> Unit,
    onNewTerminal: () -> Unit,
    onSettings: () -> Unit,
    wordWrap: Boolean,
    onToggleWordWrap: () -> Unit,
    onCloseEditor: () -> Unit,
    onShowDestination: (ActivityDestination) -> Unit,
    onSwitchEditor: (Int) -> Unit,
    splitEditorVisible: Boolean,
    canSplitEditor: Boolean,
    onToggleSplitEditor: () -> Unit,
    onDesktopExplorerCommand: (DesktopExplorerCommand) -> Unit,
    auxiliaryPaneAvailable: Boolean,
    auxiliaryPaneVisible: Boolean,
    onToggleAuxiliaryPane: () -> Unit,
) {
    if (desktopWorkspace) {
        DesktopMenuBar(
            building = building,
            showBuildStatistics = showBuildStatistics,
            buildStatistics = buildStatistics,
            onToggleBuildStatistics = onToggleBuildStatistics,
            terminalVisible = terminalVisible,
            sidePanelVisible = sidePanelVisible,
            debugActions = debugActions,
            onRun = onRun,
            onDebug = onDebug,
            onStop = onStop,
            onToggleSidebar = onToggleSidebar,
            onToggleTerminal = onToggleTerminal,
            onNewTerminal = onNewTerminal,
            onSettings = onSettings,
            wordWrap = wordWrap,
            onToggleWordWrap = onToggleWordWrap,
            onCloseEditor = onCloseEditor,
            onShowDestination = onShowDestination,
            onSwitchEditor = onSwitchEditor,
            splitEditorVisible = splitEditorVisible,
            canSplitEditor = canSplitEditor,
            onToggleSplitEditor = onToggleSplitEditor,
            onExplorerCommand = onDesktopExplorerCommand,
            auxiliaryPaneAvailable = auxiliaryPaneAvailable,
            auxiliaryPaneVisible = auxiliaryPaneVisible,
            onToggleAuxiliaryPane = onToggleAuxiliaryPane,
        )
        return
    }
    val barHeight = if (desktopWorkspace) 40.dp else when (mode) {
        LayoutMode.Compact -> 50.dp
        LayoutMode.Medium -> 54.dp
        LayoutMode.Expanded -> 58.dp
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight)
            .background(Panel)
            .border(1.dp, Border)
            .padding(horizontal = if (compactControls) 8.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(if (compactControls) 28.dp else 30.dp)
                .background(if (showMenu) Rail else Accent)
                .clickable(enabled = showMenu, onClick = onMenu),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (showMenu) "☰" else "F", color = Color.White, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(if (compactControls) 6.dp else 10.dp))
        if (!compactControls) {
            Text("FOLDCODE", color = Foreground, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
        Spacer(Modifier.weight(1f))
        BuildActions(
            compact = compactControls,
            building = building,
            showBuildStatistics = showBuildStatistics,
            buildStatistics = buildStatistics,
            onToggleBuildStatistics = onToggleBuildStatistics,
            debugActions = debugActions,
            terminalVisible = terminalVisible,
            sidePanelVisible = sidePanelVisible,
            onRun = onRun,
            onStop = onStop,
            onToggleTerminal = onToggleTerminal,
            onToggleSidebar = onToggleSidebar,
        )
    }
}

internal enum class DesktopExplorerCommand {
    CreateProject, CreateFile, CreateFolder,
    OpenProject, OpenFile, CloneRepository,
}

private data class DesktopMenuEntry(
    val label: String,
    val enabled: Boolean = true,
    val action: () -> Unit,
)

@Composable
private fun DesktopMenuBar(
    building: Boolean,
    showBuildStatistics: Boolean,
    buildStatistics: String,
    onToggleBuildStatistics: () -> Unit,
    terminalVisible: Boolean,
    sidePanelVisible: Boolean,
    debugActions: DebugToolbarActions,
    onRun: () -> Unit,
    onDebug: () -> Unit,
    onStop: () -> Unit,
    onToggleSidebar: () -> Unit,
    onToggleTerminal: () -> Unit,
    onNewTerminal: () -> Unit,
    onSettings: () -> Unit,
    wordWrap: Boolean,
    onToggleWordWrap: () -> Unit,
    onCloseEditor: () -> Unit,
    onShowDestination: (ActivityDestination) -> Unit,
    onSwitchEditor: (Int) -> Unit,
    splitEditorVisible: Boolean,
    canSplitEditor: Boolean,
    onToggleSplitEditor: () -> Unit,
    onExplorerCommand: (DesktopExplorerCommand) -> Unit,
    auxiliaryPaneAvailable: Boolean,
    auxiliaryPaneVisible: Boolean,
    onToggleAuxiliaryPane: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(start = 4.dp, top = 4.dp, end = 4.dp)
            .clip(DexPaneShape)
            .background(Panel)
            .border(1.dp, Border, DexPaneShape)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DesktopMenu(
            "File",
            listOf(
                DesktopMenuEntry("New Project") { onExplorerCommand(DesktopExplorerCommand.CreateProject) },
                DesktopMenuEntry("New File") { onExplorerCommand(DesktopExplorerCommand.CreateFile) },
                DesktopMenuEntry("New Folder") { onExplorerCommand(DesktopExplorerCommand.CreateFolder) },
                DesktopMenuEntry("Open Project") { onExplorerCommand(DesktopExplorerCommand.OpenProject) },
                DesktopMenuEntry("Open File") { onExplorerCommand(DesktopExplorerCommand.OpenFile) },
                DesktopMenuEntry("Clone Repository") { onExplorerCommand(DesktopExplorerCommand.CloneRepository) },
                DesktopMenuEntry("Save", debugActions.saveActions.canSaveCurrent, debugActions.saveActions.onSave),
                DesktopMenuEntry("Save All", debugActions.saveActions.hasUnsavedChanges, debugActions.saveActions.onSaveAll),
                DesktopMenuEntry("Close Editor", debugActions.saveActions.activeFileName.isNotBlank(), onCloseEditor),
            ),
        )
        DesktopMenu(
            "Edit",
            listOf(
                DesktopMenuEntry("Undo", ActiveEditorHistory.canUndo(), ActiveEditorHistory::undo),
                DesktopMenuEntry("Redo", ActiveEditorHistory.canRedo(), ActiveEditorHistory::redo),
            ),
        )
        DesktopMenu("Selection", listOf(DesktopMenuEntry("Select All", action = ActiveEditorHistory::selectAll)))
        DesktopMenu(
            "View",
            listOf(
                DesktopMenuEntry("Explorer") { onShowDestination(ActivityDestination.Files) },
                DesktopMenuEntry("Search") { onShowDestination(ActivityDestination.Search) },
                DesktopMenuEntry("Source Control") { onShowDestination(ActivityDestination.SourceControl) },
                DesktopMenuEntry("Run and Debug") { onShowDestination(ActivityDestination.Run) },
                DesktopMenuEntry("Extensions") { onShowDestination(ActivityDestination.Extensions) },
                DesktopMenuEntry(if (sidePanelVisible) "Hide Side Panel" else "Show Side Panel", action = onToggleSidebar),
                DesktopMenuEntry(if (terminalVisible) "Hide Terminal" else "Show Terminal", action = onToggleTerminal),
                DesktopMenuEntry(
                    if (showBuildStatistics) "Hide Build Statistics" else "Show Build Statistics",
                    action = onToggleBuildStatistics,
                ),
                DesktopMenuEntry(if (wordWrap) "Disable Word Wrap" else "Enable Word Wrap", action = onToggleWordWrap),
                DesktopMenuEntry(
                    if (splitEditorVisible) "Close Secondary Editor" else "Split Editor Right",
                    splitEditorVisible || canSplitEditor,
                    onToggleSplitEditor,
                ),
            ),
        )
        DesktopMenu(
            "Go",
            listOf(
                DesktopMenuEntry("Previous Editor") { onSwitchEditor(-1) },
                DesktopMenuEntry("Next Editor") { onSwitchEditor(1) },
                DesktopMenuEntry("Go to File") { onShowDestination(ActivityDestination.Search) },
            ),
        )
        DesktopMenu(
            "Run",
            listOf(
                DesktopMenuEntry("Start Debugging", !building && !debugActions.visible, onDebug),
                DesktopMenuEntry("Run Without Debugging", !building && !debugActions.visible, onRun),
                DesktopMenuEntry("Stop", building || debugActions.visible, if (debugActions.visible) debugActions.onStop else onStop),
                DesktopMenuEntry("Continue", debugActions.status == DebugSessionStatus.Paused, debugActions.onContinue),
                DesktopMenuEntry("Step Over", debugActions.status == DebugSessionStatus.Paused, debugActions.onStepOver),
                DesktopMenuEntry("Step Into", debugActions.status == DebugSessionStatus.Paused, debugActions.onStepInto),
                DesktopMenuEntry("Step Out", debugActions.status == DebugSessionStatus.Paused, debugActions.onStepOut),
                DesktopMenuEntry("Restart Debugging", debugActions.visible, debugActions.onRestart),
            ),
        )
        DesktopMenu(
            "Terminal",
            listOf(
                DesktopMenuEntry("New Terminal", action = onNewTerminal),
                DesktopMenuEntry(if (terminalVisible) "Hide Terminal Panel" else "Show Terminal Panel", action = onToggleTerminal),
            ),
        )
        DesktopMenu("Window", listOf(DesktopMenuEntry("Settings", action = onSettings)))
        Spacer(Modifier.weight(1f))
        BuildActions(
            compact = true,
            building = building,
            showBuildStatistics = showBuildStatistics,
            buildStatistics = buildStatistics,
            onToggleBuildStatistics = onToggleBuildStatistics,
            debugActions = debugActions,
            terminalVisible = terminalVisible,
            sidePanelVisible = sidePanelVisible,
            onRun = onRun,
            onStop = onStop,
            onToggleTerminal = onToggleTerminal,
            onToggleSidebar = onToggleSidebar,
            showOverflow = false,
        )
        if (auxiliaryPaneAvailable) {
            Spacer(Modifier.width(6.dp))
            AuxiliaryPaneToggleButton(
                visible = auxiliaryPaneVisible,
                onClick = onToggleAuxiliaryPane,
            )
        }
    }
}

@Composable
private fun AuxiliaryPaneToggleButton(visible: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (visible) Accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HAND)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(17.dp)
                .height(14.dp)
                .border(1.dp, Foreground, RoundedCornerShape(3.dp)),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(5.dp)
                    .background(if (visible) Accent else Foreground.copy(alpha = 0.28f)),
            )
        }
    }
}

@Composable
private fun DesktopMenu(label: String, entries: List<DesktopMenuEntry>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text(
            label,
            color = Foreground,
            fontSize = 11.sp,
            fontWeight = if (label == "Code") FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HAND))
                .clickable(enabled = entries.isNotEmpty()) { expanded = true }
                .padding(horizontal = 8.dp, vertical = 7.dp),
        )
        val menuSurface = Color(0xFF1D2129)
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(menuSurface),
            containerColor = menuSurface,
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
        ) {
            entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label, color = if (entry.enabled) Foreground else Muted, fontSize = 12.sp) },
                    enabled = entry.enabled,
                    onClick = { expanded = false; entry.action() },
                )
            }
        }
    }
}

internal fun androidx.compose.ui.input.key.KeyEvent.matchesShortcut(binding: String): Boolean {
    if (binding.isBlank()) return false
    val parts = binding.split('+').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return false
    val modifiers = parts.dropLast(1).map { it.lowercase() }.toSet()
    val native = nativeKeyEvent
    if (native.isCtrlPressed != ("ctrl" in modifiers || "control" in modifiers)) return false
    if (native.isShiftPressed != ("shift" in modifiers)) return false
    if (native.isAltPressed != ("alt" in modifiers || "option" in modifiers)) return false
    if (native.isMetaPressed != ("meta" in modifiers || "cmd" in modifiers || "command" in modifiers)) return false
    val keyName = parts.last().trim()
    val androidName = when (keyName.lowercase()) {
        "`", "grave" -> "KEYCODE_GRAVE"
        "escape", "esc" -> "KEYCODE_ESCAPE"
        "enter", "return" -> "KEYCODE_ENTER"
        "space" -> "KEYCODE_SPACE"
        "tab" -> "KEYCODE_TAB"
        else -> "KEYCODE_${keyName.uppercase()}"
    }
    return native.keyCode == android.view.KeyEvent.keyCodeFromString(androidName)
}

@Composable
internal fun FloatingBuildActions(
    visible: Boolean,
    compact: Boolean,
    context: WorkspaceToolbarContext,
    building: Boolean,
    showBuildStatistics: Boolean,
    buildStatistics: String,
    onToggleBuildStatistics: () -> Unit,
    terminalVisible: Boolean,
    sidePanelVisible: Boolean,
    debugActions: DebugToolbarActions,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onToggleTerminal: () -> Unit,
    onToggleSidebar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Box(modifier.fillMaxWidth().height(50.dp).background(Panel).border(1.dp, Border)) {
        Text(
            context.target,
            color = if (building) Accent else Muted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp),
        )
        BuildActions(
            compact = compact,
            building = building,
            showBuildStatistics = showBuildStatistics,
            buildStatistics = buildStatistics,
            onToggleBuildStatistics = onToggleBuildStatistics,
            terminalVisible = terminalVisible,
            sidePanelVisible = sidePanelVisible,
            debugActions = debugActions,
            onRun = onRun,
            onStop = onStop,
            onToggleTerminal = onToggleTerminal,
            onToggleSidebar = onToggleSidebar,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

internal data class WorkspaceToolbarContext(val target: String)

internal fun workspaceToolbarContext(
    sources: Map<String, String>,
    activeFileName: String,
    building: Boolean,
    programRunning: Boolean,
): WorkspaceToolbarContext {
    val language = when (activeFileName.substringAfterLast('.', "").lowercase()) {
        "c" -> "C"
        "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx" -> "C++"
        "py" -> if (isPicoMicroPythonProject(sources)) "MicroPython" else "Python"
        "rs" -> "Rust"
        "f", "f77", "f90", "f95", "f03", "f08" -> "Fortran"
        "cob", "cbl" -> "COBOL"
        "s", "asm" -> "Assembly"
        "cmake", "txt" -> if (activeFileName.endsWith("CMakeLists.txt", true)) "CMake" else "Text"
        else -> "Workspace"
    }
    val target = when {
        isPicoMicroPythonProject(sources) -> "${picoBoard(sources).displayName} · MicroPython"
        isPicoRustProject(sources) -> "${picoBoard(sources).displayName} · Rust"
        isPicoSdkProject(sources) -> "${picoBoard(sources).displayName} · $language"
        else -> language
    }
    val state = when {
        programRunning -> "RUNNING"
        building -> "BUILDING"
        else -> null
    }
    return WorkspaceToolbarContext(target = state?.let { "$it · $target" } ?: target)
}

@Composable
private fun BuildActions(
    compact: Boolean,
    building: Boolean,
    showBuildStatistics: Boolean,
    buildStatistics: String,
    onToggleBuildStatistics: () -> Unit,
    debugActions: DebugToolbarActions,
    terminalVisible: Boolean,
    sidePanelVisible: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onToggleTerminal: () -> Unit,
    onToggleSidebar: () -> Unit,
    showOverflow: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val saveActions = debugActions.saveActions
    val focusManager = LocalFocusManager.current
    var actionsExpanded by remember { mutableStateOf(false) }
    var saveExpanded by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (debugActions.visible) {
            DebugControls(compact = compact, actions = debugActions)
        }
        if (buildStatistics.isNotBlank()) {
            Text(
                buildStatistics,
                color = Muted,
                fontSize = if (compact) 9.sp else 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = if (compact) 220.dp else 250.dp),
            )
        }
        Text(
            if (building) "■" else "▷",
            color = if (building) Color(0xFFFF8A80) else Foreground,
            fontSize = if (compact) 19.sp else 20.sp,
            modifier = Modifier.pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HAND))
                .clickable {
                    focusManager.clearFocus(force = true)
                    if (building) onStop() else onRun()
                }.padding(horizontal = 8.dp, vertical = 5.dp),
        )
        val canUndo = ActiveEditorHistory.canUndo()
        val canRedo = ActiveEditorHistory.canRedo()
        Text(
            "↶",
            color = if (canUndo) Foreground else Muted.copy(alpha = 0.45f),
            fontSize = if (compact) 18.sp else 19.sp,
            modifier = Modifier.pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HAND))
                .clickable(enabled = canUndo, onClick = ActiveEditorHistory::undo)
                .padding(horizontal = 6.dp, vertical = 5.dp),
        )
        Text(
            "↷",
            color = if (canRedo) Foreground else Muted.copy(alpha = 0.45f),
            fontSize = if (compact) 18.sp else 19.sp,
            modifier = Modifier.pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HAND))
                .clickable(enabled = canRedo, onClick = ActiveEditorHistory::redo)
                .padding(horizontal = 6.dp, vertical = 5.dp),
        )
        if (saveActions.enabled) Box {
            Text(
                "SAVE",
                color = if (saveActions.hasUnsavedChanges) Foreground else Muted,
                fontSize = if (compact) 9.sp else 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HAND))
                    .clickable { saveExpanded = true }
                    .padding(horizontal = 7.dp, vertical = 8.dp),
            )
            DropdownMenu(
                expanded = saveExpanded,
                onDismissRequest = { saveExpanded = false },
                containerColor = Panel,
            ) {
                DropdownMenuItem(
                    text = { Text("Save", color = if (saveActions.canSaveCurrent) Foreground else Muted) },
                    enabled = saveActions.canSaveCurrent,
                    onClick = { saveExpanded = false; saveActions.onSave() },
                )
                DropdownMenuItem(
                    text = { Text("Save All", color = if (saveActions.hasUnsavedChanges) Foreground else Muted) },
                    enabled = saveActions.hasUnsavedChanges,
                    onClick = { saveExpanded = false; saveActions.onSaveAll() },
                )
            }
        }
        if (showOverflow) Box {
            Text(
                "⋯",
                color = Foreground,
                fontSize = 21.sp,
                modifier = Modifier.pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HAND))
                    .clickable { actionsExpanded = true }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
            DropdownMenu(
                expanded = actionsExpanded,
                onDismissRequest = { actionsExpanded = false },
                containerColor = Panel,
            ) {
                DropdownMenuItem(
                    text = { Text(if (building) "Stop build" else "Compile project", color = Foreground) },
                    onClick = {
                        actionsExpanded = false
                        if (building) onStop() else onRun()
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (terminalVisible) "Hide terminal" else "Show terminal", color = Foreground) },
                    onClick = { actionsExpanded = false; onToggleTerminal() },
                )
                DropdownMenuItem(
                    text = { Text(if (sidePanelVisible) "Hide side panel" else "Show side panel", color = Foreground) },
                    onClick = { actionsExpanded = false; onToggleSidebar() },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (showBuildStatistics) "Hide build statistics" else "Show build statistics",
                            color = Foreground,
                        )
                    },
                    onClick = { actionsExpanded = false; onToggleBuildStatistics() },
                )
            }
        }
    }
}

@Composable
internal fun ActivityRail(
    selected: ActivityDestination,
    desktopMode: Boolean,
    terminalVisible: Boolean,
    onToggleTerminal: () -> Unit,
    onSettings: () -> Unit,
    onSelect: (ActivityDestination) -> Unit,
) {
    val shape = if (desktopMode) DexPaneShape else RoundedCornerShape(0.dp)
    Column(
        Modifier
            .width(46.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(Rail)
            .border(1.dp, Border, shape),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ActivityDestination.entries.forEach { destination ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HAND))
                    .clickable { onSelect(destination) },
                contentAlignment = Alignment.Center,
            ) {
                if (destination == selected) {
                    Box(Modifier.align(Alignment.CenterStart).width(3.dp).fillMaxHeight().background(Accent))
                }
                Text(
                    destination.glyph,
                    color = if (destination == selected) Foreground else Muted,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Light,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (terminalVisible) "⌄" else "⌃",
            color = if (terminalVisible) Foreground else Muted,
            fontSize = 19.sp,
            modifier = Modifier
                .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HAND))
                .clickable(onClick = onToggleTerminal)
                .padding(horizontal = 10.dp, vertical = 9.dp),
        )
        Text(
            "⚙",
            color = Muted,
            fontSize = 21.sp,
            modifier = Modifier
                .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HAND))
                .clickable(onClick = onSettings)
                .padding(horizontal = 10.dp, vertical = 9.dp),
        )
    }
}
