package dev.foldcode.ide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.InputDevice
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.lang.format.Formatter
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.DirectAccessProps
import io.github.rosemoe.sora.widget.EditorRenderer
import io.github.rosemoe.sora.widget.SymbolPairMatch
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.component.DefaultCompletionLayout
import io.github.rosemoe.sora.widget.component.DiagnosticTooltipLayout
import io.github.rosemoe.sora.widget.component.EditorCompletionAdapter
import io.github.rosemoe.sora.widget.component.EditorDiagnosticTooltipWindow
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.style.DiagnosticIndicatorStyle
import io.github.rosemoe.sora.util.IntPair
import org.eclipse.tm4e.core.registry.IThemeSource
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlin.math.min

/** Shared TextMate registry. Grammars are tiny base-app resources; compilers stay extensions. */
private object FoldCodeTextMateRegistry {
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val assets = context.applicationContext.assets
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
        val path = "textmate/foldcode-dark.json"
        val theme = ThemeModel(
            IThemeSource.fromInputStream(assets.open(path), path, null),
            "foldcode-dark",
        ).apply { isDark = true }
        ThemeRegistry.getInstance().loadTheme(theme)
        ThemeRegistry.getInstance().setTheme("foldcode-dark")
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
        initialized = true
    }
}

/** The toolbar targets whichever normal-sized source editor is currently visible. */
internal object ActiveEditorHistory {
    private var activeEditor = WeakReference<CodeEditor>(null)

    fun attach(editor: CodeEditor) {
        activeEditor = WeakReference(editor)
    }

    fun detach(editor: CodeEditor) {
        if (activeEditor.get() === editor) activeEditor.clear()
    }

    fun canUndo(): Boolean = activeEditor.get()?.canUndo() == true

    fun canRedo(): Boolean = activeEditor.get()?.canRedo() == true

    fun undo() {
        activeEditor.get()?.takeIf { it.canUndo() }?.undo()
    }

    fun redo() {
        activeEditor.get()?.takeIf { it.canRedo() }?.redo()
    }

    fun selectAll() {
        activeEditor.get()?.selectAll()
    }
}

/**
 * Keeps TextMate's incremental analysis/indentation while supplying FoldCode semantic items.
 * The semantic list is updated by the C/C++ intelligence backend without recreating the editor.
 */
private class FoldCodeCppLanguage(
    private val textMate: TextMateLanguage,
    private val semanticItems: AtomicReference<List<CompletionItem>>,
    private val fileName: String,
    private val indentWithTabs: AtomicBoolean,
) : Language {
    override fun getAnalyzeManager(): AnalyzeManager = textMate.analyzeManager
    override fun getInterruptionLevel(): Int = textMate.interruptionLevel
    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int =
        textMate.getIndentAdvance(content, line, column)
    override fun useTab(): Boolean = indentWithTabs.get()
    override fun getFormatter(): Formatter = textMate.formatter
    override fun getSymbolPairs(): SymbolPairMatch = textMate.symbolPairs
    override fun getNewlineHandlers(): Array<NewlineHandler> = textMate.newlineHandlers ?: emptyArray()

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle,
    ) {
        val source = content.reference.toString()
        val cursor = position.index.coerceIn(0, source.length)
        val context = semanticCompletionContext(source, cursor, fileName)
        if (!context.valid) return
        val includeItems = if (context.includeTriggered && context.openingDelimiter == '<') {
            standardHeaderCompletions
        } else {
            emptyList()
        }
        val eligibleItems = (includeItems + semanticItems.get())
            .distinctBy { it.label }
            .mapNotNull {
                if (completionMatchScore(it.label, context.prefix) == null) return@mapNotNull null
                val includeCandidateAllowed = when {
                    !context.includeTriggered -> true
                    context.prefix.startsWith('_') -> true
                    context.openingDelimiter == '"' ->
                        it.detail == "Project header" ||
                            (context.prefix.isNotEmpty() && isPublicIncludeCandidate(it))
                    context.openingDelimiter == '<' && context.prefix.isEmpty() ->
                        isUsefulEmptyAngleCandidate(it)
                    else -> isPublicIncludeCandidate(it)
                }
                if (includeCandidateAllowed) it else null
            }
        val candidates = rankSemanticCompletionItems(
            items = eligibleItems,
            prefix = context.prefix,
            // clangd and the offline semantic engine already return namespace/member
            // completions in relevance order. Re-sorting an empty `std::` result
            // alphabetically pushed common entries such as cout/cin past the UI limit.
            preserveSourceOrder = context.operatorTriggered && context.prefix.isEmpty(),
        )
            .mapNotNull {
                var insertion = if (context.includeTriggered) it.insertion.trimStart() else it.insertion
                insertion = normalizePreprocessorInsertion(context, it.label, insertion)
                // clangd occasionally advertises `include "header` while its
                // textEdit contains only `include`. Never show a completion
                // that would replace the current prefix with identical text.
                if (insertion == context.prefix) return@mapNotNull null
                val closing = context.closingDelimiter
                if (closing != null && source.getOrNull(cursor) == closing && insertion.endsWith(closing)) {
                    // Samsung Keyboard and Sora commonly insert paired "" or <>.
                    // Reuse the existing closer instead of producing "header.h"".
                    insertion = insertion.dropLast(1)
                }
                SimpleCompletionItem(it.label, it.displayCategory(), context.prefix.length, insertion)
            }
        publisher.addItems(candidates)
        publisher.updateList(true)
    }

    override fun destroy() = textMate.destroy()
}

internal fun rankSemanticCompletionItems(
    items: List<CompletionItem>,
    prefix: String,
    preserveSourceOrder: Boolean,
    limit: Int = 80,
): List<CompletionItem> {
    val scored = items.mapNotNull { item ->
        val score = completionMatchScore(item.label, prefix) ?: return@mapNotNull null
        item to score
    }
    val ranked = if (preserveSourceOrder) {
        scored
    } else {
        scored.sortedWith(
            compareBy<Pair<CompletionItem, Int>> { it.second }
                .thenBy { it.first.label.lowercase() },
        )
    }
    return ranked.asSequence().map { it.first }.take(limit).toList()
}

private const val DESKTOP_SCROLLBAR_HIDE_DELAY_MS = 2_500L

