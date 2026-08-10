package dev.foldcode.ide

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** DeX browser surface that also becomes the local preview for a running web project. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebPreviewBrowser(url: String, mode: LayoutMode, modifier: Modifier = Modifier) {
    val initialUrl = remember(url) { browserAddressToUrl(url) ?: DEFAULT_WEB_BROWSER_URL }
    var address by remember(initialUrl) { mutableStateOf(initialUrl) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var status by remember(initialUrl) { mutableStateOf("CONNECTING") }
    var console by remember(initialUrl) { mutableStateOf<List<String>>(emptyList()) }
    var showConsole by remember(initialUrl, mode) {
        mutableStateOf(mode != LayoutMode.Compact && isLocalWebAddress(initialUrl))
    }
    // The WebView itself stays alive when the workspace switches between the
    // local preview and the general browser. Do not reset this flag merely
    // because the model URL changed: the AndroidView update below starts the
    // replacement navigation and explicitly reveals the native view.
    var pageVisible by remember { mutableStateOf(false) }
    var consoleHeightDp by remember(initialUrl, mode) {
        mutableFloatStateOf(if (mode == LayoutMode.Expanded) 120f else 88f)
    }
    val consoleVerticalScroll = rememberScrollState()
    val consoleHorizontalScroll = rememberScrollState()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current

    fun navigateToAddress() {
        val destination = browserAddressToUrl(address) ?: return
        address = destination
        status = "CONNECTING"
        focusManager.clearFocus()
        webView?.let { view ->
            pageVisible = true
            view.visibility = View.VISIBLE
            view.loadUrl(destination)
        }
    }

    fun recoverWebViewBeforeNavigation(action: (WebView) -> Unit) {
        webView?.let { view ->
            pageVisible = true
            view.visibility = View.VISIBLE
            action(view)
        }
    }

    LaunchedEffect(console.size, consoleHeightDp) {
        consoleVerticalScroll.scrollTo(consoleVerticalScroll.maxValue)
    }

    BoxWithConstraints(modifier.background(Background)) {
        val toolbarHeight = if (mode == LayoutMode.Compact) 38.dp else 42.dp
        val maximumConsoleHeight = (maxHeight.value - toolbarHeight.value - 72f).coerceAtLeast(64f)
        val visibleConsoleHeight = consoleHeightDp.coerceIn(64f, maximumConsoleHeight)

        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(toolbarHeight)
                    .background(Panel).border(1.dp, Border)
                    .padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "‹",
                    color = Foreground,
                    fontSize = 20.sp,
                    modifier = Modifier.clickable {
                        recoverWebViewBeforeNavigation { it.goBack() }
                    },
                )
                Text(
                    "›",
                    color = Foreground,
                    fontSize = 20.sp,
                    modifier = Modifier.clickable {
                        recoverWebViewBeforeNavigation { it.goForward() }
                    },
                )
                Text(
                    "↻",
                    color = Foreground,
                    fontSize = 17.sp,
                    modifier = Modifier.clickable {
                        recoverWebViewBeforeNavigation { view ->
                            view.clearCache(false)
                            view.reload()
                        }
                    },
                )
                BasicTextField(
                    value = address,
                    onValueChange = { address = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Foreground, fontSize = 11.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { navigateToAddress() }),
                    modifier = Modifier
                        .weight(1f)
                        .background(Background)
                        .border(1.dp, Border)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
                Text(status, color = if (status == "READY") Success else Muted, fontSize = 9.sp)
                Text("CONSOLE", color = if (showConsole) Accent else Muted, fontSize = 9.sp,
                    modifier = Modifier.clickable { showConsole = !showConsole })
            }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Background)) {
                // Keep the native view stable when a local preview disconnects.
                // Removing a focused WebView during Compose applyChanges can
                // trigger a nested focus search and corrupt the slot writer.
                AndroidView(
                    factory = { context ->
                        // Debug APKs stay inspectable during development. Production
                        // exposes the same socket only for an active IDE browser-debug session.
                        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                        WheelFocusWebView(context).apply {
                        setBackgroundColor(AndroidColor.rgb(24, 26, 31))
                        visibility = View.INVISIBLE
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            private var mainFrameFailed = false
                            private var activeMainFrameUrl: String? = null

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                activeMainFrameUrl = url
                                mainFrameFailed = false
                                address = url
                                status = "CONNECTING"
                                // A previous localhost failure may have hidden the
                                // native surface. Any subsequent main-frame load is
                                // a recovery attempt and must be allowed to render.
                                pageVisible = true
                                view.visibility = View.VISIBLE
                            }

                            override fun onPageCommitVisible(view: WebView, url: String) {
                                if (!isCurrentWebNavigation(activeMainFrameUrl, url)) return
                                pageVisible = true
                                view.visibility = View.VISIBLE
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                !isBrowsableWebUri(request.url)

                            override fun onPageFinished(view: WebView, url: String) {
                                if (!isCurrentWebNavigation(activeMainFrameUrl, url)) return
                                if (mainFrameFailed) return
                                status = "READY"
                                pageVisible = true
                                view.visibility = View.VISIBLE
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                if (!request.isForMainFrame) return
                                // Stopping localhost can deliver its failure after a new
                                // internet navigation has already started. Never let that
                                // stale callback hide the replacement page.
                                if (!isCurrentWebNavigation(activeMainFrameUrl, request.url.toString())) return
                                mainFrameFailed = true
                                status = if (isLocalWebAddress(request.url.toString())) "SERVER OFFLINE" else "PAGE ERROR"
                                pageVisible = false
                                view.visibility = View.INVISIBLE
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                val entry = "${message.messageLevel()} · ${message.message()} (${message.sourceId().substringAfterLast('/')}:${message.lineNumber()})"
                                console = (console + entry).takeLast(100)
                                return true
                            }
                        }
                        webView = this
                        // AndroidView is still being attached here. Starting a
                        // hardware-rendered WebView synchronously can make its
                        // first frame use the editor's pre-chrome bounds.
                        workspaceUrl = null
                        }
                    },
                    update = { view ->
                        webView = view
                        if (view.workspaceUrl != initialUrl) {
                            val firstNavigation = view.workspaceUrl == null
                            view.workspaceUrl = initialUrl
                            if (firstNavigation) {
                                pageVisible = false
                                view.visibility = View.INVISIBLE
                            } else {
                                pageVisible = true
                                view.visibility = View.VISIBLE
                            }
                            // Schedule the model-driven navigation after the current
                            // Compose apply/layout pass. Besides preserving a focused
                            // native view, this guarantees its first visible frame is
                            // clipped below the file tabs and browser toolbar.
                            view.post {
                                if (view.isAttachedToWindow && view.workspaceUrl == initialUrl) {
                                    view.requestLayout()
                                    (view.parent as? View)?.requestLayout()
                                    view.post {
                                        if (view.isAttachedToWindow && view.workspaceUrl == initialUrl) {
                                            view.stopLoading()
                                            view.loadUrl(initialUrl)
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onRelease = { released ->
                        released.clearFocus()
                        released.stopLoading()
                        released.destroy()
                        if (webView === released) webView = null
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (!pageVisible) Box(Modifier.fillMaxSize().background(Background))
            }
            if (showConsole) {
                Box(
                    Modifier.fillMaxWidth().height(7.dp).background(Panel)
                        .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW))
                        .pointerInput(maximumConsoleHeight, density) {
                            detectVerticalDragGestures { change, dragAmount ->
                                change.consume()
                                consoleHeightDp = (consoleHeightDp - dragAmount / density.density)
                                    .coerceIn(64f, maximumConsoleHeight)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.width(38.dp).height(2.dp).background(Border))
                }
                Box(
                    Modifier.fillMaxWidth().height(visibleConsoleHeight.dp)
                        .clipToBounds().background(Panel).border(1.dp, Border),
                ) {
                    Box(Modifier.fillMaxSize().verticalScroll(consoleVerticalScroll)) {
                        Text(
                            text = console.joinToString("\n").ifBlank {
                                "Browser console · messages and JavaScript errors appear here"
                            },
                            color = if (console.isEmpty()) Muted else Foreground,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            softWrap = false,
                            modifier = Modifier.horizontalScroll(consoleHorizontalScroll).padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun isBrowsableWebUri(uri: Uri): Boolean = uri.scheme?.lowercase() in setOf("http", "https")

internal fun isCurrentWebNavigation(activeUrl: String?, callbackUrl: String): Boolean =
    activeUrl != null && activeUrl == callbackUrl

internal fun isLocalWebAddress(value: String): Boolean = runCatching {
    URI(value).host?.lowercase() in setOf("127.0.0.1", "localhost", "::1", "[::1]")
}.getOrDefault(false)

/** Converts an address-bar value into a safe HTTP(S) URL or a Google search. */
internal fun browserAddressToUrl(value: String): String? {
    val input = value.trim()
    if (input.isEmpty()) return null
    val explicitScheme = input.substringBefore("://", missingDelimiterValue = "")
        .takeIf { it.isNotEmpty() }
        ?.lowercase()
    if (explicitScheme != null) return input.takeIf { explicitScheme in setOf("http", "https") }
    val localHostWithPort = input.startsWith("localhost:", ignoreCase = true) ||
        input.startsWith("127.") || input.startsWith("[::1]:")
    if (!localHostWithPort && Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(input)) return null

    val looksLikeAddress = input.none(Char::isWhitespace) && (
        '.' in input || ':' in input || input.equals("localhost", ignoreCase = true)
    )
    if (looksLikeAddress) {
        val local = input.startsWith("localhost", ignoreCase = true) ||
            input.startsWith("127.") || input.startsWith("[::1]")
        return "${if (local) "http" else "https"}://$input"
    }

    val query = URLEncoder.encode(input, StandardCharsets.UTF_8.name()).replace("+", "%20")
    return "https://www.google.com/search?q=$query"
}
