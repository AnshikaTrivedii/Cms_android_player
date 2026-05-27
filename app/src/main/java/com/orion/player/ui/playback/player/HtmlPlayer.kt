package com.orion.player.ui.playback.player

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Full-screen HTML content player using WebView.
 * Loads from a local file path when available, falling back to remote URL.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlPlayer(
    url: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Key the WebView on the URL so a new instance is created for each different URL.
    // This avoids the lifecycle conflict where destroy() is called on a remembered instance
    // that would be reused for a different URL.
    val webView = remember(url) {
        WebView(context).apply {
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // Allow file access for locally cached HTML content
                allowFileAccess = true
            }

            // Disable scrolling for signage display
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(android.graphics.Color.BLACK)

            loadUrl(url)
        }
    }

    DisposableEffect(url) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier.fillMaxSize()
    )
}
