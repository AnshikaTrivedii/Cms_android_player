package com.orion.player.data.playback

import java.io.File
import java.util.zip.ZipFile
import java.util.regex.Pattern

/**
 * Offline OOXML (DOCX / PPTX) content extraction for fullscreen document playback.
 * Architecture is extensible for additional Office formats.
 */
object OfficeDocumentExtractor {
    private val TAG_PATTERN = Pattern.compile("<[^>]+>")
    private val WHITESPACE = Pattern.compile("[ \\t\\x0B\\f\\r]+")

    data class ExtractedDocument(
        val title: String,
        val pages: List<String>
    )

    fun extract(file: File, extension: String): ExtractedDocument? {
        return when (extension.lowercase()) {
            "docx" -> extractDocx(file)
            "pptx" -> extractPptx(file)
            else -> null
        }
    }

    private fun extractDocx(file: File): ExtractedDocument? = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return@use null
            val xml = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            val text = normalizeText(stripTags(xml.replace("</w:p>", "\n")))
            if (text.isBlank()) return@use null
            // Split long docs into page-sized chunks for carousel display.
            val pages = text.chunked(1200).map { it.trim() }.filter { it.isNotEmpty() }
            ExtractedDocument(title = file.nameWithoutExtension, pages = pages.ifEmpty { listOf(text) })
        }
    }.getOrNull()

    private fun extractPptx(file: File): ExtractedDocument? = runCatching {
        ZipFile(file).use { zip ->
            val slideEntries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.matches(Regex("""ppt/slides/slide\d+\.xml""")) }
                .sortedBy { it.name }
                .toList()
            if (slideEntries.isEmpty()) return@use null
            val pages = slideEntries.mapNotNull { entry ->
                val xml = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                normalizeText(stripTags(xml.replace("</a:t>", " ").replace("</a:p>", "\n")))
                    .takeIf { it.isNotBlank() }
            }
            if (pages.isEmpty()) return@use null
            ExtractedDocument(title = file.nameWithoutExtension, pages = pages)
        }
    }.getOrNull()

    private fun stripTags(xml: String): String = TAG_PATTERN.matcher(xml).replaceAll(" ")

    private fun normalizeText(raw: String): String =
        WHITESPACE.matcher(raw)
            .replaceAll(" ")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
}
