package io.github.excludebarrier.orsikrelay.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSourceDescriptorTest {

    @Test
    fun terminalSourcesAreMapped() {
        assertEquals(SessionSourceDescriptor.Terminal, sessionSourceDescriptor("cli"))
        assertEquals(SessionSourceDescriptor.Terminal, sessionSourceDescriptor("tui"))
        assertEquals(SessionSourceDescriptor.Terminal, sessionSourceDescriptor("local"))
    }

    @Test
    fun knownSourcesAreMapped() {
        assertEquals(SessionSourceDescriptor.Telegram, sessionSourceDescriptor("telegram"))
        assertEquals(SessionSourceDescriptor.Email, sessionSourceDescriptor("email"))
        assertEquals(SessionSourceDescriptor.Sms, sessionSourceDescriptor("sms"))
        assertEquals(SessionSourceDescriptor.WhatsApp, sessionSourceDescriptor("whatsapp"))
        assertEquals(SessionSourceDescriptor.WhatsApp, sessionSourceDescriptor("whatsapp_cloud"))
        assertEquals(SessionSourceDescriptor.Signal, sessionSourceDescriptor("signal"))
        assertEquals(SessionSourceDescriptor.Scheduled, sessionSourceDescriptor("cron"))
    }

    @Test
    fun comparisonIgnoresCaseAndSurroundingWhitespace() {
        assertEquals(SessionSourceDescriptor.Terminal, sessionSourceDescriptor("  CLI  "))
        assertEquals(SessionSourceDescriptor.Telegram, sessionSourceDescriptor("Telegram"))
        assertEquals(SessionSourceDescriptor.WhatsApp, sessionSourceDescriptor(" WhatsApp_Cloud "))
    }

    @Test
    fun missingSourceHasNoDescriptorName() {
        assertEquals(SessionSourceDescriptor.Missing, sessionSourceDescriptor(null))
        assertEquals(SessionSourceDescriptor.Missing, sessionSourceDescriptor(""))
        assertEquals(SessionSourceDescriptor.Missing, sessionSourceDescriptor("   "))
    }

    @Test
    fun unknownSourceKeepsTheTrimmedRawValue() {
        assertEquals(
            SessionSourceDescriptor.Other("discord"),
            sessionSourceDescriptor("discord")
        )
        assertEquals(
            SessionSourceDescriptor.Other("Discord"),
            sessionSourceDescriptor("  Discord ")
        )
    }
}
