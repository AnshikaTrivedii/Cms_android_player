package com.orion.player.data.analytics

import com.orion.player.data.local.SecurePrefs
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide PoP health counters for heartbeat, watchdog, and diagnostics.
 */
@Singleton
class PopHealthTracker @Inject constructor(
    private val securePrefs: SecurePrefs
) {
    private val lastGeneratedMs = AtomicLong(0L)
    private val lastUploadedMs = AtomicLong(0L)
    private val generatedCount = AtomicLong(0L)
    private val uploadedCount = AtomicLong(0L)

    @Volatile
    private var lastError: String? = securePrefs.popLastError

    fun recordGenerated() {
        val now = System.currentTimeMillis()
        lastGeneratedMs.set(now)
        generatedCount.incrementAndGet()
        securePrefs.popLastGeneratedAt = Instant.ofEpochMilli(now).toString()
        securePrefs.popLastError = null
        lastError = null
    }

    fun recordUploaded(count: Int) {
        if (count <= 0) return
        val now = System.currentTimeMillis()
        lastUploadedMs.set(now)
        uploadedCount.addAndGet(count.toLong())
        securePrefs.popLastUploadedAt = Instant.ofEpochMilli(now).toString()
        securePrefs.popLastError = null
        lastError = null
    }

    fun recordError(reason: String) {
        lastError = reason
        securePrefs.popLastError = reason
    }

    fun lastGeneratedAgeMs(): Long {
        val at = lastGeneratedMs.get()
        if (at <= 0L) {
            val stored = securePrefs.popLastGeneratedAt ?: return Long.MAX_VALUE
            val parsed = runCatching { Instant.parse(stored).toEpochMilli() }.getOrNull()
                ?: return Long.MAX_VALUE
            lastGeneratedMs.compareAndSet(0L, parsed)
            return (System.currentTimeMillis() - parsed).coerceAtLeast(0L)
        }
        return (System.currentTimeMillis() - at).coerceAtLeast(0L)
    }

    fun lastGeneratedAtIso(): String? = securePrefs.popLastGeneratedAt

    fun lastUploadedAtIso(): String? = securePrefs.popLastUploadedAt

    fun lastError(): String? = lastError

    fun generatedTotal(): Long = generatedCount.get()

    fun uploadedTotal(): Long = uploadedCount.get()

    fun snapshot(pendingCount: Int): PopHealthSnapshot = PopHealthSnapshot(
        pendingCount = pendingCount,
        lastGeneratedAt = lastGeneratedAtIso(),
        lastUploadedAt = lastUploadedAtIso(),
        lastError = lastError(),
        generatedTotal = generatedTotal(),
        uploadedTotal = uploadedTotal()
    )
}

data class PopHealthSnapshot(
    val pendingCount: Int,
    val lastGeneratedAt: String?,
    val lastUploadedAt: String?,
    val lastError: String?,
    val generatedTotal: Long,
    val uploadedTotal: Long
)
