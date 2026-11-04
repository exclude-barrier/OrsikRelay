package io.github.excludebarrier.orsikrelay.data.connection

import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionMode
import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionRoute
import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionSettings
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.network.ServerIdentityProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointResolverTest {

    private val private = "https://server.tailnet.example.invalid:8443"
    private val public = "https://relay.example.invalid"
    private val id = "0123456789abcdef0123456789abcdef"
    private val other = "fedcba9876543210fedcba9876543210"

    private val answers = mutableMapOf<String, ServerIdentityProbe>()
    private val probed = mutableListOf<String>()
    private val pins = mutableListOf<Pair<String, String>>()
    private val bindings = mutableListOf<Pair<String, String?>>()
    private var now = 0L

    private val resolver = EndpointResolver(
        probe = { url -> probed += url; answers[url] ?: ServerIdentityProbe.Unreachable },
        pin = { profileId, serverId -> pins += profileId to serverId },
        bindClientCertificate = { host, alias -> bindings += host to alias },
        nowMillis = { now }
    )

    private fun profile(mode: ConnectionMode, serverId: String? = id, alias: String? = "Orsik Relay") =
        ServerProfile.create(
            "p1", "Server", private,
            if (mode == ConnectionMode.PRIVATE_ONLY) {
                ConnectionSettings.PRIVATE_ONLY
            } else {
                ConnectionSettings.create(mode, public, alias, serverId)
            }
        )

    @Test
    fun `private only never probes and never goes public`() {
        assertEquals(private, resolver.baseUrlFor(profile(ConnectionMode.PRIVATE_ONLY)))
        assertTrue(probed.isEmpty())
    }

    @Test
    fun `automatic prefers the private endpoint whenever it answers`() {
        answers[private] = ServerIdentityProbe.Reachable(id)
        answers[public] = ServerIdentityProbe.Reachable(id)
        assertEquals(private, resolver.baseUrlFor(profile(ConnectionMode.AUTOMATIC)))
        assertEquals(listOf(private), probed)
        assertEquals(ConnectionRoute.PRIVATE, resolver.routes.value["p1"])
    }

    @Test
    fun `automatic falls back to the public endpoint only with the pinned identity`() {
        answers[public] = ServerIdentityProbe.Reachable(id)
        assertEquals(public, resolver.baseUrlFor(profile(ConnectionMode.AUTOMATIC)))
        assertEquals(ConnectionRoute.PUBLIC, resolver.routes.value["p1"])
        assertEquals(listOf("relay.example.invalid" to "Orsik Relay"), bindings)
    }

    @Test
    fun `a foreign or unknown public identity keeps the key on the paired endpoint`() {
        for (answer in listOf(
            ServerIdentityProbe.Reachable(other),
            ServerIdentityProbe.Reachable(null),
            ServerIdentityProbe.Unreachable
        )) {
            resolver.invalidate()
            answers[public] = answer
            assertEquals(private, resolver.baseUrlFor(profile(ConnectionMode.PUBLIC_ONLY)))
        }
    }

    @Test
    fun `without a pinned identity the public endpoint is never used`() {
        answers[public] = ServerIdentityProbe.Reachable(id)
        assertEquals(private, resolver.baseUrlFor(profile(ConnectionMode.PUBLIC_ONLY, serverId = null)))
        assertTrue(pins.isEmpty())
    }

    @Test
    fun `the identity is pinned from the paired endpoint`() {
        answers[private] = ServerIdentityProbe.Reachable(id)
        answers[public] = ServerIdentityProbe.Reachable(id)
        assertEquals(public, resolver.baseUrlFor(profile(ConnectionMode.PUBLIC_ONLY, serverId = null)))
        assertEquals(listOf("p1" to id), pins)
    }

    @Test
    fun `a choice is reused until it expires or is invalidated`() {
        answers[public] = ServerIdentityProbe.Reachable(id)
        val profile = profile(ConnectionMode.AUTOMATIC)
        resolver.baseUrlFor(profile)
        val first = probed.size
        resolver.baseUrlFor(profile)
        assertEquals(first, probed.size)
        now += 60_000
        resolver.baseUrlFor(profile)
        assertTrue(probed.size > first)
        val second = probed.size
        resolver.invalidate()
        resolver.baseUrlFor(profile)
        assertTrue(probed.size > second)
    }
}