private class FoldCodeEditorRenderer(private val owner: FoldCodeSoraView) : EditorRenderer(owner) {
    override fun drawScrollBars(canvas: Canvas) {
        verticalScrollBarRect.setEmpty()
        horizontalScrollBarRect.setEmpty()

        val handler = owner.eventHandler
        val temporaryMouseBars = owner.isInMouseMode && owner.desktopScrollbarsVisible
        if (!handler.shouldDrawScrollBarForTouch() && !temporaryMouseBars) return

        val fade = if (temporaryMouseBars) 0f else handler.scrollBarFadeOutPercentageForTouch
        val fadeOffset = owner.dpUnit * 10f * fade
        if (
            owner.isHorizontalScrollBarEnabled &&
            !owner.isWordwrap &&
            hasHorizontalEditorOverflow(owner.scrollMaxX, owner.width)
        ) {
            val state = canvas.save()
            canvas.translate(0f, fadeOffset)
            drawScrollBarTrackHorizontal(canvas)
            drawScrollBarHorizontal(canvas)
            canvas.restoreToCount(state)
        }
        if (
            owner.isVerticalScrollBarEnabled &&
            hasVerticalEditorOverflow(owner.scrollMaxY, owner.height, owner.verticalExtraSpaceFactor)
        ) {
            val state = canvas.save()
            canvas.translate(fadeOffset, 0f)
            drawScrollBarTrackVertical(canvas)
            drawScrollBarVertical(canvas)
            canvas.restoreToCount(state)
        }
    }
}

