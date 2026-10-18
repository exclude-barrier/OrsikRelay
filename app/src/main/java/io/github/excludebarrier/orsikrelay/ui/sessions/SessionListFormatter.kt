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
     * Locally formatted time only, used for the Today and Yesterday groups.
     *
     * The pattern comes from the platform localization data for [locale]
     * (short time), in [zone].
     *
     * @return `null` for missing or invalid timestamps; the row then
     * simply omits that part.
     */
    fun formatLastActiveTime(
        epochSeconds: Long?,
        locale: Locale,
        zone: ZoneId
    ): String? {
        if (epochSeconds == null || epochSeconds < 0) return null
        return try {
            DateTimeFormatter
                .ofLocalizedTime(FormatStyle.SHORT)
                .withLocale(locale)
                .withZone(zone)
                .format(Instant.ofEpochSecond(epochSeconds))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Locally formatted medium date, used for every group except Today and
     * Yesterday.
     *
     * @return `null` for missing or invalid timestamps.
     */
    fun formatLastActiveDate(
        epochSeconds: Long?,
        locale: Locale,
        zone: ZoneId
    ): String? {
        if (epochSeconds == null || epochSeconds < 0) return null
        return try {
            DateTimeFormatter
                .ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(locale)
                .withZone(zone)
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
