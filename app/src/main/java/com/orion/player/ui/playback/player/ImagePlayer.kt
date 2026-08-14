package com.orion.player.ui.playback.player

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale
import com.orion.player.ui.playback.LocalStretchToFit
import java.io.File

/**
 * Full-screen image player using Coil with disk-only caching to limit heap growth
 * during 24x7 playlist loops.
 *
 * Stretch to Fit draws with [ContentScale.FillBounds]: the decoded image is mapped
 * onto the actual container width and height (no uniform zoom, no center-crop).
 * Coil decode uses [Scale.FIT] so the bitmap still contains the full source;
 * otherwise Coil would map FillBounds to Scale.FILL and crop at decode time.
 */
@Composable
fun ImagePlayer(
    file: File? = null,
    url: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val stretchToFit = LocalStretchToFit.current
    val model = file ?: url ?: return
    val request = ImageRequest.Builder(context)
        .data(model)
        .apply {
            // FillBounds must not be decoded with Scale.FILL or Coil center-crops
            // the source before Compose can stretch it to the container.
            if (stretchToFit) scale(Scale.FIT)
        }
        .memoryCachePolicy(CachePolicy.DISABLED)
        .crossfade(false)
        .build()
    val scaleMode = if (stretchToFit) ContentScale.FillBounds else ContentScale.Fit
    var containerWidth by remember { mutableIntStateOf(0) }
    var containerHeight by remember { mutableIntStateOf(0) }
    var imageWidth by remember { mutableIntStateOf(0) }
    var imageHeight by remember { mutableIntStateOf(0) }

    LaunchedEffect(stretchToFit, containerWidth, containerHeight, imageWidth, imageHeight) {
        if (containerWidth <= 0 || containerHeight <= 0) return@LaunchedEffect
        Log.i(
            STRETCH_TAG,
            "Asset Type = IMAGE Stretch To Fit = $stretchToFit " +
                "Container = ${containerWidth}x$containerHeight " +
                "Image = ${imageWidth}x$imageHeight " +
                "Scale Mode = ${if (stretchToFit) "STRETCH" else "FIT"}"
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size ->
                containerWidth = size.width
                containerHeight = size.height
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = request,
            contentDescription = "Digital signage content",
            contentScale = scaleMode,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { state ->
                imageWidth = state.result.drawable.intrinsicWidth
                imageHeight = state.result.drawable.intrinsicHeight
            }
        )
    }
}

private const val STRETCH_TAG = "OrionStretchToFit"
