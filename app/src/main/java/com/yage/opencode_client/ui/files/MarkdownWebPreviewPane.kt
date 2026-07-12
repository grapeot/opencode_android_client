package com.yage.opencode_client.ui.files

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mikepenz.markdown.m3.Markdown
import com.yage.opencode_client.R
import com.yage.opencode_client.BuildConfig
import com.yage.opencode_client.data.repository.OpenCodeRepository
import com.yage.opencode_client.ui.theme.markdownTypographyCompact
import com.yage.opencode_client.ui.util.DataUriImageTransformer
import com.yage.opencode_client.ui.util.MarkdownImageResolver
import org.json.JSONObject

private const val webPreviewMaxTotalLength = 60_000
private const val webPreviewMaxLineLength = 5_000
private const val webPreviewLogTag = "MarkdownWebPreview"

@Composable
internal fun MarkdownWebPreviewPane(
    content: String,
    filePath: String,
    repository: OpenCodeRepository,
    sessionDirectory: String?,
    onMarkdownLinkClick: (String) -> Unit = {},
    onOpenNative: () -> Unit,
    onOpenSource: () -> Unit
) {
    var confirmedOversize by remember(content, filePath) { mutableStateOf(false) }
    val normalizedContent = remember(content) { MarkdownImageResolver.normalizeStandaloneImageBlocks(content) }
    val isOversize = remember(normalizedContent) {
        normalizedContent.length > webPreviewMaxTotalLength ||
            normalizedContent.lineSequence().any { it.length > webPreviewMaxLineLength }
    }

    when {
        isOversize && !confirmedOversize -> WebPreviewOversizeGate(
            onContinue = { confirmedOversize = true },
            onOpenNative = onOpenNative,
            onOpenSource = onOpenSource
        )
        else -> ResolvedMarkdownWebPreview(
            content = normalizedContent,
            filePath = filePath,
            repository = repository,
            sessionDirectory = sessionDirectory,
            onMarkdownLinkClick = onMarkdownLinkClick,
            onOpenNative = onOpenNative,
            onOpenSource = onOpenSource
        )
    }
}

@Composable
private fun ResolvedMarkdownWebPreview(
    content: String,
    filePath: String,
    repository: OpenCodeRepository,
    sessionDirectory: String?,
    onMarkdownLinkClick: (String) -> Unit,
    onOpenNative: () -> Unit,
    onOpenSource: () -> Unit
) {
    var resolvedContent by remember(content, filePath) { mutableStateOf(content) }
    var error by remember(content, filePath) { mutableStateOf<String?>(null) }
    val resolverMarkdownPath = remember(filePath, sessionDirectory) {
        resolveRelativePreviewPath(filePath, sessionDirectory)
    }

    LaunchedEffect(content, resolverMarkdownPath, sessionDirectory, repository) {
        error = null
        resolvedContent = try {
            MarkdownImageResolver.resolveImages(
                text = content,
                markdownFilePath = resolverMarkdownPath,
                workspaceDirectory = sessionDirectory,
                fetchContent = { path -> repository.getFileContent(path).getOrThrow() }
            )
        } catch (e: Exception) {
            error = e.message ?: "Failed to resolve Markdown images"
            content
        }
    }

    if (error != null && resolvedContent.isBlank()) {
        WebPreviewError(
            message = error ?: "Web Preview failed",
            onOpenNative = onOpenNative,
            onOpenSource = onOpenSource
        )
    } else {
        MarkdownWebView(
            markdown = resolvedContent,
            onRenderError = { error = it },
            onMarkdownLinkClick = onMarkdownLinkClick,
            onOpenNative = onOpenNative,
            onOpenSource = onOpenSource
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MarkdownWebView(
    markdown: String,
    onRenderError: (String) -> Unit,
    onMarkdownLinkClick: (String) -> Unit,
    onOpenNative: () -> Unit,
    onOpenSource: () -> Unit
) {
    val context = LocalContext.current
    val theme = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) "dark" else "light"
    val webViewBackground = MaterialTheme.colorScheme.background.toArgb()
    var webContentReady by remember { mutableStateOf(false) }

    LaunchedEffect(markdown, theme) {
        webPreviewLog("content changed length=${markdown.length} theme=$theme")
        webContentReady = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (webContentReady) 1f else 0f),
            factory = { ctx ->
                webPreviewLog("factory start")
                WebView(ctx).apply {
                    webPreviewLog("webview constructed")
                    setBackgroundColor(webViewBackground)
                    settings.javaScriptEnabled = true
                    settings.offscreenPreRaster = true
                    // Required for file:///android_asset preview.html to load sibling JS/CSS assets.
                    // Workspace files still never go through WebView file URLs; images are pre-resolved to data URIs.
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    addJavascriptInterface(
                        AndroidPreviewBridge(
                            onRendered = {
                                webPreviewLog("js rendered")
                                webContentReady = true
                            },
                            onError = onRenderError,
                            onLink = onMarkdownLinkClick
                        ),
                        "AndroidPreviewBridge"
                    )
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            webPreviewLog("onPageFinished url=$url")
                            view.renderMarkdown(markdown, theme)
                        }

                        override fun onPageCommitVisible(view: WebView, url: String?) {
                            webPreviewLog("onPageCommitVisible url=$url")
                            view.postVisualStateCallback(
                                System.nanoTime(),
                                object : WebView.VisualStateCallback() {
                                    override fun onComplete(requestId: Long) {
                                        webPreviewLog("visual state complete requestId=$requestId")
                                        webContentReady = true
                                    }
                                }
                            )
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val uri = request.url ?: return true
                            if (uri.scheme == "file" && uri.toString().startsWith("file:///android_asset/web_preview/")) {
                                return false
                            }
                            if (uri.fragment != null && uri.scheme == null) return false
                            return true
                        }
                    }
                    webPreviewLog("loadUrl preview.html")
                    loadUrl("file:///android_asset/web_preview/preview.html")
                }
            },
            update = { webView ->
                webPreviewLog("update render length=${markdown.length} theme=$theme ready=$webContentReady")
                webView.setBackgroundColor(webViewBackground)
                webView.renderMarkdown(markdown, theme)
            }
        )

        if (!webContentReady) {
            MarkdownFallbackOverlay(markdown = markdown)
        }
    }
}

