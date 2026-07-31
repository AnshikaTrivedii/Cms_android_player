package com.orion.player.data.playback

import com.orion.player.data.remote.AssetInfo
import com.orion.player.data.remote.AssetType
import com.orion.player.data.remote.AssetType.normalizedType
import java.io.File
import java.util.Locale

enum class DocumentRenderMode {
    PDF,
    OFFICE_OOXML,
    OFFICE_LEGACY,
    TEXT,
    UNSUPPORTED
}

/**
 * Resolves how a DOCUMENT asset should be rendered.
 * Supported: PDF, DOC/DOCX, PPT/PPTX (Office OOXML offline text/slide view;
 * legacy binary DOC/PPT shows a fullscreen document card).
 */
object DocumentFormat {
    private val OOXML_EXTENSIONS = setOf("docx", "pptx")
    private val LEGACY_OFFICE_EXTENSIONS = setOf("doc", "ppt")
    private val OFFICE_EXTENSIONS = OOXML_EXTENSIONS + LEGACY_OFFICE_EXTENSIONS

    fun renderMode(asset: AssetInfo, file: File): DocumentRenderMode {
        val extension = extensionFor(asset, file)
        val format = asset.documentFormat?.lowercase(Locale.US)
        return when {
            extension == "pdf" || isPdfMagic(file) || format == "pdf" ||
                asset.mimeType.contains("pdf", ignoreCase = true) -> DocumentRenderMode.PDF
            extension in OOXML_EXTENSIONS ||
                format in setOf("docx", "pptx") ||
                (format == "word" && extension != "doc") ||
                (format == "powerpoint" && extension != "ppt") ||
                asset.mimeType.contains("wordprocessingml", ignoreCase = true) ||
                asset.mimeType.contains("presentationml", ignoreCase = true) ->
                DocumentRenderMode.OFFICE_OOXML
            extension in LEGACY_OFFICE_EXTENSIONS ||
                format in setOf("doc", "ppt", "word", "powerpoint") ||
                asset.mimeType.contains("msword", ignoreCase = true) ||
                asset.mimeType.contains("ms-powerpoint", ignoreCase = true) ->
                DocumentRenderMode.OFFICE_LEGACY
            extension == "txt" ||
                format == "text" ||
                asset.mimeType.contains("text/plain", ignoreCase = true) ->
                DocumentRenderMode.TEXT
            extension in OFFICE_EXTENSIONS -> DocumentRenderMode.OFFICE_LEGACY
            else -> DocumentRenderMode.UNSUPPORTED
        }
    }

    fun extensionFor(asset: AssetInfo, file: File? = null): String {
        val fromName = asset.name.substringAfterLast('.', "").lowercase(Locale.US)
        if (fromName.isNotBlank()) return fromName
        file?.extension?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }?.let { return it }
        return when {
            asset.documentFormat.equals("pdf", ignoreCase = true) -> "pdf"
            asset.documentFormat.equals("docx", ignoreCase = true) -> "docx"
            asset.documentFormat.equals("doc", ignoreCase = true) -> "doc"
            asset.documentFormat.equals("pptx", ignoreCase = true) -> "pptx"
            asset.documentFormat.equals("ppt", ignoreCase = true) -> "ppt"
            asset.documentFormat.equals("word", ignoreCase = true) -> "docx"
            asset.documentFormat.equals("powerpoint", ignoreCase = true) -> "pptx"
            asset.mimeType.contains("pdf", ignoreCase = true) -> "pdf"
            asset.mimeType.contains("text/plain", ignoreCase = true) -> "txt"
            asset.mimeType.contains("wordprocessingml", ignoreCase = true) -> "docx"
            asset.mimeType.contains("msword", ignoreCase = true) -> "doc"
            asset.mimeType.contains("presentationml", ignoreCase = true) -> "pptx"
            asset.mimeType.contains("ms-powerpoint", ignoreCase = true) -> "ppt"
            else -> "bin"
        }
    }

    fun formatLabel(asset: AssetInfo, file: File): String {
        val ext = extensionFor(asset, file).uppercase(Locale.US)
        val format = asset.documentFormat?.lowercase(Locale.US)
        return when {
            format == "pdf" || ext == "PDF" -> "PDF"
            format in setOf("word", "doc", "docx") || ext in setOf("DOC", "DOCX") -> "Word"
            format in setOf("powerpoint", "ppt", "pptx") || ext in setOf("PPT", "PPTX") -> "PowerPoint"
            else -> ext.ifBlank { "Document" }
        }
    }

    fun popContentLabel(asset: AssetInfo): String = when (asset.normalizedType()) {
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
