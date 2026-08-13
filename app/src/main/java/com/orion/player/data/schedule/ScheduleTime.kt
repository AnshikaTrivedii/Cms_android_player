package com.orion.player.data.schedule

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * CMS schedule timestamps.
 *
 * A schedule is ACTIVE only when:
 *   startDateTime <= currentServerTime < endDateTime
 *
 * Naive wall times (and time-of-day-only values) are Asia/Kolkata unless the
 * payload includes Z or an explicit offset.
 */
object ScheduleTime {
    val CMS_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    private val NAIVE_FORMATS = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.S"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SS"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
    )

    private val OFFSET_NO_COLON = Regex("^(.*)([+-])(\\d{2})(\\d{2})$")
    private val DATE_ONLY = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val EPOCH_MILLIS = Regex("^\\d{12,13}$")
    private val TIME_ONLY = Regex("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?:\\.\\d+)?$")

    fun parse(raw: String?, zone: ZoneId = CMS_ZONE): Instant? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        parseEpoch(value)?.let { return it }
        parseInstant(value)?.let { return it }
        parseOffset(value)?.let { return it }
        parseOffsetNoColon(value)?.let { return it }
        parseRfc1123(value)?.let { return it }
        parseNaive(value, zone)?.let { return it }
        parseTimeOfDay(value, zone)?.let { return it }
        parseDateOnly(value, zone)?.let { return it }
        return null
    }

    fun windowState(
        startRaw: String?,
        endRaw: String?,
        now: Instant = ScheduleClock.now(),
        zone: ZoneId = CMS_ZONE
    ): WindowState {
        var start = parse(startRaw, zone)
        var end = parse(endRaw, zone)
        if (start == null && end == null) return WindowState.UNKNOWN
        // Time-only end before start means the window crosses midnight.
        if (start != null && end != null && !end.isAfter(start) &&
            isTimeOnly(startRaw) && isTimeOnly(endRaw)
        ) {
            end = end.plus(Duration.ofDays(1))
        }
        if (start != null && now.isBefore(start)) return WindowState.FUTURE
        if (end != null && !now.isBefore(end)) return WindowState.EXPIRED
        return WindowState.ACTIVE
    }

    fun isCurrentlyActive(startRaw: String?, endRaw: String?, now: Instant = ScheduleClock.now()): Boolean =
        windowState(startRaw, endRaw, now) == WindowState.ACTIVE

    fun isExpired(startRaw: String?, endRaw: String?, now: Instant = ScheduleClock.now()): Boolean =
        windowState(startRaw, endRaw, now) == WindowState.EXPIRED

    fun isFuture(startRaw: String?, endRaw: String?, now: Instant = ScheduleClock.now()): Boolean =
        windowState(startRaw, endRaw, now) == WindowState.FUTURE

    fun millisUntil(raw: String?, now: Instant = ScheduleClock.now(), zone: ZoneId = CMS_ZONE): Long? {
        val target = parse(raw, zone) ?: return null
        return Duration.between(now, target).toMillis()
    }

    fun calculatedStatus(startRaw: String?, endRaw: String?, terminalCmsStatus: Boolean): String {
        if (terminalCmsStatus) return "EXPIRED"
        return when (windowState(startRaw, endRaw)) {
            WindowState.ACTIVE -> "ACTIVE"
            WindowState.FUTURE -> "FUTURE"
            WindowState.EXPIRED -> "EXPIRED"
            WindowState.UNKNOWN -> "UNKNOWN"
        }
    }

    private fun isTimeOnly(raw: String?): Boolean =
        raw?.trim()?.let { TIME_ONLY.matches(it) } == true

    private fun parseEpoch(value: String): Instant? {
        if (!EPOCH_MILLIS.matches(value)) return null
        return runCatching { Instant.ofEpochMilli(value.toLong()) }.getOrNull()
    }

    private fun parseInstant(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()

    private fun parseOffset(value: String): Instant? =
        try {
            OffsetDateTime.parse(value).toInstant()
        } catch (_: DateTimeParseException) {
            runCatching {
                OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
            }.getOrNull()
        }

    private fun parseOffsetNoColon(value: String): Instant? {
        val match = OFFSET_NO_COLON.matchEntire(value) ?: return null
        val normalized =
            "${match.groupValues[1]}${match.groupValues[2]}${match.groupValues[3]}:${match.groupValues[4]}"
        return parseOffset(normalized)
    }

    private fun parseRfc1123(value: String): Instant? =
        runCatching {
            DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US).parse(value, Instant::from)
        }.getOrNull()

    private fun parseNaive(value: String, zone: ZoneId): Instant? {
        val normalized = value.replace(' ', 'T')
        runCatching {
            return LocalDateTime.parse(normalized).atZone(zone).toInstant()
        }
        for (formatter in NAIVE_FORMATS) {
            try {
                return LocalDateTime.parse(value.replace('T', ' ').let {
                    // keep original for space formats; also try normalized
                    value
                }, formatter).atZone(zone).toInstant()
            } catch (_: DateTimeParseException) {
                try {
                    return LocalDateTime.parse(normalized, formatter).atZone(zone).toInstant()
                } catch (_: DateTimeParseException) {
                    continue
                }
            }
        }
        return null
    }

    private fun parseTimeOfDay(value: String, zone: ZoneId): Instant? {
        val match = TIME_ONLY.matchEntire(value) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        val second = match.groupValues[3].toIntOrNull() ?: 0
        val time = runCatching { LocalTime.of(hour, minute, second) }.getOrNull() ?: return null
        return LocalDate.now(zone).atTime(time).atZone(zone).toInstant()
    }

    private fun parseDateOnly(value: String, zone: ZoneId): Instant? {
        if (!DATE_ONLY.matches(value)) return null
        return runCatching {
            LocalDate.parse(value).atStartOfDay(zone).toInstant()
        }.getOrNull()
    }

    enum class WindowState { ACTIVE, FUTURE, EXPIRED, UNKNOWN }
}

/**
 * Prefers CMS `serverTime` and HTTP `Date` so schedule windows are compared against
 * the server clock, not a drifting device clock.
 */
object ScheduleClock {
    private val offsetMs = AtomicLong(0L)
    private const val MAX_OFFSET_MS = 24 * 60 * 60 * 1000L

    fun noteServerTime(raw: String?) {
        val server = ScheduleTime.parse(raw) ?: return
        applyOffset(server)
    }

    fun noteHttpDate(raw: String?) {
        val server = ScheduleTime.parse(raw) ?: return
        applyOffset(server)
    }

    fun now(): Instant = Instant.now().plusMillis(offsetMs.get())

    fun deviceNow(): Instant = Instant.now()

    fun isoNow(): String = now().toString()

    fun isoUtc(instant: Instant = now()): String =
        instant.atOffset(ZoneOffset.UTC).toString()

    fun offsetMs(): Long = offsetMs.get()

    private fun applyOffset(server: Instant) {
        val delta = Duration.between(Instant.now(), server).toMillis()
        if (kotlin.math.abs(delta) > MAX_OFFSET_MS) return
        offsetMs.set(delta)
    }
}
