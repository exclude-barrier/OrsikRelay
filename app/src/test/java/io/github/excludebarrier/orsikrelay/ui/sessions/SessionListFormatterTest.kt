package io.github.excludebarrier.orsikrelay.ui.sessions

import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SessionListFormatterTest {

    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun germanTimestampUsesGermanLocalizedFormat() {
        assertEquals(
            "01.01.1970, 00:00",
            SessionListFormatter.formatLastActive(0L, Locale.GERMANY)
        )
    }

    @Test
    fun englishTimestampUsesEnglishLocalizedFormat() {
        assertEquals(
            "Jan 1, 1970, 12:00 AM",
            SessionListFormatter.formatLastActive(0L, Locale.US)
        )
    }

    @Test
    fun absentOrNegativeTimestampIsOmitted() {
        assertNull(SessionListFormatter.formatLastActive(null, Locale.GERMANY))
        assertNull(SessionListFormatter.formatLastActive(-1L, Locale.GERMANY))
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
