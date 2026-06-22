package com.orion.player.util

import android.net.Uri

/**
 * Validates remote URLs for signage playback.
 * Only http and https schemes are permitted.
 */
object UrlSecurityUtil {

    private val ALLOWED_SCHEMES = setOf("http", "https")
    private val BLOCKED_SCHEMES = setOf("javascript", "file", "content")

    sealed class ValidationResult {
        data class Valid(val url: String) : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    /**
     * Normalizes common CMS inputs (e.g. "www.example.com/page") to a valid http(s) URL.
     */
    fun normalizeUrl(raw: String?): String? =
        when (val result = validatePlaybackUrl(raw)) {
            is ValidationResult.Valid -> result.url
            is ValidationResult.Invalid -> null
        }

    fun validatePlaybackUrl(raw: String?): ValidationResult {
        if (raw.isNullOrBlank()) {
            return ValidationResult.Invalid("URL is empty")
        }

        val trimmed = raw.trim().let { value ->
            if (value.contains("://")) value else "https://$value"
        }
        val uri = try {
            Uri.parse(trimmed)
        } catch (e: Exception) {
            return ValidationResult.Invalid("Malformed URL")
        }

        val scheme = uri.scheme?.lowercase()
            ?: return ValidationResult.Invalid("URL scheme is required")

        if (scheme in BLOCKED_SCHEMES) {
            return ValidationResult.Invalid("Blocked URL scheme: $scheme")
        }

        if (scheme !in ALLOWED_SCHEMES) {
            return ValidationResult.Invalid("Only http and https URLs are allowed")
        }

        if (uri.host.isNullOrBlank()) {
            return ValidationResult.Invalid("URL host is required")
        }

        return ValidationResult.Valid(trimmed)
    }

    fun isAllowedNavigationUrl(url: String): Boolean =
        validatePlaybackUrl(url) is ValidationResult.Valid
}
