package io.github.excludebarrier.orsikrelay.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionSettingsTest {

    private val serverId = "0123456789abcdef0123456789abcdef"

    @Test
    fun `private only is the default and needs no public endpoint`() {
        val profile = ServerProfile.create("id", "Server", "https://private.example.invalid")
        assertEquals(ConnectionSettings.PRIVATE_ONLY, profile.connection)
        assertFalse(profile.connection.usesPublicEndpoint)
    }

    @Test
    fun `a public mode needs a canonical https endpoint`() {
        assertFails { ConnectionSettings.create(ConnectionMode.AUTOMATIC, null, null, null) }
        assertFails { ConnectionSettings.create(ConnectionMode.PUBLIC_ONLY, "http://relay.example.invalid", null, null) }
        assertFails { ConnectionSettings.create(ConnectionMode.PUBLIC_ONLY, "https://relay.example.invalid/", null, null) }
        assertFails { ConnectionSettings.create(ConnectionMode.PUBLIC_ONLY, "https://u:p@relay.example.invalid", null, null) }
        val settings = ConnectionSettings.create(ConnectionMode.PUBLIC_ONLY, "https://relay.example.invalid", "alias", null)
        assertTrue(settings.usesPublicEndpoint)
    }

    @Test
    fun `aliases and server ids are bounded and strict`() {
        val url = "https://relay.example.invalid"
        assertFails { ConnectionSettings.create(ConnectionMode.AUTOMATIC, url, " ", null) }
        assertFails { ConnectionSettings.create(ConnectionMode.AUTOMATIC, url, "a\nb", null) }
        assertFails { ConnectionSettings.create(ConnectionMode.AUTOMATIC, url, "a".repeat(257), null) }
        assertFails { ConnectionSettings.create(ConnectionMode.AUTOMATIC, url, null, serverId.uppercase()) }
        assertFails { ConnectionSettings.create(ConnectionMode.AUTOMATIC, url, null, serverId + "0") }
        val pinned = ConnectionSettings.create(ConnectionMode.AUTOMATIC, url, null, null).withServerId(serverId)
        assertEquals(serverId, pinned.serverId)
    }

    @Test
    fun `the public endpoint must differ from the paired one`() {
        val same = ConnectionSettings.create(ConnectionMode.AUTOMATIC, "https://private.example.invalid", null, null)
        assertFails { ServerProfile.create("id", "Server", "https://private.example.invalid", same) }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