private class FoldCodeSoraView(context: Context, private val fileName: String) : CodeEditor(context) {
    val semanticItems = AtomicReference<List<CompletionItem>>(emptyList())
    private val indentWithTabs = AtomicBoolean(false)
    var applyingExternalText = false
    private var appliedFontSizeSp = Float.NaN
    private var appliedLineHeightSp = Float.NaN
    private var appliedGutterWidthDp = Float.NaN
    private var appliedDensity = Float.NaN
    private var appliedScaledDensity = Float.NaN
    private var appliedWordWrap: Boolean? = null
    private var appliedTabWidth: Int? = null
    private var appliedIndentWithSpaces: Boolean? = null
    private var appliedScrollbarsVisible: Boolean? = null
    private var diagnosticErrorLines: Set<Int> = emptySet()
    private var breakpointLines: Set<Int> = emptySet()
    private var replaceBreakpointLineNumber = false
    private var handlingGutterTap = false
    private var gutterTapLine = -1
    private var draggingHorizontalScrollbar = false
    private var horizontalScrollbarDownX = 0f
    private var horizontalScrollbarStartOffset = 0
    private var mouseTextGestureActive = false
    private val consumedHardwareKeys = mutableSetOf<Int>()
    private val wheelScrollAccumulator = MouseWheelScrollAccumulator()
    private val scrollbarHandler = Handler(Looper.getMainLooper())
    var desktopScrollbarsVisible = false
        private set
    private val hideDesktopScrollbars = Runnable {
        desktopScrollbarsVisible = false
        invalidate()
    }
    private val wheelHorizontalFactor = ViewConfiguration.get(context).scaledHorizontalScrollFactor
    private val wheelVerticalFactor = ViewConfiguration.get(context).scaledVerticalScrollFactor
    var editorZoomDelta: ((Float) -> Unit)? = null
    private val gutterBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(24, 26, 31)
        style = Paint.Style.FILL
    }
    private val gutterDiagnosticPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(244, 67, 54)
        style = Paint.Style.FILL
    }
    private val gutterBreakpointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(229, 83, 75)
        style = Paint.Style.FILL
    }

    override fun onCreateRenderer(): EditorRenderer = FoldCodeEditorRenderer(this)

    init {
        FoldCodeTextMateRegistry.initialize(context)
        typefaceText = Typeface.MONOSPACE
        typefaceLineNumber = Typeface.MONOSPACE
        setPinLineNumber(true)
        isWordwrap = false
        isHighlightCurrentLine = true
        isHighlightCurrentBlock = true
        isHighlightBracketPair = true
        isBlockLineEnabled = true
        setDiagnosticIndicatorStyle(DiagnosticIndicatorStyle.LINE)
        setBlockLineWidth(1f)
        // Sora's own scale gesture competes with desktop mouse-wheel scrolling
        // and briefly compresses the text before scrolling. DeX uses the
        // explicit Ctrl+wheel callback below instead.
        isScalable = false
        setScrollBarEnabled(false)
        colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance()).apply {
            setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.WHOLE_BACKGROUND, Color.rgb(24, 26, 31))
            setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER_BACKGROUND, Color.rgb(24, 26, 31))
            setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_DIVIDER, Color.rgb(52, 56, 64))
            setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.CURRENT_LINE, Color.rgb(32, 36, 43))
            setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.BLOCK_LINE, Color.rgb(62, 68, 78))
            setColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.BLOCK_LINE_CURRENT, Color.rgb(94, 129, 172))
            setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, Color.rgb(37, 40, 48))
            setColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT, Color.rgb(9, 71, 113))
            setColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, Color.rgb(236, 239, 244))
            setColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, Color.rgb(180, 187, 200))
            setColor(EditorColorScheme.PROBLEM_ERROR, Color.rgb(244, 67, 54))
            setColor(EditorColorScheme.PROBLEM_WARNING, Color.rgb(255, 193, 7))
        }
        setEditorLanguage(FoldCodeCppLanguage(
            TextMateLanguage.create(
                when {
                    fileName.endsWith(".rs", ignoreCase = true) -> "source.rust"
                    fileName.endsWith(".py", ignoreCase = true) -> "source.python"
                    fileName.endsWith(".tsx", true) || fileName.endsWith(".jsx", true) -> "source.tsx"
                    fileName.endsWith(".js", true) || fileName.endsWith(".mjs", true) || fileName.endsWith(".cjs", true) -> "source.js"
                    fileName.endsWith(".ts", true) -> "source.ts"
                    fileName.endsWith(".html", true) || fileName.endsWith(".htm", true) -> "text.html.basic"
                    fileName.endsWith(".css", true) -> "source.css"
                    fileName.endsWith(".json", true) || fileName.substringAfterLast('/') == "package.json" -> "source.json"
                    isFortranSource(fileName) -> "source.fortran"
                    isCobolSource(fileName) -> "source.cobol"
                    isAssemblySource(fileName) -> "source.assembly"
                    else -> "source.cpp"
                },
                false,
            ),
            semanticItems,
            fileName,
            indentWithTabs,
        ))
        getComponent(EditorAutoCompletion::class.java).apply {
            setLayout(DefaultCompletionLayout())
            setAdapter(FoldCodeCompletionAdapter())
            setMaxHeight((156 * resources.displayMetrics.density).toInt())
            setHighlightMatchedLabel(true)
            setCompletionWndPositionMode(EditorAutoCompletion.WINDOW_POS_MODE_AUTO)
        }
        getComponent(EditorDiagnosticTooltipWindow::class.java).apply {
            layout = FoldCodeDiagnosticLayout()
        }
    }

    fun applyDiagnostics(diagnostics: List<CodeDiagnostic>) {
        diagnosticErrorLines = diagnostics.asSequence()
            .filter { it.severity == "error" }
            .map { (it.line - 1).coerceAtLeast(0) }
            .toSet()
        setDiagnostics(diagnostics.toSoraDiagnostics(this))
        invalidate()
    }

    fun applyBreakpoints(lines: Set<Int>) {
        breakpointLines = lines.map { (it - 1).coerceAtLeast(0) }.toSet()
        invalidate()
    }

    fun applyFoldCodePalette(palette: IdePalette) {
        gutterBackgroundPaint.color = palette.background.toArgb()
        colorScheme.apply {
            setColor(EditorColorScheme.WHOLE_BACKGROUND, palette.background.toArgb())
            setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, palette.background.toArgb())
            setColor(EditorColorScheme.LINE_DIVIDER, palette.border.toArgb())
            setColor(EditorColorScheme.CURRENT_LINE, palette.panel.toArgb())
            setColor(EditorColorScheme.BLOCK_LINE, palette.border.toArgb())
            setColor(EditorColorScheme.BLOCK_LINE_CURRENT, palette.accent.toArgb())
            setColor(EditorColorScheme.LINE_NUMBER, palette.muted.toArgb())
            setColor(EditorColorScheme.TEXT_NORMAL, palette.foreground.toArgb())
            setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, palette.panel.toArgb())
            // Keep the selected completion readable in every theme. The default
            // TextMate value is nearly white and washes out the row's text.
            setColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT, palette.accent.copy(alpha = 0.55f).toArgb())
            setColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, palette.foreground.toArgb())
            setColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, palette.muted.toArgb())
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isLineNumberEnabled) return
        // CodeEditor scrolls through View.scrollTo(), so Android supplies onDraw() with a
        // canvas that is already translated by -scrollX/-scrollY. Sora cancels that
        // translation while drawing its own fixed gutter. Do the same for our custom
        // markers; getCharOffsetY() already returns viewport coordinates and would
        // otherwise have the vertical scroll offset applied a second time.
        val markerCanvasState = canvas.save()
        canvas.translate(offsetX.toFloat(), offsetY.toFloat())
        try {
            val density = resources.displayMetrics.density
            val diagnosticRadius = 2.5f * density
            val breakpointRadius = 4.25f * density
            // Draw in the gutter's dedicated left marker lane, before the digits.
            val markerX = if (replaceBreakpointLineNumber) 5f * density else 17f * density
            diagnosticErrorLines.forEach { line ->
                if (line >= lineCount) return@forEach
                // getCharOffsetY() is the row bottom, not its top.
                val centerY = getCharOffsetY(line, 0) - rowHeight / 2f
                if (centerY + diagnosticRadius >= 0f && centerY - diagnosticRadius <= height) {
                    canvas.drawCircle(markerX, centerY, diagnosticRadius, gutterDiagnosticPaint)
                }
            }
            breakpointLines.forEach { line ->
                if (line >= lineCount) return@forEach
                val centerY = getCharOffsetY(line, 0) - rowHeight / 2f
                if (centerY + breakpointRadius >= 0f && centerY - breakpointRadius <= height) {
                    val markerX = if (replaceBreakpointLineNumber) {
                        val gutterScale = appliedGutterWidthDp.takeIf(Float::isFinite)?.div(36f) ?: 1f
                        // Erase this row's number while preserving the gutter divider.
                        canvas.drawRect(
                            0f,
                            centerY - rowHeight / 2f,
                            (measureTextRegionOffset() - 8f * density).coerceAtLeast(0f),
                            centerY + rowHeight / 2f,
                            gutterBackgroundPaint,
                        )
                        26f * gutterScale * density
                    } else {
                        7f * density
                    }
                    canvas.drawCircle(markerX, centerY, breakpointRadius, gutterBreakpointPaint)
                }
            }
        } finally {
            canvas.restoreToCount(markerCanvasState)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isMouseEvent(event) && event.actionMasked != MotionEvent.ACTION_CANCEL) {
            showDesktopScrollbarsTemporarily()
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            mouseTextGestureActive = isMouseEvent(event) &&
                event.buttonState and MotionEvent.BUTTON_PRIMARY != 0 &&
                event.x > measureTextRegionOffset() &&
                !isHorizontalScrollbarStrip(event.y)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (isMouseEvent(event) && isHorizontalScrollbarThumbHit(event.x, event.y)) {
                // Sora requires an exact hit on its narrow painted thumb. Give mouse
                // users a small tolerance so the bar remains draggable on DeX/high-DPI
                // displays while retaining the library's normal touch behavior.
                draggingHorizontalScrollbar = true
                horizontalScrollbarDownX = event.x
                horizontalScrollbarStartOffset = offsetX
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            } else if (event.x <= measureTextRegionOffset()) {
                // MotionEvent coordinates are relative to the visible editor viewport.
                // getPointPosition() expects document-layout coordinates, so using it
                // here loses offsetY and selects a line above the one shown in a
                // scrolled editor. The screen variant applies both scroll offsets and
                // also handles Sora's sticky-line region consistently.
                val packed = getPointPositionOnScreen(event.x, event.y)
                gutterTapLine = IntPair.getFirst(packed)
                handlingGutterTap = gutterTapLine in 0 until lineCount
                if (handlingGutterTap) return true
            }
            MotionEvent.ACTION_MOVE -> if (draggingHorizontalScrollbar) {
                scrollHorizontalScrollbarTo(event.x)
                return true
            } else if (handlingGutterTap) return true
            MotionEvent.ACTION_UP -> if (draggingHorizontalScrollbar) {
                scrollHorizontalScrollbarTo(event.x)
                draggingHorizontalScrollbar = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            } else if (handlingGutterTap) {
                val line = gutterTapLine
                handlingGutterTap = false
                gutterTapLine = -1
                if (line in 0 until lineCount) {
                    DebugBreakpointStore.toggle(fileName, line + 1)
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (draggingHorizontalScrollbar) {
                draggingHorizontalScrollbar = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            } else if (handlingGutterTap) {
                handlingGutterTap = false
                gutterTapLine = -1
                return true
            }
        }
        if (mouseTextGestureActive) {
            // Sora's cached TextAdvances hit test floors every pointer position
            // to the preceding character boundary. Shift mouse coordinates by
            // half of the actual cell so clicks resolve to the nearest boundary,
            // while leaving touch selection unchanged.
            val adjusted = MotionEvent.obtain(event)
            adjusted.offsetLocation(mouseCaretHitTestBias(event.x, event.y), 0f)
            val handled = super.onTouchEvent(adjusted)
            adjusted.recycle()
            if (
                event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                mouseTextGestureActive = false
            }
            return handled
        }
        return super.onTouchEvent(event)
    }

    private fun mouseCaretHitTestBias(x: Float, y: Float): Float {
        val packed = getPointPositionOnScreen(x, y)
        val line = IntPair.getFirst(packed)
        val column = IntPair.getSecond(packed)
        if (line !in 0 until lineCount || column !in 0 until text.getColumnCount(line)) return 0f
        val cellStart = getCharOffsetX(line, column)
        val cellEnd = getCharOffsetX(line, column + 1)
        return nearestCaretBoundaryBias(cellStart, cellEnd)
    }

    private fun isMouseEvent(event: MotionEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.getToolType(event.actionIndex.coerceAtLeast(0)) == MotionEvent.TOOL_TYPE_MOUSE

    private fun isHorizontalScrollbarStrip(y: Float): Boolean =
        appliedScrollbarsVisible == true &&
            desktopScrollbarsVisible &&
            hasHorizontalEditorOverflow(scrollMaxX, width) &&
            y >= height - 16f * resources.displayMetrics.density

    private fun isHorizontalScrollbarThumbHit(x: Float, y: Float): Boolean {
        if (!isHorizontalScrollbarStrip(y)) return false
        val thumb = renderer.horizontalScrollBarRect
        if (thumb.isEmpty) return false
        val density = resources.displayMetrics.density
        return RectF(thumb).apply {
            inset(-4f * density, -6f * density)
        }.contains(x, y)
    }

    private fun scrollHorizontalScrollbarTo(pointerX: Float) {
        showDesktopScrollbarsTemporarily()
        val thumbWidth = renderer.horizontalScrollBarRect.width()
        val travel = (width - thumbWidth).coerceAtLeast(1f)
        val targetX = (
            horizontalScrollbarStartOffset +
                (pointerX - horizontalScrollbarDownX) * scrollMaxX / travel
            ).toInt().coerceIn(0, scrollMaxX)
        scroller.startScroll(targetX, offsetY, 0, 0, 0)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DesktopPointerFocusRouter.register(this)
    }

    override fun onDetachedFromWindow() {
        scrollbarHandler.removeCallbacks(hideDesktopScrollbars)
        DesktopPointerFocusRouter.unregister(this)
        super.onDetachedFromWindow()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (
            isMouseEvent(event) &&
            event.actionMasked in setOf(
                MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_HOVER_MOVE,
                MotionEvent.ACTION_SCROLL,
            )
        ) {
            showDesktopScrollbarsTemporarily()
        }
        if (
            (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
                event.actionMasked == MotionEvent.ACTION_SCROLL) &&
            !hasFocus()
        ) {
            // DeX can route ACTION_SCROLL to the previously focused native pane.
            // Focus on pointer entry so the hovered editor owns the wheel beforehand.
            requestFocus()
        }
        if (
            event.actionMasked == MotionEvent.ACTION_SCROLL &&
            event.metaState and KeyEvent.META_CTRL_ON != 0
        ) {
            val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (vertical != 0f) {
                editorZoomDelta?.invoke(if (vertical > 0f) 0.10f else -0.10f)
                return true
            }
        }

        if (
            event.actionMasked == MotionEvent.ACTION_SCROLL &&
            (
                event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) ||
                    event.getAxisValue(MotionEvent.AXIS_HSCROLL) != 0f ||
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL) != 0f
            )
        ) {
            var deltaX = -event.getAxisValue(MotionEvent.AXIS_HSCROLL) * wheelHorizontalFactor
            var deltaY = -event.getAxisValue(MotionEvent.AXIS_VSCROLL) * wheelVerticalFactor
            if (event.metaState and KeyEvent.META_ALT_ON != 0) {
                deltaX *= props.fastScrollSensitivity
                deltaY *= props.fastScrollSensitivity
            }
            if (event.metaState and KeyEvent.META_SHIFT_ON != 0) {
                val originalX = deltaX
                deltaX = deltaY
                deltaY = originalX
            }

            val step = wheelScrollAccumulator.consume(deltaX, deltaY)
            if (step.x != 0 || step.y != 0) {
                val currentX = offsetX
                val currentY = offsetY
                val unclampedX = currentX + step.x
                val unclampedY = currentY + step.y
                val targetX = unclampedX.coerceIn(0, scrollMaxX)
                val targetY = unclampedY.coerceIn(0, scrollMaxY)

                if (targetX != unclampedX) wheelScrollAccumulator.clearX()
                if (targetY != unclampedY) wheelScrollAccumulator.clearY()

                // Sora routes wheel events through its touch scroller. Free-spin wheels can
                // deliver another event before that zero-duration scroll is committed, so
                // several events repeatedly start at the same stale offset. Synchronize the
                // scroller and view immediately to keep every wheel step and avoid false ends.
                if (targetX != currentX || targetY != currentY) {
                    dispatchEvent(
                        ScrollEvent(
                            this,
                            currentX,
                            currentY,
                            targetX,
                            targetY,
                            ScrollEvent.CAUSE_USER_DRAG,
                        ),
                    )
                }
                scroller.startScroll(targetX, targetY, 0, 0, 0)
                invalidate()
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun showDesktopScrollbarsTemporarily() {
        if (appliedScrollbarsVisible != true) return
        desktopScrollbarsVisible = true
        scrollbarHandler.removeCallbacks(hideDesktopScrollbars)
        scrollbarHandler.postDelayed(hideDesktopScrollbars, DESKTOP_SCROLLBAR_HIDE_DELAY_MS)
        invalidate()
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): android.view.PointerIcon? {
        // CodeEditor delegates this to individual rendered-text hit regions, so
        // DeX can show an I-beam over glyphs but an arrow over trailing space or
        // an unfocused split pane. The complete area after the gutter is still
        // editable/selectable text and should use one consistent cursor.
        if (
            isHorizontalScrollbarStrip(event.y) ||
            renderer.verticalScrollBarRect.contains(event.x, event.y)
        ) {
            return android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_ARROW)
        }
        if (event.x > measureTextRegionOffset()) {
            return android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_TEXT)
        }
        return super.onResolvePointerIcon(event, pointerIndex)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (
            isPhysicalKeyboardEvent(event) &&
            !event.isCtrlPressed &&
            !event.isAltPressed &&
            !event.isMetaPressed
        ) {
            when (keyCode) {
                KeyEvent.KEYCODE_SHIFT_LEFT,
                KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                    // Sora can interpret modifier-only DeX events as U+0000 and
                    // insert the visible text "NULL". Keep consuming the event,
                    // but forward it to Sora's modifier-state tracker so arrow
                    // keys extend the selection while Shift is held.
                    keyMetaStates.onKeyDown(event)
                    consumedHardwareKeys += keyCode
                    return true
                }

                KeyEvent.KEYCODE_DEL -> {
                    deleteText()
                    consumedHardwareKeys += keyCode
                    return true
                }
            }

            hardwareKeyText(keyCode, event)?.let { text ->
                commitText(text)
                consumedHardwareKeys += keyCode
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!consumedHardwareKeys.remove(keyCode)) return super.onKeyUp(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            keyMetaStates.onKeyUp(event)
        }
        return true
    }

    private fun isPhysicalKeyboardEvent(event: KeyEvent): Boolean =
        event.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD &&
            (event.source and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD

    private fun hardwareKeyText(keyCode: Int, event: KeyEvent): String? {
        val numpadText = when (keyCode) {
            KeyEvent.KEYCODE_NUMPAD_0 -> "0"
            KeyEvent.KEYCODE_NUMPAD_1 -> "1"
            KeyEvent.KEYCODE_NUMPAD_2 -> "2"
            KeyEvent.KEYCODE_NUMPAD_3 -> "3"
            KeyEvent.KEYCODE_NUMPAD_4 -> "4"
            KeyEvent.KEYCODE_NUMPAD_5 -> "5"
            KeyEvent.KEYCODE_NUMPAD_6 -> "6"
            KeyEvent.KEYCODE_NUMPAD_7 -> "7"
            KeyEvent.KEYCODE_NUMPAD_8 -> "8"
            KeyEvent.KEYCODE_NUMPAD_9 -> "9"
            KeyEvent.KEYCODE_NUMPAD_ADD -> "+"
            KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "-"
            KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> "*"
            KeyEvent.KEYCODE_NUMPAD_DIVIDE -> "/"
            KeyEvent.KEYCODE_NUMPAD_DOT -> "."
            KeyEvent.KEYCODE_NUMPAD_COMMA -> ","
            KeyEvent.KEYCODE_NUMPAD_EQUALS -> "="
            KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN -> "("
            KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN -> ")"
            KeyEvent.KEYCODE_NUMPAD_ENTER -> "\n"
            else -> null
        }
        if (numpadText != null) return numpadText

        val unicode = event.unicodeChar
        return if (unicode >= 0x20 && unicode != 0x7f) {
            String(Character.toChars(unicode))
        } else {
            null
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun applyFoldCodeMetrics(
        fontSizeSp: Float,
        lineHeightSp: Float,
        gutterWidthDp: Float,
        replaceBreakpointLineNumber: Boolean,
        wordWrap: Boolean,
        tabWidth: Int,
        indentWithSpaces: Boolean,
        showScrollbars: Boolean,
    ) {
        val metrics = resources.displayMetrics
        val scaledDensity = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, metrics)
        if (
            appliedFontSizeSp == fontSizeSp &&
            appliedLineHeightSp == lineHeightSp &&
            appliedGutterWidthDp == gutterWidthDp &&
            appliedDensity == metrics.density &&
            appliedScaledDensity == scaledDensity &&
            this.replaceBreakpointLineNumber == replaceBreakpointLineNumber &&
            appliedWordWrap == wordWrap &&
            appliedTabWidth == tabWidth &&
            appliedIndentWithSpaces == indentWithSpaces &&
            appliedScrollbarsVisible == showScrollbars
        ) {
            return
        }
        appliedFontSizeSp = fontSizeSp
        appliedLineHeightSp = lineHeightSp
        appliedGutterWidthDp = gutterWidthDp
        this.replaceBreakpointLineNumber = replaceBreakpointLineNumber
        appliedDensity = metrics.density
        appliedScaledDensity = scaledDensity
        appliedWordWrap = wordWrap
        appliedTabWidth = tabWidth
        appliedIndentWithSpaces = indentWithSpaces
        appliedScrollbarsVisible = showScrollbars
        props.mouseMode = if (showScrollbars) {
            DirectAccessProps.MOUSE_MODE_ALWAYS
        } else {
            DirectAccessProps.MOUSE_MODE_AUTO
        }
        props.mouseModeAlwaysShowScrollbars = false
        setScrollBarEnabled(showScrollbars)
        if (showScrollbars) showDesktopScrollbarsTemporarily()
        isWordwrap = wordWrap
        setTabWidth(tabWidth.coerceIn(2, 8))
        indentWithTabs.set(!indentWithSpaces)
        isBlockLineEnabled = true

        // setTextSize() makes Sora rebuild its layout and restart the active
        // InputConnection. Reapplying it after every Compose recomposition made
        // the current-line highlight flash and reset symbol mode in some IMEs.
        setTextSize(fontSizeSp)
        val desiredRowHeight = lineHeightSp * scaledDensity
        val naturalRowHeight = rowHeightOfText.toFloat()
        setLineSpacing((desiredRowHeight - naturalRowHeight).coerceAtLeast(0f), 1f)

        // Recreate the previous fixed visual gutter: breathing room before the
        // number, a visible divider, then the editor's original left inset.
        val gutterScale = gutterWidthDp / 36f
        setLineNumberMarginLeft(8f * gutterScale * metrics.density)
        setDividerMargin(
            6f * gutterScale * metrics.density,
            10f * gutterScale * metrics.density,
        )
        setDividerWidth(metrics.density.coerceAtLeast(1f))
        lineNumberAlign = android.graphics.Paint.Align.RIGHT
    }

}

internal fun nearestCaretBoundaryBias(cellStart: Float, cellEnd: Float): Float =
    ((cellEnd - cellStart).coerceAtLeast(0f)) / 2f

internal fun hasHorizontalEditorOverflow(scrollMaxX: Int, viewportWidth: Int): Boolean =
    viewportWidth > 0 && scrollMaxX > viewportWidth / 2f

internal fun hasVerticalEditorOverflow(
    scrollMaxY: Int,
    viewportHeight: Int,
    verticalExtraSpaceFactor: Float,
): Boolean = viewportHeight > 0 && scrollMaxY > viewportHeight * verticalExtraSpaceFactor

/** One compact, predictable completion row for every FoldCode language. */
private class FoldCodeCompletionAdapter : EditorCompletionAdapter() {
    override fun getItemHeight(): Int = (34 * context.resources.displayMetrics.density).toInt()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup, isCurrentCursorPosition: Boolean): View {
        val density = context.resources.displayMetrics.density
        val row = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((9 * density).toInt(), 0, (8 * density).toInt(), 0)
            addView(TextView(context).apply {
                tag = "label"
                typeface = Typeface.MONOSPACE
                textSize = 12.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.75f))
            addView(TextView(context).apply {
                tag = "detail"
                typeface = Typeface.MONOSPACE
                textSize = 9.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.75f))
        }
        val item = getItem(position)
        val label = row.findViewWithTag<TextView>("label")
        val detail = row.findViewWithTag<TextView>("detail")
        label.text = item.label
        label.setTextColor(getThemeColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY))
        detail.visibility = View.VISIBLE
        detail.text = item.desc
        detail.setTextColor(getThemeColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY))
        row.setBackgroundColor(
            if (isCurrentCursorPosition) getThemeColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.COMPLETION_WND_ITEM_CURRENT)
            else Color.TRANSPARENT,
        )
        return row
    }
}

