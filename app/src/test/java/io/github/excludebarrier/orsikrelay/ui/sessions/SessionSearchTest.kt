package io.github.excludebarrier.orsikrelay.ui.sessions

import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSearchTest {

    private val sessions = listOf(
        session("alpha", "Alpha Session"),
        session("umlaut", "Prüfen und liefern"),
        session("untitled", null),
        session("beta", "Beta Session"),
        session("blank", "   ")
    )

    @Test
    fun emptyQueryReturnsTheListUnchanged() {
        assertEquals(sessions, SessionSearch.filter(sessions, "", Locale.ENGLISH))
    }

    @Test
    fun whitespaceQueryReturnsTheListUnchanged() {
        assertEquals(sessions, SessionSearch.filter(sessions, "  \t\n ", Locale.ENGLISH))
    }

    @Test
    fun matchIsCaseInsensitiveAndTrimsTheQuery() {
        assertEquals(
            listOf("alpha"),
            SessionSearch.filter(sessions, "  ALPHA  ", Locale.ENGLISH).map { it.id }
        )
    }

    @Test
    fun germanUmlautMatchesIndependentOfCase() {
        assertEquals(
            listOf("umlaut"),
            SessionSearch.filter(sessions, "PRÜFEN", Locale.GERMAN).map { it.id }
        )
        assertEquals(
            listOf("umlaut"),
            SessionSearch.filter(sessions, "prüfen", Locale.GERMAN).map { it.id }
        )
    }

    @Test
    fun sessionsWithoutATitleNeverMatch() {
        val matches = SessionSearch.filter(sessions, "session", Locale.ENGLISH).map { it.id }

        assertEquals(listOf("alpha", "beta"), matches)
    }

    @Test
    fun serverOrderIsPreserved() {
        assertEquals(
            listOf("alpha", "beta"),
            SessionSearch.filter(sessions, "session", Locale.ENGLISH).map { it.id }
        )
    }

    @Test
    fun noMatchReturnsAnEmptyList() {
        assertEquals(
            emptyList<HermesSession>(),
            SessionSearch.filter(sessions, "missing", Locale.ENGLISH)
        )
    }

    @Test
    fun displayQueryTrimsAndShortensLongQueries() {
        assertEquals("alpha", SessionSearch.displayQuery("  alpha  "))
        val longQuery = "a".repeat(41)
        assertEquals("a".repeat(40) + "…", SessionSearch.displayQuery(longQuery))
        assertEquals("a".repeat(40), SessionSearch.displayQuery("a".repeat(40)))
    }

    private fun session(id: String, title: String?): HermesSession = HermesSession(
        id = id,
        title = title,
        source = null,
        lastActiveEpochSeconds = null,
        messageCount = null,
        pinned = false
    )
}
