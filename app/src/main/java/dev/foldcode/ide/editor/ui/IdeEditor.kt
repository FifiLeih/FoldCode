package dev.foldcode.ide

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.text.LineBreaker
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.LeadingMarginSpan
import android.view.Gravity
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import kotlinx.coroutines.delay

internal enum class EditorGroup { Primary, Secondary }

@Composable
internal fun EditorArea(
    files: Map<String, ProjectFile>,
    dirtyFiles: Set<String>,
    openTabs: List<String>,
    activeFile: ProjectFile?,
    mode: LayoutMode,
    desktopMode: Boolean = false,
    editorScale: Float = 1f,
    secondaryEditorScale: Float = editorScale,
    editorFontSizeSp: Float = 0f,
    tabWidth: Int = 4,
    indentWithSpaces: Boolean = true,
    wordWrap: Boolean = false,
    navigationRequest: EditorNavigationRequest? = null,
    splitEditor: Boolean = false,
    secondaryActiveFile: ProjectFile? = null,
    secondaryOpenTabs: List<String> = emptyList(),
    splitRatio: Float = 0.5f,
    onSplitRatioChange: (Float) -> Unit = {},
    onEditorZoomDelta: (EditorGroup, Float) -> Unit = { _, _ -> },
    onActivateSecondaryFile: (String) -> Unit = {},
    onCloseSecondaryFile: (String) -> Unit = {},
    onFileDragChanged: (String?, Offset?, EditorGroup?) -> Unit = { _, _, _ -> },
    onRequestSplitFile: (String, Offset?, EditorGroup) -> Unit = { _, _, _ -> },
    dropPreviewGroup: EditorGroup? = null,
    onActivateFile: (String) -> Unit,
    onCloseFile: (String) -> Unit,
    onFileChange: (String, String) -> Unit,
    onEditorFocusChanged: (Boolean) -> Unit,
    diagnostics: List<CodeDiagnostic>,
    semanticCompletions: List<CompletionItem>,
    onRequestSemanticCompletion: (String, String, Int) -> Unit,
    onRequestSemanticDiagnostics: (String, String) -> Unit,
    cppExtensionInfo: FoldCodeExtensionInfo?,
    picoExtensionInfo: FoldCodeExtensionInfo?,
    pythonExtensionInfo: FoldCodeExtensionInfo?,
    rustExtensionInfo: FoldCodeExtensionInfo?,
    gnuLanguagesExtensionInfo: FoldCodeExtensionInfo? = null,
    webExtensionInfo: FoldCodeExtensionInfo? = null,
    gitExtensionInfo: FoldCodeExtensionInfo?,
    gnuArmExtensionInfo: FoldCodeExtensionInfo?,
    extensionBusy: Boolean,
    extensionNotice: String?,
    extensionServerConfigured: Boolean,
    onInstallPicoExtension: () -> Unit,
    onInstallCppExtension: () -> Unit,
    onInstallPythonExtension: () -> Unit,
    onInstallRustExtension: () -> Unit,
    onInstallGnuLanguagesExtension: () -> Unit = {},
    onInstallWebExtension: () -> Unit = {},
    onInstallGitExtension: () -> Unit,
    onInstallGnuArmExtension: () -> Unit,
    onInstallPicoExtensionFromFile: () -> Unit,
    onPreparePicoComponents: (Set<String>) -> Unit,
    onDeletePicoComponents: (Set<String>) -> Unit,
    onUninstallPicoExtension: () -> Unit,
    onUninstallCppExtension: () -> Unit,
    onUninstallPythonExtension: () -> Unit,
    onUninstallRustExtension: () -> Unit,
    onUninstallGnuLanguagesExtension: () -> Unit = {},
    onUninstallWebExtension: () -> Unit = {},
    onUninstallGitExtension: () -> Unit,
    onUninstallGnuArmExtension: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(Background).clipToBounds()) {
        val contentModifier = Modifier.fillMaxSize()
        if (activeFile == null) {
            Box(contentModifier)
        } else {
        val renderPane: @Composable (ProjectFile, List<String>, EditorGroup, (String) -> Unit, (String) -> Unit, Modifier) -> Unit =
            { paneFile, paneTabs, group, activateFile, closeFile, paneModifier ->
            Editor(
                files, dirtyFiles, paneTabs, paneFile, mode, desktopMode,
                if (group == EditorGroup.Primary) editorScale else secondaryEditorScale,
                editorFontSizeSp, tabWidth, indentWithSpaces, wordWrap,
                navigationRequest?.takeIf { it.fileName == paneFile.name }, activateFile, closeFile,
                group, onFileDragChanged, onRequestSplitFile,
                { delta -> onEditorZoomDelta(group, delta) }, onFileChange,
                { focused ->
                    if (focused) activateFile(paneFile.name)
                    onEditorFocusChanged(focused)
                },
                diagnostics, semanticCompletions, onRequestSemanticCompletion, onRequestSemanticDiagnostics,
                cppExtensionInfo, picoExtensionInfo, pythonExtensionInfo, rustExtensionInfo, gnuLanguagesExtensionInfo, webExtensionInfo, gitExtensionInfo, gnuArmExtensionInfo,
                extensionBusy, extensionNotice, extensionServerConfigured,
                onInstallPicoExtension, onInstallCppExtension, onInstallPythonExtension, onInstallRustExtension, onInstallGnuLanguagesExtension, onInstallWebExtension, onInstallGitExtension,
                onInstallGnuArmExtension, onInstallPicoExtensionFromFile,
                onPreparePicoComponents, onDeletePicoComponents, onUninstallPicoExtension, onUninstallCppExtension,
                onUninstallPythonExtension, onUninstallRustExtension, onUninstallGnuLanguagesExtension, onUninstallWebExtension, onUninstallGitExtension, onUninstallGnuArmExtension,
                paneModifier,
            )
        }
        if (splitEditor && secondaryActiveFile != null) {
            BoxWithConstraints(contentModifier) {
                val density = LocalDensity.current
                val totalWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                val visibleRatio = splitRatio.coerceIn(0.20f, 0.80f)
                val latestSplitRatio by rememberUpdatedState(visibleRatio)
                Row(Modifier.fillMaxSize()) {
                    renderPane(activeFile, openTabs, EditorGroup.Primary, onActivateFile, onCloseFile, Modifier.weight(visibleRatio).fillMaxHeight())
                    Box(
                        Modifier.width(7.dp).fillMaxHeight().background(Border.copy(alpha = 0.7f))
                            .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
                            // Do not key this gesture to splitRatio: changing that value
                            // during a drag used to cancel the gesture after a few pixels.
                            .pointerInput(totalWidthPx) {
                                var draggedRatio = latestSplitRatio
                                detectHorizontalDragGestures(
                                    onDragStart = { draggedRatio = latestSplitRatio },
                                    onHorizontalDrag = { _, amount ->
                                        draggedRatio = (draggedRatio + amount / totalWidthPx).coerceIn(0.20f, 0.80f)
                                        onSplitRatioChange(draggedRatio)
                                    },
                                )
                            },
                    )
                    renderPane(
                        secondaryActiveFile, secondaryOpenTabs, EditorGroup.Secondary,
                        onActivateSecondaryFile, onCloseSecondaryFile,
                        Modifier.weight(1f - visibleRatio).fillMaxHeight(),
                    )
                }
            }
        } else {
            renderPane(activeFile, openTabs, EditorGroup.Primary, onActivateFile, onCloseFile, contentModifier)
        }
        if (dropPreviewGroup != null && !splitEditor) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.5f)
                    .align(
                        if (dropPreviewGroup == EditorGroup.Primary) Alignment.CenterStart
                        else Alignment.CenterEnd,
                    )
                    .padding(3.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .border(2.dp, Color.White.copy(alpha = 0.58f), RoundedCornerShape(6.dp)),
            )
        }
    }
}
}

