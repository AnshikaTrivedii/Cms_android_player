package com.orion.player.ui.playback.player

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/**
 * Plain-text document renderer using WebView.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TextDocumentPlayer(
    file: File,
    playbackSessionKey: String = "",
    onLoadSuccess: () -> Unit = {},
    onLoadFailed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var loadReported by remember(file.absolutePath, playbackSessionKey) { mutableStateOf(false) }
    val textContent = remember(file.absolutePath) {
        runCatching { file.readText() }.getOrNull()
    }

    LaunchedEffect(file.absolutePath, playbackSessionKey, textContent) {
        if (textContent == null && !loadReported) {
            loadReported = true
            onLoadFailed()
        }
    }

    if (textContent == null) {
        return
    }

    val webView = remember(file.absolutePath, playbackSessionKey) {
        WebView(context.applicationContext).apply {
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    if (!loadReported) {
                        loadReported = true
                        onLoadSuccess()
                    }
                }
            }
            webChromeClient = WebChromeClient()
            settings.javaScriptEnabled = false
            setBackgroundColor(android.graphics.Color.BLACK)
            val escaped = textContent
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <style>
                    body {
                      margin: 0;
                      padding: 24px;
                      background: #000;
                      color: #f5f5f5;
                      font-family: sans-serif;
                      white-space: pre-wrap;
                      word-break: break-word;
                    }
                  </style>
                </head>
                <body><pre>$escaped</pre></body>
                </html>
            """.trimIndent()
            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
    }

    DisposableEffect(file.absolutePath, playbackSessionKey) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier.fillMaxSize()
    )
}
