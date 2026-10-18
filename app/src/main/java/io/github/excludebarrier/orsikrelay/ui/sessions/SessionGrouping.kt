package io.github.excludebarrier.orsikrelay.ui.sessions

import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Date sections of the session list, in display order.
 */
enum class SessionGroup {
    Pinned,
    Today,
    Yesterday,
    LastSevenDays,
    Older
}

/**
 * One non-empty section of the session list.
 */
data class SessionGroupSection(
    val group: SessionGroup,
    val sessions: List<HermesSession>
)

/**
 * Pure grouping of the server-ordered session list.
 *
 * Pinned sessions always form the first section, independent of their
 * last-activity date. All other sessions are classified by calendar day in
 * [zone]: today, yesterday, 2 to 6 days back, or older (including a missing
 * last activity). The relative order inside every section stays exactly the
 * server order. Empty sections are omitted.
 */
object SessionGrouping {

    fun groupSessions(
        sessions: List<HermesSession>,
        now: Instant,
        zone: ZoneId
    ): List<SessionGroupSection> {
        val today = now.atZone(zone).toLocalDate()
        val buckets = SessionGroup.entries.associateWith { mutableListOf<HermesSession>() }
        sessions.forEach { session ->
            val group = if (session.pinned) {
                SessionGroup.Pinned
            } else {
                groupOf(session.lastActiveEpochSeconds, today, zone)
            }
            buckets.getValue(group) += session
        }
        return SessionGroup.entries.mapNotNull { group ->
            buckets.getValue(group)
                .takeIf { it.isNotEmpty() }
                ?.let { SessionGroupSection(group, it) }
        }
    }

    /**
     * Calendar-day group of a last-activity time, ignoring the pinned flag:
     * the session row uses it to show only the time for today and yesterday,
     * also inside the pinned section.
     */
    fun dayGroupOf(epochSeconds: Long?, now: Instant, zone: ZoneId): SessionGroup =
        groupOf(epochSeconds, now.atZone(zone).toLocalDate(), zone)

    private fun groupOf(
        epochSeconds: Long?,
        today: LocalDate,
        zone: ZoneId
    ): SessionGroup {
        if (epochSeconds == null || epochSeconds < 0) return SessionGroup.Older
        val date = try {
            Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate()
        } catch (_: Exception) {
            return SessionGroup.Older
        }
        val daysBack = ChronoUnit.DAYS.between(date, today)
        return when {
            daysBack <= 0L -> SessionGroup.Today
            daysBack == 1L -> SessionGroup.Yesterday
            daysBack <= 6L -> SessionGroup.LastSevenDays
            else -> SessionGroup.Older
        }
    }
}