@Composable
private fun Editor(
    files: Map<String, ProjectFile>,
    dirtyFiles: Set<String>,
    openTabs: List<String>,
    activeFile: ProjectFile,
    mode: LayoutMode,
    desktopMode: Boolean,
    editorScale: Float,
    editorFontSizeSp: Float,
    tabWidth: Int,
    indentWithSpaces: Boolean,
    wordWrap: Boolean,
    navigationRequest: EditorNavigationRequest?,
    onActivateFile: (String) -> Unit,
    onCloseFile: (String) -> Unit,
    editorGroup: EditorGroup,
    onFileDragChanged: (String?, Offset?, EditorGroup?) -> Unit,
    onRequestSplitFile: (String, Offset?, EditorGroup) -> Unit,
    onEditorZoomDelta: (Float) -> Unit,
    onFileChange: (String, String) -> Unit,
    onEditorFocusChanged: (Boolean) -> Unit,
    diagnostics: List<CodeDiagnostic>,
    semanticCompletions: List<CompletionItem>,
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
    onInstallPicoExtension: () -> Unit,
    onInstallCppExtension: () -> Unit,
    onInstallPythonExtension: () -> Unit,
    onInstallRustExtension: () -> Unit,
    onInstallGnuLanguagesExtension: () -> Unit,
    onInstallWebExtension: () -> Unit,
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
    onUninstallWebExtension: () -> Unit,
    onUninstallGitExtension: () -> Unit,
    onUninstallGnuArmExtension: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabHeight = if (mode == LayoutMode.Compact) 34.dp else 36.dp
    val density = LocalDensity.current
    val splitDragThreshold = with(density) { 8.dp.toPx() }
    val editorPadding = when (mode) {
        LayoutMode.Compact -> 8.dp
        LayoutMode.Medium -> 12.dp
        LayoutMode.Expanded -> 16.dp
    }
    val automaticFontSize = when (mode) {
        LayoutMode.Compact -> 12.sp
        LayoutMode.Medium -> 13.sp
        LayoutMode.Expanded -> 14.sp
    }
    val fontSize = (if (editorFontSizeSp > 0f) editorFontSizeSp.sp else automaticFontSize) * editorScale
    val lineHeight = (if (editorFontSizeSp > 0f) (editorFontSizeSp * 1.5f).sp else when (mode) {
        LayoutMode.Compact -> 18.sp
        LayoutMode.Medium -> 20.sp
        LayoutMode.Expanded -> 22.sp
    }) * editorScale
    val gutterWidth = when (mode) {
        LayoutMode.Compact -> 32.dp
        LayoutMode.Medium -> 36.dp
        // DeX has room for a stable breakpoint lane instead of replacing the
        // line number as compact phone layouts do.
        LayoutMode.Expanded -> 52.dp
    } * editorScale
    val largeFileMode = remember(activeFile.name, activeFile.content) {
        activeFile.content.length >= 150_000 || activeFile.content.count { it == '\n' } >= 5_000
    }
    var editorValue by remember(activeFile.name) {
        mutableStateOf(TextFieldValue(activeFile.content, TextRange(activeFile.content.length)))
    }
    var editorFocused by remember(activeFile.name) { mutableStateOf(false) }
    var completionArmed by remember(activeFile.name) { mutableStateOf(false) }
    var selectedCompletion by remember(activeFile.name) { mutableIntStateOf(0) }
    var textLayout by remember(activeFile.name) { mutableStateOf<TextLayoutResult?>(null) }
    val editorScroll = rememberScrollState()
    val horizontalEditorScroll = rememberScrollState()
    LaunchedEffect(activeFile.content) {
        if (activeFile.content != editorValue.text) {
            val cursor = editorValue.selection.start.coerceAtMost(activeFile.content.length)
            editorValue = TextFieldValue(activeFile.content, TextRange(cursor))
        }
    }
    val cursor = editorValue.selection.start
    val prefix = if (largeFileMode) "" else Regex("[A-Za-z_][A-Za-z0-9_]*$")
        .find(editorValue.text.take(cursor))?.value.orEmpty()
    LaunchedEffect(activeFile.name, editorValue.text, cursor, prefix, editorFocused, completionArmed) {
        if (editorFocused && completionArmed && prefix.isNotEmpty()) {
            delay(180)
            onRequestSemanticCompletion(activeFile.name, editorValue.text, cursor)
        }
    }
    val suggestions = remember(prefix, editorValue.text, editorFocused, completionArmed, semanticCompletions) {
        if (!editorFocused || !completionArmed || prefix.isEmpty()) emptyList() else (semanticCompletions + completionItems(editorValue.text, activeFile.name))
            .distinctBy { it.label }
            .mapNotNull { item -> completionMatchScore(item.label, prefix)?.let { item to it } }
            .filter { it.first.label != prefix }
            .sortedWith(compareBy<Pair<CompletionItem, Int>> { it.second }.thenBy { it.first.label.lowercase() })
            .map { it.first }
            .take(8)
    }
    LaunchedEffect(suggestions) { selectedCompletion = 0 }
    val fileDiagnostics = diagnostics.filter {
        (it.file == activeFile.name ||
            it.file.substringAfterLast('/') == activeFile.name.substringAfterLast('/')) &&
            it.severity != "note"
    }

    Column(modifier.background(Background).clipToBounds()) {
        Row(
            Modifier.fillMaxWidth().height(tabHeight).background(Panel)
                .clipToBounds()
                .horizontalScroll(rememberScrollState()),
        ) {
            openTabs.forEach { fileName ->
                val active = fileName == activeFile.name
                var tabBounds by remember(fileName) { mutableStateOf<Rect?>(null) }
                Column(
                    Modifier.height(tabHeight)
                        // Tabs need a real width constraint. widthIn(max = …) still lets
                        // the child claim most of the available row, which only moved the
                        // close icon without making the tab itself visibly smaller.
                        .width(if (mode == LayoutMode.Compact) 118.dp else 136.dp)
                        .background(if (active) EditorTab else Panel).border(1.dp, Border)
                        .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HAND))
                        .onGloballyPositioned { coordinates ->
                            val topLeft = coordinates.positionInRoot()
                            tabBounds = Rect(
                                offset = topLeft,
                                size = Size(
                                    coordinates.size.width.toFloat(),
                                    coordinates.size.height.toFloat(),
                                ),
                            )
                        }
                        .pointerInput(fileName, mode) {
                            if (mode != LayoutMode.Expanded) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (down.type != PointerType.Mouse) {
                                    waitForUpOrCancellation()
                                    return@awaitEachGesture
                                }
                                var current = down.position
                                var dragging = false
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    current = change.position
                                    if (!dragging && (current - down.position).getDistance() >= splitDragThreshold) {
                                        dragging = true
                                    }
                                    if (dragging) {
                                        change.consume()
                                        tabBounds?.let { onFileDragChanged(fileName, it.topLeft + current, editorGroup) }
                                    }
                                    if (!change.pressed) break
                                }
                                val bounds = tabBounds
                                if (dragging && bounds != null) {
                                    val releaseInRoot = bounds.topLeft + current
                                    onRequestSplitFile(fileName, releaseInRoot, editorGroup)
                                }
                                onFileDragChanged(null, null, null)
                            }
                        }
                        .clickable { onActivateFile(fileName) },
                ) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) Accent else Color.Transparent))
                    Row(
                        Modifier.padding(horizontal = 8.dp).weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(fileGlyph(fileName), color = fileColor(fileName), fontSize = 11.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            fileName,
                            color = if (active) Foreground else Muted,
                            fontSize = if (active) 11.5.sp else 11.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (fileName in dirtyFiles) {
                            Spacer(Modifier.width(7.dp))
                            Text("●", color = Color.White, fontSize = 9.sp)
                        }
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "×",
                            color = Muted,
                            fontSize = if (desktopMode) 18.sp else 16.sp,
                            modifier = Modifier
                                .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HAND))
                                .clickable { onCloseFile(fileName) }
                                .padding(
                                    horizontal = if (desktopMode) 3.dp else 0.dp,
                                    vertical = if (desktopMode) 1.dp else 0.dp,
                                ),
                        )
                    }
                }
            }
        }
        if (activeFile.kind == ProjectFileKind.CppExtensionDetails) {
            CppExtensionDetails(
                info = cppExtensionInfo,
                busy = extensionBusy,
                notice = extensionNotice,
                serverConfigured = extensionServerConfigured,
                onInstall = onInstallCppExtension,
                onInstallFromFile = onInstallPicoExtensionFromFile,
                onUninstall = onUninstallCppExtension,
            )
        } else if (activeFile.kind == ProjectFileKind.PythonExtensionDetails) {
            PythonExtensionDetails(
                info = pythonExtensionInfo,
                busy = extensionBusy,
                notice = extensionNotice,
                serverConfigured = extensionServerConfigured,
                onInstall = onInstallPythonExtension,
                onInstallFromFile = onInstallPicoExtensionFromFile,
                onUninstall = onUninstallPythonExtension,
            )
        } else if (activeFile.kind == ProjectFileKind.RustExtensionDetails) {
            RustExtensionDetails(
                info = rustExtensionInfo,
                busy = extensionBusy,
                notice = extensionNotice,
                serverConfigured = extensionServerConfigured,
                onInstall = onInstallRustExtension,
                onInstallFromFile = onInstallPicoExtensionFromFile,
                onUninstall = onUninstallRustExtension,
            )
        } else if (activeFile.kind == ProjectFileKind.GnuLanguagesExtensionDetails) {
            GnuLanguagesExtensionDetails(
                info = gnuLanguagesExtensionInfo,
                busy = extensionBusy,
                notice = extensionNotice,
                serverConfigured = extensionServerConfigured,
                onInstall = onInstallGnuLanguagesExtension,
                onInstallFromFile = onInstallPicoExtensionFromFile,
                onUninstall = onUninstallGnuLanguagesExtension,
            )
        } else if (activeFile.kind == ProjectFileKind.WebExtensionDetails) {
            WebExtensionDetails(
                info = webExtensionInfo,
                busy = extensionBusy,
                notice = extensionNotice,
                serverConfigured = extensionServerConfigured,
                onInstall = onInstallWebExtension,
                onInstallFromFile = onInstallPicoExtensionFromFile,
                onUninstall = onUninstallWebExtension,
            )
        } else if (activeFile.kind == ProjectFileKind.GitExtensionDetails) {
            GitExtensionDetails(
                info = gitExtensionInfo,
                busy = extensionBusy,
                notice = extensionNotice,
                serverConfigured = extensionServerConfigured,
                onInstall = onInstallGitExtension,
                onInstallFromFile = onInstallPicoExtensionFromFile,
                onUninstall = onUninstallGitExtension,
            )
        } else if (activeFile.kind == ProjectFileKind.PicoExtensionDetails) {
            PicoExtensionDetails(
                picoInfo = picoExtensionInfo,
                gnuArmInfo = gnuArmExtensionInfo,
                busy = extensionBusy,
                notice = extensionNotice,
                serverConfigured = extensionServerConfigured,
                onInstall = onInstallPicoExtension,
                onInstallFromFile = onInstallPicoExtensionFromFile,
                onPrepareComponents = onPreparePicoComponents,
                onDeleteComponents = onDeletePicoComponents,
                onUninstall = onUninstallPicoExtension,
                onInstallGnuArm = onInstallGnuArmExtension,
                onUninstallGnuArm = onUninstallGnuArmExtension,
            )
        } else if (activeFile.kind == ProjectFileKind.WebPreview) {
            WebPreviewBrowser(
                url = activeFile.content,
                mode = mode,
                // Give the native WebView an exact slot below the tab row.
                // fillMaxSize on a non-weighted Column child can briefly use
                // the parent's pre-tab bounds during initial AndroidView attach.
                modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
            )
        } else if (activeFile.kind == ProjectFileKind.Documentation) {
            PicoDocumentationBrowser(
                documentName = activeFile.name,
                offlineFallback = activeFile.content,
                mode = mode,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (largeFileMode) {
            LargeFileEditor(
                fileName = activeFile.name,
                content = activeFile.content,
                readOnly = activeFile.readOnly,
                fontSizeSp = fontSize.value,
                showScrollbars = desktopMode,
                onFileChange = onFileChange,
                onFocusChanged = onEditorFocusChanged,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (false) Box(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val guides = remember(editorValue.text) { bracketGuides(editorValue.text) }
            val sourceLineStarts = remember(editorValue.text) {
                buildList {
                    add(0)
                    editorValue.text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
                }
            }
            val topInset = with(density) { editorPadding.toPx() }
            val textInset = with(density) { gutterWidth.toPx() + editorPadding.toPx() }
            val gutterPixels = with(density) { gutterWidth.toPx() }
            val fontPixels = with(density) { fontSize.toPx() }
            val diagnosticLines = remember(fileDiagnostics) { fileDiagnostics.map { it.line - 1 }.toSet() }
            val lineNumberPaint = remember(fontPixels) {
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.rgb(116, 123, 136)
                    textSize = fontPixels * 0.72f
                    textAlign = android.graphics.Paint.Align.RIGHT
                    typeface = android.graphics.Typeface.MONOSPACE
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val layout = textLayout ?: return@Canvas
                val safeCursor = cursor.coerceIn(0, editorValue.text.length)
                val activeVisualLine = layout.getLineForOffset(safeCursor)
                val activeTop = topInset + layout.getLineTop(activeVisualLine) - editorScroll.value
                val activeBottom = topInset + layout.getLineBottom(activeVisualLine) - editorScroll.value
                if (activeBottom >= 0f && activeTop <= size.height) {
                    drawRect(
                        Color(0xFF20242B),
                        topLeft = Offset(gutterPixels, activeTop),
                        size = Size((size.width - gutterPixels).coerceAtLeast(0f), activeBottom - activeTop),
                    )
                }
                drawLine(
                    Border,
                    start = Offset(gutterPixels - 1f, 0f),
                    end = Offset(gutterPixels - 1f, size.height),
                    strokeWidth = 1f,
                )
                drawContext.canvas.nativeCanvas.apply {
                    val firstVisibleLine = visualLineForY(layout,
                        (editorScroll.value - topInset).coerceAtLeast(0f),
                    )
                    val lastVisibleLine = visualLineForY(layout,
                        (editorScroll.value + size.height - topInset).coerceAtLeast(0f),
                    )
                    for (line in firstVisibleLine..lastVisibleLine) {
                        val baseline = topInset + layout.getLineBaseline(line) - editorScroll.value
                        if (baseline >= -fontPixels && baseline <= size.height + fontPixels) {
                            val sourceOffset = layout.getLineStart(line)
                            val search = sourceLineStarts.binarySearch(sourceOffset)
                            val sourceLine = if (search >= 0) search + 1 else -search - 1
                            drawText(sourceLine.toString(), gutterPixels - 8f, baseline, lineNumberPaint)
                        }
                    }
                }
                diagnosticLines.forEach { line ->
                    val sourceOffset = editorValue.text.lineSequence().take(line).sumOf { it.length + 1 }
                        .coerceIn(0, editorValue.text.length)
                    val visualLine = layout.getLineForOffset(sourceOffset)
                    val centerY = topInset + (layout.getLineTop(visualLine) + layout.getLineBottom(visualLine)) / 2f - editorScroll.value
                    if (centerY in 0f..size.height) {
                        drawCircle(Color(0xFFFF6B6B), radius = 3.5f, center = Offset(7f, centerY))
                    }
                }
                guides.forEach { guide ->
                    val openingLine = layout.getLineForOffset(guide.openingOffset)
                    val closingLine = layout.getLineForOffset(guide.closingOffset)
                    // Match VS Code's bracket-pair guide: begin at the indentation
                    // of the declaration/control line and continue to its closing brace.
                    val x = textInset + layout.getHorizontalPosition(guide.indentationOffset, true) - horizontalEditorScroll.value
                    val top = topInset + layout.getLineBottom(openingLine) - editorScroll.value
                    val bottom = topInset + layout.getLineTop(closingLine) - editorScroll.value
                    if (x >= gutterPixels && bottom >= 0 && top <= size.height) {
                        drawLine(Color(0xFF40454E), start = Offset(x, top), end = Offset(x, bottom), strokeWidth = 1f)
                    }
                }
            }
            fun acceptCompletion(index: Int) {
                val completion = suggestions.getOrNull(index) ?: return
                val start = (editorValue.selection.start - prefix.length).coerceAtLeast(0)
                val end = editorValue.selection.end
                val updated = editorValue.text.replaceRange(start, end, completion.insertion)
                val newCursor = start + completion.insertion.length
                editorValue = TextFieldValue(updated, TextRange(newCursor))
                completionArmed = false
                onFileChange(activeFile.name, updated)
            }
            BasicTextField(
                value = editorValue,
                onValueChange = {
                    val inserted = it.text.length > editorValue.text.length
                    completionArmed = inserted && it.selection.collapsed &&
                        it.selection.start > 0 && it.text[it.selection.start - 1].let { char -> char.isLetterOrDigit() || char == '_' }
                    editorValue = it
                    if (!activeFile.readOnly) onFileChange(activeFile.name, it.text)
                },
                textStyle = TextStyle(color = Foreground, fontFamily = FontFamily.Monospace, fontSize = fontSize, lineHeight = lineHeight),
                visualTransformation = remember(fileDiagnostics) { CodeVisualTransformation(fileDiagnostics) },
                readOnly = activeFile.readOnly,
                onTextLayout = { textLayout = it },
                modifier = Modifier.fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        if (suggestions.isNotEmpty()) {
                            when (event.key) {
                                Key.DirectionDown -> { selectedCompletion = (selectedCompletion + 1) % suggestions.size; return@onPreviewKeyEvent true }
                                Key.DirectionUp -> { selectedCompletion = (selectedCompletion - 1 + suggestions.size) % suggestions.size; return@onPreviewKeyEvent true }
                                Key.Enter, Key.Tab -> { acceptCompletion(selectedCompletion); return@onPreviewKeyEvent true }
                                Key.Escape -> { completionArmed = false; return@onPreviewKeyEvent true }
                            }
                        }
                        val selection = editorValue.selection
                        val caret = selection.end.coerceIn(0, editorValue.text.length)
                        val target = when (event.key) {
                            Key.DirectionLeft -> if (!event.isShiftPressed && !selection.collapsed) selection.min else (caret - 1).coerceAtLeast(0)
                            Key.DirectionRight -> if (!event.isShiftPressed && !selection.collapsed) selection.max else (caret + 1).coerceAtMost(editorValue.text.length)
                            Key.DirectionUp -> verticalCursorOffset(editorValue.text, caret, -1)
                            Key.DirectionDown -> verticalCursorOffset(editorValue.text, caret, 1)
                            Key.MoveHome -> editorValue.text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
                            Key.MoveEnd -> editorValue.text.indexOf('\n', caret).let { if (it < 0) editorValue.text.length else it }
                            else -> return@onPreviewKeyEvent false
                        }
                        completionArmed = false
                        editorValue = editorValue.copy(
                            selection = if (event.isShiftPressed) TextRange(selection.start, target) else TextRange(target),
                        )
                        true
                    }
                    // The gutter is outside both scroll containers. This keeps line
                    // numbers fixed while long source lines move horizontally.
                    .padding(start = gutterWidth)
                    .clipToBounds()
                    .verticalScroll(editorScroll)
                    .horizontalScroll(horizontalEditorScroll)
                    .padding(
                        start = editorPadding,
                        top = editorPadding,
                        end = editorPadding,
                        bottom = editorPadding,
                    )
                    .onFocusChanged { state -> editorFocused = state.isFocused; if (!state.isFocused) completionArmed = false },
                cursorBrush = SolidColor(Accent),
            )
            if (suggestions.isNotEmpty()) {
                val cursorRect = textLayout?.getCursorRect(cursor.coerceIn(0, editorValue.text.length))
                val popupBaseX = (cursorRect?.left ?: 0f) + textInset
                val popupBaseY = (cursorRect?.bottom ?: 0f) + topInset + with(density) { 3.dp.toPx() }
                Column(
                    Modifier.offset {
                        IntOffset(
                            (popupBaseX - horizontalEditorScroll.value).toInt().coerceAtLeast(gutterPixels.toInt()),
                            (popupBaseY - editorScroll.value).toInt().coerceAtLeast(0),
                        )
                    }.width(if (mode == LayoutMode.Compact) 250.dp else 310.dp)
                        .background(Color(0xFF252930)).border(1.dp, Accent.copy(alpha = 0.65f)),
                ) {
                    suggestions.forEachIndexed { index, completion ->
                        Row(
                            Modifier.fillMaxWidth().background(if (index == selectedCompletion) Color(0xFF094771) else Color.Transparent)
                                .clickable { acceptCompletion(index) }.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("◇", color = Color(0xFFC586C0), fontSize = 11.sp)
                            Spacer(Modifier.width(7.dp))
                            Text(completion.label, color = Foreground, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            Text(completion.detail, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.2f))
                        }
                    }
                }
            }
        } else {
            FoldCodeNativeEditor(
                fileName = activeFile.name,
                content = activeFile.content,
                readOnly = activeFile.readOnly,
                fontSizeSp = fontSize.value,
                lineHeightSp = lineHeight.value,
                gutterWidthDp = gutterWidth.value,
                replaceBreakpointLineNumber = mode != LayoutMode.Expanded,
                wordWrap = wordWrap,
                tabWidth = tabWidth,
                indentWithSpaces = indentWithSpaces,
                showScrollbars = desktopMode,
                diagnostics = fileDiagnostics,
                navigationRequest = navigationRequest?.takeIf { it.fileName == activeFile.name },
                semanticCompletions = semanticCompletions,
                onFileChange = onFileChange,
                onRequestSemanticCompletion = onRequestSemanticCompletion,
                onRequestSemanticDiagnostics = onRequestSemanticDiagnostics,
                onEditorZoomDelta = if (mode == LayoutMode.Expanded) onEditorZoomDelta else null,
                onFocusChanged = onEditorFocusChanged,
                modifier = Modifier.fillMaxSize().padding(top = editorPadding),
            )
        }
    }
}

/**
 * Android's native editable layout is substantially better behaved for generated files with
 * thousands of lines than Compose BasicTextField. Expensive syntax, completion and bracket
 * passes are intentionally disabled here. Changes are debounced before entering Compose state.
 */
@Composable
private fun LargeFileEditor(
    fileName: String,
    content: String,
    readOnly: Boolean,
    fontSizeSp: Float,
    showScrollbars: Boolean,
    onFileChange: (String, String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.runtime.key(fileName) {
        AndroidView(
            modifier = modifier.background(Background),
            factory = { context ->
                LargeFileEditText(context).apply {
                    setTextColor(android.graphics.Color.rgb(216, 222, 233))
                    setHintTextColor(android.graphics.Color.rgb(137, 144, 158))
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    typeface = Typeface.MONOSPACE
                    textSize = fontSizeSp
                    gravity = Gravity.TOP or Gravity.START
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    isSingleLine = false
                    setHorizontallyScrolling(true)
                    isVerticalScrollBarEnabled = showScrollbars
                    isHorizontalScrollBarEnabled = showScrollbars
                    overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
                    }
                    hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                    includeFontPadding = false
                    setPadding(18, 14, 18, 14)
                    changeListener = { updated -> onFileChange(fileName, updated) }
                    replaceContent(content)
                    setOnFocusChangeListener { _, focused ->
                        if (!focused) flushChange()
                        onFocusChanged(focused)
                    }
                    if (readOnly) {
                        keyListener = null
                        setTextIsSelectable(true)
                    }
                }
            },
            update = { editor ->
                editor.changeListener = { updated -> onFileChange(fileName, updated) }
                editor.isVerticalScrollBarEnabled = showScrollbars
                editor.isHorizontalScrollBarEnabled = showScrollbars
                if (!editor.hasFocus() && editor.text.toString() != content) editor.replaceContent(content)
            },
        )
    }
}

private class LargeFileEditText(context: android.content.Context) : EditText(context) {
    var changeListener: (String) -> Unit = {}
    private val handler = Handler(Looper.getMainLooper())
    private var pendingChange: Runnable? = null
    private var replacing = false

    init {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(source: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(source: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(value: Editable?) {
                if (replacing) return
                pendingChange?.let(handler::removeCallbacks)
                val task = Runnable {
                    pendingChange = null
                    changeListener(text.toString())
                }
                pendingChange = task
                handler.postDelayed(task, 350L)
            }
        })
    }

    fun replaceContent(value: String) {
        replacing = true
        setText(value)
        setSelection(0)
        replacing = false
    }

    fun flushChange() {
        val pending = pendingChange ?: return
        handler.removeCallbacks(pending)
        pendingChange = null
        changeListener(text.toString())
    }

    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (
            (event.actionMasked == android.view.MotionEvent.ACTION_HOVER_ENTER ||
                event.actionMasked == android.view.MotionEvent.ACTION_SCROLL) &&
            !hasFocus()
        ) {
            requestFocus()
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DesktopPointerFocusRouter.register(this)
    }

    override fun onDetachedFromWindow() {
        DesktopPointerFocusRouter.unregister(this)
        flushChange()
        super.onDetachedFromWindow()
    }
}

private fun visualLineForY(layout: TextLayoutResult, y: Float): Int {
    if (layout.lineCount <= 1) return 0
    var low = 0
    var high = layout.lineCount - 1
    while (low < high) {
        val middle = (low + high) ushr 1
        if (layout.getLineBottom(middle) < y) low = middle + 1 else high = middle
    }
    return low
}

private fun verticalCursorOffset(text: String, offset: Int, direction: Int): Int {
    val safeOffset = offset.coerceIn(0, text.length)
    val currentStart = text.lastIndexOf('\n', (safeOffset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
    val column = safeOffset - currentStart
    return if (direction < 0) {
        if (currentStart == 0) safeOffset else {
            val previousEnd = currentStart - 1
            val previousStart = text.lastIndexOf('\n', (previousEnd - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            previousStart + column.coerceAtMost(previousEnd - previousStart)
        }
    } else {
        val currentEnd = text.indexOf('\n', safeOffset).let { if (it < 0) text.length else it }
        if (currentEnd == text.length) safeOffset else {
            val nextStart = currentEnd + 1
            val nextEnd = text.indexOf('\n', nextStart).let { if (it < 0) text.length else it }
            nextStart + column.coerceAtMost(nextEnd - nextStart)
        }
    }
}

/**
 * A terminal prompt does not need Compose's selection, decoration and gesture stack. Keeping a
 * single native EditText alive gives the IME one stable input connection and avoids paying the
 * expensive Compose text-selection verification cost on the first terminal focus.
 */
@Composable
private fun TerminalInputField(
    value: String,
    prompt: String,
    promptColor: Color,
    placeholder: String,
    fontSizeSp: Float,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onHistoryUp: () -> Unit,
    onHistoryDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.heightIn(min = (fontSizeSp * 1.7f).dp),
        factory = { context ->
            TerminalInputEditText(context).apply {
                setTextColor(android.graphics.Color.rgb(216, 222, 233))
                setHintTextColor(android.graphics.Color.rgb(137, 144, 158))
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                isSingleLine = false
                maxLines = Int.MAX_VALUE
                setHorizontallyScrolling(false)
                isVerticalScrollBarEnabled = false
                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                includeFontPadding = false
                minimumHeight = 0
                minHeight = 0
                setPadding(0, 0, 0, 0)
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
        },
        update = { input ->
            input.textSize = fontSizeSp
            input.configurePrompt(prompt, promptColor.toArgb())
            input.hint = placeholder
            input.valueChanged = onValueChange
            input.submitAction = onSubmit
            input.historyUpAction = onHistoryUp
            input.historyDownAction = onHistoryDown
            input.replaceContentIfNeeded(value)
        },
    )
}

private class TerminalInputEditText(context: android.content.Context) : EditText(context) {
    var valueChanged: (String) -> Unit = {}
    var submitAction: () -> Unit = {}
    var historyUpAction: () -> Unit = {}
    var historyDownAction: () -> Unit = {}

    private var replacing = false
    private var lastSubmitAt = 0L
    private var promptMarginSpan: LeadingMarginSpan.Standard? = null
    private var promptMarginPx = 0
    private var promptText = ""
    private val promptPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)

    init {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(source: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(source: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(value: Editable?) {
                applyPromptMargin()
                if (!replacing) valueChanged(value?.toString().orEmpty())
            }
        })
        setOnEditorActionListener { _, actionId, event ->
            val send = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                    event.action == android.view.KeyEvent.ACTION_DOWN)
            if (send) submitOnce() else false
        }
        setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val clipboardAction = when {
                event.isCtrlPressed && keyCode == android.view.KeyEvent.KEYCODE_A -> android.R.id.selectAll
                event.isCtrlPressed && keyCode == android.view.KeyEvent.KEYCODE_C -> android.R.id.copy
                event.isCtrlPressed && keyCode == android.view.KeyEvent.KEYCODE_X -> android.R.id.cut
                event.isCtrlPressed && keyCode == android.view.KeyEvent.KEYCODE_V -> android.R.id.paste
                event.isCtrlPressed && keyCode == android.view.KeyEvent.KEYCODE_INSERT -> android.R.id.copy
                event.isShiftPressed && keyCode == android.view.KeyEvent.KEYCODE_INSERT -> android.R.id.paste
                event.isShiftPressed && keyCode == android.view.KeyEvent.KEYCODE_FORWARD_DEL -> android.R.id.cut
                else -> null
            }
            if (clipboardAction != null) return@setOnKeyListener onTextContextMenuItem(clipboardAction)
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    historyUpAction()
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    historyDownAction()
                    true
                }
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> submitOnce()
                else -> false
            }
        }
    }

    fun configurePrompt(value: String, color: Int) {
        promptText = value
        promptPaint.apply {
            this.color = color
            typeface = this@TerminalInputEditText.typeface
            textSize = paint.textSize
        }
        val nextMargin = kotlin.math.ceil(paint.measureText(value).toDouble()).toInt()
        if (promptMarginPx != nextMargin) {
            promptMarginPx = nextMargin
            applyPromptMargin()
            requestLayout()
        }
        invalidate()
    }

    private fun applyPromptMargin() {
        promptMarginSpan?.let(editableText::removeSpan)
        promptMarginSpan = null
        if (promptMarginPx <= 0) return
        val firstParagraphEnd = editableText.indexOf('\n').let { newline ->
            if (newline >= 0) newline + 1 else editableText.length
        }
        LeadingMarginSpan.Standard(promptMarginPx, 0).also { span ->
            promptMarginSpan = span
            editableText.setSpan(
                span,
                0,
                firstParagraphEnd,
                Spanned.SPAN_INCLUSIVE_INCLUSIVE,
            )
        }
    }

    override fun onDraw(canvas: AndroidCanvas) {
        super.onDraw(canvas)
        if (promptText.isEmpty()) return
        val textLayout = layout ?: return
        val baseline = compoundPaddingTop + textLayout.getLineBaseline(0) - scrollY
        canvas.drawText(
            promptText,
            (compoundPaddingLeft - scrollX).toFloat(),
            baseline.toFloat(),
            promptPaint,
        )
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id != android.R.id.paste && id != android.R.id.pasteAsPlainText) {
            return super.onTextContextMenuItem(id)
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        val pasted = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            ?: return false
        val normalized = normalizeTerminalPasteText(pasted)
        val start = minOf(selectionStart, selectionEnd).takeIf { it >= 0 } ?: editableText.length
        val end = maxOf(selectionStart, selectionEnd).takeIf { it >= 0 } ?: editableText.length
        android.view.inputmethod.BaseInputConnection.removeComposingSpans(editableText)
        editableText.replace(start, end, normalized)
        setSelection(start + normalized.length)
        requestLayout()
        return true
    }

    private fun submitOnce(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSubmitAt < 120L) return true
        lastSubmitAt = now
        android.view.inputmethod.BaseInputConnection.removeComposingSpans(editableText)
        submitAction()
        return true
    }

    fun replaceContentIfNeeded(value: String) {
        if (text.toString() == value) return
        replacing = true
        android.view.inputmethod.BaseInputConnection.removeComposingSpans(editableText)
        setText(value)
        applyPromptMargin()
        setSelection(value.length)
        scrollTo(0, 0)
        requestLayout()
        replacing = false
    }
}