/** A single concise diagnostic instead of Sora's duplicated brief/detail panel. */
private class FoldCodeDiagnosticLayout : DiagnosticTooltipLayout {
    private lateinit var text: TextView
    private var pointerOver = false

    override fun attach(window: EditorDiagnosticTooltipWindow) = Unit

    override fun createView(inflater: LayoutInflater): View {
        val density = inflater.context.resources.displayMetrics.density
        text = TextView(inflater.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding((10 * density).toInt(), (7 * density).toInt(), (10 * density).toInt(), (7 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 7 * density
                setStroke((1 * density).toInt().coerceAtLeast(1), Color.rgb(68, 73, 83))
                setColor(Color.rgb(34, 37, 43))
            }
            setOnTouchListener { view, event ->
                pointerOver = event.action != android.view.MotionEvent.ACTION_UP &&
                    event.action != android.view.MotionEvent.ACTION_CANCEL
                if (event.action == android.view.MotionEvent.ACTION_UP) view.performClick()
                false
            }
        }
        return text
    }

    override fun applyColorScheme(colorScheme: io.github.rosemoe.sora.widget.schemes.EditorColorScheme) {
        if (::text.isInitialized) {
            text.setTextColor(colorScheme.getColor(io.github.rosemoe.sora.widget.schemes.EditorColorScheme.DIAGNOSTIC_TOOLTIP_BRIEF_MSG))
        }
    }

    override fun renderDiagnostic(diagnostic: DiagnosticDetail?) {
        if (!::text.isInitialized) return
        if (text.layoutParams == null) {
            text.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        text.text = diagnostic?.briefMessage ?: ""
    }

    override fun measureContent(maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        val density = text.resources.displayMetrics.density
        val width = min(maxWidth, (280 * density).toInt())
        text.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
        return text.measuredWidth to text.measuredHeight
    }

    override fun isPointerOverPopup(): Boolean = pointerOver
    override fun isMenuShowing(): Boolean = false
    override fun onWindowDismissed() { pointerOver = false }
}

@Composable
internal fun FoldCodeNativeEditor(
    fileName: String,
    content: String,
    readOnly: Boolean,
    fontSizeSp: Float,
    lineHeightSp: Float,
    gutterWidthDp: Float,
    replaceBreakpointLineNumber: Boolean,
    wordWrap: Boolean,
    tabWidth: Int,
    indentWithSpaces: Boolean,
    showScrollbars: Boolean,
    diagnostics: List<CodeDiagnostic>,
    navigationRequest: EditorNavigationRequest? = null,
    semanticCompletions: List<CompletionItem>,
    onFileChange: (String, String) -> Unit,
    onRequestSemanticCompletion: (String, String, Int) -> Unit,
    onRequestSemanticDiagnostics: (String, String) -> Unit,
    onEditorZoomDelta: ((Float) -> Unit)? = null,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = currentIdeThemePalette()
    var editor by remember(fileName) { mutableStateOf<FoldCodeSoraView?>(null) }
    var liveText by remember(fileName) { mutableStateOf(content) }
    var cursorIndex by remember(fileName) { mutableIntStateOf(content.length) }

    LaunchedEffect(fileName, liveText, cursorIndex, readOnly) {
        if (!readOnly && isCompletionEditorFile(fileName) && shouldRequestSemanticCompletion(liveText, cursorIndex, fileName)) {
            delay(650)
            onRequestSemanticCompletion(fileName, liveText, cursorIndex)
        }
    }
    LaunchedEffect(fileName, liveText, readOnly) {
        if (!readOnly && isDiagnosticsEditorFile(fileName)) {
            // Long enough to avoid analyzing every keystroke, but short enough
            // that syntax mistakes feel immediate on a phone editor.
            delay(900)
            onRequestSemanticDiagnostics(fileName, liveText)
        }
    }
    LaunchedEffect(semanticCompletions) {
        val view = editor ?: return@LaunchedEffect
        view.semanticItems.set(semanticCompletions)
        val context = semanticCompletionContext(view.text.toString(), view.cursor.left, fileName)
        if (view.hasFocus() && context.valid && semanticCompletions.isNotEmpty()) {
            val completion = view.getComponent(EditorAutoCompletion::class.java)
            completion.cancelCompletion()
            // ContentChangeEvent may have just finished an empty TextMate
            // completion pass. Start a fresh pass after the semantic items are
            // installed instead of reusing that stale completion session.
            view.post { if (view.hasFocus()) completion.requireCompletion() }
        }
    }
    LaunchedEffect(diagnostics, liveText) {
        val view = editor ?: return@LaunchedEffect
        view.applyDiagnostics(diagnostics)
    }
    LaunchedEffect(navigationRequest?.revision) {
        val request = navigationRequest ?: return@LaunchedEffect
        val view = editor ?: return@LaunchedEffect
        if (request.fileName != fileName || view.text.lineCount <= 0) return@LaunchedEffect
        val line = (request.line - 1).coerceIn(0, view.text.lineCount - 1)
        val column = (request.column - 1).coerceIn(0, view.text.getColumnCount(line))
        view.setSelection(line, column)
        view.ensurePositionVisible(line, column)
        view.requestFocus()
    }

    key(fileName) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                FoldCodeSoraView(context, fileName).also { view ->
                    editor = view
                    ActiveEditorHistory.attach(view)
                    view.applyFoldCodePalette(palette)
                    view.editorZoomDelta = onEditorZoomDelta
                    view.applyFoldCodeMetrics(fontSizeSp, lineHeightSp, gutterWidthDp, replaceBreakpointLineNumber, wordWrap, tabWidth, indentWithSpaces, showScrollbars)
                    view.isEditable = !readOnly
                    view.applyingExternalText = true
                    view.setText(content)
                    view.setSelection(0, 0)
                    view.applyingExternalText = false
                    view.setOnFocusChangeListener { _, focused -> onFocusChanged(focused) }
                    view.semanticItems.set(semanticCompletions)
                    view.applyDiagnostics(diagnostics)
                    view.applyBreakpoints(DebugBreakpointStore.lines(fileName))
                    view.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                        if (!view.applyingExternalText) {
                            val updated = view.text.toString()
                            liveText = updated
                            view.semanticItems.set(emptyList())
                            // A provider invocation can retain the completion items it
                            // captured before this edit. Invalidate that popup now,
                            // rather than allowing the old prefix/global list to remain
                            // visible until the debounced semantic request completes.
                            view.getComponent(EditorAutoCompletion::class.java).cancelCompletion()
                            onFileChange(fileName, updated)
                            // Sora dispatches ContentChangeEvent while its cursor can still
                            // describe the pre-edit position. Resolve it on the next UI turn;
                            // otherwise semantic completion sees an empty/previous prefix in
                            // TS/TSX (especially at the start of a new line) and never asks LSP.
                            view.post {
                                if (view.applyingExternalText) return@post
                                val settledText = view.text.toString()
                                var settledCursor = view.cursor.left
                                includePairCursor(settledText, settledCursor)?.let { targetIndex ->
                                    val target = view.text.indexer.getCharPosition(targetIndex)
                                    view.setSelection(target.line, target.column)
                                    settledCursor = targetIndex
                                }
                                liveText = settledText
                                cursorIndex = settledCursor
                                Log.d(
                                    "FoldCodeCompletion",
                                    "editor change file=$fileName cursor=$settledCursor prefix=${semanticCompletionContext(settledText, settledCursor, fileName).prefix}",
                                )
                            }
                        }
                    }
                }
            },
            update = { view ->
                editor = view
                ActiveEditorHistory.attach(view)
                view.applyFoldCodePalette(palette)
                view.editorZoomDelta = onEditorZoomDelta
                view.applyFoldCodeMetrics(fontSizeSp, lineHeightSp, gutterWidthDp, replaceBreakpointLineNumber, wordWrap, tabWidth, indentWithSpaces, showScrollbars)
                if (view.isEditable != !readOnly) {
                    view.isEditable = !readOnly
                }
                if (!view.hasFocus() && view.text.toString() != content) {
                    view.applyingExternalText = true
                    view.setText(content)
                    view.applyingExternalText = false
                    liveText = content
                }
                view.applyDiagnostics(diagnostics)
                view.applyBreakpoints(DebugBreakpointStore.lines(fileName))
            },
        )
    }

    DisposableEffect(fileName) {
        val breakpointObserver = DebugBreakpointStore.observe {
            editor?.post { editor?.applyBreakpoints(DebugBreakpointStore.lines(fileName)) }
        }
        onDispose {
            breakpointObserver.close()
            editor?.let(ActiveEditorHistory::detach)
            editor?.release()
            editor = null
        }
    }
}

