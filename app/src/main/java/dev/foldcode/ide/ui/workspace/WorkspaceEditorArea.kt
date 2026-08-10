package dev.foldcode.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DeferredCompose(content: @Composable () -> Unit) {
    content()
}

internal val DexPaneShape = RoundedCornerShape(7.dp)

/**
 * Before a split exists, broad side regions create one while the editor centre
 * remains a neutral drop area. Once split, each complete pane is a destination.
 */
internal fun editorDropTarget(
    position: Offset,
    bounds: Rect,
    splitEditor: Boolean,
    splitRatio: Float,
): EditorGroup? {
    if (!bounds.contains(position)) return null
    if (splitEditor) {
        return if (position.x < editorDividerX(bounds, splitRatio)) {
            EditorGroup.Primary
        } else {
            EditorGroup.Secondary
        }
    }

    val sideWidth = bounds.width * 0.32f
    return when {
        position.x <= bounds.left + sideWidth -> EditorGroup.Primary
        position.x >= bounds.right - sideWidth -> EditorGroup.Secondary
        else -> null
    }
}

private fun editorDividerX(bounds: Rect, splitRatio: Float): Float =
    bounds.left + bounds.width * splitRatio.coerceIn(0.20f, 0.80f)

internal fun editorGroupBounds(
    bounds: Rect,
    group: EditorGroup,
    splitEditor: Boolean,
    splitRatio: Float,
): Rect {
    val divider = if (splitEditor) editorDividerX(bounds, splitRatio) else bounds.center.x
    return if (group == EditorGroup.Primary) {
        Rect(bounds.left, bounds.top, divider, bounds.bottom)
    } else {
        Rect(divider, bounds.top, bounds.right, bounds.bottom)
    }
}

private fun Modifier.dexPane(enabled: Boolean): Modifier = if (enabled) {
    clip(DexPaneShape).border(1.dp, Border, DexPaneShape)
} else {
    this
}

@Composable
internal fun DexBrowserPane(
    file: ProjectFile,
    mode: LayoutMode,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(DexPaneShape)
            .background(Panel)
            .border(1.dp, Border, DexPaneShape),
    ) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (file.kind == ProjectFileKind.WebPreview) "Web Browser" else file.name,
                color = Foreground,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "×",
                color = Muted,
                fontSize = 18.sp,
                modifier = Modifier
                    .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HAND))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 4.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (file.kind) {
                ProjectFileKind.Documentation -> PicoDocumentationBrowser(
                    documentName = file.name,
                    offlineFallback = file.content,
                    mode = mode,
                    modifier = Modifier.fillMaxSize(),
                )
                ProjectFileKind.WebPreview -> WebPreviewBrowser(
                    url = file.content,
                    mode = mode,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Unit
            }
        }
    }
}

