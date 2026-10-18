package io.github.excludebarrier.orsikrelay.ui.sessions

import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionGroupingTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val now = Instant.parse("2026-10-18T12:00:00Z")

    @Test
    fun sectionsFollowTheDisplayOrderAndOmitEmptyOnes() {
        val sections = SessionGrouping.groupSessions(
            sessions = listOf(
                session("older", Instant.parse("2026-10-01T10:00:00Z")),
                session("today", Instant.parse("2026-10-18T10:00:00Z")),
                session("pinned", Instant.parse("2020-01-01T10:00:00Z"), pinned = true),
                session("yesterday", Instant.parse("2026-10-17T10:00:00Z")),
                session("recent", Instant.parse("2026-10-12T10:00:00Z"))
            ),
            now = now,
            zone = zone
        )

        assertEquals(
            listOf(
                SessionGroup.Pinned,
                SessionGroup.Today,
                SessionGroup.Yesterday,
                SessionGroup.LastSevenDays,
                SessionGroup.Older
            ),
            sections.map { it.group }
        )
        assertEquals(listOf("pinned"), sections[0].sessions.map { it.id })
        assertEquals(listOf("today"), sections[1].sessions.map { it.id })
        assertEquals(listOf("yesterday"), sections[2].sessions.map { it.id })
        assertEquals(listOf("recent"), sections[3].sessions.map { it.id })
        assertEquals(listOf("older"), sections[4].sessions.map { it.id })
    }

    @Test
    fun onlyNonEmptySectionsAreReturned() {
        val sections = SessionGrouping.groupSessions(
            sessions = listOf(session("today", Instant.parse("2026-10-18T10:00:00Z"))),
            now = now,
            zone = zone
        )

        assertEquals(listOf(SessionGroup.Today), sections.map { it.group })
    }

    @Test
    fun midnightBoundaryUsesCalendarDaysInTheGivenZone() {
        val sections = SessionGrouping.groupSessions(
            sessions = listOf(
                session("midnight", Instant.parse("2026-10-17T22:00:00Z")),
                session("oneSecondBefore", Instant.parse("2026-10-17T21:59:59Z"))
            ),
            now = now,
            zone = zone
        )

        assertEquals(
            mapOf(
                SessionGroup.Today to listOf("midnight"),
                SessionGroup.Yesterday to listOf("oneSecondBefore")
            ),
            sections.associate { it.group to it.sessions.map { session -> session.id } }
        )
    }

    @Test
    fun sixDaysBackIsRecentAndSevenDaysBackIsOlder() {
        val sections = SessionGrouping.groupSessions(
            sessions = listOf(
                session("sixDays", Instant.parse("2026-10-12T10:00:00Z")),
                session("sevenDays", Instant.parse("2026-10-11T10:00:00Z"))
            ),
            now = now,
            zone = zone
        )

        assertEquals(listOf(SessionGroup.LastSevenDays, SessionGroup.Older), sections.map { it.group })
        assertEquals(listOf("sixDays"), sections[0].sessions.map { it.id })
        assertEquals(listOf("sevenDays"), sections[1].sessions.map { it.id })
    }

    @Test
    fun missingAndInvalidActivityAreOlder() {
        val sections = SessionGrouping.groupSessions(
            sessions = listOf(
                session("missing", null),
                session("negative", Instant.ofEpochSecond(-1))
            ),
            now = now,
            zone = zone
        )

        assertEquals(listOf(SessionGroup.Older), sections.map { it.group })
        assertEquals(listOf("missing", "negative"), sections[0].sessions.map { it.id })
    }

    @Test
    fun pinnedSessionWithOldActivityStaysInThePinnedSection() {
        val sections = SessionGrouping.groupSessions(
            sessions = listOf(
                session("pinned", Instant.parse("2019-05-01T10:00:00Z"), pinned = true),
                session("today", Instant.parse("2026-10-18T10:00:00Z"))
            ),
            now = now,
            zone = zone
        )

        assertEquals(listOf(SessionGroup.Pinned, SessionGroup.Today), sections.map { it.group })
        assertEquals(listOf("pinned"), sections[0].sessions.map { it.id })
    }

    @Test
    fun serverOrderIsKeptInsideEachSection() {
        val sections = SessionGrouping.groupSessions(
            sessions = listOf(
                session("today-b", Instant.parse("2026-10-18T11:00:00Z")),
                session("today-a", Instant.parse("2026-10-18T10:00:00Z")),
                session("older-b", Instant.parse("2026-09-01T10:00:00Z")),
                session("today-c", Instant.parse("2026-10-18T09:00:00Z"))
            ),
            now = now,
            zone = zone
        )

        assertEquals(listOf(SessionGroup.Today, SessionGroup.Older), sections.map { it.group })
        assertEquals(listOf("today-b", "today-a", "today-c"), sections[0].sessions.map { it.id })
        assertEquals(listOf("older-b"), sections[1].sessions.map { it.id })
    }

    @Test
    fun dayGroupIgnoresThePinnedFlag() {
        val todayNoon = now.epochSecond
        val yesterday = now.minusSeconds(86_400).epochSecond
        val older = now.minusSeconds(10 * 86_400L).epochSecond
        assertEquals(SessionGroup.Today, SessionGrouping.dayGroupOf(todayNoon, now, zone))
        assertEquals(SessionGroup.Yesterday, SessionGrouping.dayGroupOf(yesterday, now, zone))
        assertEquals(SessionGroup.Older, SessionGrouping.dayGroupOf(older, now, zone))
        assertEquals(SessionGroup.Older, SessionGrouping.dayGroupOf(null, now, zone))
    }

    private fun session(
        id: String,
        lastActive: Instant?,
        pinned: Boolean = false
    ) = HermesSession(
        id = id,
        title = id,
        source = null,
        lastActiveEpochSeconds = lastActive?.epochSecond,
        messageCount = null,
        pinned = pinned
    )
}
