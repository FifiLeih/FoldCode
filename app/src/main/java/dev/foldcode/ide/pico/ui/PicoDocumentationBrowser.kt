package dev.foldcode.ide

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

private const val OFFICIAL_DOCS_ROOT = "https://www.raspberrypi.com/documentation/pico-sdk/"

/**
 * Browser for the complete Doxygen site generated from the official Pico SDK.
 *
 * A generated site delivered with the extension always wins. The official 2.3
 * site is used while connected, and the editor-friendly reference remains an
 * offline last resort until the optional generated documentation pack exists.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun PicoDocumentationBrowser(
    documentName: String,
    offlineFallback: String,
    mode: LayoutMode,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sdkRoot = remember { PicoCMakeSdkBundle(context.applicationContext).sdkRoot }
    val localDocsRoot = remember(sdkRoot) { findGeneratedDocumentation(sdkRoot) }
    val remoteStartPage = when (documentName) {
        PICO_HARDWARE_APIS_TAB -> "hardware.html"
        PICO_HIGH_LEVEL_APIS_TAB -> "high_level.html"
        PICO_NETWORKING_LIBRARIES_TAB -> "networking.html"
        PICO_RUNTIME_INFRASTRUCTURE_TAB -> "runtime.html"
        else -> "index_doxygen.html"
    }
    val homeUrl = remember(localDocsRoot, remoteStartPage) {
        localDocsRoot?.let { root ->
            val localPage = if (remoteStartPage == "index_doxygen.html") {
                listOf("index_doxygen.html", "index.html").firstOrNull { File(root, it).isFile }
            } else {
                remoteStartPage.takeIf { File(root, it).isFile }
            }
            localPage?.let { File(root, it).toURI().toString() }
        } ?: "$OFFICIAL_DOCS_ROOT$remoteStartPage"
    }
    val fallbackHtml = remember(documentName, offlineFallback) {
        fallbackDocumentHtml(documentName, offlineFallback)
    }
    var query by remember(documentName) { mutableStateOf("") }
    var status by remember(documentName) {
        mutableStateOf(if (localDocsRoot != null) "OFFLINE SDK 2.3.0" else "OFFICIAL SDK 2.3.0")
    }
    var canGoBack by remember(documentName) { mutableStateOf(false) }
    var canGoForward by remember(documentName) { mutableStateOf(false) }
    var webView by remember(documentName) { mutableStateOf<WebView?>(null) }
    var pageVisible by remember(documentName, homeUrl) { mutableStateOf(false) }

    DisposableEffect(documentName) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    val toolbarHeight = if (mode == LayoutMode.Compact) 38.dp else 42.dp
    Column(modifier.background(Background)) {
        Row(
            Modifier.fillMaxWidth().height(toolbarHeight).background(Panel).border(1.dp, Border)
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DocumentationToolButton("‹", canGoBack) { webView?.goBack() }
            DocumentationToolButton("›", canGoForward) { webView?.goForward() }
            DocumentationToolButton("⌂", true) { webView?.loadUrl(homeUrl) }
            DocumentationToolButton("↻", true) { webView?.reload() }
            BasicTextField(
                value = query,
                onValueChange = {
                    query = it
                    webView?.findAllAsync(it)
                },
                singleLine = true,
                textStyle = TextStyle(color = Foreground, fontSize = 11.sp, fontFamily = FontFamily.SansSerif),
                cursorBrush = SolidColor(Accent),
                decorationBox = { inner ->
                    Box(
                        Modifier.weight(1f).height(if (mode == LayoutMode.Compact) 27.dp else 30.dp)
                            .background(Background).border(1.dp, Border).padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isBlank()) {
                            androidx.compose.material3.Text("Find on page", color = Muted, fontSize = 10.sp)
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.Text(
                status,
                color = if (localDocsRoot != null) Success else Muted,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        key(documentName, homeUrl) {
            Box(Modifier.fillMaxSize().background(Background)) {
            AndroidView(
                factory = { androidContext ->
                    WheelFocusWebView(androidContext).apply {
                        setBackgroundColor(AndroidColor.rgb(24, 26, 31))
                        visibility = View.INVISIBLE
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = mode != LayoutMode.Compact
                        settings.allowContentAccess = false
                        settings.allowFileAccess = localDocsRoot != null
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        webViewClient = object : WebViewClient() {
                            private var fallbackShown = false

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                pageVisible = false
                                view.visibility = View.INVISIBLE
                            }

                            override fun onPageCommitVisible(view: WebView, url: String) {
                                pageVisible = true
                                view.visibility = View.VISIBLE
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                return !allowedDocumentationUrl(request.url, localDocsRoot)
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                pageVisible = true
                                view.visibility = View.VISIBLE
                                canGoBack = view.canGoBack()
                                canGoForward = view.canGoForward()
                                status = when {
                                    url.startsWith("file:") -> "OFFLINE SDK 2.3.0"
                                    url.startsWith("data:") -> "OFFLINE SUMMARY"
                                    else -> "OFFICIAL SDK 2.3.0"
                                }
                                if (query.isNotBlank()) view.findAllAsync(query)
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                if (request.isForMainFrame && !fallbackShown) {
                                    fallbackShown = true
                                    pageVisible = false
                                    status = "OFFLINE SUMMARY"
                                    view.loadDataWithBaseURL(
                                        OFFICIAL_DOCS_ROOT,
                                        fallbackHtml,
                                        "text/html",
                                        "UTF-8",
                                        null,
                                    )
                                }
                            }
                        }
                        webView = this
                        loadUrl(homeUrl)
                    }
                },
                update = { webView = it },
                modifier = Modifier.fillMaxSize(),
            )
                if (!pageVisible) {
                    Box(Modifier.fillMaxSize().background(Background))
                }
            }
        }
    }
}

@Composable
private fun DocumentationToolButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Text(
        label,
        color = if (enabled) Foreground else Muted.copy(alpha = 0.45f),
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.width(27.dp).clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp),
    )
}

private fun findGeneratedDocumentation(sdkRoot: File): File? = listOf(
    File(sdkRoot, "docs/html"),
    File(sdkRoot, "docs/generated/html"),
    File(sdkRoot.parentFile, "documentation/html"),
    File(sdkRoot.parentFile, "pico-sdk-docs/html"),
).firstOrNull { root ->
    (File(root, "index_doxygen.html").isFile || File(root, "index.html").isFile) &&
        File(root, "hardware.html").isFile
}

private fun allowedDocumentationUrl(uri: Uri, localRoot: File?): Boolean = when (uri.scheme?.lowercase()) {
    "https", "http", "data", "about" -> true
    "file" -> localRoot != null && runCatching {
        File(uri.path.orEmpty()).canonicalPath.startsWith(localRoot.canonicalPath + File.separator)
    }.getOrDefault(false)
    else -> false
}

private fun fallbackDocumentHtml(title: String, markdown: String): String {
    val body = markdown.lineSequence().joinToString("\n") { line ->
        val escaped = line.escapeHtml()
        when {
            line.startsWith("# ") -> "<h1>${line.drop(2).escapeHtml()}</h1>"
            line.startsWith("## ") -> "<h2>${line.drop(3).escapeHtml()}</h2>"
            line.startsWith("### ") -> "<h3>${line.drop(4).escapeHtml()}</h3>"
            line.startsWith("- ") -> "<div class='item'>• ${line.drop(2).escapeHtml()}</div>"
            line.isBlank() -> "<br>"
            else -> "<div>$escaped</div>"
        }
    }
    return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
        body{font:15px system-ui,sans-serif;color:#d8dee9;background:#181a1f;margin:0;padding:18px;line-height:1.55}
        h1,h2,h3{color:#fff}h1{font-size:24px}h2{font-size:19px;border-bottom:1px solid #343840;padding-bottom:6px}
        .item{padding:3px 0 3px 14px}code,pre{font-family:monospace;color:#82c99a}a{color:#73a9ff}
        </style></head><body><h1>${title.escapeHtml()}</h1>$body</body></html>
    """.trimIndent()
}

private fun String.escapeHtml(): String = buildString(length) {
    this@escapeHtml.forEach { char ->
        append(when (char) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            '"' -> "&quot;"
            '\'' -> "&#39;"
            else -> char
        })
    }
}