private fun List<CodeDiagnostic>.toSoraDiagnostics(editor: CodeEditor): DiagnosticsContainer {
    val container = DiagnosticsContainer()
    val text = editor.text
    filter { it.severity == "error" || it.severity == "warning" }.forEachIndexed { index, diagnostic ->
        val raw = text.toString()
        if (text.lineCount == 0 || raw.isEmpty()) return@forEachIndexed
        val line = (diagnostic.line - 1).coerceIn(0, text.lineCount - 1)
        val column = (diagnostic.column - 1).coerceIn(0, text.getColumnCount(line))
        val start = text.getCharIndex(line, column)
        val end = generateSequence(start) { it + 1 }
            .takeWhile { it < raw.length && (raw[it].isLetterOrDigit() || raw[it] == '_') }
            .lastOrNull()?.plus(1) ?: (start + 1).coerceAtMost(raw.length)
        val severity = if (diagnostic.severity == "error") {
            DiagnosticRegion.SEVERITY_ERROR
        } else DiagnosticRegion.SEVERITY_WARNING
        container.addDiagnostic(
            DiagnosticRegion(
                start,
                end.coerceAtLeast(start + 1).coerceAtMost(raw.length),
                severity,
                index.toLong(),
                DiagnosticDetail(diagnostic.message, diagnostic.message, emptyList(), diagnostic),
            ),
        )
    }
    return container
}

