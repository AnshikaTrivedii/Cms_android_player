package com.orion.player.ui.playback.player

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/**
 * Full-screen HTML content player using WebView.
 * Supports offline local .html/.htm files, JavaScript, CSS, and responsive layout.
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
    var loadReported by remember(url, localFile?.absolutePath, playbackSessionKey) { mutableStateOf(false) }

    val webView = remember(url, localFile?.absolutePath, playbackSessionKey) {
        WebView(context.applicationContext).apply {
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    if (!loadReported && !finishedUrl.isNullOrBlank() && finishedUrl != "about:blank") {
                        loadReported = true
                        onLoadSuccess()
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    if (!loadReported) {
                        loadReported = true
                        onLoadFailed()
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    if (request?.isForMainFrame == true && !loadReported) {
                        loadReported = true
                        onLoadFailed()
                    }
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

            when {
                localFile != null && localFile.exists() -> loadUrl(localFile.toURI().toString())
                else -> loadUrl(url)
            }
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