internal fun normalizeTerminalPasteText(value: String): String = value
    .replace("\r\n", "\n")
    .replace('\r', '\n')
    .trimEnd('\n')

@Composable
internal fun Terminal(
    terminalText: String,
    outputText: String,
    diagnosticsText: String,
    semanticDiagnostics: List<CodeDiagnostic>,
    terminalPrompt: String,
    terminalSessions: List<TerminalSessionInfo>,
    selectedTerminalId: Int,
    mode: LayoutMode,
    terminalFontSizeSp: Float = 0f,
    nativeInputScale: Float = 1f,
    consoleActive: Boolean,
    inputVisible: Boolean,
    onConsoleInput: (String) -> Unit,
    onNewTerminal: () -> Unit,
    onSelectTerminal: (Int) -> Unit,
    onDeleteTerminal: (Int) -> Unit,
    desktopMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val compact = mode == LayoutMode.Compact
    val terminalFontSize = if (terminalFontSizeSp > 0f) terminalFontSizeSp.sp else if (compact) 11.sp else 12.sp
    val terminalLineHeight = (if (terminalFontSizeSp > 0f) terminalFontSizeSp * 1.5f else if (compact) 16f else 18f).sp
    val focusManager = LocalFocusManager.current
    var selectedPanel by rememberSaveable { mutableStateOf(BottomPanel.Terminal) }
    // A shell session is intentionally process-local. Restoring the IME's old
    // composing buffer and command history while the workspace is still being
    // reconstructed causes a costly first focus and can make Samsung IME reopen
    // with a stale input connection.
    var consoleInput by remember { mutableStateOf("") }
    var commandHistory by remember { mutableStateOf(emptyList<String>()) }
    var historyIndex by remember { mutableIntStateOf(0) }
    var terminalSelectorVisible by rememberSaveable { mutableStateOf(false) }
    var terminalSelectorWidth by rememberSaveable { mutableFloatStateOf(if (desktopMode) 150f else 40f) }
    var previousTerminalCount by remember { mutableIntStateOf(terminalSessions.size) }
    val density = LocalDensity.current
    val latestSelectorWidth by rememberUpdatedState(terminalSelectorWidth)
    val terminalScroll = rememberLazyListState()
    val selectedTerminalRunning = terminalSessions
        .firstOrNull { it.id == selectedTerminalId }
        ?.commandRunning == true
    val terminalProgress = remember(terminalText, selectedTerminalRunning, consoleActive) {
        terminalProgressState(terminalText, selectedTerminalRunning || consoleActive)
    }
    val problems = remember(outputText, diagnosticsText, semanticDiagnostics) {
        val live = (parseDiagnostics(diagnosticsText) + semanticDiagnostics)
            .filter { it.severity != "note" }
            .distinct()
            .map {
            "${it.file}:${it.line}:${it.column} ${it.severity}: ${it.message}"
        }
        val build = outputText.lineSequence().filter { it.contains("error:", true) || it.contains("warning:", true) }.toList()
        (live + build).distinct()
    }
    val panelText = when (selectedPanel) {
        BottomPanel.Terminal -> terminalText
        BottomPanel.Execution -> outputText
        BottomPanel.Problems -> if (problems.isEmpty()) "No problems detected" else problems.joinToString("\n")
    }
    fun submitInput() {
        val value = consoleInput
        if (value.isBlank() && !consoleActive) return
        if (!consoleActive && value.isNotBlank()) {
            commandHistory = (commandHistory + value).takeLast(100)
            historyIndex = commandHistory.size
        }
        onConsoleInput(value)
        consoleInput = ""
    }
    LaunchedEffect(selectedTerminalId) {
        consoleInput = ""
    }
    LaunchedEffect(terminalSessions.size) {
        // Creating a second terminal opens the selector. Deleting back to one
        // terminal deliberately keeps it open so the delete action does not
        // unexpectedly collapse the UI.
        if (terminalSessions.size > previousTerminalCount && terminalSessions.size > 1) {
            terminalSelectorVisible = true
        } else if (terminalSessions.isEmpty()) {
            terminalSelectorVisible = false
        }
        previousTerminalCount = terminalSessions.size
    }
    fun recallHistory(offset: Int) {
        if (consoleActive || commandHistory.isEmpty()) return
        val next = (historyIndex + offset).coerceIn(0, commandHistory.size)
        historyIndex = next
        consoleInput = commandHistory.getOrNull(next).orEmpty()
    }
    LaunchedEffect(terminalText, selectedPanel, selectedTerminalId) {
        if (selectedPanel == BottomPanel.Terminal) {
            delay(16)
            val lastIndex = terminalScroll.layoutInfo.totalItemsCount - 1
            if (lastIndex >= 0) {
                terminalScroll.scrollToItem(lastIndex)
            }
        }
    }
    Column(modifier.fillMaxWidth().background(Color(0xFF111318)).border(1.dp, Border)) {
        Row(
            Modifier.fillMaxWidth().height(if (compact) 32.dp else 34.dp).background(Panel)
                .padding(horizontal = if (compact) 10.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 18.dp),
        ) {
            BottomPanel.entries.forEach { panel ->
                val selected = panel == selectedPanel
                Column(
                    Modifier.fillMaxHeight().clickable { focusManager.clearFocus(force = true); selectedPanel = panel },
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        panel.label,
                        color = if (selected) Foreground else Muted,
                        fontSize = if (compact) 10.sp else 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                    Box(
                        Modifier.align(Alignment.CenterHorizontally).width(if (compact) 46.dp else 54.dp).height(2.dp)
                            .background(if (selected) Accent else Color.Transparent),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (selectedPanel == BottomPanel.Terminal) {
                Text(
                    "＋",
                    color = Muted,
                    fontSize = if (compact) 16.sp else 18.sp,
                    modifier = Modifier.clickable { onNewTerminal() }
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                )
                Text(
                    "⌫",
                    color = if (selectedTerminalId != 0) Muted else Muted.copy(alpha = 0.35f),
                    fontSize = if (compact) 15.sp else 17.sp,
                    modifier = Modifier.clickable(enabled = selectedTerminalId != 0) {
                        onDeleteTerminal(selectedTerminalId)
                    }
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                )
            }
        }
        Column(Modifier.fillMaxSize()) {
            if (selectedPanel == BottomPanel.Terminal) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        val terminalPadding = if (compact) 9.dp else 12.dp
                        val terminalTopPadding = if (compact) 3.dp else 5.dp
                        LazyColumn(
                            state = terminalScroll,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = terminalPadding,
                                top = terminalTopPadding,
                                end = terminalPadding,
                                bottom = 2.dp,
                            ),
                        ) {
                            // SelectionContainer cannot safely own a LazyColumn whose
                            // selectable children are added and removed while output is
                            // streaming. Keep one stable selectable transcript instead.
                            item(key = "terminal-transcript") {
                                SelectionContainer(Modifier.fillMaxWidth()) {
                                    Text(
                                        // An empty transcript must not reserve a blank
                                        // line above the live terminal prompt.
                                        terminalText.trimEnd(),
                                        color = Foreground,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = terminalFontSize,
                                        lineHeight = terminalLineHeight,
                                        softWrap = true,
                                        overflow = TextOverflow.Clip,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            terminalProgress?.let { progress ->
                                item(key = "terminal-progress") {
                                    Row(
                                        Modifier.widthIn(max = 320.dp).fillMaxWidth().padding(
                                            top = 4.dp,
                                            bottom = 6.dp,
                                        ),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (progress.fraction != null) {
                                            LinearProgressIndicator(
                                                progress = { progress.fraction },
                                                modifier = Modifier.weight(1f).height(5.dp),
                                                color = Accent,
                                                trackColor = Border,
                                            )
                                            Spacer(Modifier.width(9.dp))
                                            Text(
                                                "${(progress.fraction * 100).toInt()}%",
                                                color = Accent,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = terminalFontSize,
                                            )
                                        } else {
                                            LinearProgressIndicator(
                                                modifier = Modifier.weight(1f).height(5.dp),
                                                color = Accent,
                                                trackColor = Border,
                                            )
                                            Spacer(Modifier.width(9.dp))
                                            Text(
                                                "WORKING",
                                                color = Muted,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 9.sp,
                                            )
                                        }
                                    }
                                }
                            }
                            item(key = "terminal-input") {
                                if (selectedTerminalId == 0) {
                                    Text(
                                        "No terminal session · press + to create one",
                                        color = Muted,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = terminalFontSize,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                } else if (inputVisible) {
                                    TerminalInputField(
                                        value = consoleInput,
                                        prompt = if (consoleActive) "" else "${terminalPrompt.ifBlank { "workspace" }} \$ ",
                                        promptColor = if (consoleActive) Accent else Success,
                                        placeholder = if (consoleActive) "program input" else "",
                                        // TerminalInputField is an Android EditText, so it does
                                        // not inherit the scaled Compose Density used by DeX.
                                        fontSizeSp = terminalFontSize.value * nativeInputScale,
                                        onValueChange = { consoleInput = it },
                                        onSubmit = { submitInput() },
                                        onHistoryUp = { recallHistory(-1) },
                                        onHistoryDown = { recallHistory(1) },
                                        modifier = Modifier.fillMaxWidth().padding(bottom = terminalPadding),
                                    )
                                }
                            }
                        }
                    }
                    if (terminalSelectorVisible && terminalSessions.isNotEmpty()) {
                        if (desktopMode) {
                            Box(
                                Modifier.width(5.dp).fillMaxHeight()
                                    .background(Border.copy(alpha = 0.55f))
                                    .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
                                    .pointerInput(density) {
                                        var draggedWidth = latestSelectorWidth
                                        detectHorizontalDragGestures(
                                            onDragStart = { draggedWidth = latestSelectorWidth },
                                        ) { _, dragAmount ->
                                            draggedWidth = (draggedWidth - dragAmount / density.density).coerceIn(40f, 240f)
                                            terminalSelectorWidth = draggedWidth
                                        }
                                    },
                            )
                        }
                        Column(
                            Modifier.width(if (desktopMode) terminalSelectorWidth.dp else 40.dp)
                                .fillMaxHeight().background(Panel).verticalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                        ) {
                            terminalSessions.forEach { session ->
                                val selected = session.id == selectedTerminalId
                                Row(
                                    Modifier.fillMaxWidth()
                                        .background(if (selected) Color(0xFF094771) else Color.Transparent)
                                        .clickable { onSelectTerminal(session.id) }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Text("▣", color = if (selected) Foreground else Muted, fontSize = 11.sp)
                                    if (desktopMode && terminalSelectorWidth >= 82f) {
                                        Spacer(Modifier.width(7.dp))
                                        Text(
                                            if (session.webServerOwner) "Web server" else "Shell",
                                            color = if (selected) Foreground else Muted,
                                            fontSize = 11.sp,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    if (session.commandRunning) Text("●", color = Accent, fontSize = 7.sp)
                                }
                            }
                        }
                    }
                }
            } else {
                Text(
                    panelText,
                    color = if (selectedPanel == BottomPanel.Problems && problems.isNotEmpty()) Color(0xFFFF8A80) else Foreground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = terminalFontSize,
                    lineHeight = terminalLineHeight,
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(if (compact) 9.dp else 12.dp),
                )
            }
        }
    }
}
