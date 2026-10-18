package io.github.excludebarrier.orsikrelay.ui.sessions

import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionListFormatterTest {

    private val utc = ZoneOffset.UTC

    @Test
    fun germanTimeUsesGermanLocalizedFormat() {
        assertEquals(
            "00:00",
            SessionListFormatter.formatLastActiveTime(0L, Locale.GERMANY, utc)
        )
    }

    @Test
    fun englishTimeUsesEnglishLocalizedFormat() {
        assertEquals(
            "12:00 AM",
            SessionListFormatter.formatLastActiveTime(0L, Locale.US, utc)
        )
    }

    @Test
    fun germanDateUsesGermanLocalizedFormat() {
        assertEquals(
            "01.01.1970",
            SessionListFormatter.formatLastActiveDate(0L, Locale.GERMANY, utc)
        )
    }

    @Test
    fun englishDateUsesEnglishLocalizedFormat() {
        assertEquals(
            "Jan 1, 1970",
            SessionListFormatter.formatLastActiveDate(0L, Locale.US, utc)
        )
    }

    @Test
    fun absentOrNegativeTimestampIsOmitted() {
        assertNull(SessionListFormatter.formatLastActiveTime(null, Locale.GERMANY, utc))
        assertNull(SessionListFormatter.formatLastActiveTime(-1L, Locale.GERMANY, utc))
        assertNull(SessionListFormatter.formatLastActiveDate(null, Locale.GERMANY, utc))
        assertNull(SessionListFormatter.formatLastActiveDate(-1L, Locale.GERMANY, utc))
    }

    @Test
    fun messageCountReturnsOnlyTheLocaleFormattedNumber() {
        assertEquals(
            "1.234",
            SessionListFormatter.formatMessageCount(1234L, Locale.GERMANY)
        )
        assertEquals(
            "1,234",
            SessionListFormatter.formatMessageCount(1234L, Locale.US)
        )
        assertNull(SessionListFormatter.formatMessageCount(null, Locale.GERMANY))
    }
}
