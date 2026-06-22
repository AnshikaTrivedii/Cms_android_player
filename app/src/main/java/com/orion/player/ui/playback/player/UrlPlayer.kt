package com.orion.player.ui.playback.player

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.orion.player.util.UrlSecurityUtil
import java.io.File

private const val FALLBACK_HTML = """
<!DOCTYPE html>
<html>
<head>
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <style>
    body {
      margin: 0;
      background: #0f0f1a;
      color: #b0b0c0;
      font-family: sans-serif;
      display: flex;
      align-items: center;
      justify-content: center;
      height: 100vh;
      text-align: center;
    }
    h1 { color: #6c63ff; font-size: 1.5rem; margin-bottom: 0.5rem; }
    p { font-size: 1rem; margin: 0; }
  </style>
</head>
<body>
  <div>
    <h1>Content Unavailable</h1>
    <p>Unable to load this URL.<br>Continuing playlist...</p>
  </div>
</body>
</html>
"""

/**
 * Full-screen remote URL player using WebView.
 * Loads the live URL when online; falls back to a sync-time cached snapshot offline.
 * Duration is controlled by [PlaybackViewModel].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun UrlPlayer(
    url: String,
    cachedFile: File? = null,
    onLoadSuccess: () -> Unit,
    onLoadFailed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var loadReported by remember(url, cachedFile?.absolutePath) { mutableStateOf(false) }
    var loadFailed by remember(url, cachedFile?.absolutePath) { mutableStateOf(false) }

    val validatedUrl = remember(url) { UrlSecurityUtil.normalizeUrl(url) }
    val cachedSnapshotUrl = remember(cachedFile?.absolutePath) {
        cachedFile?.takeIf { it.exists() && it.length() > 0L }?.toURI()?.toString()
    }

    LaunchedEffect(url, validatedUrl, cachedSnapshotUrl) {
        if (validatedUrl == null && cachedSnapshotUrl == null && !loadReported) {
            loadReported = true
            loadFailed = true
            onLoadFailed()
        }
    }

    val webView = remember(url, cachedSnapshotUrl) {
        var triedCacheFallback = false

        WebView(context.applicationContext).apply {
            webChromeClient = WebChromeClient()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowFileAccess = cachedSnapshotUrl != null
                allowContentAccess = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = true
                }
            }

            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(android.graphics.Color.BLACK)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    if (loadFailed || loadReported) return
                    if (finishedUrl.isNullOrBlank() || finishedUrl == "about:blank") return
                    loadReported = true
                    onLoadSuccess()
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    if (request?.isForMainFrame != true) return
                    val statusCode = errorResponse?.statusCode ?: return
                    if (statusCode < 400) return
                    handleLoadFailure(view)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        handleLoadFailure(view)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    if (failingUrl != null) {
                        handleLoadFailure(view)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val targetUrl = request?.url?.toString() ?: return true
                    return !UrlSecurityUtil.isAllowedNavigationUrl(targetUrl)
                }

                private fun handleLoadFailure(view: WebView?) {
                    if (tryLoadCachedSnapshot(view)) return
                    if (loadReported) return
                    loadFailed = true
                    loadReported = true
                    onLoadFailed()
                    view?.loadDataWithBaseURL(null, FALLBACK_HTML, "text/html", "UTF-8", null)
                }

                private fun tryLoadCachedSnapshot(view: WebView?): Boolean {
                    if (triedCacheFallback || cachedSnapshotUrl == null) return false
                    triedCacheFallback = true
                    view?.loadUrl(cachedSnapshotUrl)
                    return true
                }
            }

            when {
                validatedUrl != null -> loadUrl(validatedUrl)
                cachedSnapshotUrl != null -> {
                    triedCacheFallback = true
                    loadUrl(cachedSnapshotUrl)
                }
                else -> loadDataWithBaseURL(null, FALLBACK_HTML, "text/html", "UTF-8", null)
            }
        }
    }

    DisposableEffect(url, cachedSnapshotUrl) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize()
        )
    }
}
