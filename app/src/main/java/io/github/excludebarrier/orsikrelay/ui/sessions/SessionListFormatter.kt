package io.github.excludebarrier.orsikrelay.ui.sessions

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure, locale-based formatting helpers for the session list
 * (Mission 004A). No I/O, no network.
 */
object SessionListFormatter {

    /**
     * Locally formatted last-activity timestamp.
     *
     * @return `null` for missing or invalid timestamps; the row then
     * simply omits the line.
     */
    fun formatLastActive(epochSeconds: Long?, locale: Locale): String? {
        if (epochSeconds == null || epochSeconds < 0) return null
        return try {
            val formatter = DateTimeFormatter
                .ofPattern("dd.MM.yyyy, HH:mm", locale)
                .withZone(ZoneId.systemDefault())
            formatter.format(Instant.ofEpochSecond(epochSeconds))
        } catch (_: Exception) {
            null
        }
    }

    /** Localized message count label, or `null` when absent. */
    fun formatMessageCount(count: Long?, locale: Locale): String? {
        val value = count ?: return null
        return "${NumberFormat.getIntegerInstance(locale).format(value)}$MESSAGE_COUNT_SUFFIX"
    }

    private const val MESSAGE_COUNT_SUFFIX = " Nachrichten"
}
