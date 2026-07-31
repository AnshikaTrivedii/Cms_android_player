package com.orion.player.ui.playback.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orion.player.data.playback.DocumentFormat
import com.orion.player.data.playback.OfficeDocumentExtractor
import com.orion.player.data.remote.AssetInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

private val DocBg = Color(0xFF0B0B14)
private val DocAccent = Color(0xFF6C63FF)
private val DocMuted = Color(0xFFB0B0C0)
private val DocText = Color(0xFFF2F2F7)

/**
 * Fullscreen Office document renderer.
 * DOCX/PPTX: offline OOXML text/slide extraction with page carousel.
 * DOC/PPT: clean fullscreen document card (binary formats have no native Android renderer).
 */
@Composable
fun OfficeDocumentPlayer(
    file: File,
    asset: AssetInfo,
    ooxml: Boolean,
    playbackSessionKey: String = "",
    pageIntervalMs: Long = 8_000L,
    onLoadSuccess: () -> Unit = {},
    onLoadFailed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val extension = DocumentFormat.extensionFor(asset, file)
    val formatLabel = DocumentFormat.formatLabel(asset, file)
    var pages by remember(file.absolutePath, playbackSessionKey) { mutableStateOf<List<String>?>(null) }
    var pageIndex by remember(file.absolutePath, playbackSessionKey) { mutableIntStateOf(0) }
    var failed by remember(file.absolutePath, playbackSessionKey) { mutableStateOf(false) }

    LaunchedEffect(file.absolutePath, playbackSessionKey, ooxml) {
        failed = false
        pages = null
        pageIndex = 0
        if (!ooxml) {
            onLoadSuccess()
            return@LaunchedEffect
        }
        val extracted = withContext(Dispatchers.IO) {
            OfficeDocumentExtractor.extract(file, extension)
        }
        if (extracted == null || extracted.pages.isEmpty()) {
            // Fall back to fullscreen card rather than blank/crash.
            pages = emptyList()
            onLoadSuccess()
        } else {
            pages = extracted.pages
            onLoadSuccess()
        }
    }

    LaunchedEffect(pages, playbackSessionKey) {
        val list = pages ?: return@LaunchedEffect
        if (list.size <= 1) return@LaunchedEffect
        while (isActive && !failed) {
            kotlinx.coroutines.delay(pageIntervalMs)
            pageIndex = (pageIndex + 1) % list.size
        }
    }

    when {
        failed -> {
            LaunchedEffect(Unit) { onLoadFailed() }
            Box(modifier = modifier.fillMaxSize().background(Color.Black))
        }
        !ooxml || pages?.isEmpty() == true -> {
            DocumentInfoCard(
                title = asset.name.ifBlank { file.name },
                formatLabel = formatLabel,
                subtitle = if (ooxml) "Document ready" else "Document ready · offline",
                modifier = modifier
            )
        }
        pages != null -> {
            val content = pages!![pageIndex.coerceIn(0, pages!!.lastIndex)]
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .background(DocBg)
                    .padding(48.dp),
                verticalArrangement = Arrangement.Top
            ) {
                Text(
                    text = formatLabel,
                    color = DocAccent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = asset.name.ifBlank { file.name },
                    color = DocMuted,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = content,
                    color = DocText,
                    fontSize = 28.sp,
                    lineHeight = 36.sp,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                )
                if ((pages?.size ?: 0) > 1) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "${pageIndex + 1} / ${pages!!.size}",
                        color = DocMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
        else -> Box(modifier = modifier.fillMaxSize().background(DocBg))
    }
}

@Composable
fun DocumentInfoCard(
    title: String,
    formatLabel: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize().background(DocBg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(48.dp)
        ) {
            Text(
                text = formatLabel,
                color = DocAccent,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                color = DocText,
                fontSize = 32.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = subtitle,
                color = DocMuted,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