private class AndroidPreviewBridge(
    private val onRendered: () -> Unit,
    private val onError: (String) -> Unit,
    private val onLink: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun postMessage(message: String) {
        val payload = runCatching { JSONObject(message) }.getOrNull() ?: return
        when (payload.optString("type")) {
            "rendered" -> mainHandler.post { onRendered() }
            "error" -> mainHandler.post { onError(payload.optString("message", "Web Preview render error")) }
            "link" -> {
                val href = payload.optString("href")
                mainHandler.post { onLink(href) }
            }
        }
    }
}

private fun WebView.renderMarkdown(markdown: String, theme: String) {
    webPreviewLog("renderMarkdown requested length=${markdown.length} theme=$theme")
    val payload = JSONObject()
        .put("markdown", markdown)
        .put("theme", theme)
        .toString()
    evaluateJavascript(
        """
        (function renderWhenReady(attempt) {
          if (typeof window.renderMarkdown === 'function') {
            window.renderMarkdown($payload);
          } else if (attempt < 40) {
            setTimeout(function () { renderWhenReady(attempt + 1); }, 50);
          } else if (document && document.body) {
            document.body.innerHTML = '<pre style="padding:16px;color:#ffd7d7;background:#5a1f1f">Markdown Web Preview failed to load renderer assets.</pre>';
          }
        })(0);
        """.trimIndent(),
        null
    )
}

private fun webPreviewLog(message: String) {
    if (BuildConfig.DEBUG) {
        Log.d(webPreviewLogTag, "${SystemClock.uptimeMillis()} $message")
    }
}

@Composable
private fun MarkdownFallbackOverlay(markdown: String) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp)
        ) {
            item {
                Markdown(
                    content = markdown,
                    typography = markdownTypographyCompact(),
                    modifier = Modifier.fillMaxWidth(),
                    imageTransformer = DataUriImageTransformer
                )
            }
        }
    }
}

@Composable
private fun WebPreviewOversizeGate(
    onContinue: () -> Unit,
    onOpenNative: () -> Unit,
    onOpenSource: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.files_markdown_large_warning),
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = onContinue, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.files_open_web_preview))
        }
        OutlinedButton(onClick = onOpenNative, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.files_open_native_preview))
        }
        OutlinedButton(onClick = onOpenSource, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.files_open_markdown_source))
        }
    }
}

@Composable
private fun WebPreviewError(
    message: String,
    onOpenNative: () -> Unit,
    onOpenSource: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onOpenNative, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.files_open_native_preview))
        }
        OutlinedButton(onClick = onOpenSource, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.files_open_markdown_source))
        }
    }
}