private fun isCppEditorFile(fileName: String): Boolean = fileName.lowercase().let {
    it.endsWith(".c") || it.endsWith(".cc") || it.endsWith(".cpp") || it.endsWith(".cxx") ||
        it.endsWith(".h") || it.endsWith(".hh") || it.endsWith(".hpp") || it.endsWith(".hxx")
}

internal fun isCppIntelligenceFile(fileName: String): Boolean = isCppEditorFile(fileName)

private fun isCompletionEditorFile(fileName: String): Boolean =
    isCppEditorFile(fileName) || fileName.endsWith(".py", ignoreCase = true) || isWebEditorFile(fileName) ||
        fileName.endsWith(".rs", ignoreCase = true) || isFortranSource(fileName) ||
        isCobolSource(fileName) || isAssemblySource(fileName)

private fun isDiagnosticsEditorFile(fileName: String): Boolean =
    isCppEditorFile(fileName) || fileName.endsWith(".rs", ignoreCase = true) || isWebEditorFile(fileName) ||
        fileName.endsWith(".py", ignoreCase = true) || isFortranSource(fileName) ||
        isCobolSource(fileName) || isAssemblySource(fileName)

private fun isWebEditorFile(fileName: String): Boolean = listOf(
    ".js", ".mjs", ".cjs", ".jsx", ".ts", ".tsx", ".html", ".htm", ".css", ".json",
).any { fileName.endsWith(it, ignoreCase = true) }

