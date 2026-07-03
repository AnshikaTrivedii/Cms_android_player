package com.orion.player.ui.playback.player

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Renders a local PDF inside the player using [PdfRenderer] — no external apps.
 */
@Composable
fun PdfPlayer(
    file: File,
    playbackSessionKey: String = "",
    onRenderSuccess: () -> Unit = {},
    onRenderFailed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember(file.absolutePath, playbackSessionKey) { mutableStateOf<Bitmap?>(null) }
    var pageCount by remember(file.absolutePath, playbackSessionKey) { mutableStateOf(0) }
    var currentPage by remember(file.absolutePath, playbackSessionKey) { mutableStateOf(0) }
    var renderFailed by remember(file.absolutePath, playbackSessionKey) { mutableStateOf(false) }

    suspend fun renderPage(pageIndex: Int): Bitmap? = withContext(Dispatchers.IO) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) return@withContext null
                pageCount = renderer.pageCount
                val safeIndex = pageIndex.coerceIn(0, renderer.pageCount - 1)
                renderer.openPage(safeIndex).use { page ->
                    val metrics = context.resources.displayMetrics
                    val scale = min(
                        metrics.widthPixels.toFloat() / page.width.coerceAtLeast(1),
                        metrics.heightPixels.toFloat() / page.height.coerceAtLeast(1)
                    ).coerceAtLeast(1f)
                    val width = max(1, (page.width * scale).toInt())
                    val height = max(1, (page.height * scale).toInt())
                    val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    rendered
                }
            }
        }
    }

    LaunchedEffect(file.absolutePath, playbackSessionKey) {
        renderFailed = false
        try {
            val firstPage = renderPage(0) ?: throw IllegalStateException("empty pdf")
            bitmap?.recycle()
            bitmap = firstPage
            currentPage = 0
            onRenderSuccess()
        } catch (_: Exception) {
            renderFailed = true
            onRenderFailed()
        }
    }

    LaunchedEffect(file.absolutePath, playbackSessionKey, pageCount) {
        if (pageCount <= 1) return@LaunchedEffect
        while (isActive && !renderFailed) {
            kotlinx.coroutines.delay(8_000L)
            val nextPage = (currentPage + 1) % pageCount
            runCatching {
                val rendered = renderPage(nextPage) ?: return@runCatching
                bitmap?.recycle()
                bitmap = rendered
                currentPage = nextPage
            }
        }
    }

    DisposableEffect(file.absolutePath, playbackSessionKey) {
        onDispose {
            bitmap?.recycle()
            bitmap = null
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = file.name,
            contentScale = ContentScale.Fit,
            modifier = modifier.fillMaxSize()
        )
    } else {
        Box(modifier = modifier.fillMaxSize().background(Color.Black))
    }
}