@Composable
internal fun WorkArea(
    files: Map<String, ProjectFile>,
    dirtyFiles: Set<String>,
    openTabs: List<String>,
    activeFileName: String,
    splitEditor: Boolean,
    secondaryActiveFileName: String,
    secondaryOpenTabs: List<String>,
    navigationRequest: EditorNavigationRequest?,
    terminal: String,
    buildOutput: String,
    diagnosticsOutput: String,
    terminalPrompt: String,
    terminalSessions: List<TerminalSessionInfo>,
    selectedTerminalId: Int,
    semanticCompletions: List<CompletionItem>,
    semanticDiagnostics: List<CodeDiagnostic>,
    onRequestSemanticCompletion: (String, String, Int) -> Unit,
    onRequestSemanticDiagnostics: (String, String) -> Unit,
    cppExtensionInfo: FoldCodeExtensionInfo?,
    picoExtensionInfo: FoldCodeExtensionInfo?,
    pythonExtensionInfo: FoldCodeExtensionInfo?,
    rustExtensionInfo: FoldCodeExtensionInfo?,
    gnuLanguagesExtensionInfo: FoldCodeExtensionInfo?,
    webExtensionInfo: FoldCodeExtensionInfo?,
    gitExtensionInfo: FoldCodeExtensionInfo?,
    gnuArmExtensionInfo: FoldCodeExtensionInfo?,
    extensionBusy: Boolean,
    extensionNotice: String?,
    extensionServerConfigured: Boolean,
    mode: LayoutMode,
    editorScale: Float,
    secondaryEditorScale: Float,
    terminalScale: Float,
    splitRatio: Float,
    onSplitRatioChange: (Float) -> Unit,
    onEditorZoomDelta: (EditorGroup, Float) -> Unit,
    preferences: IdePreferences,
    shortLandscape: Boolean,
    desktopWorkspace: Boolean,
    terminalVisible: Boolean,
    terminalHeightValue: Float,
    terminalWidthValue: Float,
    onTerminalHeightChange: (Float) -> Unit,
    onTerminalWidthChange: (Float) -> Unit,
    consoleActive: Boolean,
    terminalInputVisible: Boolean,
    onConsoleInput: (String) -> Unit,
    onNewTerminal: () -> Unit,
    onSelectTerminal: (Int) -> Unit,
    onDeleteTerminal: (Int) -> Unit,
    onActivateFile: (String) -> Unit,
    onActivateSecondaryFile: (String) -> Unit,
    onCloseSecondaryFile: (String) -> Unit,
    onFileDragChanged: (String?, Offset?, EditorGroup?) -> Unit,
    onRequestSplitFile: (String, Offset?, EditorGroup) -> Unit,
    dropPreviewGroup: EditorGroup?,
    onEditorBoundsChanged: (Rect) -> Unit,
    onCloseFile: (String) -> Unit,
    onFileChange: (String, String) -> Unit,
    onEditorFocusChanged: (Boolean) -> Unit,
    onInstallPicoExtension: () -> Unit,
    onInstallCppExtension: () -> Unit,
    onInstallPythonExtension: () -> Unit,
    onInstallRustExtension: () -> Unit,
    onInstallGnuLanguagesExtension: () -> Unit,
    onInstallGitExtension: () -> Unit,
    onInstallGnuArmExtension: () -> Unit,
    onInstallPicoExtensionFromFile: () -> Unit,
    onPreparePicoComponents: (Set<String>) -> Unit,
    onDeletePicoComponents: (Set<String>) -> Unit,
    onUninstallPicoExtension: () -> Unit,
    onUninstallCppExtension: () -> Unit,
    onUninstallPythonExtension: () -> Unit,
    onUninstallRustExtension: () -> Unit,
    onUninstallGnuLanguagesExtension: () -> Unit,
    onInstallWebExtension: () -> Unit,
    onUninstallWebExtension: () -> Unit,
    onUninstallGitExtension: () -> Unit,
    onUninstallGnuArmExtension: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeFile = files[activeFileName]
    val secondaryActiveFile = files[secondaryActiveFileName]
    val density = LocalDensity.current
    val latestTerminalHeight by rememberUpdatedState(terminalHeightValue)
    val latestTerminalWidth by rememberUpdatedState(terminalWidthValue)
    if (shortLandscape) {
        BoxWithConstraints(modifier.fillMaxHeight()) {
            val maximumTerminalWidth = (maxWidth * 0.65f).coerceAtLeast(240.dp)
            val terminalWidth = terminalWidthValue.dp.coerceIn(240.dp, maximumTerminalWidth)
            Row(Modifier.fillMaxSize()) {
                EditorArea(
                    files = files,
                    dirtyFiles = dirtyFiles,
                    openTabs = openTabs,
                    activeFile = activeFile,
                    mode = mode,
                    desktopMode = desktopWorkspace,
                    editorScale = editorScale,
                    secondaryEditorScale = secondaryEditorScale,
                    editorFontSizeSp = preferences.editorFontSizeSp,
                    tabWidth = preferences.tabWidth,
                    indentWithSpaces = preferences.indentWithSpaces,
                    wordWrap = preferences.wordWrap,
                    navigationRequest = navigationRequest,
                    splitEditor = splitEditor,
                    secondaryActiveFile = secondaryActiveFile,
                    secondaryOpenTabs = secondaryOpenTabs,
                    splitRatio = splitRatio,
                    onSplitRatioChange = onSplitRatioChange,
                    onEditorZoomDelta = onEditorZoomDelta,
                    onActivateSecondaryFile = onActivateSecondaryFile,
                    onCloseSecondaryFile = onCloseSecondaryFile,
                    onFileDragChanged = onFileDragChanged,
                    onRequestSplitFile = onRequestSplitFile,
                    dropPreviewGroup = dropPreviewGroup,
                    onActivateFile = onActivateFile,
                    onCloseFile = onCloseFile,
                    onFileChange = onFileChange,
                    onEditorFocusChanged = onEditorFocusChanged,
                    diagnostics = (parseDiagnostics(diagnosticsOutput) + semanticDiagnostics).distinct(),
                    semanticCompletions = semanticCompletions,
                    onRequestSemanticCompletion = onRequestSemanticCompletion,
                    onRequestSemanticDiagnostics = onRequestSemanticDiagnostics,
                    cppExtensionInfo = cppExtensionInfo,
                    picoExtensionInfo = picoExtensionInfo,
                    pythonExtensionInfo = pythonExtensionInfo,
                    rustExtensionInfo = rustExtensionInfo,
                    gnuLanguagesExtensionInfo = gnuLanguagesExtensionInfo,
                    webExtensionInfo = webExtensionInfo,
                    gitExtensionInfo = gitExtensionInfo,
                    gnuArmExtensionInfo = gnuArmExtensionInfo,
                    extensionBusy = extensionBusy,
                    extensionNotice = extensionNotice,
                    extensionServerConfigured = extensionServerConfigured,
                    onInstallPicoExtension = onInstallPicoExtension,
                    onInstallCppExtension = onInstallCppExtension,
                    onInstallPythonExtension = onInstallPythonExtension,
                    onInstallRustExtension = onInstallRustExtension,
                    onInstallGnuLanguagesExtension = onInstallGnuLanguagesExtension,
                    onInstallWebExtension = onInstallWebExtension,
                    onInstallGitExtension = onInstallGitExtension,
                    onInstallGnuArmExtension = onInstallGnuArmExtension,
                    onInstallPicoExtensionFromFile = onInstallPicoExtensionFromFile,
                    onPreparePicoComponents = onPreparePicoComponents,
                    onDeletePicoComponents = onDeletePicoComponents,
                    onUninstallPicoExtension = onUninstallPicoExtension,
                    onUninstallCppExtension = onUninstallCppExtension,
                    onUninstallPythonExtension = onUninstallPythonExtension,
                    onUninstallRustExtension = onUninstallRustExtension,
                    onUninstallGnuLanguagesExtension = onUninstallGnuLanguagesExtension,
                    onUninstallWebExtension = onUninstallWebExtension,
                    onUninstallGitExtension = onUninstallGitExtension,
                    onUninstallGnuArmExtension = onUninstallGnuArmExtension,
                    modifier = Modifier.weight(1f)
                        .onGloballyPositioned { coordinates ->
                            val topLeft = coordinates.positionInRoot()
                            onEditorBoundsChanged(
                                Rect(
                                    offset = topLeft,
                                    size = androidx.compose.ui.geometry.Size(
                                        coordinates.size.width.toFloat(),
                                        coordinates.size.height.toFloat(),
                                    ),
                                ),
                            )
                        }
                        .dexPane(desktopWorkspace),
                )
                if (terminalVisible) {
                    Box(
                        Modifier
                            .width(7.dp)
                            .fillMaxHeight()
                            .background(if (desktopWorkspace) Background else Border.copy(alpha = 0.55f))
                            .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
                            .pointerInput(maximumTerminalWidth, density) {
                                var draggedWidth = latestTerminalWidth
                                detectHorizontalDragGestures(
                                    onDragStart = { draggedWidth = latestTerminalWidth },
                                ) { _, dragAmount ->
                                    draggedWidth = (draggedWidth - dragAmount / density.density)
                                        .coerceIn(240f, maximumTerminalWidth.value)
                                    onTerminalWidthChange(draggedWidth)
                                }
                            },
                    )
                    Terminal(
                        terminal,
                        buildOutput,
                        diagnosticsOutput,
                        semanticDiagnostics,
                        terminalPrompt,
                        terminalSessions,
                        selectedTerminalId,
                        mode,
                        preferences.terminalFontSizeSp,
                        terminalScale,
                        consoleActive,
                        terminalInputVisible,
                        onConsoleInput,
                        onNewTerminal,
                        onSelectTerminal,
                        onDeleteTerminal,
                        desktopWorkspace,
                        Modifier.width(terminalWidth).fillMaxHeight().dexPane(desktopWorkspace),
                    )
                }
            }
        }
    } else {
        BoxWithConstraints(modifier.fillMaxHeight()) {
            val maximumTerminalHeight = (maxHeight * 0.68f).coerceAtLeast(120.dp)
            val terminalHeight = terminalHeightValue.dp.coerceIn(120.dp, maximumTerminalHeight)
            Column(Modifier.fillMaxSize()) {
                EditorArea(
                    files = files,
                    dirtyFiles = dirtyFiles,
                    openTabs = openTabs,
                    activeFile = activeFile,
                    mode = mode,
                    desktopMode = desktopWorkspace,
                    editorScale = editorScale,
                    secondaryEditorScale = secondaryEditorScale,
                    editorFontSizeSp = preferences.editorFontSizeSp,
                    tabWidth = preferences.tabWidth,
                    indentWithSpaces = preferences.indentWithSpaces,
                    wordWrap = preferences.wordWrap,
                    navigationRequest = navigationRequest,
                    splitEditor = splitEditor,
                    secondaryActiveFile = secondaryActiveFile,
                    secondaryOpenTabs = secondaryOpenTabs,
                    splitRatio = splitRatio,
                    onSplitRatioChange = onSplitRatioChange,
                    onEditorZoomDelta = onEditorZoomDelta,
                    onActivateSecondaryFile = onActivateSecondaryFile,
                    onCloseSecondaryFile = onCloseSecondaryFile,
                    onFileDragChanged = onFileDragChanged,
                    onRequestSplitFile = onRequestSplitFile,
                    dropPreviewGroup = dropPreviewGroup,
                    onActivateFile = onActivateFile,
                    onCloseFile = onCloseFile,
                    onFileChange = onFileChange,
                    onEditorFocusChanged = onEditorFocusChanged,
                    diagnostics = (parseDiagnostics(diagnosticsOutput) + semanticDiagnostics).distinct(),
                    semanticCompletions = semanticCompletions,
                    onRequestSemanticCompletion = onRequestSemanticCompletion,
                    onRequestSemanticDiagnostics = onRequestSemanticDiagnostics,
                    cppExtensionInfo = cppExtensionInfo,
                    picoExtensionInfo = picoExtensionInfo,
                    pythonExtensionInfo = pythonExtensionInfo,
                    rustExtensionInfo = rustExtensionInfo,
                    gnuLanguagesExtensionInfo = gnuLanguagesExtensionInfo,
                    webExtensionInfo = webExtensionInfo,
                    gitExtensionInfo = gitExtensionInfo,
                    gnuArmExtensionInfo = gnuArmExtensionInfo,
                    extensionBusy = extensionBusy,
                    extensionNotice = extensionNotice,
                    extensionServerConfigured = extensionServerConfigured,
                    onInstallPicoExtension = onInstallPicoExtension,
                    onInstallCppExtension = onInstallCppExtension,
                    onInstallPythonExtension = onInstallPythonExtension,
                    onInstallRustExtension = onInstallRustExtension,
                    onInstallGnuLanguagesExtension = onInstallGnuLanguagesExtension,
                    onInstallWebExtension = onInstallWebExtension,
                    onInstallGitExtension = onInstallGitExtension,
                    onInstallGnuArmExtension = onInstallGnuArmExtension,
                    onInstallPicoExtensionFromFile = onInstallPicoExtensionFromFile,
                    onPreparePicoComponents = onPreparePicoComponents,
                    onDeletePicoComponents = onDeletePicoComponents,
                    onUninstallPicoExtension = onUninstallPicoExtension,
                    onUninstallCppExtension = onUninstallCppExtension,
                    onUninstallPythonExtension = onUninstallPythonExtension,
                    onUninstallRustExtension = onUninstallRustExtension,
                    onUninstallGnuLanguagesExtension = onUninstallGnuLanguagesExtension,
                    onUninstallWebExtension = onUninstallWebExtension,
                    onUninstallGitExtension = onUninstallGitExtension,
                    onUninstallGnuArmExtension = onUninstallGnuArmExtension,
                    modifier = Modifier.weight(1f)
                        .onGloballyPositioned { coordinates ->
                            val topLeft = coordinates.positionInRoot()
                            onEditorBoundsChanged(
                                Rect(
                                    offset = topLeft,
                                    size = androidx.compose.ui.geometry.Size(
                                        coordinates.size.width.toFloat(),
                                        coordinates.size.height.toFloat(),
                                    ),
                                ),
                            )
                        }
                        .dexPane(desktopWorkspace),
                )
                if (terminalVisible) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .background(if (desktopWorkspace) Background else Border.copy(alpha = 0.55f))
                            .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW))
                            .pointerInput(maximumTerminalHeight, density) {
                                var draggedHeight = latestTerminalHeight
                                detectVerticalDragGestures(
                                    onDragStart = { draggedHeight = latestTerminalHeight },
                                ) { _, dragAmount ->
                                    draggedHeight = (draggedHeight - dragAmount / density.density)
                                        .coerceIn(120f, maximumTerminalHeight.value)
                                    onTerminalHeightChange(draggedHeight)
                                }
                            },
                    )
                    Terminal(
                        terminal,
                        buildOutput,
                        diagnosticsOutput,
                        semanticDiagnostics,
                        terminalPrompt,
                        terminalSessions,
                        selectedTerminalId,
                        mode,
                        preferences.terminalFontSizeSp,
                        terminalScale,
                        consoleActive,
                        terminalInputVisible,
                        onConsoleInput,
                        onNewTerminal,
                        onSelectTerminal,
                        onDeleteTerminal,
                        desktopWorkspace,
                        Modifier.fillMaxWidth().height(terminalHeight).dexPane(desktopWorkspace),
                    )
                }
            }
        }
    }
}
