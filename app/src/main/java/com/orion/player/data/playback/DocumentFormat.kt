package com.orion.player.data.playback

import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.AssetType
import com.orion.player.data.remote.AssetType.normalizedType
import java.io.File
import java.util.Locale

enum class DocumentRenderMode {
    PDF,
    HTML,
    TEXT,
    UNSUPPORTED
}

/**
 * Resolves how a DOCUMENT (or HTML) asset should be rendered inside the player.
 * Office formats should be converted to PDF or HTML on the CMS before delivery.
 */
object DocumentFormat {
    private val OFFICE_EXTENSIONS = setOf("doc", "docx", "ppt", "pptx", "xls", "xlsx")

    fun renderMode(asset: AssetInfo, file: File): DocumentRenderMode {
        val extension = extensionFor(asset, file)
        return when {
            extension == "pdf" || isPdfMagic(file) -> DocumentRenderMode.PDF
            extension == "html" || extension == "htm" -> DocumentRenderMode.HTML
            extension == "txt" -> DocumentRenderMode.TEXT
            extension in OFFICE_EXTENSIONS -> DocumentRenderMode.UNSUPPORTED
            asset.mimeType.contains("pdf", ignoreCase = true) -> DocumentRenderMode.PDF
            asset.mimeType.contains("html", ignoreCase = true) -> DocumentRenderMode.HTML
            asset.mimeType.contains("text/plain", ignoreCase = true) -> DocumentRenderMode.TEXT
            else -> DocumentRenderMode.UNSUPPORTED
        }
    }

    fun extensionFor(asset: AssetInfo, file: File? = null): String {
        val fromName = asset.name.substringAfterLast('.', "").lowercase(Locale.US)
        if (fromName.isNotBlank()) return fromName
        file?.extension?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }?.let { return it }
        return when {
            asset.mimeType.contains("pdf", ignoreCase = true) -> "pdf"
            asset.mimeType.contains("html", ignoreCase = true) -> "html"
            asset.mimeType.contains("text/plain", ignoreCase = true) -> "txt"
            asset.mimeType.contains("word", ignoreCase = true) -> "docx"
            asset.mimeType.contains("presentation", ignoreCase = true) -> "pptx"
            asset.mimeType.contains("spreadsheet", ignoreCase = true) ||
                asset.mimeType.contains("excel", ignoreCase = true) -> "xlsx"
            else -> "bin"
        }
    }

    fun htmlExtension(asset: AssetInfo): String {
        val fromName = asset.name.substringAfterLast('.', "").lowercase(Locale.US)
        return when (fromName) {
            "htm", "html" -> fromName
            else -> "html"
        }
    }

    fun popContentLabel(asset: AssetInfo): String = when (asset.normalizedType()) {
        AssetType.HTML -> "HTML viewed"
        AssetType.DOCUMENT -> "Document viewed"
        AssetType.URL -> "URL viewed"
        else -> "Asset viewed"
    }

    private fun isPdfMagic(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                if (input.read(header) != 4) return@runCatching false
                header[0] == '%'.code.toByte() &&
                    header[1] == 'P'.code.toByte() &&
                    header[2] == 'D'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)
    }
}
