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
    fun timestampUsesRequestedLocaleAndLocalZone() {
        assertEquals(
            "01.01.1970, 00:00",
            SessionListFormatter.formatLastActive(0L, Locale.GERMANY)
        )
    }

    @Test
    fun absentOrNegativeTimestampIsOmitted() {
        assertNull(SessionListFormatter.formatLastActive(null, Locale.GERMANY))
        assertNull(SessionListFormatter.formatLastActive(-1L, Locale.GERMANY))
    }

    @Test
    fun messageCountUsesLocaleFormatting() {
        assertEquals(
            "1.234 Nachrichten",
            SessionListFormatter.formatMessageCount(1234L, Locale.GERMANY)
        )
        assertNull(SessionListFormatter.formatMessageCount(null, Locale.GERMANY))
    }
}