private fun shouldRequestSemanticCompletion(source: String, cursor: Int, fileName: String): Boolean {
    val context = semanticCompletionContext(source, cursor, fileName)
    return context.operatorTriggered || context.includeTriggered || context.prefix.isNotEmpty()
}

internal fun isEmptyOperatorCompletion(source: String, cursor: Int, fileName: String): Boolean {
    val context = semanticCompletionContext(source, cursor, fileName)
    return context.operatorTriggered && context.prefix.isEmpty()
}

private data class SemanticCompletionContext(
    val prefix: String,
    val operatorTriggered: Boolean,
    val includeTriggered: Boolean,
    val preprocessorDirective: Boolean = false,
    val openingDelimiter: Char? = null,
    val closingDelimiter: Char? = null,
) {
    val valid: Boolean get() = operatorTriggered || includeTriggered || prefix.isNotEmpty()
}

private fun semanticCompletionContext(source: String, cursor: Int, fileName: String = ""): SemanticCompletionContext {
    val before = source.take(cursor.coerceIn(0, source.length))
    val currentLine = before.substringAfterLast('\n')
    val includePath = Regex("^\\s*#\\s*include\\s*[<\"]([^>\"]*)$")
        .matchEntire(currentLine)
        ?.groupValues
        ?.get(1)
    if (includePath != null) {
        val opening = currentLine.firstOrNull { it == '<' || it == '"' }
        return SemanticCompletionContext(
            prefix = includePath.substringAfterLast('/'),
            operatorTriggered = false,
            includeTriggered = true,
            preprocessorDirective = true,
            openingDelimiter = opening,
            closingDelimiter = if (opening == '<') '>' else '"',
        )
    }
    val prefixPattern = if (isCobolSource(fileName)) {
        Regex("[A-Za-z][A-Za-z0-9-]*$")
    } else {
        Regex("[A-Za-z_][A-Za-z0-9_]*$")
    }
    val prefix = prefixPattern.find(before)?.value.orEmpty()
    val operatorTriggered = prefix.isEmpty() &&
        (before.endsWith("::") || before.endsWith("->") || before.endsWith('.') ||
            (isFortranSource(fileName) && before.endsWith('%')))
    return SemanticCompletionContext(
        prefix,
        operatorTriggered,
        includeTriggered = false,
        preprocessorDirective = currentLine.trimStart().startsWith('#'),
    )
}

