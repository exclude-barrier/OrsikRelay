package io.github.excludebarrier.orsikrelay.ui.sessions

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Pure, locale-based formatting helpers for the session list.
 * No I/O, no network, no Android Context.
 */
object SessionListFormatter {

    /**
     * Locally formatted last-activity timestamp.
     *
     * The pattern comes from the platform localization data for [locale]
     * (medium date, short time), so English and German produce their own
     * date order and separators. The zone stays the device default.
     *
     * @return `null` for missing or invalid timestamps; the row then
     * simply omits the line.
     */
    fun formatLastActive(epochSeconds: Long?, locale: Locale): String? {
        if (epochSeconds == null || epochSeconds < 0) return null
        return try {
            DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale)
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(epochSeconds))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Locally formatted message count NUMBER only, or `null` when absent.
     *
     * Pluralization happens in the composable through the
     * `session_message_count` plural resource; this pure formatter stays
     * context-free and only formats the digits.
     */
    fun formatMessageCount(count: Long?, locale: Locale): String? {
        val value = count ?: return null
        return NumberFormat.getIntegerInstance(locale).format(value)
    }
}
