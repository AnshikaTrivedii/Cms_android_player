package com.orion.player.ui.playback.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.orion.player.ui.playback.LocalStretchToFit
import java.io.File

/**
 * Full-screen image player using Coil with disk-only caching to limit heap growth
 * during 24x7 playlist loops.
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
        .memoryCachePolicy(CachePolicy.DISABLED)
        .crossfade(false)
        .build()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = request,
            contentDescription = "Digital signage content",
            contentScale = if (stretchToFit) ContentScale.Crop else ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}