/** Turns clangd's descriptive directive rows into a useful next edit state. */
private fun normalizePreprocessorInsertion(
    context: SemanticCompletionContext,
    label: String,
    insertion: String,
): String {
    if (!context.preprocessorDirective || context.includeTriggered) return insertion
    val directive = when {
        label.startsWith("include_next", ignoreCase = true) -> "include_next"
        label.startsWith("include", ignoreCase = true) -> "include"
        else -> return insertion
    }
    return when {
        label.contains('"') -> "$directive \""
        label.contains('<') -> "$directive <"
        else -> insertion
    }
}

private val standardHeaderCompletions = listOf(
    "iostream", "vector", "string", "cmath", "algorithm", "array", "memory",
    "map", "unordered_map", "set", "unordered_set", "optional", "variant",
    "tuple", "utility", "functional", "iterator", "ranges", "span",
    "string_view", "filesystem", "fstream", "sstream", "iomanip", "limits",
    "numeric", "random", "chrono", "thread", "mutex", "atomic", "future",
    "condition_variable", "queue", "deque", "list", "stack", "bitset",
    "concepts", "type_traits", "exception", "stdexcept", "cstdint", "cstddef",
    "cstdio", "cstdlib", "cstring", "cctype", "cassert", "climits", "cfloat",
).map { header ->
    CompletionItem(header, "$header>", "C++ standard header")
}

private fun isPublicIncludeCandidate(item: CompletionItem): Boolean {
    val path = item.label.trim().trimEnd('>', '"')
    if (path.isEmpty()) return false
    val segments = path.split('/')
    // Hide Clang/libc++ implementation headers such as __chrono/ and
    // __clang_cuda_*. They remain reachable when explicitly typed, but do not
    // overwhelm the useful root include list.
    return segments.none { it.startsWith('_') } &&
        segments.first() !in setOf("bits", "ext")
}

private val publicCHeaders = setOf(
    "assert.h", "ctype.h", "errno.h", "float.h", "inttypes.h", "limits.h",
    "locale.h", "math.h", "setjmp.h", "signal.h", "stdarg.h", "stdbool.h",
    "stddef.h", "stdint.h", "stdio.h", "stdlib.h", "string.h", "time.h",
    "uchar.h", "wchar.h", "wctype.h",
)

/**
 * Keep an empty `<...>` popup useful. Compiler intrinsic and Android platform
 * roots are still returned after the user types a matching prefix, but they do
 * not displace the standard library and third-party SDK namespaces initially.
 */
private fun isUsefulEmptyAngleCandidate(item: CompletionItem): Boolean {
    if (item.detail == "C++ standard header") return true
    val path = item.label.trim().trimEnd('>', '"', '/')
    if (path in publicCHeaders) return true
    if (path in setOf("tusb.h", "pico.h")) return true
    val root = path.substringBefore('/').lowercase()
    if (root in setOf("pico", "hardware", "lwip", "mbedtls", "btstack", "tinyusb")) return true
    if ('/' !in path) return false
    if (root.startsWith('_') || root in setOf(
            "bits", "ext", "sles", "aarch64-linux-android", "arm-linux-androideabi",
            "android", "aaudio", "amidi", "camera", "egl", "gles", "gles2", "gles3",
            "media", "vulkan",
        )
    ) return false
    return isPublicIncludeCandidate(item)
}

/**
 * Some Samsung Keyboard configurations commit both characters of an include
 * pair and leave the cursor after them. Put it between the pair so header
 * completion has a valid insertion point. Normal quoted strings are untouched.
 */
private fun includePairCursor(source: String, cursor: Int): Int? {
    val safe = cursor.coerceIn(0, source.length)
    val before = source.take(safe)
    val line = before.substringAfterLast('\n')
    val paired = Regex("^\\s*#\\s*include\\s*(?:\"\"|<>)$").matches(line)
    return if (paired && safe > 0) safe - 1 else null
}
