package com.orion.player.ui.playback.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.orion.player.data.playback.DocumentFormat
import com.orion.player.data.playback.DocumentRenderMode
import com.orion.player.data.remote.AssetInfo
import java.io.File

/**
 * In-player document renderer for PDF and Office documents.
 */
@Composable
fun DocumentPlayer(
    file: File,
    asset: AssetInfo,
    playbackSessionKey: String = "",
    onLoadSuccess: () -> Unit = {},
    onLoadFailed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    when (DocumentFormat.renderMode(asset, file)) {
        DocumentRenderMode.PDF -> PdfPlayer(
            file = file,
            playbackSessionKey = playbackSessionKey,
            onRenderSuccess = onLoadSuccess,
            onRenderFailed = onLoadFailed,
            modifier = modifier
        )
        DocumentRenderMode.OFFICE_OOXML -> OfficeDocumentPlayer(
            file = file,
            asset = asset,
            ooxml = true,
            playbackSessionKey = playbackSessionKey,
            onLoadSuccess = onLoadSuccess,
            onLoadFailed = onLoadFailed,
            modifier = modifier
        )
        DocumentRenderMode.OFFICE_LEGACY -> OfficeDocumentPlayer(
            file = file,
            asset = asset,
            ooxml = false,
            playbackSessionKey = playbackSessionKey,
            onLoadSuccess = onLoadSuccess,
            onLoadFailed = onLoadFailed,
            modifier = modifier
        )
        DocumentRenderMode.TEXT -> TextDocumentPlayer(
            file = file,
            playbackSessionKey = playbackSessionKey,
            onLoadSuccess = onLoadSuccess,
            onLoadFailed = onLoadFailed,
            modifier = modifier
        )
        DocumentRenderMode.UNSUPPORTED -> {
            LaunchedEffect(file.absolutePath, playbackSessionKey) {
                onLoadFailed()
            }
            Box(modifier = modifier.fillMaxSize().background(Color.Black))
        }
    }
}
