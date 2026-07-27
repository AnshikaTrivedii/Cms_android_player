package com.orion.player.ui.playback.player

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen HTML content player using WebView.
 *
 * Local files are loaded with [WebView.loadDataWithBaseURL] (not fragile file:// URLs)
 * so offline HTML works reliably under Compose + modern WebView.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlPlayer(
    url: String,
    localFile: File? = null,
    playbackSessionKey: String = "",
    onLoadSuccess: () -> Unit = {},
    onLoadFailed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val loadReported = remember(url, localFile?.absolutePath, playbackSessionKey) {
        AtomicBoolean(false)
    }
    val latestOnSuccess = rememberUpdatedState(onLoadSuccess)
    val latestOnFailed = rememberUpdatedState(onLoadFailed)

    val webView = remember(url, localFile?.absolutePath, playbackSessionKey) {
        WebView(context).apply {
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    // Ignore the dispose-time about:blank navigation only.
                    if (finishedUrl == "about:blank") return
                    if (!loadReported.compareAndSet(false, true)) return
                    Log.i(
                        TAG,
                        "html_ready url=${finishedUrl.orEmpty()} local=${localFile?.name.orEmpty()} " +
                            "bytes=${localFile?.length() ?: -1}"
                    )
                    latestOnSuccess.value()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame != true) return
                    if (!loadReported.compareAndSet(false, true)) return
                    Log.e(
                        TAG,
                        "html_error mainFrame code=${error?.errorCode} " +
                            "desc=${error?.description} url=${request.url}"
                    )
                    latestOnFailed.value()
                }

                @Deprecated("Deprecated in Java")
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    // API 23+ delivers main-frame failures via the WebResourceRequest overload.
                    // Ignoring the deprecated callback avoids false failures from subresources.
                }
            }
            webChromeClient = WebChromeClient()

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowFileAccess = true
                allowContentAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = localFile != null
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = localFile != null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = false
                }
            }

            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(android.graphics.Color.BLACK)

            loadHtmlContent(localFile = localFile, fallbackUrl = url, onUnreadable = {
                if (loadReported.compareAndSet(false, true)) {
                    Log.e(TAG, "html_unreadable file=${localFile?.absolutePath}")
                    latestOnFailed.value()
                }
            })
        }
    }

    DisposableEffect(url, localFile?.absolutePath, playbackSessionKey) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier.fillMaxSize()
    )
}

private fun WebView.loadHtmlContent(
    localFile: File?,
    fallbackUrl: String,
    onUnreadable: () -> Unit
) {
    val file = localFile?.takeIf { it.exists() && it.length() > 0L }
    if (file != null) {
        val html = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        if (html != null) {
            // Prefer in-memory load — avoids file:/ vs file:/// WebView quirks.
            val parent = file.parentFile ?: file
            val baseUrl = Uri.fromFile(parent).toString().let { uri ->
                if (uri.endsWith("/")) uri else "$uri/"
            }
            loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
            return
        }
        // Binary / unreadable as UTF-8 — fall back to proper file:/// URI.
        loadUrl(Uri.fromFile(file).toString())
        return
    }

    if (fallbackUrl.isNotBlank()) {
        // Prefer Uri.fromFile form when the fallback is already a file path URI.
        loadUrl(fallbackUrl)
    } else {
        onUnreadable()
    }
}

private const val TAG = "OrionPlayback"
